package com.akashic.shell

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey
import org.json.JSONObject

/** 在独立测试应用内执行真实 KeyStore 与文件持久化检查，不连接 ADB 服务。 */
class AdbIdentityProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName == "com.akashic.shell.identitytest") { "Isolated identitytest package required" }
            val root = File(targetContext.noBackupFilesDir, "identity-probe-${UUID.randomUUID()}")
            check(root.mkdirs())
            val app = object : ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = root
            }
            val file = File(root, "adb/adbkey.bin")
            val checks = JSONObject()

            // 1. 走真实生产入口：AndroidKeyStore、文件同步、原子替换和目录同步。
            clearCache()
            val created = AdbIdentity.forConnect(app)
            val saved = file.readBytes()
            clearCache()
            val loaded = AdbIdentity.forConnect(app)
            check(created.publicKey.encoded.contentEquals(loaded.publicKey.encoded))
            check(saved.contentEquals(file.readBytes()))
            checks.put("create_reload_same_public_key", true)
            checks.put("public_key_sha256", hash(loaded.publicKey.encoded))
            checks.put("file_and_directory_sync_returned", true)

            // 2. 清掉进程缓存再连接，不能把损坏文件换成新身份。
            val corrupt = byteArrayOf(12, 1, 2)
            file.writeBytes(corrupt)
            clearCache()
            expectIo { AdbIdentity.forConnect(app) }
            check(corrupt.contentEquals(file.readBytes()))
            check(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".bak") })
            checks.put("corrupt_connect_preserved", true)

            // 3. 只有显式配对入口可修复，备份保留原字节。
            val repaired = AdbIdentity.forPairing(app)
            val backup = file.parentFile!!.listFiles()!!.single { it.name.endsWith(".bak") }
            check(corrupt.contentEquals(backup.readBytes()))
            clearCache()
            check(repaired.publicKey.encoded.contentEquals(AdbIdentity.forConnect(app).publicKey.encoded))
            checks.put("explicit_pair_backup_and_reload", true)

            // 4. 使用本测试 UID 的真实 AES key，在最后的替换阶段制造写入失败。
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aes = store.getKey("akashic_adb_key_wrap", null) as SecretKey
            val blocked = File(root, "blocked.bin")
            expectIo {
                AdbIdentity.load(blocked, "probe", false) { create ->
                    check(create)
                    check(blocked.mkdir())
                    File(blocked, "keep").writeText("untouched")
                    aes
                }
            }
            check(File(blocked, "keep").readText() == "untouched")
            check(root.listFiles()!!.none { it.name.endsWith(".tmp") })
            checks.put("write_failure_reported", true)
            checks.put("private_data_exported", false)
            checks.put("process_restart_tested", false)
            checks.put("reboot_or_power_loss_tested", false)
            val report = File(root, "result.json")
            report.writeText(checks.toString(2))
            result.putString("ok", "true")
            result.putString("report", checks.toString())
            result.putString("report_path", report.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            // 测试边界把任何失败传回 runner，不能让脚本误报成功。
            result.putString("ok", "false")
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun clearCache() {
        AdbIdentity::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
    }

    private fun expectIo(action: () -> Unit) {
        try {
            action()
        } catch (_: IOException) {
            return
        }
        error("Expected an explicit persistence failure")
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
