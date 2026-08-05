package com.guideglasses.core.domain.face

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FaceIdentificationStrategyTest {

    private class FakeStrategy(
        override val name: String,
        override val isAvailable: Boolean,
        private val result: AppResult<FaceMatch>,
    ) : FaceIdentificationStrategy {
        var callCount = 0

        override suspend fun identify(
            frame: CameraFrame,
            face: DetectedFace,
        ): AppResult<FaceMatch> {
            callCount++
            return result
        }
    }

    private val frame = CameraFrame(
        bytes = ByteArray(8),
        format = CameraFrame.Format.JPEG,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        timestampMillis = 0L,
    )

    private val face = DetectedFace(left = 0.4f, top = 0.4f, width = 0.2f, height = 0.2f)

    private fun person(name: String) = KnownPerson(
        id = 1,
        name = name,
        relation = null,
        embedding = FaceEmbedding(floatArrayOf(1f, 0f)),
    )

    private fun confident(name: String) =
        AppResult.Success(FaceMatch.Confident(person(name), 0.9f) as FaceMatch)

    @Test
    fun `端側可用時不會呼叫遠端`() = runTest {
        val onDevice = FakeStrategy("on-device", true, confident("端側"))
        val remote = FakeStrategy("remote", true, confident("遠端"))

        val result = CompositeFaceIdentification(listOf(onDevice, remote))
            .identify(frame, face)

        assertThat(((result as AppResult.Success).data as FaceMatch.Confident).person.name)
            .isEqualTo("端側")
        assertThat(remote.callCount).isEqualTo(0)
    }

    @Test
    fun `端側不可用時自動改走遠端`() = runTest {
        // 這正是「沒放 .tflite 模型檔」的情況
        val onDevice = FakeStrategy("on-device", false, confident("端側"))
        val remote = FakeStrategy("remote", true, confident("遠端"))

        val composite = CompositeFaceIdentification(listOf(onDevice, remote))

        assertThat(composite.isAvailable).isTrue()
        assertThat(composite.name).isEqualTo("remote")
        assertThat(onDevice.callCount).isEqualTo(0)
        assertThat(
            ((composite.identify(frame, face) as AppResult.Success).data as FaceMatch.Confident)
                .person.name,
        ).isEqualTo("遠端")
    }

    @Test
    fun `端側執行失敗時退到遠端`() = runTest {
        // 例如模型載入了但推論爆掉
        val onDevice = FakeStrategy(
            "on-device", true, AppResult.Failure(AppError.Unknown("inference failed")),
        )
        val remote = FakeStrategy("remote", true, confident("遠端"))

        val result = CompositeFaceIdentification(listOf(onDevice, remote))
            .identify(frame, face)

        assertThat(onDevice.callCount).isEqualTo(1)
        assertThat(remote.callCount).isEqualTo(1)
        assertThat(((result as AppResult.Success).data as FaceMatch.Confident).person.name)
            .isEqualTo("遠端")
    }

    @Test
    fun `兩條都失敗時回傳最後一個錯誤`() = runTest {
        val onDevice = FakeStrategy(
            "on-device", true, AppResult.Failure(AppError.Unknown("local")),
        )
        val remote = FakeStrategy("remote", true, AppResult.Failure(AppError.NoNetwork()))

        val result = CompositeFaceIdentification(listOf(onDevice, remote))
            .identify(frame, face)

        assertThat((result as AppResult.Failure).error)
            .isInstanceOf(AppError.NoNetwork::class.java)
    }

    @Test
    fun `全都不可用時明確回報`() = runTest {
        val composite = CompositeFaceIdentification(
            listOf(FakeStrategy("on-device", false, confident("x"))),
        )

        assertThat(composite.isAvailable).isFalse()
        assertThat(composite.name).isEqualTo("none")
        assertThat((composite.identify(frame, face) as AppResult.Failure).error)
            .isInstanceOf(AppError.CapabilityUnavailable::class.java)
    }

    @Test
    fun `空清單不會崩潰`() = runTest {
        val composite = CompositeFaceIdentification(emptyList())

        assertThat(composite.isAvailable).isFalse()
        assertThat(composite.identify(frame, face)).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `端側策略的可用性跟著模型檔走`() {
        val embedder = object : FaceEmbedder {
            override val isAvailable = false
            override val dimension = 0
            override suspend fun embed(frame: CameraFrame, face: DetectedFace) =
                AppResult.Failure(AppError.NoResult())
        }
        val repository = object : PersonRepository {
            override suspend fun all() = emptyList<KnownPerson>()
            override suspend fun add(name: String, relation: String?, embedding: FaceEmbedding) =
                person(name)
            override suspend fun update(person: KnownPerson) = Unit
            override suspend fun deleteById(id: Long) = Unit
            override suspend fun deleteAll() = Unit
            override suspend fun count() = 0
        }

        assertThat(OnDeviceFaceIdentification(embedder, repository).isAvailable).isFalse()
    }
}
