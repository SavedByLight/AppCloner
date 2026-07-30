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
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.zip.CRC32
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
 *   3. Re-zipping -- preserving each entry's original compression method and, for entries
 *      that must stay uncompressed (resources.arsc, native libs), padding them onto the
 *      same alignment boundary `zipalign` would -- then signing with a freshly generated key.
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
        Logger.log("Rewrote AndroidManifest.xml package/authorities, preserved compression, aligned entries")

        val signedApk = File(workDir, "${newPackageName}_signed.apk")
        signApk(rewrittenApk, signedApk)
        Logger.log("Re-signed APK with generated key -> ${signedApk.name}")

        return signedApk
    }

    // ---------------------------------------------------------------------
    // Manifest rewriting + zip reassembly
    // ---------------------------------------------------------------------

    private fun rewriteManifest(inputApk: File, outputApk: File, oldPkg: String, newPkg: String) {
        ZipFile(inputApk).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("No AndroidManifest.xml found")

            val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
            val newManifestBytes = rewritePackageInAxml(manifestBytes, oldPkg, newPkg)

            val counting = CountingOutputStream(FileOutputStream(outputApk))
            ZipOutputStream(counting).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    if (entry.name == "META-INF/MANIFEST.MF" ||
                        (entry.name.startsWith("META-INF/") &&
                                (entry.name.endsWith(".SF") || entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA")))
                    ) {
                        // Drop the original signature block; we re-sign from scratch below.
                        continue
                    }

                    val bytes = if (entry.name == "AndroidManifest.xml") newManifestBytes
                                else zip.getInputStream(entry).readBytes()

                    writeEntry(zos, counting, entry.name, bytes, entry.method, alignmentFor(entry.name))
                }
            }
        }
    }

    /**
     * Since Android 11 (API 30) the platform installer requires resources.arsc to be stored
     * uncompressed and 4-byte aligned, or install fails outright. Native libraries that are
     * marked for direct mmap (extractNativeLibs="false") need to stay uncompressed and page
     * aligned too. Everything else can be left wherever it lands.
     */
    private fun alignmentFor(entryName: String): Int = when {
        entryName == "resources.arsc" -> 4
        entryName.startsWith("lib/") && entryName.endsWith(".so") -> 4096
        else -> 1
    }

    /**
     * Writes a single zip entry, preserving STORED vs DEFLATED so we don't silently recompress
     * things the platform requires to stay uncompressed, and -- for STORED entries that need
     * it -- padding with a self-describing "ignore me" extra field so the entry's data starts
     * on the required alignment boundary. This is the same trick the `zipalign` tool uses.
     */
    private fun writeEntry(
        zos: ZipOutputStream,
        counting: CountingOutputStream,
        name: String,
        bytes: ByteArray,
        method: Int,
        alignment: Int
    ) {
        val entry = ZipEntry(name)
        if (method == ZipEntry.STORED) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            val crc = CRC32()
            crc.update(bytes)
            entry.crc = crc.value

            if (alignment > 1) {
                val headerSize = 30 + name.toByteArray(Charsets.UTF_8).size
                val unpaddedOffset = counting.count + headerSize
                val remainder = (unpaddedOffset % alignment).toInt()
                if (remainder != 0) {
                    var padLen = alignment - remainder
                    // A valid extra-field sub-record needs 4 bytes of header (id + length).
                    // If the raw gap is smaller than that, round up a full alignment so
                    // there's room to encode it and still land on the boundary.
                    if (padLen < 4) padLen += alignment
                    val extra = ByteArray(padLen)
                    extra[0] = 0xD9.toByte(); extra[1] = 0x35.toByte() // arbitrary "unknown, skip" id
                    val dataLen = padLen - 4
                    extra[2] = (dataLen and 0xFF).toByte()
                    extra[3] = ((dataLen shr 8) and 0xFF).toByte()
                    entry.extra = extra
                }
            }
        } else {
            entry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    /** Tracks bytes written so far so we know each entry's offset for alignment padding. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    // ---------------------------------------------------------------------
    // AXML manifest rewriting
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Signing
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------

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
