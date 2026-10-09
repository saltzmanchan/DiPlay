package com.shilapi.xcertplay.adb

import java.net.Socket
import java.security.KeyPair
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator

/**
 * TLS for Android 11+ wireless debugging (AOSP adb/protocol.txt, "STLS"). adbd authenticates the
 * client by the RSA key in its certificate, so DiPlay presents a self-signed certificate for its own
 * [AdbKeys] pair. Some head units run adbd with authentication off and accept any key.
 */
internal object AdbTls {
    private const val ALIAS = "diplay"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val TLS_PORT_PROPERTY = "service.adb.tls.port"

    fun context(key: KeyPair): SSLContext {
        val chain = arrayOf(selfSignedCertificate(key))
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(ClientKeyManager(chain, key.private)), arrayOf(LoopbackTrustManager), SecureRandom())
        }
    }

    /** The port adbd listens on for wireless debugging; it changes whenever adbd restarts. */
    fun wirelessDebuggingPort(): Int? = runCatching {
        val process = ProcessBuilder("getprop", TLS_PORT_PROPERTY).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.trim().toIntOrNull()?.takeIf { it in 1..65_535 }
    }.getOrNull()

    internal fun selfSignedCertificate(key: KeyPair): X509Certificate {
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val name = X500Name("CN=DiPlay")
        val now = System.currentTimeMillis()
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(now))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - DAY_MILLIS)))
            setEndDate(Time(Date(now + 3650 * DAY_MILLIS)))
            setSignature(algorithm)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(key.public.encoded))
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private)
            update(tbs.encoded)
            sign()
        }
        val der = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signature))).encoded
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private class ClientKeyManager(
        private val chain: Array<X509Certificate>,
        private val privateKey: PrivateKey,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = privateKey
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    /** adbd's certificate is self-signed and regenerated per device, so only its loopback address is checked. */
    private object LoopbackTrustManager : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (socket?.inetAddress?.isLoopbackAddress != true) throw CertificateException("adbd must be on this head unit")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            throw CertificateException("adbd connections use a socket")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("adbd connections use a socket")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            throw CertificateException("client mode only")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            throw CertificateException("client mode only")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client mode only")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
