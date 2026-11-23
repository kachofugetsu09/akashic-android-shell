package com.akashic.shell

internal object AccessibilityComponentMigration {
    const val SERVICE_CLASS = "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    fun targetComponent(packageName: String): String = "$packageName/$SERVICE_CLASS"

    fun merge(current: String?, packageName: String): String {
        val target = targetComponent(packageName)
        val legacy = setOf(
            "$packageName/$packageName.AkashicAccessibilityService",
            "$packageName/.AkashicAccessibilityService",
        )
        val items = (current ?: "")
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" }
            ?.split(":")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "null" && it !in legacy }
            ?.toMutableList()
            ?: mutableListOf()
        if (target !in items) {
            items.add(target)
        }
        return items.joinToString(":")
    }
}
