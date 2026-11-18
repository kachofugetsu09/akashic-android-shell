package com.akashic.shell

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import org.json.JSONArray
import org.json.JSONObject

/** 快照里一行可引用的元素；ownKey 用于动作前核对节点仍是同一个控件。 */
data class ScreenElement(
    val ref: Int,
    val node: AccessibilityNodeInfo,
    val role: String,
    val name: String,
    val resourceId: String,
    val fingerprint: String,
    val ownKey: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
)

data class ScreenTree(
    val nodes: JSONArray,
    val elements: Map<Int, ScreenElement>,
    val namedCount: Int,
    // 无文字的可操作元素（自绘网格、图标卡片），多于有名元素时需要看图。
    val blankCount: Int,
    val truncated: Boolean,
    val hasWebView: Boolean,
)

/** 语义定位：文字（默认子串、忽略大小写）或 resource-id，可附角色过滤。 */
data class ScreenSelector(val text: String?, val exact: Boolean, val resourceId: String?, val role: String?)

/**
 * 同一控件跨快照沿用同一编号（对标 ego-lite 的持久 ref）。
 * AccessibilityNodeInfo 的 equals 由窗口与源节点 id 决定；再以角色、resource-id 与名称为指纹，
 * 列表项被回收复用、内容变了就换新编号。编号单调递增从不复用，旧编号不会误指新控件。
 */
class RefRegistry {
    private var known = HashMap<AccessibilityNodeInfo, Pair<Int, String>>()
    private var pass: HashMap<AccessibilityNodeInfo, Pair<Int, String>>? = null
    private var nextRef = 1

    @Synchronized
    fun begin() {
        pass = HashMap()
    }

    @Synchronized
    fun assign(node: AccessibilityNodeInfo, fingerprint: String): Int {
        val previous = known[node]
        val ref = if (previous != null && previous.second == fingerprint) previous.first else nextRef++
        pass?.put(node, ref to fingerprint)
        return ref
    }

    @Synchronized
    fun commit() {
        pass?.let { known = it }
        pass = null
    }

    // 换显示器时只丢弃身份表，编号继续递增。
    @Synchronized
    fun forgetNodes() {
        known = HashMap()
        pass = null
    }
}

object ScreenModel {
    private const val MAX_LINES = 220
    private const val MAX_NAME = 120
    private const val MAX_DEPTH = 60

    // 1. 只保留可操作控件与有文字的节点 2. 无名容器折叠、子节点上提
    // 3. 可点击控件吸收其非可操作后代的文字作为名称 4. 编号跨快照稳定。
    fun capture(roots: List<AccessibilityNodeInfo>, registry: RefRegistry): ScreenTree {
        val out = JSONArray()
        val elements = LinkedHashMap<Int, ScreenElement>()
        var named = 0
        var blank = 0
        var truncated = false
        var webView = false
        val rect = Rect()
        registry.begin()

        fun visit(node: AccessibilityNodeInfo?, depth: Int, level: Int) {
            if (node == null || level > MAX_DEPTH || !node.isVisibleToUser) return
            if (out.length() >= MAX_LINES) {
                truncated = true
                return
            }
            if (node.className?.toString()?.contains("WebView") == true) webView = true
            node.getBoundsInScreen(rect)
            val clickable = isClickable(node)
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val own = label(node)
            val emit = rect.width() > 0 && rect.height() > 0 && (clickable || editable || scrollable || own.isNotEmpty())
            if (!emit) {
                forEachChild(node) { visit(it, depth, level + 1) }
                return
            }
            val folds = clickable && !editable && !scrollable
            val name = when {
                folds -> mergedName(own, node)
                editable -> own.ifEmpty { hint(node) }
                else -> own
            }
            val element = element(node, rect, name, clickable, editable, scrollable, registry)
            elements[element.ref] = element
            out.put(json(element, node, rect, depth))
            if (name.isNotEmpty()) named++ else if (clickable) blank++
            // 折叠控件的文字已并入名称，只继续展开其中的可操作后代。
            if (folds) forEachChild(node) { visitActionable(it, depth + 1, level + 1, ::visit) }
            else forEachChild(node) { visit(it, depth + 1, level + 1) }
        }

        for (root in roots) visit(root, 0, 0)
        registry.commit()
        return ScreenTree(out, elements, named, blank, truncated, webView)
    }

    fun matches(tree: ScreenTree, selector: ScreenSelector): List<ScreenElement> {
        val wanted = selector.text?.let(::normalize)
        return tree.elements.values.filter { element ->
            val name = normalize(element.name)
            val textOk = wanted == null || (if (selector.exact) name == wanted else name.contains(wanted))
            val idOk = selector.resourceId == null || element.resourceId == selector.resourceId ||
                element.resourceId.substringAfter(":id/") == selector.resourceId.substringAfter(":id/")
            val roleOk = selector.role == null || element.role == selector.role
            textOk && idOk && roleOk
        }
    }

    // 动作前的轻量核对：只比对节点自身属性，不重算合并名称。
    fun ownKey(node: AccessibilityNodeInfo): String {
        val label = if (node.isEditable) hint(node) else label(node)
        return "${node.className}|${node.viewIdResourceName.orEmpty()}|$label"
    }

    private fun visitActionable(
        node: AccessibilityNodeInfo?,
        depth: Int,
        level: Int,
        visit: (AccessibilityNodeInfo?, Int, Int) -> Unit,
    ) {
        if (node == null || level > MAX_DEPTH || !node.isVisibleToUser) return
        if (isActionable(node)) visit(node, depth, level)
        else forEachChild(node) { visitActionable(it, depth, level + 1, visit) }
    }

