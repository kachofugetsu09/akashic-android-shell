package com.akashic.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

internal class EdPoint private constructor(
    private val x: BigInteger,
    private val y: BigInteger,
    private val z: BigInteger,
    private val t: BigInteger,
) {
    operator fun plus(o: EdPoint): EdPoint {
        val a = (y - x) * (o.y - o.x) % P
        val b = (y + x) * (o.y + o.x) % P
        val c = t * D2 % P * o.t % P
        val d = z * TWO * o.z % P
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return EdPoint(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    operator fun unaryMinus() = EdPoint(P - x.mod(P), y, z, P - t.mod(P))
    operator fun minus(o: EdPoint) = this + (-o)

    fun times(k: BigInteger): EdPoint {
        var acc = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            acc += acc
            if (k.testBit(i)) acc += this
        }
        return acc
    }

    fun encode(): ByteArray {
        val zi = z.mod(P).modInverse(P)
        val ax = x * zi % P
        val ay = (y * zi).mod(P)
        val out = toLe(ay, 32)
        if (ax.mod(P).testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    companion object {
        private val TWO = BigInteger.valueOf(2)
        val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
        val L: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
        private val D: BigInteger = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val D2 = D * TWO % P
        private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
        val IDENTITY = EdPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val BASE: EdPoint = requireNotNull(decode(hex("5866666666666666666666666666666666666666666666666666666666666666")))

        fun decode(bytes: ByteArray): EdPoint? {
            if (bytes.size != 32) return null
            val raw = bytes.copyOf()
            val sign = (raw[31].toInt() ushr 7) and 1
            raw[31] = (raw[31].toInt() and 0x7f).toByte()
            val y = fromLe(raw)
            if (y >= P) return null
            val yy = y * y % P
            val u = (yy - BigInteger.ONE).mod(P)
            val v = (D * yy + BigInteger.ONE) % P
            val v3 = v * v % P * v % P
            val v7 = v3 * v3 % P * v % P
            var x = u * v3 % P * (u * v7 % P).modPow((P - BigInteger.valueOf(5)).shiftRight(3), P) % P
            val vxx = v * x % P * x % P
            x = when {
                vxx == u -> x
                vxx == (P - u).mod(P) -> x * SQRT_M1 % P
                else -> return null
            }
            if (x.signum() == 0 && sign == 1) return null
            if (x.testBit(0) != (sign == 1)) x = P - x
            return EdPoint(x, y, BigInteger.ONE, x * y % P)
        }

        fun fromLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

        fun toLe(n: BigInteger, len: Int): ByteArray {
            val be = n.toByteArray()
            val out = ByteArray(len)
            for (i in 0 until minOf(len, be.size)) out[i] = be[be.size - 1 - i]
            return out
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

internal class Spake2(private val myName: ByteArray, private val theirName: ByteArray) {
    private var privateKey: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null

    fun generateMessage(password: ByteArray): ByteArray {
        val seed = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val priv = EdPoint.fromLe(seed).mod(EdPoint.L).shiftLeft(3)
        val hash = MessageDigest.getInstance("SHA-512").digest(password)
        var s = EdPoint.fromLe(hash).mod(EdPoint.L)
        if (s.testBit(0)) s += EdPoint.L
        if (s.testBit(1)) s += EdPoint.L.shiftLeft(1)
        if (s.testBit(2)) s += EdPoint.L.shiftLeft(2)
        val msg = (EdPoint.BASE.times(priv) + M.times(s)).encode()
        privateKey = priv
        passwordScalar = s
        passwordHash = hash
        myMsg = msg
        return msg.copyOf()
    }

    fun processMessage(theirMsg: ByteArray): ByteArray {
        val msg = checkNotNull(myMsg)
        require(theirMsg.size == 32)
        val qStar = requireNotNull(EdPoint.decode(theirMsg)) { "peer point not on curve" }
        val shared = (qStar - N.times(passwordScalar!!)).times(privateKey!!).encode()
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            val len = ByteArray(8)
            var l = b.size.toLong()
            for (i in 0 until 8) { len[i] = (l and 0xff).toByte(); l = l ushr 8 }
            sha.update(len)
            sha.update(b)
        }
        put(myName); put(theirName); put(msg); put(theirMsg); put(shared); put(passwordHash!!)
        return sha.digest()
    }

    companion object {
        val M = requireNotNull(EdPoint.decode(EdPoint.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        val N = requireNotNull(EdPoint.decode(EdPoint.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")))
    }
}

internal class PairingCipher(keyMaterial: ByteArray) {
    private val key: SecretKeySpec
    private var encSeq = 0L
    private var decSeq = 0L

    init {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(keyMaterial)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update("adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII))
        mac.update(1.toByte())
        val okm = mac.doFinal().copyOfRange(0, 16)
        key = SecretKeySpec(okm, "AES")
    }

    fun encrypt(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, encSeq++, plain)
    fun decrypt(sealed: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, decSeq, sealed).also { decSeq++ }

    private fun run(mode: Int, seq: Long, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[i] = (seq ushr (8 * i)).toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(128, nonce))
        return c.doFinal(data)
    }
}

class AdbIdentity(val keyPair: KeyPair, val name: String) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey
    val certificate: X509Certificate by lazy { createCertificate(keyPair) }
    val adbPublicKey: String by lazy {
        Base64.getEncoder().encodeToString(encodeAdbPublicKey(publicKey)) + " " + name.replace(Regex("\\s"), "_")
    }

    companion object {
        private const val WRAP_ALIAS = "akashic_adb_key_wrap"
        @Volatile private var cached: AdbIdentity? = null

        @Synchronized
        fun get(context: Context): AdbIdentity {
            cached?.let { return it }
            val file = File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")
            val name = "akashic@${Build.MODEL}"
            if (file.isFile) {
                runCatching {
                    val b = file.readBytes()
                    val ivLen = b[0].toInt() and 0xff
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
                    val pkcs8 = c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateKey
                    val exp = (priv as? RSAPrivateCrtKey)?.publicExponent ?: RSAKeyGenParameterSpec.F4
                    val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, exp)) as RSAPublicKey
                    return AdbIdentity(KeyPair(pub, priv), name).also { cached = it }
                }
            }
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val id = AdbIdentity(g.generateKeyPair(), name)
            runCatching {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, wrappingKey())
                val iv = c.iv
                val out = byteArrayOf(iv.size.toByte()) + iv + c.doFinal(id.keyPair.private.encoded)
                file.parentFile?.mkdirs()
                file.writeBytes(out)
            }
            cached = id
            return id
        }

        private fun wrappingKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        private fun encodeAdbPublicKey(key: RSAPublicKey): ByteArray {
            val n = key.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32 - n.mod(r32).modInverse(r32)
            val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(64)
            buf.putInt(n0inv.toInt())
            buf.put(EdPoint.toLe(n, 256))
            buf.put(EdPoint.toLe(rr, 256))
            buf.putInt(key.publicExponent.toInt())
            return buf.array()
        }

        private fun createCertificate(keyPair: KeyPair): X509Certificate {
            val sha256Rsa = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
            val cn = byteArrayOf(0x55, 0x04, 0x03)
            val algId = seq(tlv(0x06, sha256Rsa), tlv(0x05, ByteArray(0)))
            val name = seq(tlv(0x31, seq(tlv(0x06, cn), tlv(0x0c, "Akashic".toByteArray()))))
            val validity = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
            val tbs = seq(tlv(0xa0, tlv(0x02, byteArrayOf(2))), tlv(0x02, byteArrayOf(1)), algId, name, validity, name, keyPair.public.encoded)
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(keyPair.private); update(tbs); sign() }
            val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}

class AdbTls(val identity: AdbIdentity) {
    val context: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(KeyMgr(identity.keyPair.private, identity.certificate)), arrayOf(TrustAll), null)
        }
    }

    fun connect(host: String, port: Int, timeoutMs: Int): SSLSocket {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), timeoutMs)
        plain.soTimeout = timeoutMs
        return wrap(plain, host, port)
    }

    fun wrap(plain: Socket, host: String, port: Int): SSLSocket {
        val s = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        s.enabledProtocols = arrayOf("TLSv1.3")
        s.useClientMode = true
        s.startHandshake()
        return s
    }

    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray =
        SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length) ?: throw SSLException("exportKeyingMaterial failed")

    private class KeyMgr(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(k: String?, i: Array<out Principal>?) = arrayOf("adb")
        override fun chooseClientAlias(k: Array<out String>?, i: Array<out Principal>?, s: Socket?) = "adb"
        override fun chooseEngineClientAlias(k: Array<out String>?, i: Array<out Principal>?, e: SSLEngine?) = "adb"
        override fun getServerAliases(k: String?, i: Array<out Principal>?) = arrayOf("adb")
        override fun chooseServerAlias(k: String?, i: Array<out Principal>?, s: Socket?) = "adb"
        override fun chooseEngineServerAlias(k: String?, i: Array<out Principal>?, e: SSLEngine?) = "adb"
        override fun getCertificateChain(a: String?) = arrayOf(cert)
        override fun getPrivateKey(a: String?) = key
    }

    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {}
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

object AdbWireless {
    const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    fun findPorts(context: Context, serviceType: String, timeoutMs: Long = 2_500L): List<Int> {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return emptyList()
        val ports = LinkedHashSet<Int>()
        val foundLatch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                val resolveLatch = CountDownLatch(1)
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolveLatch.countDown()
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host
                        if (isLocal(host)) {
                            synchronized(ports) { ports.add(info.port) }
                            foundLatch.countDown()
                        }
                        resolveLatch.countDown()
                    }
                })
                resolveLatch.await(1_200L, TimeUnit.MILLISECONDS)
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { foundLatch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        foundLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        Thread.sleep(250L)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return synchronized(ports) { ports.toList() }
    }

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
                nic.inetAddresses.asSequence().any { it == host }
            }
        }.getOrDefault(false)
    }

    fun pair(context: Context, port: Int, code: String) {
        val tls = AdbTls(AdbIdentity.get(context))
        tls.connect("127.0.0.1", port, 12_000).use { socket ->
            val exported = tls.exportKeyingMaterial(socket, "adb-label\u0000", 64)
            val spake = Spake2(
                "adb pair client\u0000".toByteArray(Charsets.US_ASCII),
                "adb pair server\u0000".toByteArray(Charsets.US_ASCII),
            )
            val myMsg = spake.generateMessage(code.toByteArray(Charsets.UTF_8) + exported)
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream
            writePairingPacket(output, 0, myMsg)
            val theirMsg = readPairingPacket(input, 0) ?: error("配对连接中断")
            val cipher = PairingCipher(spake.processMessage(theirMsg))
            val info = ByteArray(8192)
            info[0] = 0
            val keyBytes = tls.identity.adbPublicKey.toByteArray(Charsets.UTF_8)
            System.arraycopy(keyBytes, 0, info, 1, keyBytes.size)
            writePairingPacket(output, 1, cipher.encrypt(info))
            val sealed = readPairingPacket(input, 1) ?: error("配对码错误或已过期")
            try {
                cipher.decrypt(sealed)
            } catch (_: AEADBadTagException) {
                error("配对码不正确")
            }
        }
    }

    private fun writePairingPacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).put(1.toByte()).put(type).putInt(payload.size).array()
        out.write(header + payload)
        out.flush()
    }

    private fun readPairingPacket(input: DataInputStream, expectedType: Byte): ByteArray? {
        val header = ByteArray(6)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        val bb = ByteBuffer.wrap(header)
        val ver = bb.get()
        val type = bb.get()
        val len = bb.getInt()
        if (ver != 1.toByte() || type != expectedType || len <= 0 || len > 16384) return null
        return ByteArray(len).also { input.readFully(it) }
    }
}

