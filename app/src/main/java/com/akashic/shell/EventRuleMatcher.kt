package com.akashic.shell

import java.util.regex.Pattern

internal data class ParsedPackageFilter(
    val includes: Set<String>?, // null => wildcard "*"
    val excludes: Set<String>,
)

internal object EventRuleMatcher {
    fun parsePackageFilter(
        packageSpec: String?,
        excludePackages: Iterable<String> = emptyList(),
    ): ParsedPackageFilter {
        val excludes = LinkedHashSet<String>()
        for (item in excludePackages) {
            val cleaned = item.trim()
            if (cleaned.isNotEmpty()) excludes.add(cleaned)
        }

        val raw = packageSpec?.trim().orEmpty()
        if (raw.isEmpty() || raw == "*") {
            return ParsedPackageFilter(includes = null, excludes = excludes)
        }

        val includes = LinkedHashSet<String>()
        var wildcard = false
        for (part in raw.split(',')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            if (token == "*") {
                wildcard = true
            } else if (token.startsWith("!")) {
                val neg = token.substring(1).trim()
                if (neg.isNotEmpty()) excludes.add(neg)
            } else {
                includes.add(token)
            }
        }
        val finalIncludes = if (wildcard || includes.isEmpty()) null else includes
        return ParsedPackageFilter(includes = finalIncludes, excludes = excludes)
    }

    fun matchesTarget(
        enabled: Boolean,
        ruleAdapter: String,
        eventAdapter: String,
        expiresAtMs: Long?,
        nowMs: Long,
        includes: Set<String>?,
        excludes: Set<String>,
        packageName: String,
    ): Boolean {
        if (!enabled || ruleAdapter != eventAdapter) return false
        if (expiresAtMs != null && expiresAtMs <= nowMs) return false
        if (packageName in excludes) return false
        if (includes != null && packageName !in includes) return false
        return true
    }

    fun matchesContent(
        titleRegex: Pattern?,
        textRegex: Pattern?,
        title: String,
        text: String,
    ): Boolean {
        if (titleRegex != null && !titleRegex.matcher(title).find()) return false
        if (textRegex != null && !textRegex.matcher(text).find()) return false
        return true
    }
}
