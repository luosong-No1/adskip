package com.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 核心服务：监听界面变化，找出「跳过 / 关闭广告」控件并点击。
 *
 * 工作流程：
 *  1. 窗口状态变化时进入 5 秒「狩猎期」，其间高频扫描（开屏广告都在这段时间内出现）
 *  2. 内容变化时，只有在狩猎期内、或事件文本里出现广告/跳过/关闭字样时才扫描
 *     —— 这样在刷信息流等场景下几乎不消耗 CPU
 *  3. 扫描分两阶段：先遍历一次收集控件快照并判断「当前窗口是否含广告」，
 *     再统一评估，避免遍历顺序影响判断结果
 *  4. 选中目标后先尝试 ACTION_CLICK，失败再退回 dispatchGesture 坐标点击
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkip"

        /** 供界面读取：服务是否已连接。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 单次扫描最多遍历的节点数，防止界面巨大时卡顿。 */
        private const val MAX_NODES = 300

        /** 树的最大深度。 */
        private const val MAX_DEPTH = 28

        /** 两次扫描的最小间隔。 */
        private const val MIN_SCAN_INTERVAL_MS = 200L

        /** 窗口切换后的「狩猎期」时长。 */
        private const val HUNT_WINDOW_MS = 5_000L

        /** 向上找可点击祖先时最多爬几层。 */
        private const val MAX_CLICK_ANCESTOR_HOPS = 5

        /** 同一个目标在多久内不重复点击。 */
        private const val REPEAT_GUARD_MS = 1_500L
    }

    private val prefs: SkipPrefs by lazy { SkipPrefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var lastScanAt = 0L
    private var lastClickAt = 0L
    private var lastSignature: String? = null
    private var lastSignatureAt = 0L
    private var huntUntil = 0L
    private val clickTimestamps = ArrayDeque<Long>()

    /** 一个候选目标的完整信息，便于第二阶段比较优先级。 */
    private class Candidate(
        val node: AccessibilityNodeInfo,
        val tier: SkipTier,
        val area: Int,
        val label: String
    )

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "服务销毁")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // ───────────────────────── 事件入口 ─────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.masterEnabled) return

        val type = event.eventType
        val isWindowChanged = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val isContentChanged = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowChanged && !isContentChanged) return

        val pkg = event.packageName?.toString().orEmpty()
        if (!isAllowedPackage(pkg)) return

        val now = SystemClock.uptimeMillis()

        if (isWindowChanged) {
            // 新界面出现：开屏广告、弹窗广告都从这里开始
            huntUntil = now + HUNT_WINDOW_MS
            // 「跳过 3」按钮往往比窗口本身晚几百毫秒才渲染出来，补几次延时扫描
            scheduleRescan(400L)
            scheduleRescan(1_000L)
            scheduleRescan(2_000L)
        } else if (now > huntUntil && !eventLooksInteresting(event)) {
            // 不在狩猎期、事件本身也没有广告特征 —— 直接放弃，省电
            return
        }

        safeScan()
    }

    private fun scheduleRescan(delayMs: Long) {
        handler.postDelayed({ safeScan() }, delayMs)
    }

    /**
     * 事件文本里是否出现了值得深入扫描的线索。
     * 用于在没有窗口切换的情况下（例如信息流里弹出的广告浮层）也能命中。
     */
    private fun eventLooksInteresting(event: AccessibilityEvent): Boolean {
        val builder = StringBuilder(64)
        val texts = event.text
        for (i in 0 until texts.size) {
            builder.append(texts[i]).append(' ')
        }
        event.contentDescription?.let { builder.append(it) }
        val blob = builder.toString()
        if (blob.isBlank()) return false
        return SkipRules.looksLikeAdText(blob) ||
            SkipRules.looksLikeSkipText(blob) ||
            SkipRules.looksLikeCloseText(blob)
    }

    private fun isAllowedPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == packageName) return false
        if (SkipPrefs.isBuiltInProtected(pkg)) return false
        if (prefs.isExcluded(pkg)) return false
        return true
    }

    // ───────────────────────── 扫描 ─────────────────────────

    private fun safeScan() {
        if (!prefs.masterEnabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return
        // 刚点过一下，先停手，避免连点造成页面乱跳
        if (now - lastClickAt < SkipPrefs.CLICK_COOLDOWN_MS) return

        try {
            val root = rootInActiveWindow ?: return
            val pkg = root.packageName?.toString().orEmpty()
            if (!isAllowedPackage(pkg)) return
            scan(root, pkg, now)
        } catch (t: Throwable) {
            // 无障碍服务里抛异常会被系统静默停用，这里必须自己兜住
            Log.w(TAG, "扫描过程出错，已忽略", t)
        }
    }

    private fun scan(root: AccessibilityNodeInfo, pkg: String, now: Long) {
        lastScanAt = now

        val (screenW, screenH) = screenSize()
        if (screenW <= 0 || screenH <= 0) return

        val enabled = prefs.enabledTiers()
        if (enabled.isEmpty()) return

        // ── 第一阶段：一次遍历，收集控件快照，同时判断窗口是否含广告 ──
        // 说明：这里刻意不调用 AccessibilityNodeInfo.recycle()。
        // API 33 起该方法已是空实现，而早期版本上主动回收容易把仍被候选列表
        // 引用的节点回收掉，反而引入难以复现的崩溃。遍历节点数已由 MAX_NODES 兜住。
        val snapshots = ArrayList<Pair<NodeInfo, AccessibilityNodeInfo>>(32)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        val rect = Rect()
        var visited = 0
        var adContext = false

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()

            if (node.isVisibleToUser && width > 0 && height > 0) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val viewId = node.viewIdResourceName ?: ""

                if (!adContext) {
                    adContext = SkipRules.looksLikeAdText(text) ||
                        SkipRules.looksLikeAdText(desc) ||
                        SkipRules.looksLikeAdId(viewId)
                }

                if (text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()) {
                    snapshots.add(
                        NodeInfo(
                            text = text,
                            desc = desc,
                            viewId = viewId,
                            className = node.className?.toString().orEmpty(),
                            clickable = node.isClickable,
                            clickableNearby = node.isClickable || node.parent?.isClickable == true,
                            childCount = node.childCount,
                            width = width,
                            height = height,
                            screenWidth = screenW,
                            screenHeight = screenH
                        ) to node
                    )
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child to depth + 1)
                }
            }
        }

        // ── 第二阶段：统一评估，按「分级优先级 + 面积更小者更具体」挑选目标 ──
        var best: Candidate? = null
        for ((info, node) in snapshots) {
            val tier = SkipRules.match(info, enabled, adContext) ?: continue
            val candidate = Candidate(node, tier, info.area, info.label)
            val current = best
            if (current == null ||
                candidate.tier.ordinal < current.tier.ordinal ||
                (candidate.tier == current.tier && candidate.area < current.area)
            ) {
                best = candidate
            }
        }

        val target = best ?: return
        click(target.node, pkg, target)
    }

    // ───────────────────────── 点击 ─────────────────────────

    private fun click(node: AccessibilityNodeInfo, pkg: String, candidate: Candidate): Boolean {
        val now = SystemClock.uptimeMillis()

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val signature = "$pkg|${candidate.tier}|${candidate.label}|" +
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"

        // 同一个目标短时间内只点一次
        if (signature == lastSignature && now - lastSignatureAt < REPEAT_GUARD_MS) return false
        // 冷却与每分钟次数上限
        if (now - lastClickAt < SkipPrefs.CLICK_COOLDOWN_MS) return false
        if (!withinRateLimit(now)) return false

        // 自己不可点击时，往上找可点击的祖先（广告按钮常把点击事件放在父容器）
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops <= MAX_CLICK_ANCESTOR_HOPS) {
            if (target.isClickable && target.isEnabled) break
            target = target.parent
            hops++
        }

        var clicked = false
        if (target != null && target.isClickable && target.isEnabled) {
            clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!clicked) {
            // 兜底：很多开屏广告的「跳过」是自绘控件，直接按坐标点一下
            clicked = tapByGesture(rect)
        }

        if (!clicked) return false

        lastSignature = signature
        lastSignatureAt = now
        lastClickAt = now
        clickTimestamps.addLast(now)

        val entry = "${timeFormat.format(Date())}  [${candidate.tier.label}]  $pkg  ·  ${candidate.label}"
        prefs.appendLog(entry)
        Log.i(TAG, "已点击：$entry")
        return true
    }

    /** 每分钟点击次数上限，避免在异常界面上疯狂连点。 */
    private fun withinRateLimit(now: Long): Boolean {
        while (clickTimestamps.isNotEmpty() && now - clickTimestamps.first() > 60_000L) {
            clickTimestamps.removeFirst()
        }
        return clickTimestamps.size < SkipPrefs.MAX_CLICKS_PER_MINUTE
    }

    /** 在控件中心派发一次手势点击。 */
    private fun tapByGesture(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val (screenW, screenH) = screenSize()
        if (screenW <= 2 || screenH <= 2) return false
        val x = rect.exactCenterX().coerceIn(1f, (screenW - 2).toFloat())
        val y = rect.exactCenterY().coerceIn(1f, (screenH - 2).toFloat())

        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "坐标点击失败", t)
            false
        }
    }

    /** 取自 WindowManager 的真实屏幕尺寸，兼容旋转与分屏。 */
    private fun screenSize(): Pair<Int, Int> {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return bounds.width() to bounds.height()
                    }
                } else {
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return dm.widthPixels to dm.heightPixels
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取屏幕尺寸失败", t)
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }
}