class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {
    fun encode(): ByteArray {
        val bb = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        bb.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command.inv())
        bb.put(data)
        return bb.array()
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        const val VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024

        fun read(input: DataInputStream): AdbMessage? {
            val h = ByteArray(24)
            try {
                input.readFully(h)
            } catch (_: EOFException) {
                return null
            }
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.getInt()
            val a0 = bb.getInt()
            val a1 = bb.getInt()
            val len = bb.getInt()
            bb.getInt()
            val magic = bb.getInt()
            if (magic != cmd.inv() || len < 0 || len > MAX_PAYLOAD) throw IOException("invalid adb packet")
            val data = if (len == 0) ByteArray(0) else ByteArray(len).also { input.readFully(it) }
            return AdbMessage(cmd, a0, a1, data)
        }
    }
}

class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val peerMaxData: Int,
) : Closeable {
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "akashic-adb-reader") {
            try {
                while (!closed) {
                    val m = AdbMessage.read(input) ?: break
                    val s = streams[m.arg1] ?: continue
                    when (m.command) {
                        AdbMessage.OKAY -> if (s.remoteId == 0) {
                            s.remoteId = m.arg0
                            s.opened.complete(true)
                        } else {
                            s.acks.release()
                        }
                        AdbMessage.WRTE -> {
                            s.incoming.put(m.data)
                            send(AdbMessage(AdbMessage.OKAY, s.localId, s.remoteId))
                        }
                        AdbMessage.CLSE -> {
                            streams.remove(s.localId)
                            s.opened.complete(false)
                            s.acks.release()
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                close()
            }
        }
    }

    fun exec(cmd: String): Stream {
        val s = Stream(nextId.getAndIncrement())
        streams[s.localId] = s
        send(AdbMessage(AdbMessage.OPEN, s.localId, 0, ("exec:$cmd\u0000").toByteArray(Charsets.UTF_8)))
        if (!s.opened.get(10, TimeUnit.SECONDS)) error("adb exec refused")
        return s
    }

    private fun send(m: AdbMessage) {
        val bytes = m.encode()
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    inner class Stream(val localId: Int) : Closeable {
        @Volatile var remoteId = 0
        val opened = CompletableFuture<Boolean>()
        val acks = Semaphore(0)
        val incoming = LinkedBlockingQueue<ByteArray>()

        fun write(data: ByteArray) {
            var pos = 0
            while (pos < data.size) {
                val n = minOf(peerMaxData, data.size - pos)
                send(AdbMessage(AdbMessage.WRTE, localId, remoteId, data.copyOfRange(pos, pos + n)))
                acks.tryAcquire(5, TimeUnit.SECONDS)
                pos += n
            }
        }

        override fun close() {
            runCatching { send(AdbMessage(AdbMessage.CLSE, localId, remoteId)) }
            streams.remove(localId)
        }
    }

    companion object {
        fun open(context: Context, port: Int, timeoutMs: Int = 8_000): AdbConnection {
            val tls = AdbTls(AdbIdentity.get(context))
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            plain.soTimeout = timeoutMs
            val out = plain.getOutputStream()
            out.write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray()).encode())
            out.flush()
            val first = AdbMessage.read(DataInputStream(plain.getInputStream())) ?: error("adb closed")
            if (first.command == AdbMessage.STLS) {
                out.write(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode())
                out.flush()
                val ssl = tls.wrap(plain, "127.0.0.1", port)
                val input = DataInputStream(ssl.inputStream)
                val cnxn = AdbMessage.read(input) ?: error("TLS certificate rejected (need pairing)")
                if (cnxn.command != AdbMessage.CNXN) error("expected CNXN")
                ssl.soTimeout = 0
                return AdbConnection(ssl, input, ssl.outputStream, cnxn.arg1.coerceAtLeast(4096))
            }
            if (first.command == AdbMessage.CNXN) {
                plain.soTimeout = 0
                return AdbConnection(plain, DataInputStream(plain.getInputStream()), out, first.arg1.coerceAtLeast(4096))
            }
            plain.close()
            error("unexpected adb handshake")
        }
    }
}
