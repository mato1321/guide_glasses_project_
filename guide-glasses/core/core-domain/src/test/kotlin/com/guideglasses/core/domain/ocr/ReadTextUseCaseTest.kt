package com.guideglasses.core.domain.ocr

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReadTextUseCaseTest {

    private class FakeFrameSource(
        private val result: AppResult<CameraFrame> = AppResult.Success(FRAME),
    ) : FrameSource {
        var lastRequest: CaptureRequest? = null

        override fun frames(request: CaptureRequest): Flow<AppResult<CameraFrame>> = flowOf(result)

        override suspend fun captureOnce(request: CaptureRequest): AppResult<CameraFrame> {
            lastRequest = request
            return result
        }
    }

    private class FakeRecognizer(
        private val result: AppResult<RecognizedText>,
        override val isAvailable: Boolean = true,
    ) : TextRecognizer {
        var callCount = 0

        override suspend fun recognize(frame: CameraFrame): AppResult<RecognizedText> {
            callCount++
            return result
        }
    }

    private fun onDevice(text: String, blocks: List<TextBlock> = emptyList()) =
        AppResult.Success(
            RecognizedText(text, blocks, RecognizedText.Source.ON_DEVICE),
        )

    private fun cloud(text: String) =
        AppResult.Success(RecognizedText(text, emptyList(), RecognizedText.Source.CLOUD))

    @Test
    fun `端側結果良好時不呼叫雲端`() = runTest {
        val local = FakeRecognizer(onDevice("每次一顆，每日三次，飯後服用。"))
        val remote = FakeRecognizer(cloud("不該被呼叫"))
        val useCase = ReadTextUseCase(FakeFrameSource(), local, remote)

        val outcome = useCase.execute()

        assertThat(outcome).isInstanceOf(ReadTextUseCase.Outcome.Success::class.java)
        val success = outcome as ReadTextUseCase.Outcome.Success
        assertThat(success.source).isEqualTo(RecognizedText.Source.ON_DEVICE)
        assertThat(remote.callCount).isEqualTo(0)
    }

    @Test
    fun `端側結果不可靠時往雲端`() = runTest {
        // 只有一個字，低於 MIN_TRUSTWORTHY_LENGTH
        val local = FakeRecognizer(onDevice("藥"))
        val remote = FakeRecognizer(cloud("每次一顆，每日三次，飯後服用。"))
        val useCase = ReadTextUseCase(FakeFrameSource(), local, remote)

        val outcome = useCase.execute() as ReadTextUseCase.Outcome.Success

        assertThat(remote.callCount).isEqualTo(1)
        assertThat(outcome.source).isEqualTo(RecognizedText.Source.CLOUD)
    }

    @Test
    fun `辨識破碎時也會往雲端`() = runTest {
        // 大量單字元區塊 = 辨識破碎
        val fragmented = listOf(
            TextBlock("藥", 0.1f), TextBlock("每", 0.1f), TextBlock("次", 0.1f),
            TextBlock("一", 0.1f), TextBlock("顆", 0.1f),
        )
        val local = FakeRecognizer(onDevice("藥每次一顆", fragmented))
        val remote = FakeRecognizer(cloud("每次一顆，每日三次，飯後服用。"))
        val useCase = ReadTextUseCase(FakeFrameSource(), local, remote)

        useCase.execute()

        assertThat(remote.callCount).isEqualTo(1)
    }

    @Test
    fun `沒有雲端時退回端側結果`() = runTest {
        val local = FakeRecognizer(onDevice("藥"))
        val useCase = ReadTextUseCase(FakeFrameSource(), local, cloudRecognizer = null)

        val outcome = useCase.execute()

        // 品質不佳但有東西總比沒有好
        assertThat(outcome).isInstanceOf(ReadTextUseCase.Outcome.Success::class.java)
    }

    @Test
    fun `雲端也失敗時退回端側結果`() = runTest {
        val local = FakeRecognizer(onDevice("藥"))
        val remote = FakeRecognizer(AppResult.Failure(AppError.NoNetwork()))
        val useCase = ReadTextUseCase(FakeFrameSource(), local, remote)

        val outcome = useCase.execute()

        assertThat(outcome).isInstanceOf(ReadTextUseCase.Outcome.Success::class.java)
    }

    @Test
    fun `端側不可用時直接走雲端`() = runTest {
        val local = FakeRecognizer(onDevice("不該被呼叫"), isAvailable = false)
        val remote = FakeRecognizer(cloud("雲端辨識的完整內容。"))
        val useCase = ReadTextUseCase(FakeFrameSource(), local, remote)

        val outcome = useCase.execute() as ReadTextUseCase.Outcome.Success

        assertThat(local.callCount).isEqualTo(0)
        assertThat(outcome.source).isEqualTo(RecognizedText.Source.CLOUD)
    }

    @Test
    fun `畫面中沒有文字時回報 NoTextFound`() = runTest {
        val local = FakeRecognizer(onDevice("   "))
        val useCase = ReadTextUseCase(FakeFrameSource(), local)

        assertThat(useCase.execute()).isEqualTo(ReadTextUseCase.Outcome.NoTextFound)
    }

    @Test
    fun `相機失敗時把錯誤往上帶`() = runTest {
        val source = FakeFrameSource(
            AppResult.Failure(AppError.PermissionDenied("android.permission.CAMERA")),
        )
        val useCase = ReadTextUseCase(source, FakeRecognizer(onDevice("x")))

        val outcome = useCase.execute()

        assertThat(outcome).isInstanceOf(ReadTextUseCase.Outcome.Failed::class.java)
        assertThat((outcome as ReadTextUseCase.Outcome.Failed).error)
            .isInstanceOf(AppError.PermissionDenied::class.java)
    }

    @Test
    fun `招牌模式只唸最大的那塊字`() = runTest {
        val blocks = listOf(
            TextBlock("八德路二段", 0.05f),
            TextBlock("金好吃自助餐", 0.35f),
            TextBlock("營業時間 11:00-20:00", 0.04f),
        )
        val local = FakeRecognizer(
            onDevice("八德路二段 金好吃自助餐 營業時間 11:00-20:00", blocks),
        )
        val useCase = ReadTextUseCase(FakeFrameSource(), local)

        val outcome = useCase.execute(OcrMode.SIGN) as ReadTextUseCase.Outcome.Success

        val spoken = buildList { while (true) { add(outcome.session.next() ?: break) } }
            .joinToString("")
        assertThat(spoken).contains("金好吃自助餐")
        assertThat(spoken).doesNotContain("營業時間")
    }

    @Test
    fun `文件模式唸出完整內容`() = runTest {
        val blocks = listOf(TextBlock("標題", 0.3f), TextBlock("內文", 0.05f))
        val local = FakeRecognizer(onDevice("標題\n這是內文的完整內容需要被唸出來。", blocks))
        val useCase = ReadTextUseCase(FakeFrameSource(), local)

        val outcome = useCase.execute(OcrMode.DOCUMENT) as ReadTextUseCase.Outcome.Success

        val spoken = buildList { while (true) { add(outcome.session.next() ?: break) } }
            .joinToString("")
        assertThat(spoken).contains("標題")
        assertThat(spoken).contains("這是內文")
    }

    @Test
    fun `OCR 用的解析度比障礙物偵測高`() = runTest {
        val source = FakeFrameSource()
        val useCase = ReadTextUseCase(source, FakeRecognizer(onDevice("完整的一段文字。")))

        useCase.execute()

        // 文字比車輛小得多，640 會讓小字糊掉
        assertThat(source.lastRequest!!.longEdgePixels).isEqualTo(1280)
        assertThat(source.lastRequest!!.jpegQuality).isEqualTo(90)
    }

    private companion object {
        val FRAME = CameraFrame(
            bytes = ByteArray(1024),
            format = CameraFrame.Format.JPEG,
            width = 1280,
            height = 960,
            rotationDegrees = 0,
            timestampMillis = 0L,
        )
    }
}

