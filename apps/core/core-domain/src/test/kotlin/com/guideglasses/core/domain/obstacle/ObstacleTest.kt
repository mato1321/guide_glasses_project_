package com.guideglasses.core.domain.obstacle

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.announce.AnnouncementPriority
import org.junit.Test

class ObstacleDistanceEstimatorTest {

    private val estimator = ObstacleDistanceEstimator()

    private fun detection(type: ObstacleClass, width: Float) =
        Detection(type, left = 0.4f, top = 0.4f, width = width, height = 0.3f, confidence = 0.9f)

    @Test
    fun `佔畫面愈寬距離愈近`() {
        val near = estimator.estimateMeters(detection(ObstacleClass.CAR, 0.5f))!!
        val far = estimator.estimateMeters(detection(ObstacleClass.CAR, 0.1f))!!
        assertThat(near).isLessThan(far)
    }

    @Test
    fun `沒有已知寬度的類別無法估距`() {
        // 斑馬線的寬度隨路口大小變化，用寬度反推沒有意義。
        assertThat(estimator.estimateMeters(detection(ObstacleClass.CROSSWALK, 0.5f))).isNull()
    }

    @Test
    fun `車輛佔畫面一半時距離在合理範圍`() {
        // 1.8m 寬的車佔畫面一半，66 度視角下應該約 2-3 公尺。
        val meters = estimator.estimateMeters(detection(ObstacleClass.CAR, 0.5f))!!
        assertThat(meters).isGreaterThan(2f)
        assertThat(meters).isLessThan(4f)
    }
}

class DangerClassifierTest {

    private val classifier = DangerClassifier()

    private fun hazard() = Detection(
        ObstacleClass.CAR, left = 0.4f, top = 0.4f, width = 0.2f, height = 0.3f, confidence = 0.9f,
    )

    private fun guide() = Detection(
        ObstacleClass.CROSSWALK, left = 0.3f, top = 0.6f, width = 0.4f, height = 0.2f,
        confidence = 0.8f,
    )

    @Test
    fun `兩公尺內的危險物打斷一切`() {
        val priority = classifier.priorityFor(hazard(), distanceMeters = 1.5f, userAsked = false)
        assertThat(priority).isEqualTo(AnnouncementPriority.CRITICAL)
    }

    @Test
    fun `五公尺內的危險物用導航優先級`() {
        val priority = classifier.priorityFor(hazard(), distanceMeters = 4f, userAsked = false)
        assertThat(priority).isEqualTo(AnnouncementPriority.NAVIGATION)
    }

    /** 走在路上不該一直有聲音 —— 遠的東西沒問就不講。 */
    @Test
    fun `太遠的危險物不主動播報`() {
        val priority = classifier.priorityFor(hazard(), distanceMeters = 20f, userAsked = false)
        assertThat(priority).isNull()
    }

    @Test
    fun `使用者主動問時一律回應且不打斷危險警示`() {
        val priority = classifier.priorityFor(hazard(), distanceMeters = 20f, userAsked = true)
        assertThat(priority).isEqualTo(AnnouncementPriority.USER_RESPONSE)
    }

    /** 斑馬線是引路資訊，不該打斷使用者正在聽的東西。 */
    @Test
    fun `導引類永遠不打斷`() {
        val priority = classifier.priorityFor(guide(), distanceMeters = 1f, userAsked = false)
        assertThat(priority).isEqualTo(AnnouncementPriority.AMBIENT)
    }

    @Test
    fun `無法估距的危險物不主動播報`() {
        val priority = classifier.priorityFor(hazard(), distanceMeters = null, userAsked = false)
        assertThat(priority).isNull()
    }
}

class ObstacleAnnouncementComposerTest {

    private val composer = ObstacleAnnouncementComposer()

    private fun at(type: ObstacleClass, centerX: Float, width: Float = 0.2f) = Detection(
        type, left = centerX - width / 2f, top = 0.4f, width = width, height = 0.3f,
        confidence = 0.9f,
    )

