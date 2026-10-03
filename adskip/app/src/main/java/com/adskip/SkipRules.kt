package com.adskip

/**
 * 识别规则的纯逻辑层：不依赖任何 Android API，便于阅读、调参与单测。
 *
 * 设计原则是「宁可不点，不要点错」：
 *  1. 危险词一票否决（支付、授权、删除类按钮永远不碰）
 *  2. 面积过大的控件不点（那多半是整块广告位，点了会跳到广告落地页）
 *  3. 按可信度分级，最可信的规则优先级最高，风险最高的规则默认关闭
 */

/** 识别分级，ordinal 越小优先级越高。 */
enum class SkipTier(val label: String) {
    /** 开屏广告的「跳过」——语义最明确，风险最低。 */
    SKIP("跳过"),

    /** 明确的广告关闭控件：「关闭广告」「不感兴趣」、ad_close 之类的 id。 */
    CLOSE_AD("关闭广告"),

    /** 单个 × / ✕，需同时满足「可点击 + 面积很小 + 是图标类控件」。 */
    X_CLOSE("叉号"),

    /** 宽松模式：任意「关闭 / Close」文本，默认关闭。 */
    LOOSE_CLOSE("宽松关闭")
}

/** 从一个控件上抽取出来的特征快照。 */
class NodeInfo(
    val text: String,
    val desc: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val clickableNearby: Boolean,
    val childCount: Int,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int
) {
    /** viewId 去掉包名前缀后的短名，例如 com.x:id/ad_close -> ad_close */
    val idShort: String = viewId.substringAfterLast('/').lowercase()

    val area: Int get() = if (width <= 0 || height <= 0) 0 else width * height

    val screenArea: Int get() = if (screenWidth <= 0 || screenHeight <= 0) 0 else screenWidth * screenHeight

    private val haystack: String = "$text $desc".trim()

    /** 原文小写形式。英文正则必须用它——保留空格，`\b` 和 `\s+` 才有意义。 */
    val haystackLower: String = haystack.lowercase()

    /** 去掉空格后的形式，用于中文关键词匹配，让「关闭 广告」也能命中。 */
    val haystackNoSpace: String = haystackLower.replace(" ", "")

    /** 写进日志用的可读标签。 */
    val label: String
        get() {
            val raw = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                viewId.isNotEmpty() -> idShort
                else -> className
            }
            return raw.trim().replace('\n', ' ').take(40)
        }
}

object SkipRules {

    // ───────────────────────── 危险词（一票否决） ─────────────────────────

    /** 中文危险词：出现即放弃该控件。 */
    private val DANGEROUS_CN = listOf(
        "取消", "确定", "确认", "支付", "付款", "购买", "立即购买", "去支付",
        "开通", "续费", "订阅", "升级", "下载", "安装", "卸载", "删除", "移除",
        "注销", "退出", "同意", "允许", "始终允许", "拒绝", "发送", "转账",
        "收款", "还款", "提现", "充值", "绑定", "授权", "登录", "注册", "验证",
        "返回", "放弃", "重置", "恢复", "举报", "投诉", "客服", "评价", "分享",
        "保存", "提交", "下一步", "免密", "指纹", "人脸"
    )

    /** 英文危险词：按单词边界匹配，避免 "look" 命中 "ok"。 */
    private val DANGEROUS_EN = listOf(
        "cancel", "confirm", "ok", "pay", "buy", "purchase", "subscribe",
        "upgrade", "download", "install", "uninstall", "delete", "remove",
        "sign out", "log out", "logout", "agree", "allow", "deny", "send",
        "transfer", "withdraw", "top up", "bind", "authorize", "login",
        "sign in", "register", "back", "submit", "save", "report", "share",
        "continue", "next"
    )

    private val DANGEROUS_EN_REGEX: Regex =
        Regex("\\b(" + DANGEROUS_EN.joinToString("|") { Regex.escape(it) } + ")\\b")

    /**
     * 判断一段文本是否属于「绝对不能点」。
     * 例外：包含「跳过」或 skip 时放行——跳过语义优先级最高。
     */
    fun isDangerous(raw: String): Boolean {
        if (raw.isEmpty()) return false
        val lower = raw.lowercase()
        if (lower.contains("跳过") || lower.contains("skip")) return false
        if (DANGEROUS_CN.any { raw.contains(it) }) return true
        return DANGEROUS_EN_REGEX.containsMatchIn(lower)
    }

