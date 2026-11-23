package com.akashic.shell

object AccessibilityComponentSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = "com.akashic.shell"
        val expected = "com.akashic.shell/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

        check(AccessibilityComponentMigration.merge(null, pkg) == expected)
        check(AccessibilityComponentMigration.merge("", pkg) == expected)
        check(AccessibilityComponentMigration.merge("null", pkg) == expected)

        val migrated = AccessibilityComponentMigration.merge(
            "com.other/a.Svc:com.akashic.shell/com.akashic.shell.AkashicAccessibilityService",
            pkg,
        )
        check(migrated == "com.other/a.Svc:$expected") { "got=$migrated" }

        val idempotent = AccessibilityComponentMigration.merge(
            "com.other/a.Svc:$expected",
            pkg,
        )
        check(idempotent == "com.other/a.Svc:$expected") { "got=$idempotent" }

        println("AccessibilityComponentSelfTest: all assertions passed")
    }
}
