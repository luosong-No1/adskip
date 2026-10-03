package com.adskip

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则层单元测试。SkipRules 不依赖任何 Android API，所以能在普通 JVM 上直接跑：
 *
 *     gradle testDebugUnitTest
 *
 * 这些用例同时是「误报防护」的回归测试——改关键词或正则前先跑一遍。
 */
class SkipRulesTest {

    private val all = setOf(SkipTier.SKIP, SkipTier.CLOSE_AD, SkipTier.X_CLOSE)
    private val allPlusLoose = all + SkipTier.LOOSE_CLOSE

    private class Case(
        val name: String,
        val node: NodeInfo,
        val enabled: Set<SkipTier>,
        val adContext: Boolean,
        val expected: SkipTier?
    )

    private fun case(
        name: String,
        node: NodeInfo,
        expected: SkipTier?,
        enabled: Set<SkipTier> = all,
        adContext: Boolean = false
    ) = Case(name, node, enabled, adContext, expected)

    private fun node(
        text: String = "",
        desc: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = true,
        clickableNearby: Boolean = true,
        childCount: Int = 0,
        width: Int = 200,
        height: Int = 80,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ) = NodeInfo(
        text = text,
        desc = desc,
        viewId = viewId,
        className = className,
        clickable = clickable,
        clickableNearby = clickableNearby,
        childCount = childCount,
        width = width,
        height = height,
        screenWidth = screenWidth,
        screenHeight = screenHeight
    )

    private val cases = listOf(
        // ── 应点击：开屏广告 ──
        case(
            "开屏「跳过 3」TextView",
            node(text = "跳过 3", viewId = "com.a:id/skip_text"),
            SkipTier.SKIP
        ),
        case(
            "开屏「跳过广告」不可点击，靠父容器处理触摸",
            node(text = "跳过广告", clickable = false, clickableNearby = false),
            SkipTier.SKIP
        ),
        case(
            "英文 Click to skip（英文正则必须用保留空格的文本）",
            node(text = "Click to skip", clickable = false, clickableNearby = false),
            SkipTier.SKIP
        ),
        case("英文 Skip Ad", node(text = "Skip Ad"), SkipTier.SKIP),
        case("「3s 后跳过」", node(text = "3s 后跳过"), SkipTier.SKIP),
        case(
            "只有 id 是 skip_btn",
            node(viewId = "com.a:id/skip_btn", className = "android.widget.FrameLayout"),
            SkipTier.SKIP
        ),
        case("全宽的「跳过」横条（叶子节点，合法）", node(text = "跳过", width = 1080, height = 120), SkipTier.SKIP),

        // ── 应点击：广告关闭 ──
        case("「关闭广告」", node(text = "关闭广告"), SkipTier.CLOSE_AD),
        case("「不感兴趣」", node(text = "不感兴趣"), SkipTier.CLOSE_AD),
        case(
            "id = iv_ad_close",
            node(viewId = "com.a:id/iv_ad_close", className = "android.widget.ImageView", width = 100, height = 100),
            SkipTier.CLOSE_AD
        ),
        case("英文 Close ad", node(text = "Close ad"), SkipTier.CLOSE_AD),
        case(
            "通用 iv_close + 窗口有广告上下文",
            node(viewId = "com.a:id/iv_close", className = "android.widget.ImageView", width = 90, height = 90),
            SkipTier.CLOSE_AD,
            adContext = true
        ),

        // ── 应点击：单个叉号 ──
        case(
            "单个 ×，ImageView",
            node(text = "×", className = "android.widget.ImageView", width = 90, height = 90),
            SkipTier.X_CLOSE
        ),
        case(
            "单个 ✕ 在 contentDescription 里",
            node(desc = "✕", className = "android.widget.ImageButton", width = 80, height = 80),
            SkipTier.X_CLOSE
        ),
        case(
            "单个小写 x",
            node(text = "x", className = "android.widget.TextView", width = 70, height = 70),
            SkipTier.X_CLOSE
        ),

        // ── 必须不点：危险按钮 ──
        case("「立即支付」", node(text = "立即支付"), null, adContext = true),
        case("「取消」", node(text = "取消"), null, adContext = true),
        case("「确定」", node(text = "确定"), null, adContext = true),
        case("「同意并继续」", node(text = "同意并继续"), null, adContext = true),
        case("「下载」", node(text = "下载"), null, adContext = true),
        case("Cancel（英文）", node(text = "Cancel"), null, adContext = true),
        case("危险词优先于「跳过」", node(text = "付款", desc = "跳过"), null, adContext = true),

        // ── 必须不点：面积护栏 ──
        case("占据大半屏的「跳过」（疑似整块广告位）", node(text = "跳过", width = 1080, height = 2000), null),
        case("有子节点的大容器（点中心会误触广告）", node(text = "跳过", childCount = 2, width = 1080, height = 300), null),
        case("过小的「跳过」（10x10）", node(text = "跳过", width = 10, height = 10), null),

        // ── 必须不点：误报防护 ──
        case(
            "id = disclosure（曾被 contains(\"close\") 误判）",
            node(viewId = "com.a:id/disclosure", width = 100, height = 60),
            null,
            adContext = true
        ),
        case(
            "通用 iv_close 但窗口里没有广告",
            node(viewId = "com.a:id/iv_close", className = "android.widget.ImageView", width = 90, height = 90),
            null
        ),
        case("普通对话框的「关闭」，宽松模式未开启", node(text = "关闭"), null),
        case("普通对话框的「关闭」，宽松模式已开启", node(text = "关闭"), SkipTier.LOOSE_CLOSE, enabled = allPlusLoose),
        case(
            "desc=关闭 且不可点击",
            node(desc = "关闭", clickable = false, clickableNearby = false),
            null,
            enabled = allPlusLoose
        ),
        case(
            "「×」但尺寸过大，不像关闭按钮",
            node(text = "×", className = "android.widget.ImageView", width = 600, height = 400),
            null,
            adContext = true
        ),

        // ── 分级开关必须真的生效 ──
        case(
            "关掉「跳过」分级后，开屏跳过不再命中",
            node(text = "跳过 3"),
            null,
            enabled = setOf(SkipTier.CLOSE_AD, SkipTier.X_CLOSE)
        ),
        case("全部分级关掉后什么都不点", node(text = "跳过 3"), null, enabled = emptySet())
    )