    private fun element(
        node: AccessibilityNodeInfo,
        rect: Rect,
        name: String,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        registry: RefRegistry,
    ): ScreenElement {
        val role = role(node, clickable, editable, scrollable)
        val resourceId = node.viewIdResourceName.orEmpty()
        // 输入框的值会随输入变化，用提示文字而不是内容作身份；指纹用完整名称，只在输出时截断。
        val identity = if (editable) hint(node) else name
        val fingerprint = "$role|$resourceId|$identity|${rect.width()}x${rect.height()}".takeIf { scrollable }
            ?: "$role|$resourceId|$identity"
        val ref = registry.assign(node, fingerprint)
        return ScreenElement(ref, node, role, shown(name), resourceId, fingerprint, ownKey(node), clickable, editable, scrollable)
    }

    private fun json(element: ScreenElement, node: AccessibilityNodeInfo, rect: Rect, depth: Int): JSONObject {
        val states = JSONArray()
        if (node.isCheckable) states.put(if (node.isChecked) "checked" else "unchecked")
        if (node.isSelected) states.put("selected")
        if (!node.isEnabled) states.put("disabled")
        if (node.isFocused) states.put("focused")
        if (!element.clickable && node.isLongClickable) states.put("long_press")
        val value = if (element.editable && !isShowingHint(node)) node.text?.toString().orEmpty() else ""
        val item = JSONObject()
            .put("ref", element.ref)
            .put("depth", depth)
            .put("role", element.role)
            .put("name", element.name)
            .put("resource_id", element.resourceId.substringAfter(":id/"))
            .put("states", states)
            .put("scroll", scrollDirections(node))
            .put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
            // 旧插件读取的字段，升级窗口内保持兼容。
            .put("node_id", element.ref)
            .put("class_name", node.className?.toString() ?: "android.view.View")
            .put("text", if (element.editable) node.text?.toString().orEmpty() else element.name)
            .put("content_description", if (element.editable) node.contentDescription?.toString().orEmpty() else "")
            .put("clickable", element.clickable)
            .put("editable", element.editable)
            .put("scrollable", element.scrollable)
            .put("focused", node.isFocused || node.isAccessibilityFocused)
        if (element.editable) item.put("value", value).put("hint", hint(node))
        if (node.isCheckable) item.put("checked", node.isChecked)
        return item
    }

    private fun mergedName(own: String, node: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>()
        if (own.isNotEmpty()) parts.add(own)
        fun collect(child: AccessibilityNodeInfo?, level: Int) {
            if (child == null || level > MAX_DEPTH || !child.isVisibleToUser || isActionable(child)) return
            label(child).takeIf { it.isNotEmpty() && it !in parts }?.let(parts::add)
            forEachChild(child) { collect(it, level + 1) }
        }
        forEachChild(node) { collect(it, 1) }
        return parts.joinToString(" · ")
    }

    private fun shown(name: String): String = if (name.length <= MAX_NAME) name else name.take(MAX_NAME - 1) + "…"

    private fun role(node: AccessibilityNodeInfo, clickable: Boolean, editable: Boolean, scrollable: Boolean): String {
        val cls = node.className?.toString()?.substringAfterLast('.')?.lowercase().orEmpty()
        return when {
            editable || "edittext" in cls -> "textbox"
            "checkbox" in cls -> "checkbox"
            "switch" in cls || "togglebutton" in cls -> "switch"
            "radiobutton" in cls -> "radio"
            "seekbar" in cls || "ratingbar" in cls -> "slider"
            "numberpicker" in cls -> "picker"
            "webview" in cls -> "webview"
            "tab" in cls -> "tab"
            scrollable || listOf("recyclerview", "listview", "gridview", "scrollview", "viewpager").any { it in cls } -> "list"
            "button" in cls -> "button"
            "image" in cls -> if (clickable) "button" else "image"
            clickable -> "item"
            else -> "text"
        }
    }

    private fun scrollDirections(node: AccessibilityNodeInfo): JSONArray {
        val out = JSONArray()
        if (!node.isScrollable) return out
        val ids = node.actionList.map { it.id }.toSet()
        val directional = listOf(
            AccessibilityAction.ACTION_SCROLL_UP to "up",
            AccessibilityAction.ACTION_SCROLL_DOWN to "down",
            AccessibilityAction.ACTION_SCROLL_LEFT to "left",
            AccessibilityAction.ACTION_SCROLL_RIGHT to "right",
        ).filter { it.first.id in ids }.map { it.second }
        if (directional.isNotEmpty()) directional.forEach(out::put)
        else {
            if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids) out.put("backward")
            if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids) out.put("forward")
        }
        return out
    }

    private fun isClickable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isCheckable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun isActionable(node: AccessibilityNodeInfo): Boolean =
        isClickable(node) || node.isEditable || node.isScrollable

    private fun label(node: AccessibilityNodeInfo): String {
        val text = if (node.isEditable) "" else node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            text.isEmpty() -> desc
            desc.isEmpty() || desc == text -> text
            else -> "$text / $desc"
        }.replace(Regex("\\s+"), " ")
    }

    private fun hint(node: AccessibilityNodeInfo): String {
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        val shown = if (isShowingHint(node)) node.text?.toString().orEmpty() else ""
        return hint.ifEmpty { shown.ifEmpty { node.contentDescription?.toString().orEmpty() } }.trim()
    }

    private fun isShowingHint(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim().lowercase()

    private inline fun forEachChild(node: AccessibilityNodeInfo, block: (AccessibilityNodeInfo?) -> Unit) {
        for (i in 0 until node.childCount) block(node.getChild(i))
    }
}
