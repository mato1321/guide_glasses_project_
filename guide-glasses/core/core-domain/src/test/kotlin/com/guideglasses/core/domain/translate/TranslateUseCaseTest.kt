package com.guideglasses.core.domain.translate

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TranslateUseCaseTest {

    private class FakeTranslator(
        override val isAvailable: Boolean = true,
        private var ready: Boolean = true,
        private val prepareResult: AppResult<Unit> = AppResult.Success(Unit),
        private val translateResult: AppResult<String> = AppResult.Success("hello"),
    ) : Translator {
        var prepareCalls = 0
        var translatedText: String? = null
        var translatedTarget: TargetLanguage? = null

        override suspend fun isReady(target: TargetLanguage) = ready

        override suspend fun prepare(target: TargetLanguage): AppResult<Unit> {
            prepareCalls++
            if (prepareResult is AppResult.Success) ready = true
            return prepareResult
        }

        override suspend fun translate(
            text: String,
            target: TargetLanguage,
        ): AppResult<String> {
            translatedText = text
            translatedTarget = target
            return translateResult
        }
    }

    @Test
    fun `翻譯成功時回傳結果與目標語言`() = runTest {
        val translator = FakeTranslator(translateResult = AppResult.Success("thank you"))
        val useCase = TranslateUseCase(translator)

        val outcome = useCase.execute("謝謝", TargetLanguage.ENGLISH)

        assertThat(outcome).isInstanceOf(TranslateUseCase.Outcome.Translated::class.java)
        val translated = outcome as TranslateUseCase.Outcome.Translated
        assertThat(translated.text).isEqualTo("thank you")
        assertThat(translated.target).isEqualTo(TargetLanguage.ENGLISH)
        assertThat(translated.truncated).isFalse()
    }

    @Test
    fun `沒有指定語言時套用預設`() = runTest {
        val translator = FakeTranslator()
        TranslateUseCase(translator, defaultTarget = TargetLanguage.JAPANESE)
            .execute("謝謝", target = null)

        assertThat(translator.translatedTarget).isEqualTo(TargetLanguage.JAPANESE)
    }

    @Test
    fun `沒有內容時不呼叫翻譯器`() = runTest {
        val translator = FakeTranslator()
        val useCase = TranslateUseCase(translator)

        assertThat(useCase.execute(null)).isEqualTo(TranslateUseCase.Outcome.NothingToTranslate)
        assertThat(useCase.execute("   ")).isEqualTo(TranslateUseCase.Outcome.NothingToTranslate)
        assertThat(translator.translatedText).isNull()
    }

    @Test
    fun `翻譯器不可用時回傳 Unavailable`() = runTest {
        val outcome = TranslateUseCase(FakeTranslator(isAvailable = false)).execute("謝謝")
        assertThat(outcome).isEqualTo(TranslateUseCase.Outcome.Unavailable)
    }

    /**
     * 語言包首次下載可能要幾十秒。上層必須有機會先播報提示，
     * 否則看不見畫面的使用者會以為系統當掉。
     */
    @Test
    fun `語言包未就緒時先通知再下載`() = runTest {
        val translator = FakeTranslator(ready = false)
        val notified = mutableListOf<TargetLanguage>()

        val outcome = TranslateUseCase(translator).execute(
            text = "謝謝",
            target = TargetLanguage.KOREAN,
            onPreparing = { notified += it },
        )

        assertThat(notified).containsExactly(TargetLanguage.KOREAN)
        assertThat(translator.prepareCalls).isEqualTo(1)
        assertThat(outcome).isInstanceOf(TranslateUseCase.Outcome.Translated::class.java)
    }

    @Test
    fun `語言包已就緒時不重複下載也不發通知`() = runTest {
        val translator = FakeTranslator(ready = true)
        var notified = false

        TranslateUseCase(translator).execute("謝謝", onPreparing = { notified = true })

        assertThat(translator.prepareCalls).isEqualTo(0)
        assertThat(notified).isFalse()
    }

    @Test
    fun `下載失敗時回傳原始錯誤讓上層播報人話`() = runTest {
        val translator = FakeTranslator(
            ready = false,
            prepareResult = AppResult.Failure(AppError.NoNetwork("斷線")),
        )

        val outcome = TranslateUseCase(translator).execute("謝謝")

        assertThat(outcome).isInstanceOf(TranslateUseCase.Outcome.Failed::class.java)
        assertThat((outcome as TranslateUseCase.Outcome.Failed).error)
            .isInstanceOf(AppError.NoNetwork::class.java)
    }

    @Test
    fun `過長內容截斷並標示`() = runTest {
        val translator = FakeTranslator()
        val long = "字".repeat(TranslateUseCase.MAX_CHARS + 500)

        val outcome = TranslateUseCase(translator).execute(long)

        assertThat(translator.translatedText).hasLength(TranslateUseCase.MAX_CHARS)
        assertThat((outcome as TranslateUseCase.Outcome.Translated).truncated).isTrue()
    }

    /**
     * 翻譯器回空字串代表出了問題，不該把空內容當成成功 ——
     * 那會讓使用者聽到一片沉默而不知道發生什麼事。
     */
    @Test
    fun `翻譯結果為空視為失敗`() = runTest {
        val translator = FakeTranslator(translateResult = AppResult.Success("   "))
        val outcome = TranslateUseCase(translator).execute("謝謝")
        assertThat(outcome).isInstanceOf(TranslateUseCase.Outcome.Failed::class.java)
    }
}
