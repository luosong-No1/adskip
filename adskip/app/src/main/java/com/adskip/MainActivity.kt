package com.adskip

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adskip.databinding.ActivityMainBinding

/**
 * 主界面：服务状态、分级开关、排除列表、点击记录。
 *
 * 注意：无障碍服务只能由用户在系统设置里开启或关闭，
 * 应用本身没有权限直接开关它，这里只能引导跳转。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SkipPrefs

    private val handler = Handler(Looper.getMainLooper())

    /** 定时刷新状态：用户从系统设置返回后能立刻看到变化。 */
    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SkipPrefs(this)

        binding.tvBuiltinExclude.text =
            getString(R.string.builtin_exclude_desc) + "\n\n" +
                SkipPrefs.builtInProtectedList().joinToString("\n")

        bindSwitchStates()
        bindListeners()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ───────────────────────── 初始化 ─────────────────────────

    /** 先按配置设置开关状态，再挂监听，避免设置状态时误触发写入。 */
    private fun bindSwitchStates() {
        binding.swMaster.isChecked = prefs.masterEnabled
        binding.swSkip.isChecked = prefs.skipEnabled
        binding.swCloseAd.isChecked = prefs.closeAdEnabled
        binding.swXClose.isChecked = prefs.xCloseEnabled
        binding.swLooseClose.isChecked = prefs.looseCloseEnabled
        binding.etExclude.setText(prefs.userExcluded.sorted().joinToString("\n"))
    }

    private fun bindListeners() {
        binding.swMaster.setOnCheckedChangeListener { _, checked ->
            prefs.masterEnabled = checked
            renderStatus()
        }
        binding.swSkip.setOnCheckedChangeListener { _, checked -> prefs.skipEnabled = checked }
        binding.swCloseAd.setOnCheckedChangeListener { _, checked -> prefs.closeAdEnabled = checked }
        binding.swXClose.setOnCheckedChangeListener { _, checked -> prefs.xCloseEnabled = checked }
        binding.swLooseClose.setOnCheckedChangeListener { _, checked ->
            prefs.looseCloseEnabled = checked
            if (checked) toast(getString(R.string.toast_loose_on))
        }

        binding.btnOpenSettings.setOnClickListener { openAccessibilitySettings() }
        binding.btnSaveExclude.setOnClickListener { saveExcludedPackages() }
        binding.btnClearLog.setOnClickListener {
            prefs.clearLog()
            refreshLog()
            toast(getString(R.string.toast_cleared))
        }
    }

    // ───────────────────────── 状态渲染 ─────────────────────────

    private fun renderStatus() {
        val running = AdSkipAccessibilityService.isRunning
        val enabledInSystem = running || isServiceEnabledInSystem()

        when {
            running -> {
                binding.tvStatus.setText(R.string.status_running)
                binding.tvStatus.setTextColor(getColor(R.color.ok))
                binding.tvStatusHint.setText(R.string.status_hint_on)
                binding.btnOpenSettings.setText(R.string.action_open_a11y_on)
            }

            enabledInSystem -> {
                binding.tvStatus.setText(R.string.status_enabled_not_connected)
                binding.tvStatus.setTextColor(getColor(R.color.brand))
                binding.tvStatusHint.setText(R.string.status_hint_on)
                binding.btnOpenSettings.setText(R.string.action_open_a11y_on)
            }

            else -> {
                binding.tvStatus.setText(R.string.status_off)
                binding.tvStatus.setTextColor(getColor(R.color.warn))
                binding.tvStatusHint.setText(R.string.status_hint_off)
                binding.btnOpenSettings.setText(R.string.action_open_a11y)
            }
        }

        // 总开关关闭时把状态文字压暗，提示"虽然服务开着但没在工作"
        binding.tvStatus.alpha = if (prefs.masterEnabled) 1f else 0.45f
    }

    /**
     * 读系统设置判断无障碍服务是否被启用。
     * 比 AccessibilityManager 更可靠，能识别出「已启用但尚未连接」的中间状态。
     */
    private fun isServiceEnabledInSystem(): Boolean {
        return try {
            val component = ComponentName(this, AdSkipAccessibilityService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            val raw = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            raw.split(':').any { it.equals(full, true) || it.equals(short, true) }
        } catch (t: Throwable) {
            false
        }
    }

    private fun openAccessibilitySettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent("android.settings.ACCESSIBILITY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 换下一个候选
            } catch (_: SecurityException) {
                // 换下一个候选
            }
        }
        toast(getString(R.string.toast_no_settings))
    }

    // ───────────────────────── 排除列表 ─────────────────────────

    private fun saveExcludedPackages() {
        val raw = binding.etExclude.text?.toString().orEmpty()
        val parts = raw.split('\n', ',', '，', ';', '；', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val valid = LinkedHashSet<String>()
        var invalid = 0
        for (part in parts) {
            if (PACKAGE_PATTERN.matches(part)) valid.add(part) else invalid++
        }

        prefs.userExcluded = valid
        binding.etExclude.setText(valid.sorted().joinToString("\n"))

        if (invalid > 0) {
            toast(getString(R.string.toast_invalid_packages, invalid))
        } else {
            toast(getString(R.string.toast_saved))
        }
    }

    // ───────────────────────── 日志 ─────────────────────────

    private fun refreshLog() {
        val entries = prefs.readLog()
        binding.tvLog.text = if (entries.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            entries.joinToString("\n")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_500L

        /** 校验包名格式，避免用户把「应用名称」填进来。 */
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
