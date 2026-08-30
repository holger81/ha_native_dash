package dev.holgerendt.hanative.data

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

class ManagementTls(context: Context) {
    private val app = context.applicationContext

    fun sslServerSocketFactory(): SSLServerSocketFactory {
        val (store, password) = loadOrCreate()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, password.toCharArray())
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, SecureRandom())
        return ssl.serverSocketFactory
    }

    private data class Material(val password: String, val p12: ByteArray)

    private fun loadOrCreate(): Pair<KeyStore, String> {
        loadMaterial(internalFile().takeIf { it.isFile }?.readBytes())?.let { material ->
            p12Store(material.p12, material.password)?.let { store ->
                if (material.password == LEGACY_PASSWORD) persist(store, randomPassword())
                return store to material.password
            }
        }
        val publicRaw = SecureRecovery.readRaw(app, RecoverableFiles.TLS_NAME)
        loadMaterial(publicRaw?.let { SecureRecovery.decryptIfSealed(app, SecureRecovery.TLS_MAGIC, it) ?: it })
            ?.let { material ->
                p12Store(material.p12, material.password)?.let { store ->
                    val password = if (material.password == LEGACY_PASSWORD) randomPassword() else material.password
                    persist(store, password)
                    return store to password
                }
            }
        val password = randomPassword()
        val store = generate(password)
        persist(store, password)
        return store to password
    }

    private fun loadMaterial(bytes: ByteArray?): Material? {
        if (bytes == null || bytes.isEmpty()) return null
        if (bytes.size > PASSWORD_PREFIX_LENGTH) {
            val prefix = String(bytes, 0, PASSWORD_PREFIX_LENGTH, Charsets.UTF_8)
            if (prefix == prefix.trim()) {
                val password = prefix.trim()
                val p12 = bytes.copyOfRange(PASSWORD_PREFIX_LENGTH, bytes.size)
                p12Store(p12, password)?.let { return Material(password, p12) }
            }
        }
        p12Store(bytes, LEGACY_PASSWORD)?.let { return Material(LEGACY_PASSWORD, bytes) }
        return null
    }

    private fun persist(store: KeyStore, password: String) {
        val p12 = ByteArrayOutputStream().use { out ->
            store.store(out, password.toCharArray())
            out.toByteArray()
        }
        val blob = password.toByteArray(Charsets.UTF_8) + p12
        runCatching { internalFile().writeBytes(blob) }
        runCatching {
            SecureRecovery.writeSealed(
                app,
                RecoverableFiles.TLS_NAME,
                "application/x-pkcs12",
                SecureRecovery.TLS_MAGIC,
                blob,
            )
        }
    }

    private fun p12Store(p12: ByteArray, password: String): KeyStore? = runCatching {
        KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(p12), password.toCharArray())
        }
    }.getOrNull()

    private fun generate(password: String): KeyStore {
        val keyPair = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048, SecureRandom())
            generateKeyPair()
        }
        val name = X500Name("CN=HA Native Dash")
        val now = Date()
        val until = Calendar.getInstance().run {
            time = now
            add(Calendar.YEAR, 10)
            time
        }
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(128, SecureRandom()),
            now,
            until,
            name,
            keyPair.public,
        )
        val sans = buildList {
            add(GeneralName(GeneralName.dNSName, "localhost"))
            add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
            LanAddresses.ipv4().forEach { ip ->
                add(GeneralName(GeneralName.iPAddress, ip))
            }
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(holder)
        return KeyStore.getInstance("PKCS12").apply {
            load(null, password.toCharArray())
            setKeyEntry(ALIAS, keyPair.private, password.toCharArray(), arrayOf(cert))
        }
    }

    private fun randomPassword(): String =
        Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP)

    private fun internalFile(): File = File(app.filesDir, RecoverableFiles.TLS_NAME)

    companion object {
        private const val ALIAS = "management"
        private const val PASSWORD_PREFIX_LENGTH = 44
        private const val LEGACY_PASSWORD = "ha-native-dash"
    }
}
