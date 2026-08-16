package dev.holgerendt.hanative.data

import android.content.Context
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
        val store = loadOrCreate()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, PASSWORD)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, SecureRandom())
        return ssl.serverSocketFactory
    }

    private fun loadOrCreate(): KeyStore {
        load(internalFile().takeIf { it.isFile }?.readBytes())?.let { return it }
        RecoverableFiles.read(app, RecoverableFiles.TLS_NAME)?.let { bytes ->
            val store = load(bytes) ?: return@let
            runCatching { internalFile().writeBytes(bytes) }
            return store
        }
        val store = generate()
        persist(store)
        return store
    }

    private fun persist(store: KeyStore) {
        val bytes = ByteArrayOutputStream().use { out ->
            store.store(out, PASSWORD)
            out.toByteArray()
        }
        runCatching { internalFile().writeBytes(bytes) }
        runCatching {
            RecoverableFiles.write(app, RecoverableFiles.TLS_NAME, "application/x-pkcs12", bytes)
        }
    }

    private fun load(bytes: ByteArray?): KeyStore? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            KeyStore.getInstance("PKCS12").apply {
                load(ByteArrayInputStream(bytes), PASSWORD)
            }
        }.getOrNull()
    }

    private fun generate(): KeyStore {
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
            load(null, PASSWORD)
            setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(cert))
        }
    }

    private fun internalFile(): File = File(app.filesDir, RecoverableFiles.TLS_NAME)

    companion object {
        private const val ALIAS = "management"
        private val PASSWORD = "ha-native-dash".toCharArray()
    }
}
