package com.savedbylight.appclonerclassic

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Rebuilds an APK so STORED entry payloads sit on aligned file offsets.
 *
 * Android 11+ requires resources.arsc to be STORED and 4-byte aligned.
 * Native libraries (.so) should be page-aligned (4096).
 *
 * DEFLATED entries are copied with their original compressed payload whenever
 * possible so we do not change compression or drop data (a previous pass that
 * re-deflated everything could shrink a ~107MB base to ~59MB and break classes).
 */
object ZipAligner {

    private const val LOCAL_HEADER = 0x04034b50
    private const val CENTRAL_HEADER = 0x02014b50
    private const val EOCD = 0x06054b50

    private class Item(
        val name: String,
        val uncompressed: ByteArray,
        val compressed: ByteArray?, // null => write as STORED from uncompressed
        val method: Int,            // 0 STORED, 8 DEFLATED
        val crc: Long
    )

    fun alignInPlace(apk: File, defaultAlignment: Int = 4, soAlignment: Int = 4096) {
        val tmp = File(apk.parentFile, apk.name + ".aligned")
        try {
            align(apk, tmp, defaultAlignment, soAlignment)
            if (!tmp.exists() || tmp.length() < 1000L) {
                Logger.log("ZipAligner: output too small (${tmp.length()} bytes) — keeping original")
                return
            }
            // Sanity: aligned APK should not be dramatically smaller than input
            // unless input was mostly STORED that we still store. If we lost
            // more than 25% of bytes, refuse to replace (protects against bugs).
            if (tmp.length() < apk.length() * 75 / 100) {
                Logger.log(
                    "ZipAligner: refusing shrink ${apk.length()} → ${tmp.length()} bytes — keeping original"
                )
                return
            }
            tmp.copyTo(apk, overwrite = true)
            Logger.log("ZipAligner: aligned ${apk.name} (${apk.length()} bytes)")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun align(input: File, output: File, defaultAlignment: Int = 4, soAlignment: Int = 4096) {
        val items = mutableListOf<Item>()

        ZipFile(input).use { zf ->
            // Map name -> raw compressed payload by scanning local headers
            val compressedByName = readCompressedPayloads(input)

            zf.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val uncompressed = zf.getInputStream(e).use { it.readBytes() }
                val forceStored = e.name == "resources.arsc" ||
                    e.name.endsWith(".so") ||
                    e.name.startsWith("lib/")

                val crc = CRC32().apply { update(uncompressed) }.value
                if (forceStored || e.method == ZipEntry.STORED) {
                    items.add(Item(e.name, uncompressed, null, ZipEntry.STORED, crc))
                } else {
                    val raw = compressedByName[e.name]
                    if (raw != null && raw.isNotEmpty()) {
                        items.add(Item(e.name, uncompressed, raw, ZipEntry.DEFLATED, crc))
                    } else {
                        // Fallback: re-deflate
                        items.add(Item(e.name, uncompressed, deflate(uncompressed), ZipEntry.DEFLATED, crc))
                    }
                }
            }
        }

        Logger.log("ZipAligner: rewriting ${items.size} entries")

        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(0)
            val centralRecords = ArrayList<ByteArray>(items.size)
            val localOffsets = ArrayList<Long>(items.size)

            for (p in items) {
                val nameBytes = p.name.toByteArray(Charsets.UTF_8)
                val payload = if (p.method == ZipEntry.STORED) p.uncompressed else (p.compressed ?: p.uncompressed)
                val align = when {
                    p.name == "resources.arsc" -> 4
                    p.name.endsWith(".so") || p.name.startsWith("lib/") -> soAlignment
                    p.method == ZipEntry.STORED -> defaultAlignment
                    else -> 1
                }

                val headerBase = 30 + nameBytes.size
                val posBefore = raf.filePointer
                var extraLen = 0
                if (align > 1 && p.method == ZipEntry.STORED) {
                    val dataStart = posBefore + headerBase
                    val mis = (dataStart % align).toInt()
                    if (mis != 0) extraLen = align - mis
                }
                val extra = ByteArray(extraLen)

                val local = ByteArrayOutputStream()
                writeInt(local, LOCAL_HEADER)
                writeShort(local, 20)
                writeShort(local, 0)
                writeShort(local, if (p.method == ZipEntry.STORED) 0 else 8)
                writeShort(local, 0)
                writeShort(local, 0)
                writeInt(local, p.crc.toInt())
                writeInt(local, payload.size)
                writeInt(local, p.uncompressed.size)
                writeShort(local, nameBytes.size)
                writeShort(local, extra.size)
                local.write(nameBytes)
                local.write(extra)

                localOffsets.add(raf.filePointer)
                raf.write(local.toByteArray())
                raf.write(payload)

                val central = ByteArrayOutputStream()
                writeInt(central, CENTRAL_HEADER)
                writeShort(central, 20)
                writeShort(central, 20)
                writeShort(central, 0)
                writeShort(central, if (p.method == ZipEntry.STORED) 0 else 8)
                writeShort(central, 0)
                writeShort(central, 0)
                writeInt(central, p.crc.toInt())
                writeInt(central, payload.size)
                writeInt(central, p.uncompressed.size)
                writeShort(central, nameBytes.size)
                writeShort(central, 0)
                writeShort(central, 0)
                writeShort(central, 0)
                writeShort(central, 0)
                writeInt(central, 0)
                writeInt(central, localOffsets.last().toInt())
                central.write(nameBytes)
                centralRecords.add(central.toByteArray())
            }

            val cdStart = raf.filePointer
            for (c in centralRecords) raf.write(c)
            val cdSize = raf.filePointer - cdStart

            val eocd = ByteArrayOutputStream()
            writeInt(eocd, EOCD)
            writeShort(eocd, 0)
            writeShort(eocd, 0)
            writeShort(eocd, items.size)
            writeShort(eocd, items.size)
            writeInt(eocd, cdSize.toInt())
            writeInt(eocd, cdStart.toInt())
            writeShort(eocd, 0)
            raf.write(eocd.toByteArray())
        }
    }

    /**
     * Walk local file headers and return name -> compressed payload bytes
     * (the exact bytes stored on disk for that entry).
     */
    private fun readCompressedPayloads(apk: File): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        RandomAccessFile(apk, "r").use { raf ->
            while (raf.filePointer < raf.length() - 4) {
                val sig = readInt(raf)
                if (sig != LOCAL_HEADER) break
                raf.skipBytes(2 + 2) // ver, flags
                val method = readShort(raf)
                raf.skipBytes(2 + 2) // time, date
                raf.skipBytes(4) // crc
                val compSize = readInt(raf).toLong() and 0xffffffffL
                val uncompSize = readInt(raf).toLong() and 0xffffffffL
                val nameLen = readShort(raf)
                val extraLen = readShort(raf)
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                raf.skipBytes(extraLen)
                val name = String(nameBytes, Charsets.UTF_8)
                // Skip data descriptors / encrypted — assume standard APKs
                val payload = ByteArray(compSize.toInt().coerceAtLeast(0))
                if (payload.isNotEmpty()) raf.readFully(payload)
                // If STORED, compressed size == uncompressed; still record
                map[name] = payload
                // Guard against runaway
                if (map.size > 100_000) break
            }
        }
        return map
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

    private fun readShort(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        return (b1 and 0xff) or ((b2 and 0xff) shl 8)
    }

    private fun readInt(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        return (b1 and 0xff) or ((b2 and 0xff) shl 8) or ((b3 and 0xff) shl 16) or ((b4 and 0xff) shl 24)
    }
}
