package com.m8trx.twin.history

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RFC 4180 CSV — CRLF line ends, a field is quoted only when it carries a comma, quote, CR or LF. */
object HistoryCsv {
    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun ts(i: Instant): String = TS.format(i)

    fun field(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Instant -> ts(v)
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun row(vararg v: Any?): String = v.joinToString(",") { field(it) }

    /** Integer cents as a plain decimal (`1234` → `12.34`) — never scientific notation, never float drift. */
    fun money(cents: Long): String = "%d.%02d".format(Locale.ROOT, cents / 100, cents % 100)

    fun fixed(v: Double, places: Int): String = "%.${places}f".format(Locale.ROOT, v)

    /** Writes header + rows; returns the data row count. */
    fun write(path: Path, header: List<String>, rows: List<String>): Int {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, Charsets.UTF_8).use { w ->
            w.write(header.joinToString(","))
            w.write("\r\n")
            rows.forEach {
                w.write(it)
                w.write("\r\n")
            }
        }
        return rows.size
    }

    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parse an RFC 4180 file back (header excluded) — for the validators. Handles quoted CR/LF-free fields. */
    fun read(path: Path): Pair<List<String>, List<List<String>>> {
        val lines = Files.readAllLines(path).filter { it.isNotEmpty() }
        val header = com.m8trx.twin.layer2.StoreCatalog.splitCsv(lines.first())
        return header to lines.drop(1).map { com.m8trx.twin.layer2.StoreCatalog.splitCsv(it) }
    }

    /** Deterministic 64-bit seed from a master seed and a label path — SHA-256, so labels never collide by accident. */
    fun derive(master: Long, vararg labels: Any): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(master.toString().toByteArray())
        labels.forEach {
            md.update(0)
            md.update(it.toString().toByteArray())
        }
        val b = md.digest()
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xff)
        return v
    }
}
