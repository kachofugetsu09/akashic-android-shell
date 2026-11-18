package com.akashic.shell

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ScreenReadout(
    val displayId: Int,
    val packageName: String,
    val tree: ScreenTree,
)

data class CapturedScreenshot(val png: ByteArray, val uptimeMs: Long)

class AkashicAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastUiEventUptimeMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ShellStatus.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            lastUiEventUptimeMs = SystemClock.uptimeMillis()
        }
    }

    fun quietForMs(nowMs: Long = SystemClock.uptimeMillis()): Long {
        val last = lastUiEventUptimeMs
        return if (last <= 0L) Long.MAX_VALUE else (nowMs - last).coerceAtLeast(0L)
    }

    /** 轻量计算目标显示器的可见节点树 FNV-1a 64-bit 指纹，用于动态等待稳定性。 */
    fun screenSignature(targetDisplayId: Int): ScreenFingerprint {
        val roots = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val byDisplay = windowsOnAllDisplays
            val list = byDisplay.get(targetDisplayId)
                ?: if (targetDisplayId == Display.DEFAULT_DISPLAY) byDisplay.get(Display.DEFAULT_DISPLAY) else null
            if (list != null) {
                for (win in list) {
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    ) {
                        win.root?.let { roots.add(it) }
                    }
                }
            }
        }
        if (roots.isEmpty() && targetDisplayId == Display.DEFAULT_DISPLAY) {
            rootInActiveWindow?.let { roots.add(it) }
        }

        var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
        fun mix(v: Long) {
            hash = (hash xor v) * 1099511628211L
        }
        fun mixStr(s: CharSequence?) {
            if (s == null) {
                mix(0L)
                return
            }
            mix(s.length.toLong())
            for (i in 0 until s.length) mix(s[i].code.toLong())
        }

        var count = 0
        val rect = Rect()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 48 || count >= 180) return
            if (node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val text = node.text
                val desc = node.contentDescription
                val clickable = node.isClickable
                val editable = node.isEditable
                val scrollable = node.isScrollable
                if (rect.width() > 0 && rect.height() > 0 &&
                    (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || clickable || editable || scrollable)
                ) {
                    count++
                    mixStr(node.packageName)
                    mixStr(node.className)
                    mixStr(text)
                    mixStr(desc)
                    mix(rect.left.toLong())
                    mix(rect.top.toLong())
                    mix(rect.right.toLong())
                    mix(rect.bottom.toLong())
                    if (node.isFocused) mix(1L)
                }
            }
            val children = node.childCount
            for (i in 0 until children) {
                walk(node.getChild(i), depth + 1)
            }
        }
        for (r in roots) walk(r, 0)
        return ScreenFingerprint(hash = hash, nodeCount = count)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdownNow()
        ShellStatus.notifyChanged()
        super.onDestroy()
    }

    /** 只读取本次目标显示器的应用身份，不借用其它显示器的历史事件。 */
    fun dumpScreen(targetDisplayId: Int, registry: RefRegistry): ScreenReadout {
        val packages = LinkedHashSet<String>()

        val roots = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val byDisplay = windowsOnAllDisplays
            val list = byDisplay.get(targetDisplayId)
                ?: if (targetDisplayId == Display.DEFAULT_DISPLAY) byDisplay.get(Display.DEFAULT_DISPLAY) else null
            if (list != null) {
                for (win in list) {
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    ) {
                        win.root?.let { root ->
                            roots.add(root)
                            if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                                root.packageName != null && (win.isActive || win.isFocused)) {
                                root.packageName.toString().takeIf { it.isNotEmpty() }?.let(packages::add)
                            }
                        }
                    }
                }
            }
        }
        if (roots.isEmpty() && targetDisplayId == Display.DEFAULT_DISPLAY) {
            rootInActiveWindow?.let { root ->
                roots.add(root)
            }
        }

        val tree = ScreenModel.capture(roots, registry)
        return ScreenReadout(targetDisplayId, packages.singleOrNull().orEmpty(), tree)
    }

    fun performTap(displayId: Int, x: Float, y: Float, durationMs: Long = 55L): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(20L, 2000L))
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
            runCatching {
                GestureDescription.Builder::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(builder, displayId)
            }
        }
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok = false
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun performSwipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(0f), y1.coerceAtLeast(0f))
            lineTo(x2.coerceAtLeast(0f), y2.coerceAtLeast(0f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 8000L))
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
            runCatching {
                GestureDescription.Builder::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(builder, displayId)
            }
        }
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok = false
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        latch.await(10, TimeUnit.SECONDS)
        return ok
    }

    /** 先用控件声明的方向滚动（列表、滚轮选择器），不支持时退回前后翻页动作。 */
    fun scrollNode(node: AccessibilityNodeInfo, direction: String): Boolean {
        val directional = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (node.actionList.any { it.id == directional.id } && node.performAction(directional.id)) return true
        val generic = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(generic)
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun captureScreenshotPng(displayId: Int): CapturedScreenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<CapturedScreenshot?>(null)
        val failure = AtomicReference<RuntimeException?>(null)
        try {
            takeScreenshot(
                displayId,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            if (hw != null) {
                                val sw = try { hw.copy(Bitmap.Config.ARGB_8888, false) } finally { hw.recycle() }
                                if (sw != null) {
                                    try {
                                        val out = ByteArrayOutputStream()
                                        check(sw.compress(Bitmap.CompressFormat.PNG, 90, out)) { "无障碍截图 PNG 编码失败" }
                                        ref.set(CapturedScreenshot(out.toByteArray(), screenshot.timestamp))
                                    } finally { sw.recycle() }
                                }
                            }
                        } catch (error: RuntimeException) {
                            failure.set(error)
                        } finally {
                            buffer.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        failure.set(IllegalStateException("PHONE_SCREENSHOT_FAILED: code=$errorCode"))
                        latch.countDown()
                    }
                },
            )
            latch.await(5, TimeUnit.SECONDS)
        } catch (error: RuntimeException) {
            throw IllegalStateException("PHONE_SCREENSHOT_FAILED: ${error.message}", error)
        }
        failure.get()?.let { throw it }
        return ref.get()
    }

    companion object {
        @Volatile var instance: AkashicAccessibilityService? = null
            private set
    }
}
