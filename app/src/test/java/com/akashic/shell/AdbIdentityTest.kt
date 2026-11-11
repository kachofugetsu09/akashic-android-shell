package com.akashic.shell

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

class AdbIdentityTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("adb-identity-test-").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sealed(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }

    @Test
    fun newIdentitySurvivesReloadAndPairingReusesIt() = inDirectory { dir ->
        val file = File(dir, "adb/adbkey.bin")
        val aes = key()
        val accesses = mutableListOf<Boolean>()
        val source: (Boolean) -> SecretKey = { create -> accesses.add(create); aes }
        val first = AdbIdentity.load(file, "test", false, source)
        val bytes = file.readBytes()
        val connected = AdbIdentity.load(file, "test", false, source)
        val paired = AdbIdentity.load(file, "test", true, source)
        assertArrayEquals(first.keyPair.private.encoded, connected.keyPair.private.encoded)
        assertArrayEquals(first.publicKey.encoded, paired.publicKey.encoded)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(true, false, false), accesses)
        assertEquals(listOf("adbkey.bin"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun connectPreservesMalformedDecryptionAndParsingFailures() = inDirectory { dir ->
        val aes = key()
        val cases = listOf(
            byteArrayOf(),
            byteArrayOf(12, 1, 2),
            sealed(byteArrayOf(1, 2, 3), key()), // 错误 AES 密钥导致认证失败。
            sealed(byteArrayOf(1, 2, 3), aes), // 正确解密后，PKCS8 解析失败。
        )
        cases.forEachIndexed { index, bytes ->
            val file = File(dir, "key-$index.bin").apply { writeBytes(bytes) }
            assertThrows(IOException::class.java) {
                AdbIdentity.load(file, "test", false) { create ->
                    assertFalse("connect must not create a wrapping key while reading", create)
                    aes
                }
            }
            assertArrayEquals(bytes, file.readBytes())
        }
        assertEquals(cases.size, dir.list()!!.size)
    }

    @Test
    fun unreadableFileIsPreservedWithoutCreatingAnIdentity() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin")
        val original = byteArrayOf(12, 1, 2)
        file.writeBytes(original)
        assertTrue(file.setReadable(false, false))
        try {
            assertFalse(file.canRead())
            assertThrows(IOException::class.java) {
                AdbIdentity.load(file, "test", false) { error("must not access keystore after a read failure") }
            }
        } finally {
            assertTrue(file.setReadable(true, true))
        }
        assertArrayEquals(original, file.readBytes())
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun missingWrappingKeyDoesNotCreateOrReplaceAnythingOnConnect() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin")
        val aes = key()
        AdbIdentity.load(file, "test", false) { aes }
        val before = file.readBytes()
        val accesses = mutableListOf<Boolean>()
        val error = assertThrows(IOException::class.java) {
            AdbIdentity.load(file, "test", false) { create ->
                accesses.add(create)
                throw GeneralSecurityException("missing wrapping key")
            }
        }
        assertTrue(error.cause is GeneralSecurityException)
        assertEquals(listOf(false), accesses)
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun explicitPairingKeepsCiphertextBackupBeforeRepair() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin")
        val original = byteArrayOf(12, 1, 2)
        file.writeBytes(original)
        val aes = key()
        val repaired = AdbIdentity.load(file, "test", true) { create ->
            assertTrue(create)
            val backups = dir.listFiles()!!.filter { it.name.contains(".before-pairing-") }
            assertEquals(1, backups.size)
            assertArrayEquals(original, backups.single().readBytes())
            assertArrayEquals(original, file.readBytes())
            aes
        }
        val reloaded = AdbIdentity.load(file, "test", false) { aes }
        assertArrayEquals(repaired.keyPair.private.encoded, reloaded.keyPair.private.encoded)
        assertEquals(2, dir.list()!!.size)
    }

    @Test
    fun repairWithMissingWrappingKeyBacksUpBeforeCreatingNewKey() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin")
        AdbIdentity.load(file, "test", false) { key() }
        val before = file.readBytes()
        val replacement = key()
        val accesses = mutableListOf<Boolean>()
        AdbIdentity.load(file, "test", true) { create ->
            accesses.add(create)
            if (!create) throw GeneralSecurityException("missing wrapping key")
            assertArrayEquals(before, dir.listFiles()!!.single { it.name.endsWith(".bak") }.readBytes())
            replacement
        }
        assertEquals(listOf(false, true), accesses)
        AdbIdentity.load(file, "test", false) { replacement }
    }

    @Test
    fun saveFailureThrowsAndLaterAttemptLoadsOnlyPersistedIdentity() = inDirectory { dir ->
        val parent = File(dir, "blocked").apply { writeText("not a directory") }
        val file = File(parent, "adbkey.bin")
        val aes = key()
        assertThrows(IOException::class.java) { AdbIdentity.load(file, "test", false) { aes } }
        assertEquals("not a directory", parent.readText())
        assertTrue(parent.delete())
        val saved = AdbIdentity.load(file, "test", false) { aes }
        val reloaded = AdbIdentity.load(file, "test", false) { aes }
        assertArrayEquals(saved.publicKey.encoded, reloaded.publicKey.encoded)
    }

    @Test
    fun atomicMoveFailureThrowsAndCleansTemporaryFile() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin")
        val aes = key()
        assertThrows(IOException::class.java) {
            AdbIdentity.load(file, "test", false) {
                // 写入前出现不可替换的目录，确定性触发最后一步的 rename 失败。
                assertTrue(file.mkdir())
                File(file, "keep").writeText("untouched")
                aes
            }
        }
        assertEquals("untouched", File(file, "keep").readText())
        assertEquals(listOf("adbkey.bin"), dir.list()!!.toList())
    }

    @Test
    fun failedRepairKeepsOriginalAndBackup() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin")
        val original = byteArrayOf(12, 1, 2)
        file.writeBytes(original)
        assertThrows(GeneralSecurityException::class.java) {
            AdbIdentity.load(file, "test", true) { throw GeneralSecurityException("injected keystore failure") }
        }
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, dir.listFiles()!!.single { it.name.endsWith(".bak") }.readBytes())
    }

    @Test
    fun directoryIsRejectedWithoutChangingItsContents() = inDirectory { dir ->
        val file = File(dir, "adbkey.bin").apply { mkdir() }
        File(file, "keep").writeText("untouched")
        assertThrows(IOException::class.java) { AdbIdentity.load(file, "test", false) { key() } }
        assertEquals("untouched", File(file, "keep").readText())
        assertEquals(listOf("adbkey.bin"), dir.list()!!.toList())
    }
}
