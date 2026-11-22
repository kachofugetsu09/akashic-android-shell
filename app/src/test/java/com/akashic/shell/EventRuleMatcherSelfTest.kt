package com.akashic.shell

import java.util.regex.Pattern

object EventRuleMatcherSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        testWildcardWithExcludePackages()
        testInlineNegationInPackageSpec()
        testBlacklistOverridesWhitelist()
        testContentRegexFilters()
        println("EventRuleMatcherSelfTest: all 4 assertions passed")
    }

    private fun testWildcardWithExcludePackages() {
        val f = EventRuleMatcher.parsePackageFilter("*", listOf(" com.google.android.as ", ""))
        check(f.includes == null) { "wildcard should keep includes=null" }
        check("com.google.android.as" in f.excludes) { "exclude_packages must contain com.google.android.as" }
        check(!EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "com.google.android.as"))
        check(EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "com.tencent.mm"))
    }

    private fun testInlineNegationInPackageSpec() {
        val f = EventRuleMatcher.parsePackageFilter("*, !com.google.android.as, !com.example.noisy")
        check(f.includes == null) { "*,!pkg should remain wildcard includes" }
        check(f.excludes == setOf("com.google.android.as", "com.example.noisy"))
        check(!EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "com.google.android.as"))
        check(EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "cn.kaiheila"))
    }

    private fun testBlacklistOverridesWhitelist() {
        val f = EventRuleMatcher.parsePackageFilter("com.tencent.mm, com.google.android.as", listOf("com.google.android.as"))
        check(f.includes == setOf("com.tencent.mm", "com.google.android.as"))
        check(!EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "com.google.android.as"))
        check(EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "com.tencent.mm"))
        check(!EventRuleMatcher.matchesTarget(true, "appops", "appops", null, 1000L, f.includes, f.excludes, "com.other"))
    }

    private fun testContentRegexFilters() {
        val titleRx = Pattern.compile("^(?!Android System Intelligence).*")
        val textRx = Pattern.compile("RECORD_AUDIO")
        check(!EventRuleMatcher.matchesContent(titleRx, textRx, "Android System Intelligence 开启 RECORD_AUDIO", "RECORD_AUDIO active=true"))
        check(EventRuleMatcher.matchesContent(titleRx, textRx, "微信 开启 RECORD_AUDIO", "RECORD_AUDIO active=true"))
        check(!EventRuleMatcher.matchesContent(titleRx, textRx, "微信 开启 CAMERA", "CAMERA active=true"))
    }
}
