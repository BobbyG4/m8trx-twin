package com.m8trx.twin.connect.sim

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Simulator #5 (partial) — the inbound SFTP file-drop CONTRACT (Connect API doc §7 SFTP ingestion).
 *
 * SCOPE (Bob's call, 2026-06-27): build the FORMATTER now; defer the sshj TRANSPORT as a fast-follow.
 * Core's `SftpFileDropJob` polls a watched directory, parses each matching file as **UTF-8 CSV with a
 * header row**, and field-maps **each row** into one canonical event of the channel's `data_type`. So
 * the load-bearing contract twin must get right is "a correctly-shaped header-row CSV" — which is a
 * pure, fully offline-testable function. The actual upload-to-watched-directory (sshj) lands once a
 * live SFTP endpoint + creds exist.
 *
 * Column names below are the canonical field names; an identity channel field-map ingests them as-is.
 */
object SftpDropDriver {
    private val log = LoggerFactory.getLogger(SftpDropDriver::class.java)

    /** RFC-4180-style CSV: CRLF row terminator; quote any field containing comma/quote/CR/LF; double internal quotes. */
    fun toCsv(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { escape(it) }).append("\r\n")
        for (row in rows) {
            sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** `sale_event` rows via the SKU path: header `external_sale_id,site_id,occurred_at,sku,quantity`. */
    fun saleEventsCsv(rows: List<SaleEventRow>): String = toCsv(
        listOf("external_sale_id", "site_id", "occurred_at", "sku", "quantity"),
        rows.map { listOf(it.externalSaleId, it.siteId, it.occurredAt, it.sku, it.quantity.toString()) },
    )

    /** `pricing_update` rows: header `sku,price_minor`. */
    fun pricingUpdatesCsv(rows: List<Pair<String, Long>>): String = toCsv(
        listOf("sku", "price_minor"),
        rows.map { listOf(it.first, it.second.toString()) },
    )

    /** `product_catalog` rows: header `sku,name`. */
    fun productCatalogCsv(rows: List<Pair<String, String>>): String = toCsv(
        listOf("sku", "name"),
        rows.map { listOf(it.first, it.second) },
    )

    /**
     * Local stand-in for the drop — writes the CSV to a LOCAL path for inspection / offline tests.
     * This is NOT the SFTP transport (that is the deferred sshj fast-follow); it just materializes the
     * exact bytes a real drop would deliver.
     */
    fun writeLocal(dir: Path, filename: String, csv: String): Path {
        Files.createDirectories(dir)
        val target = dir.resolve(filename)
        Files.writeString(target, csv)
        log.info("wrote SFTP-shaped CSV ({} bytes) → {}", csv.toByteArray().size, target)
        return target
    }

    private fun escape(field: String): String = if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + field.replace("\"", "\"\"") + "\""
    } else {
        field
    }
}

/** One SKU-path sale row for [SftpDropDriver.saleEventsCsv]. */
data class SaleEventRow(val externalSaleId: String, val siteId: String, val occurredAt: String, val sku: String, val quantity: Int)
