package com.savedbylight.appclonerclassic

import android.content.Context
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Generates (once) and persists a throwaway self-signed RSA key/cert used to
 * sign every cloned APK produced by this app.
 *
 * All APKs belonging to one clone install session (base + splits) must be
 * signed with the SAME certificate or PackageInstaller rejects the session.
 * Reusing the same persisted key across cloneApp() runs also means
 * re-cloning the same app later produces an APK that can update the
 * previous clone in place, instead of requiring an uninstall first.
 *
 * This is a local, throwaway identity — it has nothing to do with (and
 * cannot match) the original app's real signing certificate, which is why
 * the clone is a distinct app from Android's point of view.
 */
object SigningIdentity {

    private const val KEYSTORE_FILE = "clone_signing.p12"
    private const val KEY_ALIAS = "clone_key"

    // Protects a local-only throwaway keystore that never leaves the
    // device and only ever signs clones this app itself installs; not a
    // meaningful secret.
    private const val STORE_PASSWORD = "app-cloner-local"

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Volatile
    private var cached: Pair<PrivateKey, List<X509Certificate>>? = null

    fun getOrCreate(context: Context): Pair<PrivateKey, List<X509Certificate>> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.filesDir, KEYSTORE_FILE)
            val result = if (file.exists()) load(file) else generate(file)
            cached = result
            return result
        }
    }

    private fun load(file: File): Pair<PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, STORE_PASSWORD.toCharArray()) }
        val key = ks.getKey(KEY_ALIAS, STORE_PASSWORD.toCharArray()) as PrivateKey
        val chain = ks.getCertificateChain(KEY_ALIAS).map { it as X509Certificate }
        return key to chain
    }

    private fun generate(file: File): Pair<PrivateKey, List<X509Certificate>> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 30L * 365 * 24 * 60 * 60 * 1000) // ~30 years, matches typical apksigner defaults
        val subject = X500Principal("CN=AppCloner Local Clone")
        val serial = BigInteger.valueOf(now)

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, STORE_PASSWORD.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, STORE_PASSWORD.toCharArray()) }

        return keyPair.private to listOf(cert)
    }
}