    @Test
    fun `播報含方位與類別`() {
        val text = composer.compose(at(ObstacleClass.CAR, centerX = 0.85f))
        assertThat(text).contains("右前方")
        assertThat(text).contains("車輛")
    }

    @Test
    fun `沒有已知寬度時不報距離`() {
        val text = composer.compose(at(ObstacleClass.CROSSWALK, centerX = 0.5f))
        assertThat(text).contains("正前方")
        assertThat(text).contains("斑馬線")
        assertThat(text).doesNotContain("公尺")
    }

    @Test
    fun `沒有障礙物時明確說出來`() {
        assertThat(composer.composeSummary(emptyList())).contains("沒有偵測到障礙物")
    }

    /** 唸太多使用者記不住，而且唸完可能已經走過去了。 */
    @Test
    fun `最多只唸三個`() {
        val many = List(6) { at(ObstacleClass.PERSON, centerX = 0.5f) }
        val summary = composer.composeSummary(many)
        assertThat(summary.split("；")).hasSize(3)
    }

    @Test
    fun `危險物排在導引物前面`() {
        val summary = composer.composeSummary(
            listOf(
                at(ObstacleClass.CROSSWALK, centerX = 0.5f, width = 0.6f),
                at(ObstacleClass.CAR, centerX = 0.2f, width = 0.1f),
            ),
        )
        assertThat(summary.indexOf("車輛")).isLessThan(summary.indexOf("斑馬線"))
    }
}

class ObstacleDebouncerTest {

    private fun detection(type: ObstacleClass = ObstacleClass.CAR, centerX: Float = 0.5f) =
        Detection(
            type, left = centerX - 0.1f, top = 0.4f, width = 0.2f, height = 0.3f,
            confidence = 0.9f,
        )

    /** 沒有這層，2fps 下同一台車會被唸三十遍，功能直接不能用。 */
    @Test
    fun `時間窗內同一個物體只播一次`() {
        var clock = 0L
        val debouncer = ObstacleDebouncer(windowMillis = 5_000L, now = { clock })

        assertThat(debouncer.shouldAnnounce(detection())).isTrue()
        clock = 1_000L
        assertThat(debouncer.shouldAnnounce(detection())).isFalse()
        clock = 4_999L
        assertThat(debouncer.shouldAnnounce(detection())).isFalse()
    }

    @Test
    fun `超過時間窗之後可以再播`() {
        var clock = 0L
        val debouncer = ObstacleDebouncer(windowMillis = 5_000L, now = { clock })

        debouncer.shouldAnnounce(detection())
        clock = 5_001L
        assertThat(debouncer.shouldAnnounce(detection())).isTrue()
    }

    @Test
    fun `不同方位視為不同物體`() {
        val debouncer = ObstacleDebouncer(now = { 0L })
        assertThat(debouncer.shouldAnnounce(detection(centerX = 0.1f))).isTrue()
        assertThat(debouncer.shouldAnnounce(detection(centerX = 0.9f))).isTrue()
    }

    @Test
    fun `不同類別視為不同物體`() {
        val debouncer = ObstacleDebouncer(now = { 0L })
        assertThat(debouncer.shouldAnnounce(detection(ObstacleClass.CAR))).isTrue()
        assertThat(debouncer.shouldAnnounce(detection(ObstacleClass.PERSON))).isTrue()
    }

    /**
     * 座標會在連續影格間微幅抖動。若用精確座標當鍵，等於沒有去抖動。
     */
    @Test
    fun `輕微移動仍視為同一個物體`() {
        val debouncer = ObstacleDebouncer(now = { 0L })
        assertThat(debouncer.shouldAnnounce(detection(centerX = 0.50f))).isTrue()
        assertThat(debouncer.shouldAnnounce(detection(centerX = 0.55f))).isFalse()
    }
}
