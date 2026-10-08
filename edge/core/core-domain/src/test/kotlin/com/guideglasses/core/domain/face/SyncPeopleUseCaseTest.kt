package com.guideglasses.core.domain.face

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncPeopleUseCaseTest {

    private val frame = CameraFrame(
        bytes = byteArrayOf(1, 2, 3),
        format = CameraFrame.Format.JPEG,
        width = 640,
        height = 480,
        rotationDegrees = 0,
        timestampMillis = 0L,
    )

    private val oneFace = DetectedFace(left = 0.4f, top = 0.3f, width = 0.2f, height = 0.3f)

    private class FakePhotoSource(
        override val isAvailable: Boolean = true,
        private val people: List<PersonPhotos> = emptyList(),
        private val listFailure: AppError? = null,
        private val unloadable: Set<String> = emptySet(),
        private val frame: CameraFrame,
    ) : PhotoSource {
        override suspend fun listPeople(): AppResult<List<PersonPhotos>> =
            listFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(people)

        override suspend fun loadPhoto(reference: String): AppResult<CameraFrame> =
            if (reference in unloadable) {
                AppResult.Failure(AppError.NoNetwork("取不到"))
            } else {
                AppResult.Success(frame)
            }
    }

    private class FakeDetector(
        override val isAvailable: Boolean = true,
        private val facesPerPhoto: Map<String, List<DetectedFace>> = emptyMap(),
        private val default: List<DetectedFace>,
    ) : FaceDetector {
        override suspend fun detect(frame: CameraFrame): AppResult<List<DetectedFace>> =
            AppResult.Success(default)
    }

    /** 依照片參照回傳可控的特徵，方便驗證平均與相似度計算。 */
    private class FakeEmbedder(
        override val isAvailable: Boolean = true,
        override val dimension: Int = 4,
        private val vectors: MutableList<FloatArray> = mutableListOf(),
    ) : FaceEmbedder {
        var calls = 0
        override suspend fun embed(
            frame: CameraFrame,
            face: DetectedFace,
        ): AppResult<FaceEmbedding> {
            val v = vectors.getOrNull(calls) ?: floatArrayOf(1f, 0f, 0f, 0f)
            calls++
            return AppResult.Success(FaceEmbedding(v.copyOf()))
        }
    }

    private class FakeRepository : PersonRepository {
        val people = mutableListOf<KnownPerson>()
        var deleteAllCalls = 0
        private var nextId = 1L

        override suspend fun all(): List<KnownPerson> = people
        override suspend fun add(
            name: String,
            relation: String?,
            embedding: FaceEmbedding,
        ): KnownPerson = KnownPerson(nextId++, name, relation, embedding).also { people += it }

        override suspend fun update(person: KnownPerson) {
            val index = people.indexOfFirst { it.id == person.id }
            if (index >= 0) people[index] = person
        }

        override suspend fun deleteById(id: Long) { people.removeAll { it.id == id } }
        override suspend fun deleteAll() { deleteAllCalls++; people.clear() }
        override suspend fun count(): Int = people.size
    }

    private fun useCase(
        source: PhotoSource,
        embedder: FaceEmbedder = FakeEmbedder(),
        repository: FakeRepository = FakeRepository(),
        detector: FaceDetector = FakeDetector(default = listOf(oneFace)),
    ) = SyncPeopleUseCase(source, detector, embedder, repository) to repository

    @Test
    fun `同步成功時寫入資料庫並回報數量`() = runTest {
        val source = FakePhotoSource(
            people = listOf(
                PersonPhotos("小明", listOf("小明/1.jpg", "小明/2.jpg")),
                PersonPhotos("小華", listOf("小華/1.jpg")),
            ),
            frame = frame,
        )
        val (sync, repo) = useCase(source)

        val outcome = sync.execute()

        assertThat(outcome).isInstanceOf(SyncPeopleUseCase.Outcome.Completed::class.java)
        val done = outcome as SyncPeopleUseCase.Outcome.Completed
        assertThat(done.people).isEqualTo(2)
        assertThat(done.photos).isEqualTo(3)
        assertThat(repo.people.map { it.name }).containsExactly("小明", "小華")
    }

    @Test
    fun `沒設定來源時不碰資料庫`() = runTest {
        val (sync, repo) = useCase(FakePhotoSource(isAvailable = false, frame = frame))
        assertThat(sync.execute()).isEqualTo(SyncPeopleUseCase.Outcome.SourceUnavailable)
        assertThat(repo.deleteAllCalls).isEqualTo(0)
    }

    @Test
    fun `缺模型時不碰資料庫`() = runTest {
        val source = FakePhotoSource(people = listOf(PersonPhotos("小明", listOf("a"))), frame = frame)
        val (sync, repo) = useCase(source, embedder = FakeEmbedder(isAvailable = false))
        assertThat(sync.execute()).isEqualTo(SyncPeopleUseCase.Outcome.ModelUnavailable)
        assertThat(repo.deleteAllCalls).isEqualTo(0)
    }

    /**
     * 最重要的一條：網路中斷時保留舊資料。
     * 清空之後同步失敗，使用者會突然誰都認不得。
     */
    @Test
    fun `所有照片都失敗時不清空既有資料`() = runTest {
        val source = FakePhotoSource(
            people = listOf(PersonPhotos("小明", listOf("a", "b"))),
            unloadable = setOf("a", "b"),
            frame = frame,
        )
        val (sync, repo) = useCase(source)
        repo.add("舊資料", null, FaceEmbedding(floatArrayOf(1f, 0f, 0f, 0f)))

        val outcome = sync.execute()

        assertThat(outcome).isInstanceOf(SyncPeopleUseCase.Outcome.Failed::class.java)
        assertThat(repo.deleteAllCalls).isEqualTo(0)
        assertThat(repo.people.map { it.name }).containsExactly("舊資料")
    }

    @Test
    fun `照片裡有多張臉時略過該張`() = runTest {
        val twoFaces = FakeDetector(default = listOf(oneFace, oneFace))
        val source = FakePhotoSource(
            people = listOf(PersonPhotos("小明", listOf("a"))),
            frame = frame,
        )
        val (sync, _) = useCase(source, detector = twoFaces)
        // 唯一一張照片被略過 -> 沒有人可寫入 -> Failed
        assertThat(sync.execute()).isInstanceOf(SyncPeopleUseCase.Outcome.Failed::class.java)
    }

    @Test
    fun `略過的照片數會被回報`() = runTest {
        val source = FakePhotoSource(
            people = listOf(PersonPhotos("小明", listOf("good", "bad"))),
            unloadable = setOf("bad"),
            frame = frame,
        )
        val (sync, _) = useCase(source)
        val done = sync.execute() as SyncPeopleUseCase.Outcome.Completed
        assertThat(done.photos).isEqualTo(1)
        assertThat(done.skippedPhotos).isEqualTo(1)
        assertThat(done.spoken).contains("略過 1 張")
    }

    /**
     * 模型前處理不符時特徵近乎隨機，同一人相似度會塌掉。
     * 這個測試守的是「把靜默失敗變成聽得見的警告」這個行為。
     */
    @Test
    fun `同一人相似度過低時警告模型可能不對`() = runTest {
        val orthogonal = FakeEmbedder(
            vectors = mutableListOf(
                floatArrayOf(1f, 0f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f, 0f), // 與上一個正交 -> 相似度 0
            ),
        )
        val source = FakePhotoSource(
            people = listOf(PersonPhotos("小明", listOf("a", "b"))),
            frame = frame,
        )
        val (sync, _) = useCase(source, embedder = orthogonal)

        val done = sync.execute() as SyncPeopleUseCase.Outcome.Completed

        assertThat(done.coherence).isLessThan(SyncPeopleUseCase.SUSPICIOUS_COHERENCE)
        assertThat(done.modelLooksBroken).isTrue()
        assertThat(done.spoken).contains("人臉模型可能不正確")
    }

    @Test
    fun `相似度正常時不發警告`() = runTest {
        val consistent = FakeEmbedder(
            vectors = mutableListOf(
                floatArrayOf(1f, 0.1f, 0f, 0f),
                floatArrayOf(1f, 0.2f, 0f, 0f), // 幾乎同向 -> 相似度接近 1
            ),
        )
        val source = FakePhotoSource(
            people = listOf(PersonPhotos("小明", listOf("a", "b"))),
            frame = frame,
        )
        val (sync, _) = useCase(source, embedder = consistent)

        val done = sync.execute() as SyncPeopleUseCase.Outcome.Completed

        assertThat(done.modelLooksBroken).isFalse()
        assertThat(done.spoken).doesNotContain("可能不正確")
    }

    @Test
    fun `註冊工具上沒有人時明確回報`() = runTest {
        val (sync, repo) = useCase(FakePhotoSource(people = emptyList(), frame = frame))
        assertThat(sync.execute()).isEqualTo(SyncPeopleUseCase.Outcome.NothingToSync)
        assertThat(repo.deleteAllCalls).isEqualTo(0)
    }

    @Test
    fun `多張照片會回報進度`() = runTest {
        val source = FakePhotoSource(
            people = listOf(
                PersonPhotos("A", listOf("a")),
                PersonPhotos("B", listOf("b")),
            ),
            frame = frame,
        )
        val (sync, _) = useCase(source)
        val progress = mutableListOf<Pair<Int, Int>>()

        sync.execute { done, total -> progress += done to total }

        assertThat(progress).containsExactly(1 to 2, 2 to 2).inOrder()
    }
}