    // ───────────────────────── 各级规则特征 ─────────────────────────

    private val SKIP_ID_HINTS = listOf(
        "skip", "ad_skip", "skip_ad", "skipad", "btn_skip", "iv_skip", "tv_skip",
        "skip_btn", "skip_img", "skip_text", "skipbutton", "jump_ad", "ad_jump"
    )

    private val SKIP_EN_REGEX = Regex("\\bskip(\\s*(this\\s*)?(ad|ads|advert|advertisement))?s?\\b")

    private val CLOSE_AD_TEXT = listOf(
        "关闭广告", "关闭该广告", "关闭此广告", "广告关闭", "不感兴趣",
        "屏蔽广告", "屏蔽此广告", "关闭推广", "去掉广告", "关闭推荐"
    )

    private val CLOSE_AD_EN_REGEX =
        Regex("\\b(close|dismiss|hide|remove)\\s+(this\\s+|the\\s+)?(ad|ads|advert|advertisement)\\b")

    /** id 里以独立单词形式出现的 ad / ads，如 ad_close、splash_ad、ad_container。 */
    private val AD_ID_REGEX = Regex("(^|[_.:-])ads?([_.:-]|$)")

    /**
     * id 里以独立单词形式出现的关闭特征。
     * 用分词而不是 contains，否则 "disclosure" 会因为含 "close" 被误判成关闭按钮。
     */
    private val CLOSE_ID_REGEX = Regex("(^|[_.:-])(close|dismiss|dislike)([_.:-]|$)")

    private val CROSS_CHARS = setOf("x", "×", "✕", "✖", "╳", "✗", "✘", "⨯", "χ", "⨉")

    /** 提前编译好，避免每次事件判断都新建 Regex 对象。 */
    private val CLOSE_TEXT_REGEX = Regex("\\bclose\\b")

    private const val MIN_TAP_PX = 24

    // ───────────────────────── 广告上下文判断 ─────────────────────────

    private val AD_CONTEXT_TEXT = listOf("广告", "推广", "赞助", "sponsored", "advertisement", "advert")

    /** 这段文本是否带广告特征（用于判断「当前窗口里有广告」）。 */
    fun looksLikeAdText(raw: String): Boolean {
        if (raw.isEmpty()) return false
        val lower = raw.lowercase()
        return AD_CONTEXT_TEXT.any { lower.contains(it) }
    }

