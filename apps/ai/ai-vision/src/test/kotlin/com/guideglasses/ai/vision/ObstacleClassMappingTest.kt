package com.guideglasses.ai.vision

import com.google.common.truth.Truth.assertThat
import com.guideglasses.ai.vision.YoloObstacleDetector.Companion.CLASS_NAMES
import com.guideglasses.ai.vision.YoloObstacleDetector.Companion.toObstacleClass
import com.guideglasses.core.domain.obstacle.ObstacleClass
import org.junit.Test

/**
 * 模型類別索引 → domain 類別的對照。
 *
 * 這是整個障礙物功能最容易出錯、而且**錯了完全不會報錯**的地方：
 * 索引錯位不會拋例外，只會把腳踏車唸成行人、把人唸成導盲磚。
 * 使用者聽到的是流暢而自信的錯誤答案。
 */
class ObstacleClassMappingTest {

    /** 順序就是 `data.yaml` 的 `names`。改模型時這裡要跟著改。 */
    private val dataYamlNames = listOf(
        "bicycle", "car", "crosswalk", "guidebrick",
        "motorcycle", "obstacle", "people", "sidewalk",
    )

    @Test
    fun `類別清單與 dataYaml 完全一致`() {
        assertThat(CLASS_NAMES).containsExactlyElementsIn(dataYamlNames).inOrder()
    }

    @Test
    fun `每個索引都對到正確的 domain 類別`() {
        val expected = listOf(
            ObstacleClass.BICYCLE,
            ObstacleClass.CAR,
            ObstacleClass.CROSSWALK,
            ObstacleClass.GUIDE_BRICK,
            ObstacleClass.MOTORCYCLE,
            ObstacleClass.OBSTACLE,
            ObstacleClass.PERSON,
            ObstacleClass.SIDEWALK,
        )

        CLASS_NAMES.forEachIndexed { index, name ->
            assertThat(name.toObstacleClass()).isEqualTo(expected[index])
        }
    }

    /**
     * 這個測試存在的唯一理由，是讓「用 ordinal 對照」這個念頭立刻失敗。
     * 八類裡只有兩類的位置剛好相同。
     */
    @Test
    fun `模型索引與 enum 的 ordinal 不一致`() {
        val misaligned = CLASS_NAMES.withIndex().count { (index, name) ->
            name.toObstacleClass()?.ordinal != index
        }

        assertThat(misaligned).isEqualTo(6)
    }

    @Test
    fun `認不得的類別名稱回傳 null 而不是猜一個`() {
        assertThat("traffic_light".toObstacleClass()).isNull()
        assertThat("".toObstacleClass()).isNull()
    }

    @Test
    fun `導引類與危險類分得正確`() {
        val guides = listOf("crosswalk", "guidebrick", "sidewalk")
        val hazards = listOf("bicycle", "car", "motorcycle", "obstacle", "people")

        guides.forEach { assertThat(it.toObstacleClass()!!.isHazard).isFalse() }
        hazards.forEach { assertThat(it.toObstacleClass()!!.isHazard).isTrue() }
    }
}
