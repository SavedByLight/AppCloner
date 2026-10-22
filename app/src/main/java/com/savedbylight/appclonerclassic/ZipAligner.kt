package com.savedbylight.appclonerclassic

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Re-writes an APK so that STORED entries have their payload at a required
 * alignment. Android 11+ (targetSdk >= 30) requires resources.arsc to be
 * STORED and aligned on a 4-byte boundary; native libraries are typically
 * page-aligned (4096).
 *
 * Approach: rebuild the zip with ZipOutputStream is insufficient for absolute
 * alignment because we cannot inject padding between the local header and
 * the data without controlling the extra field length relative to the current
 * file offset. So we write a raw ZIP structure manually for STORED entries.
 *
 * For simplicity and reliability we:
 * 1. Read all entries into memory (clone APKs are large but already buffered).
 * 2. Write local headers + data with padding in the extra field so data starts
 *    at an aligned file offset.
 * 3. Write central directory and EOCD.
 */
object ZipAligner {

    private const val LOCAL_HEADER = 0x04034b50
    private const val CENTRAL_HEADER = 0x02014b50
    private const val EOCD = 0x06054b50

    data class Entry(
        val name: String,
        val data: ByteArray,
        val stored: Boolean,
        val time: Int = 0,
        val date: Int = 0
    )

    fun align(input: File, output: File, defaultAlignment: Int = 4, soAlignment: Int = 4096) {
        val entries = mutableListOf<Entry>()
        ZipFile(input).use { zf ->
            zf.entries().asSequence().forEach { e ->
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                val mustStore = e.name == "resources.arsc" ||
                    e.method == ZipEntry.STORED ||
                    e.name.endsWith(".so")
                entries.add(Entry(e.name, bytes, mustStore, 0, 0))
            }
        }

        // Compress DEFLATED entries first so we know sizes
        data class Prepared(
            val name: String,
            val payload: ByteArray, // compressed or raw
            val uncompressed: ByteArray,
            val stored: Boolean,
            val crc: Long,
            val time: Int,
            val date: Int
        )

        val prepared = entries.map { e ->
            val crc = CRC32().apply { update(e.data) }.value
            if (e.stored) {
                Prepared(e.name, e.data, e.data, true, crc, e.time, e.date)
            } else {
                val baos = ByteArrayOutputStream()
                java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true).let { def ->
                    // Use ZipOutputStream for a single entry to get proper deflate
                }
                // Simpler: use ZipOutputStream to compress one entry
                val compressed = deflate(e.data)
                Prepared(e.name, compressed, e.data, false, crc, e.time, e.date)
            }
        }

        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(0)
            val centralRecords = mutableListOf<ByteArray>()
            val localOffsets = mutableListOf<Long>()

            for (p in prepared) {
                val nameBytes = p.name.toByteArray(Charsets.UTF_8)
                val align = when {
                    p.name == "resources.arsc" -> 4
                    p.name.endsWith(".so") || p.name.startsWith("lib/") -> soAlignment
                    p.stored -> defaultAlignment
                    else -> 1
                }

                // Local header is 30 + nameLen + extraLen; data follows.
                // We choose extraLen so that (currentPos + 30 + nameLen + extraLen) % align == 0
                val headerBase = 30 + nameBytes.size
                val posBefore = raf.filePointer
                var extraLen = 0
                if (align > 1 && p.stored) {
                    val dataStartUnpadded = posBefore + headerBase
                    val mis = (dataStartUnpadded % align).toInt()
                    if (mis != 0) extraLen = align - mis
                }
                val extra = ByteArray(extraLen)

                val local = ByteArrayOutputStream()
                writeInt(local, LOCAL_HEADER)
                writeShort(local, 20) // version needed
                writeShort(local, 0) // flags
                writeShort(local, if (p.stored) 0 else 8) // method
                writeShort(local, p.time)
                writeShort(local, p.date)
                writeInt(local, p.crc.toInt())
                writeInt(local, p.payload.size)
                writeInt(local, p.uncompressed.size)
                writeShort(local, nameBytes.size)
                writeShort(local, extra.size)
                local.write(nameBytes)
                local.write(extra)

                localOffsets.add(raf.filePointer)
                raf.write(local.toByteArray())
                raf.write(p.payload)

                // Central directory record
                val central = ByteArrayOutputStream()
                writeInt(central, CENTRAL_HEADER)
                writeShort(central, 20) // version made by
                writeShort(central, 20) // version needed
                writeShort(central, 0) // flags
                writeShort(central, if (p.stored) 0 else 8)
                writeShort(central, p.time)
                writeShort(central, p.date)
                writeInt(central, p.crc.toInt())
                writeInt(central, p.payload.size)
                writeInt(central, p.uncompressed.size)
                writeShort(central, nameBytes.size)
                writeShort(central, 0) // extra
                writeShort(central, 0) // comment
                writeShort(central, 0) // disk start
                writeShort(central, 0) // internal attrs
                writeInt(central, 0) // external attrs
                writeInt(central, localOffsets.last().toInt())
                central.write(nameBytes)
                centralRecords.add(central.toByteArray())
            }

            val cdStart = raf.filePointer
            for (c in centralRecords) raf.write(c)
            val cdSize = raf.filePointer - cdStart

            // EOCD
            val eocd = ByteArrayOutputStream()
            writeInt(eocd, EOCD)
            writeShort(eocd, 0)
            writeShort(eocd, 0)
            writeShort(eocd, prepared.size)
            writeShort(eocd, prepared.size)
            writeInt(eocd, cdSize.toInt())
            writeInt(eocd, cdStart.toInt())
            writeShort(eocd, 0)
            raf.write(eocd.toByteArray())
        }
    }

    /** Align in place. */
    fun alignInPlace(apk: File, defaultAlignment: Int = 4, soAlignment: Int = 4096) {
        val tmp = File(apk.parentFile, apk.name + ".aligned")
        try {
            align(apk, tmp, defaultAlignment, soAlignment)
            tmp.copyTo(apk, overwrite = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }
}
