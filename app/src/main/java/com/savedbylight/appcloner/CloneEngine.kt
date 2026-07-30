package com.savedbylight.appcloner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlVisitor
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import java.io.File
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal
import java.math.BigInteger
import java.util.Date
import java.util.Calendar
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Clones an installed app by:
 *   1. Copying its APK out of /data/app
 *   2. Rewriting the package name (and all matching permissions/authorities) inside the
 *      binary AndroidManifest.xml using the AXML format directly.
 *   3. Re-zipping and signing with a freshly generated key.
 *   4. Handing the result to the system installer.
 */
class CloneEngine(private val context: Context) {

    private val workDir: File
        get() = File(context.cacheDir, "cloned_apks").apply { mkdirs() }

    fun cloneApp(app: InstalledApp): File {
        val newPackageName = "${app.packageName}.clone1"
        Logger.log("Starting clone of ${app.packageName} -> $newPackageName")

        val sourceApk = File(app.sourceApkPath)
        val workingCopy = File(workDir, "${app.packageName}_work.apk")
        sourceApk.copyTo(workingCopy, overwrite = true)
        Logger.log("Copied source APK (${sourceApk.length()} bytes)")

        val rewrittenApk = File(workDir, "${app.packageName}_rewritten.apk")
        rewriteManifest(workingCopy, rewrittenApk, app.packageName, newPackageName)
        Logger.log("Rewrote AndroidManifest.xml package/authorities")

        val signedApk = File(workDir, "${newPackageName}_signed.apk")
        signApk(rewrittenApk, signedApk)
        Logger.log("Re-signed APK with generated key -> ${signedApk.name}")

        return signedApk
    }

    private fun rewriteManifest(inputApk: File, outputApk: File, oldPkg: String, newPkg: String) {
        ZipFile(inputApk).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("No AndroidManifest.xml found")

            val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
            val newManifestBytes = rewritePackageInAxml(manifestBytes, oldPkg, newPkg)

            ZipOutputStream(outputApk.outputStream()).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "AndroidManifest.xml") {
                        zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                        zos.write(newManifestBytes)
                    } else if (entry.name == "META-INF/MANIFEST.MF" ||
                        entry.name.startsWith("META-INF/") && (entry.name.endsWith(".SF") ||
                                entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA"))
                    ) {
                        // Drop original signature block
                        continue
                    } else {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun rewritePackageInAxml(manifestBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        Logger.log("AXML: parsing manifest (${manifestBytes.size} bytes)")
        val reader = AxmlReader(manifestBytes)
        val writer = AxmlWriter()

        Logger.log("AXML: walking nodes to rewrite package/authorities")
        reader.accept(object : AxmlVisitor(writer) {
            override fun child(ns: String?, name: String?): NodeVisitor {
                val safeNs = ns ?: ""
                val superVisitor = super.child(safeNs, name)
                return RewritingNodeVisitor(superVisitor, oldPkg, newPkg)
            }

            override fun ns(prefix: String?, uri: String?, line: Int) {
                super.ns(prefix ?: "", uri ?: "", line)
            }
        })

        Logger.log("AXML: serializing rewritten manifest")
        return writer.toByteArray()
    }

    private class RewritingNodeVisitor(
        parent: NodeVisitor,
        private val oldPkg: String,
        private val newPkg: String
    ) : NodeVisitor(parent) {

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, value: Any?) {
            val safeNs = ns ?: ""
            var newValue = value

            // TYPE_STRING in AXML is 0x03. Guard against null string values to prevent NPEs in AxmlWriter
            if (type == 0x03 && newValue == null) {
                newValue = ""
            }

            // Replace all string attribute occurrences of the package name (packages, authorities, permissions, etc.)
            if (newValue is String && newValue.contains(oldPkg)) {
                newValue = newValue.replace(oldPkg, newPkg)
            }
            super.attr(safeNs, name, resourceId, type, newValue)
        }

        override fun child(ns: String?, name: String?): NodeVisitor {
            val safeNs = ns ?: ""
            return RewritingNodeVisitor(super.child(safeNs, name), oldPkg, newPkg)
        }
    }

    private fun signApk(inputApk: File, outputApk: File) {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        val now = Date()
        val expiry = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, 30)
        }.time

        val subject = X500Principal("CN=AppCloner, O=SavedByLight")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(System.currentTimeMillis()),
            now, expiry, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert: X509Certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "clonekey", keyPair.private, listOf(cert)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    fun launchInstall(apkFile: File) {
        Logger.log("Launching installer for ${apkFile.name}")
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