    @Test
    fun allMatchCasesBehaveAsExpected() {
        val failures = cases.mapNotNull { item ->
            val actual = SkipRules.match(item.node, item.enabled, item.adContext)
            if (actual == item.expected) {
                null
            } else {
                "${item.name}：期望 ${item.expected}，实际 $actual"
            }
        }
        assertTrue("以下用例不符合预期：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun adContextDetectionDoesNotFlagOrdinaryViews() {
        assertTrue(SkipRules.looksLikeAdText("广告"))
        assertTrue(SkipRules.looksLikeAdText("赞助内容"))
        assertTrue(!SkipRules.looksLikeAdText("确认订单"))

        assertTrue(SkipRules.looksLikeAdId("com.a:id/ad_container"))
        assertTrue(SkipRules.looksLikeAdId("com.a:id/splash_ad"))
        assertTrue(SkipRules.looksLikeAdId("com.a:id/advert_view"))
        // 下面这些含 "ad" 字样但不是广告，必须为 false
        assertTrue(!SkipRules.looksLikeAdId("com.a:id/download_button"))
        assertTrue(!SkipRules.looksLikeAdId("com.a:id/header"))
        assertTrue(!SkipRules.looksLikeAdId("com.a:id/adsorber"))
    }

    @Test
    fun eventHintsAreDetectedCorrectly() {
        assertTrue(SkipRules.looksLikeSkipText("跳过 3"))
        assertTrue(SkipRules.looksLikeSkipText("click to skip"))
        assertTrue(!SkipRules.looksLikeSkipText("skipping rope"))

        assertTrue(SkipRules.looksLikeCloseText("关闭窗口"))
        assertTrue(!SkipRules.looksLikeCloseText("clock"))
    }

    @Test
    fun dangerousWordsCoverBothLanguagesAndSkipWins() {
        assertTrue(SkipRules.isDangerous("立即购买"))
        assertTrue(SkipRules.isDangerous("Cancel"))
        assertTrue(SkipRules.isDangerous("Delete account"))
        // "look" 里含 "ok"，但按单词边界不能命中
        assertTrue(!SkipRules.isDangerous("Look at this"))
        // 跳过语义优先
        assertTrue(!SkipRules.isDangerous("跳过"))
        assertTrue(!SkipRules.isDangerous("Skip ad"))
    }
}
