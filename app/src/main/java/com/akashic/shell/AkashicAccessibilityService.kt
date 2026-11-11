package com.akashic.shell

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
    val nodes: JSONArray,
    val nodeRefs: Map<Int, AccessibilityNodeInfo>,
)

class AkashicAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ShellStatus.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdownNow()
        ShellStatus.notifyChanged()
        super.onDestroy()
    }

    /** 只读取本次目标显示器的应用身份，不借用其它显示器的历史事件。 */
    fun dumpScreen(targetDisplayId: Int): ScreenReadout {
        val nodeArray = JSONArray()
        val refs = LinkedHashMap<Int, AccessibilityNodeInfo>()
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

        var nextId = 1
        val rect = Rect()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 48 || nextId > 180) return
            if (node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val clickable = node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
                val scrollable = node.isScrollable
                if (rect.width() > 0 && rect.height() > 0 && (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable)) {
                    val id = nextId++
                    refs[id] = node
                    val item = JSONObject()
                        .put("node_id", id)
                        .put("class_name", node.className?.toString() ?: "android.view.View")
                        .put("text", text)
                        .put("content_description", desc)
                        .put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
                        .put("clickable", clickable)
                        .put("editable", editable)
                        .put("scrollable", scrollable)
                        .put("focused", node.isFocused || node.isAccessibilityFocused)
                    if (node.isCheckable) item.put("checked", node.isChecked)
                    nodeArray.put(item)
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                traverse(node.getChild(i), depth + 1)
            }
        }

        for (r in roots) traverse(r, 0)
        return ScreenReadout(targetDisplayId, packages.singleOrNull().orEmpty(), nodeArray, refs)
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

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun captureScreenshotPng(displayId: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<ByteArray?>(null)
        try {
            takeScreenshot(
                displayId,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (hw != null) {
                                val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                                hw.recycle()
                                screenshot.hardwareBuffer.close()
                                if (sw != null) {
                                    val out = ByteArrayOutputStream()
                                    sw.compress(Bitmap.CompressFormat.PNG, 90, out)
                                    sw.recycle()
                                    ref.set(out.toByteArray())
                                }
                            } else {
                                screenshot.hardwareBuffer.close()
                            }
                        } catch (_: Throwable) {
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            return null
        }
        return ref.get()
    }

    companion object {
        @Volatile var instance: AkashicAccessibilityService? = null
            private set
    }
}
