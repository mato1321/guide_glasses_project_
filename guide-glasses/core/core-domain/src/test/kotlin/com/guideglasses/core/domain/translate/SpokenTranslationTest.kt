package com.guideglasses.core.domain.translate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpokenTranslationTest {

    @Test
    fun `口述內容加翻成英文會抽出內容`() {
        assertThat(SpokenTranslation.extractText("今天天氣很好翻成英文"))
            .isEqualTo("今天天氣很好")
    }

    @Test
    fun `標點不影響抽取`() {
        assertThat(SpokenTranslation.extractText("今天天氣很好，翻成英文。"))
            .isEqualTo("今天天氣很好")
    }

    /**
     * 客套詞的歧義是本質的（「幫我開門」的「幫我」就是內容），所以一律不從
     * 內容裡刪字。多翻幾個客套字沒有壞處，少翻半句話使用者卻察覺不到。
     */
    @Test
    fun `絕不從要翻譯的內容裡刪字`() {
        // 「我要」在這裡是內容的一部分，被剝掉的話英文會少半句
        assertThat(SpokenTranslation.extractText("我要去車站翻成英文"))
            .isEqualTo("我要去車站")
        assertThat(SpokenTranslation.extractText("幫我開門翻成英文"))
            .isEqualTo("幫我開門")
        assertThat(SpokenTranslation.extractText("把門關上翻成英文"))
            .isEqualTo("把門關上")
    }

    @Test
    fun `翻譯成與翻成與翻作都認得`() {
        assertThat(SpokenTranslation.extractText("我要去車站翻譯成日文"))
            .isEqualTo("我要去車站")
        assertThat(SpokenTranslation.extractText("我要去車站翻作韓文"))
            .isEqualTo("我要去車站")
    }

    /**
     * 「翻譯成」比「翻譯」長，必須先命中，否則邊界會落在「翻譯」之後，
     * 內容莫名多出一個「成」字。
     */
    @Test
    fun `長觸發詞優先避免邊界落錯`() {
        assertThat(SpokenTranslation.extractText("你好翻譯成英文")).isEqualTo("你好")
    }

    @Test
    fun `沒有自帶內容時回傳 null 讓上層改用上一次OCR`() {
        assertThat(SpokenTranslation.extractText("翻成英文")).isNull()
        assertThat(SpokenTranslation.extractText("翻譯")).isNull()
    }

    /**
     * 「這句話」是指涉上一次 OCR 的內容，不是要翻譯的字面文字。
     * 少了這層判斷，使用者會聽到 "this sentence"。
     */
    @Test
    fun `指涉詞不會被當成要翻譯的內容`() {
        assertThat(SpokenTranslation.extractText("這句話翻成英文")).isNull()
        assertThat(SpokenTranslation.extractText("把上面的字翻成英文")).isNull()
        assertThat(SpokenTranslation.extractText("剛剛那句翻成日文")).isNull()
    }

    @Test
    fun `內容放在語言後面也認得`() {
        assertThat(SpokenTranslation.extractText("翻成英文今天天氣很好"))
            .isEqualTo("今天天氣很好")
    }

    /** 要翻譯的內容裡本身出現「翻譯」時，邊界要取最後一個觸發詞。 */
    @Test
    fun `內容含有翻譯兩字時取最後一個觸發詞`() {
        assertThat(SpokenTranslation.extractText("這份翻譯很爛翻成英文"))
            .isEqualTo("這份翻譯很爛")
    }

    @Test
    fun `endsWithRequest 要同時有內容與語言`() {
        assertThat(SpokenTranslation.endsWithRequest("今天天氣很好翻成英文")).isTrue()
        // 沒有內容
        assertThat(SpokenTranslation.endsWithRequest("翻成英文")).isFalse()
        // 沒有語言
        assertThat(SpokenTranslation.endsWithRequest("今天天氣很好翻譯")).isFalse()
        // 根本不是翻譯
        assertThat(SpokenTranslation.endsWithRequest("這是誰")).isFalse()
    }

    @Test
    fun `完全不相關的句子回傳 null`() {
        assertThat(SpokenTranslation.extractText("前面有什麼")).isNull()
        assertThat(SpokenTranslation.extractText("")).isNull()
    }
}
