package com.guideglasses.core.domain.readiness

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.face.DetectedFace
import com.guideglasses.core.domain.face.FaceEmbedder
import com.guideglasses.core.domain.face.FaceEmbedding
import com.guideglasses.core.domain.face.KnownPerson
import com.guideglasses.core.domain.face.PersonPhotos
import com.guideglasses.core.domain.face.PersonRepository
import com.guideglasses.core.domain.face.PhotoSource
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.translate.TargetLanguage
import com.guideglasses.core.domain.translate.Translator
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReadinessCheckUseCaseTest {

    private class FakeEmbedder(override val isAvailable: Boolean) : FaceEmbedder {
        override val dimension = 512
        override suspend fun embed(frame: CameraFrame, face: DetectedFace) =
            AppResult.Success(FaceEmbedding(floatArrayOf(1f)))
    }

    private class FakeRepository(private val n: Int) : PersonRepository {
        override suspend fun all(): List<KnownPerson> = emptyList()
        override suspend fun add(name: String, relation: String?, embedding: FaceEmbedding) =
            KnownPerson(1, name, relation, embedding)
        override suspend fun update(person: KnownPerson) = Unit
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun count(): Int = n
    }

    private class FakeTranslator(
        override val isAvailable: Boolean = true,
        private val ready: Set<TargetLanguage> = emptySet(),
    ) : Translator {
        override suspend fun isReady(target: TargetLanguage) = target in ready
        override suspend fun prepare(target: TargetLanguage) = AppResult.Success(Unit)
        override suspend fun translate(text: String, target: TargetLanguage) =
            AppResult.Success(text)
    }

    private class FakePhotoSource(override val isAvailable: Boolean) : PhotoSource {
        override suspend fun listPeople() = AppResult.Success(emptyList<PersonPhotos>())
        override suspend fun loadPhoto(reference: String) =
            AppResult.Success(
                CameraFrame(byteArrayOf(1), CameraFrame.Format.JPEG, 1, 1, 0, 0L),
            )
    }

    private fun check(
        modelReady: Boolean = true,
        peopleCount: Int = 3,
        readyLanguages: Set<TargetLanguage> = setOf(TargetLanguage.ENGLISH),
        photoSource: Boolean = true,
    ) = ReadinessCheckUseCase(
        embedder = FakeEmbedder(modelReady),
        people = FakeRepository(peopleCount),
        translator = FakeTranslator(ready = readyLanguages),
        photoSource = FakePhotoSource(photoSource),
    )

    @Test
    fun `全部就緒時說可以出門`() = runTest {
        val report = check().execute()

        assertThat(report.allReady).isTrue()
        assertThat(report.spoken).contains("可以出門了")
        assertThat(report.spoken).contains("3 個人")
        assertThat(report.spoken).contains("英文")
    }

    /** 實測最常見的失敗：忘記同步人臉就出門。 */
    @Test
    fun `人臉資料庫空的時候要說該做什麼`() = runTest {
        val report = check(peopleCount = 0).execute()

        assertThat(report.faceReadyOffline).isFalse()
        assertThat(report.spoken).contains("人臉資料庫是空的")
        assertThat(report.spoken).contains("同步人臉")
    }

    @Test
    fun `沒設定註冊工具時提示的內容不同`() = runTest {
        val report = check(peopleCount = 0, photoSource = false).execute()
        assertThat(report.spoken).contains("還沒設定註冊工具")
    }

    @Test
    fun `語言包沒下載時要說該做什麼`() = runTest {
        val report = check(readyLanguages = emptySet()).execute()

        assertThat(report.translateReadyOffline).isFalse()
        assertThat(report.spoken).contains("語言包還沒下載")
        assertThat(report.spoken).contains("準備翻譯")
    }

    @Test
    fun `缺模型時直接指出缺模型而不是說資料庫空的`() = runTest {
        val report = check(modelReady = false, peopleCount = 0).execute()
        assertThat(report.spoken).contains("缺少人臉模型檔")
        assertThat(report.spoken).doesNotContain("同步人臉")
    }

    /**
     * 就算有缺，也要說清楚哪些現在就能用 ——
     * 使用者可能只想測 OCR，那他現在就可以出門。
     */
    @Test
    fun `未就緒時仍列出目前可用的功能`() = runTest {
        val report = check(peopleCount = 0, readyLanguages = emptySet()).execute()

        assertThat(report.spoken).contains("目前離線可用的有")
        assertThat(report.spoken).contains("OCR 朗讀")
        assertThat(report.spoken).doesNotContain("人臉辨識、")
    }

    @Test
    fun `翻譯器不可用時視為語言包缺失`() = runTest {
        val useCase = ReadinessCheckUseCase(
            embedder = FakeEmbedder(true),
            people = FakeRepository(2),
            translator = FakeTranslator(isAvailable = false),
            photoSource = FakePhotoSource(true),
        )
        val report = useCase.execute()
        assertThat(report.missingLanguages).isNotEmpty()
        assertThat(report.allReady).isFalse()
    }
}