    /** 这个控件 id 是否带广告特征，如 ad_container、splash_ad、xxx_ad。 */
    fun looksLikeAdId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val short = viewId.substringAfterLast('/').lowercase()
        if (short.contains("advert")) return true
        return AD_ID_REGEX.containsMatchIn(short)
    }

    /** 事件文本里是否出现「跳过 / skip」，用于决定要不要做完整扫描。 */
    fun looksLikeSkipText(raw: String): Boolean {
        if (raw.isEmpty()) return false
        return raw.contains("跳过") || SKIP_EN_REGEX.containsMatchIn(raw.lowercase())
    }

    /** 事件文本里是否出现「关闭 / close」，用于决定要不要做完整扫描。 */
    fun looksLikeCloseText(raw: String): Boolean {
        if (raw.isEmpty()) return false
        return raw.contains("关闭") || CLOSE_TEXT_REGEX.containsMatchIn(raw.lowercase())
    }

    // ───────────────────────── 主入口 ─────────────────────────

    /**
     * 给出该控件的识别结果；返回 null 表示不应点击。
     *
     * @param enabled   用户在设置里开启的分级
     * @param adContext 当前窗口里是否出现了广告特征（用于判断通用关闭按钮是否属于广告）
     */
    fun match(node: NodeInfo, enabled: Set<SkipTier>, adContext: Boolean): SkipTier? {
        if (enabled.isEmpty()) return null
        if (node.area <= 0) return null

        // 面积护栏 1：大块区域不点，避免点到广告落地页
        if (node.screenArea > 0 && node.area > node.screenArea / 4) return null
        // 面积护栏 2：有子节点的大容器不点，真正的按钮都是叶子节点
        if (node.childCount > 0 && node.screenArea > 0 && node.area > node.screenArea / 12) return null
        // 面积护栏 3：太小的一律忽略，避免点到装饰性像素
        if (node.width < MIN_TAP_PX || node.height < MIN_TAP_PX) return null

        // 危险词一票否决
        if (isDangerous(node.text) || isDangerous(node.desc)) return null

        if (SkipTier.SKIP in enabled && matchesSkip(node)) return SkipTier.SKIP
        if (SkipTier.CLOSE_AD in enabled && matchesAdClose(node, adContext)) return SkipTier.CLOSE_AD
        if (SkipTier.X_CLOSE in enabled && matchesXClose(node)) return SkipTier.X_CLOSE
        if (SkipTier.LOOSE_CLOSE in enabled && matchesLooseClose(node)) return SkipTier.LOOSE_CLOSE
        return null
    }

    /**
     * 「跳过」：只认文本语义，不要求控件可点击。
     * 因为很多开屏广告的「跳过 3」是不可点击的 TextView，靠父容器的 onTouchEvent 处理，
     * 这种情况交给服务层用 dispatchGesture 兜底。
     */
    private fun matchesSkip(node: NodeInfo): Boolean {
        if (node.haystackNoSpace.contains("跳过")) return true
        if (SKIP_ID_HINTS.any { node.idShort.contains(it) }) return true
        return SKIP_EN_REGEX.containsMatchIn(node.haystackLower)
    }

    private fun matchesAdClose(node: NodeInfo, adContext: Boolean): Boolean {
        if (!node.clickableNearby) return false
        if (CLOSE_AD_TEXT.any { node.haystackNoSpace.contains(it) }) return true
        if (CLOSE_AD_EN_REGEX.containsMatchIn(node.haystackLower)) return true
        // id 特征：ad_close / close_ad / iv_ad_close / ad_dislike ...
        val looksLikeAdId = AD_ID_REGEX.containsMatchIn(node.idShort) ||
            node.idShort.contains("adclose") || node.idShort.contains("closead")
        val looksLikeClose = CLOSE_ID_REGEX.containsMatchIn(node.idShort)
        if (looksLikeAdId && looksLikeClose) return true
        // 窗口里确实有广告 + 通用关闭控件 => 认定是广告的关闭按钮
        return adContext && looksLikeClose && isSmallOnScreen(node)
    }

    /**
     * 单个 × / ✕：条件收紧到「图标类控件 + 可点击 + 尺寸很小」，
     * 这样普通界面里的装饰性字符不会被误点。
     */
    private fun matchesXClose(node: NodeInfo): Boolean {
        if (!node.clickableNearby) return false
        if (!isIconLikeClass(node.className)) return false
        if (!isSmallOnScreen(node)) return false
        val text = stripVariationSelector(node.text)
        val desc = stripVariationSelector(node.desc)
        return isCrossChar(text) || (text.isEmpty() && isCrossChar(desc))
    }

    private fun matchesLooseClose(node: NodeInfo): Boolean {
        if (!node.clickableNearby) return false
        val h = node.haystackNoSpace
        return h == "关闭" || h == "close" || h == "关闭广告" || h == "closead"
    }

    // ───────────────────────── 工具方法 ─────────────────────────

    private fun isCrossChar(raw: String): Boolean =
        raw.length == 1 && CROSS_CHARS.contains(raw.lowercase())

    private fun stripVariationSelector(raw: String): String =
        raw.replace("\uFE0F", "").replace("\u200B", "").trim()

    private fun isIconLikeClass(className: String): Boolean {
        if (className.isEmpty()) return false
        return className.contains("ImageView") ||
            className.contains("ImageButton") ||
            className.contains("TextView") ||
            className.contains("Button") ||
            className.endsWith("View")
    }

    /** 是否小到像个按钮：宽不超过屏宽 22%，高不超过屏高 12%。 */
    private fun isSmallOnScreen(node: NodeInfo): Boolean {
        if (node.screenWidth <= 0 || node.screenHeight <= 0) return false
        return node.width <= node.screenWidth * 0.22 && node.height <= node.screenHeight * 0.12
    }

    /** 把长文本切成便于日志展示的短串。 */
    fun shortLabel(raw: String): String = raw.trim().replace('\n', ' ').take(40)
}
