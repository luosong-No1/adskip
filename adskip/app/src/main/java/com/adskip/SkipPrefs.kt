package com.adskip

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储。全部落在本机 SharedPreferences，不出设备。
 */
class SkipPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ───────────────────────── 开关 ─────────────────────────

    /** 应用内总开关。关掉后不做任何识别，但系统里的无障碍服务仍处于开启状态。 */
    var masterEnabled: Boolean
        get() = sp.getBoolean(KEY_MASTER, true)
        set(value) = sp.edit().putBoolean(KEY_MASTER, value).apply()

    var skipEnabled: Boolean
        get() = sp.getBoolean(KEY_SKIP, true)
        set(value) = sp.edit().putBoolean(KEY_SKIP, value).apply()

    var closeAdEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOSE_AD, true)
        set(value) = sp.edit().putBoolean(KEY_CLOSE_AD, value).apply()

    var xCloseEnabled: Boolean
        get() = sp.getBoolean(KEY_X_CLOSE, true)
        set(value) = sp.edit().putBoolean(KEY_X_CLOSE, value).apply()

    /** 宽松模式默认关闭：它会把普通对话框的关闭按钮也点掉。 */
    var looseCloseEnabled: Boolean
        get() = sp.getBoolean(KEY_LOOSE_CLOSE, false)
        set(value) = sp.edit().putBoolean(KEY_LOOSE_CLOSE, value).apply()

    /** 用户自定义排除的包名（支持前缀，例如填 com.tencent 会连子包一起排除）。 */
    var userExcluded: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDE, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_EXCLUDE, value).apply()

    fun enabledTiers(): Set<SkipTier> {
        val set = LinkedHashSet<SkipTier>(4)
        if (skipEnabled) set.add(SkipTier.SKIP)
        if (closeAdEnabled) set.add(SkipTier.CLOSE_AD)
        if (xCloseEnabled) set.add(SkipTier.X_CLOSE)
        if (looseCloseEnabled) set.add(SkipTier.LOOSE_CLOSE)
        return set
    }

    /** 该包名是否被用户排除（精确匹配或前缀匹配）。 */
    fun isExcluded(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return userExcluded.any { pkg.equals(it, true) || pkg.startsWith("$it.", true) }
    }

    // ───────────────────────── 点击记录 ─────────────────────────

    @Synchronized
    fun appendLog(entry: String) {
        val current = sp.getString(KEY_LOG, "").orEmpty()
        val lines = (entry + "\n" + current)
            .lines()
            .filter { it.isNotBlank() }
            .take(MAX_LOG_LINES)
        sp.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun readLog(): List<String> =
        sp.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() }

    fun clearLog() = sp.edit().remove(KEY_LOG).apply()

    companion object {
        private const val FILE = "adskip"

        private const val KEY_MASTER = "master_enabled"
        private const val KEY_SKIP = "tier_skip"
        private const val KEY_CLOSE_AD = "tier_close_ad"
        private const val KEY_X_CLOSE = "tier_x_close"
        private const val KEY_LOOSE_CLOSE = "tier_loose_close"
        private const val KEY_EXCLUDE = "excluded_packages"
        private const val KEY_LOG = "click_log"

        private const val MAX_LOG_LINES = 40

        /** 两次点击之间的最小间隔，防止界面抖动时连点。 */
        const val CLICK_COOLDOWN_MS = 700L

        /** 每分钟最多点击次数，兜底防连点。 */
        const val MAX_CLICKS_PER_MINUTE = 25

        /**
         * 内置保护名单：这些包名下的界面永远不介入。
         *
         * 想放开某个应用（例如允许在支付宝里跳过开屏广告），
         * 把对应那行删掉重新编译即可。删之前请想清楚误点的后果。
         */
        private val BUILT_IN_PROTECTED = listOf(
            // 系统界面与关键系统组件
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.incallui",
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.keyguard",
            "com.samsung.android.app.telephonyui",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.huawei.systemmanager",
            // 桌面启动器。从应用退回桌面会触发窗口切换并进入扫描期，
            // 而桌面上不存在广告，误点文件夹/小部件的关闭按钮只会帮倒忙。
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.oneplus.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
            "com.teslacoilsw.launcher",
            "com.microsoft.launcher",
            "com.transsion.XOSLauncher",
            // 输入法：弹出键盘同样会触发窗口变化
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou",
            "com.baidu.input",
            "com.iflytek.inputmethod",
            "com.tencent.qqpinyin",
            // 电话与短信：误点代价远大于收益
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.contacts",
            "com.android.mms",
            "com.google.android.apps.messaging",
            // 本应用自己
            "com.adskip",
            // 银行、支付与金融类
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.icbc",
            "com.chinamworld",
            "cmb.pb",
            "com.cmbchina",
            "com.android.bankabc",
            "com.bankcomm",
            "com.yitong.mbank.psbc",
            "cn.com.spdb.mobilebank.per",
            "com.spdbccc.app",
            "com.cebbank.mobile",
            "com.cib.cibmb",
            "com.pingan.paces.ccms",
            "com.pingan.pabank.activity",
            "com.cgbchina.xpt",
            "com.hxb.mobile.client",
            "com.bank.ningbo",
            "com.bankofbeijing.mobilebanking",
            "com.boc.bocsoft.mobile.bocmobile",
            "com.ecitic.bank.mobile",
            "com.mybank.android.phone",
            "com.chase.sig.android",
            "com.paypal.android.p2pmobile"
        )

        /** 精确匹配或前缀匹配内置保护名单。 */
        fun isBuiltInProtected(pkg: String): Boolean {
            if (pkg.isEmpty()) return false
            return BUILT_IN_PROTECTED.any { pkg.equals(it, true) || pkg.startsWith("$it.", true) }
        }

        fun builtInProtectedList(): List<String> = BUILT_IN_PROTECTED
    }
}