class ReadingSessionTest {

    private fun session(vararg segments: String) = ReadingSession(segments.toList())

    @Test
    fun `依序取出每一段`() {
        val s = session("第一段", "第二段", "第三段")

        assertThat(s.next()).isEqualTo("第一段")
        assertThat(s.next()).isEqualTo("第二段")
        assertThat(s.next()).isEqualTo("第三段")
        assertThat(s.next()).isNull()
    }

    @Test
    fun `唸完之後 isFinished 為真`() {
        val s = session("唯一一段")

        s.next()

        assertThat(s.isFinished).isTrue()
        assertThat(s.remaining).isEqualTo(0)
    }

    @Test
    fun `上一段會回到剛才那段的前一段`() {
        val s = session("甲", "乙", "丙")
        s.next() // 甲
        s.next() // 乙

        // 剛唸完「乙」，說「上一段」使用者要的是「甲」
        assertThat(s.previous()).isEqualTo("甲")
    }

    @Test
    fun `在第一段說上一段不會越界`() {
        val s = session("甲", "乙")
        s.next() // 甲

        assertThat(s.previous()).isEqualTo("甲")
        assertThat(s.previous()).isEqualTo("甲")
    }

    @Test
    fun `重聽會唸同一段且不影響進度`() {
        val s = session("甲", "乙", "丙")
        s.next() // 甲
        s.next() // 乙

        assertThat(s.repeatCurrent()).isEqualTo("乙")
        assertThat(s.next()).isEqualTo("丙")
    }

    @Test
    fun `restart 從頭開始`() {
        val s = session("甲", "乙")
        s.next()
        s.next()

        s.restart()

        assertThat(s.next()).isEqualTo("甲")
    }

    @Test
    fun `skipToEnd 放棄剩下的內容`() {
        val s = session("甲", "乙", "丙")
        s.next()

        s.skipToEnd()

        assertThat(s.isFinished).isTrue()
        assertThat(s.next()).isNull()
    }

    @Test
    fun `空白段落會被過濾掉`() {
        val s = ReadingSession(listOf("甲", "", "   ", "乙"))

        assertThat(s.total).isEqualTo(2)
    }

    @Test
    fun `空 session 的操作都安全`() {
        val s = ReadingSession(emptyList())

        assertThat(s.isEmpty).isTrue()
        assertThat(s.next()).isNull()
        assertThat(s.previous()).isNull()
        assertThat(s.repeatCurrent()).isNull()
        assertThat(s.progressDescription()).isEqualTo("沒有內容")
    }

    @Test
    fun `進度描述正確`() {
        val s = session("甲", "乙", "丙")

        s.next()
        assertThat(s.progressDescription()).isEqualTo("第 1 段，共 3 段")

        s.next()
        assertThat(s.progressDescription()).isEqualTo("第 2 段，共 3 段")
    }

    @Test
    fun `remaining 正確反映剩餘段數`() {
        val s = session("甲", "乙", "丙")

        assertThat(s.remaining).isEqualTo(3)
        s.next()
        assertThat(s.remaining).isEqualTo(2)
    }
}
