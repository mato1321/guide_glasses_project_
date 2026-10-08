package com.guideglasses.core.domain.translate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TargetLanguageTest {

    @Test
    fun `辨識常見說法`() {
        assertThat(TargetLanguage.fromSpoken("翻成英文")).isEqualTo(TargetLanguage.ENGLISH)
        assertThat(TargetLanguage.fromSpoken("翻譯成日語")).isEqualTo(TargetLanguage.JAPANESE)
        assertThat(TargetLanguage.fromSpoken("翻成越南話")).isEqualTo(TargetLanguage.VIETNAMESE)
    }

    @Test
    fun `標點與全形不影響判定`() {
        assertThat(TargetLanguage.fromSpoken("翻成英文。")).isEqualTo(TargetLanguage.ENGLISH)
        assertThat(TargetLanguage.fromSpoken("translate to ＥＮＧＬＩＳＨ"))
            .isEqualTo(TargetLanguage.ENGLISH)
    }

    /**
     * 中文語序把目標語言放在「翻成」之後，取第一個命中會答錯。
     * 這是這個函式最容易寫錯的地方。
     */
    @Test
    fun `同時出現兩種語言時取最後出現的`() {
        assertThat(TargetLanguage.fromSpoken("把這句英文翻成日文"))
            .isEqualTo(TargetLanguage.JAPANESE)
        assertThat(TargetLanguage.fromSpoken("這個日文翻成中文"))
            .isEqualTo(TargetLanguage.CHINESE)
    }

    @Test
    fun `沒有語言時回傳 null 而不是預設值`() {
        // 預設值屬於 UseCase 的領域決策，不該由解析函式決定。
        assertThat(TargetLanguage.fromSpoken("翻譯一下")).isNull()
        assertThat(TargetLanguage.fromSpoken("")).isNull()
    }

    /**
     * 兩字母語言碼刻意不列入 aliases，否則英文單字裡的字母序列會誤命中。
     */
    @Test
    fun `英文單字不會誤判成語言`() {
        assertThat(TargetLanguage.fromSpoken("seven eleven")).isNull()
        assertThat(TargetLanguage.fromSpoken("id card")).isNull()
    }

    @Test
    fun `可從語言碼或中文名稱解析`() {
        assertThat(TargetLanguage.fromCodeOrName("ja")).isEqualTo(TargetLanguage.JAPANESE)
        assertThat(TargetLanguage.fromCodeOrName("EN")).isEqualTo(TargetLanguage.ENGLISH)
        assertThat(TargetLanguage.fromCodeOrName("韓文")).isEqualTo(TargetLanguage.KOREAN)
        assertThat(TargetLanguage.fromCodeOrName("克林貢語")).isNull()
    }
}
