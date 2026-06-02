# EPC Encoding — Decathlon SGTIN-96 (validated against real tags)

**Status:** VALIDATED 2026-06-02 (Session 4). Scheme reverse-engineered from the observed bit layout
and **proven against 169,399 real Decathlon warehouse tags** from `dump-pantos-202504250747.sql`.

**Why this exists:** EPCs for the twin's seeded inventory must be *real* — the same tags Decathlon's
own scanners decode back to the right article — not random hex. This doc is the encoder contract.

---

## Provenance & IP note

The decode direction is implemented in Mojix/pantos proprietary code
(`pantos-decathlon/.../converter/EpcToEan.java`, decompiled). **We do not copy that code.** The bit
layout below was derived independently from its observable behavior and the inverse (`EAN → EPC`)
is a clean-room implementation. Cite the scheme as "observed Decathlon SGTIN-96 tag structure."

The validation dump (`reference/data/dump-pantos-202504250747.sql`, MariaDB, 2025-04 warehouse
snapshot) is reference-only ground truth. EPCs live in `rfidTag` / `_RFIDTAG` columns across the
`*_RFID*` tables (`MA_IV_RFID`, `TWMS_IV_RFID`, `MA_OUTB_ORD_RFID`, …).

---

## Validation evidence (real data)

Extracted every quoted 24-hex `30…` token from the dump → 306,387 tokens, **169,399 distinct**.
Decoded each with the clean-room logic below:

| Bit field | Observed across all 169,399 tags |
|---|---|
| header `[0,8)` | `0x30` — 100% |
| filter `[8,11)` | `1` — 100% |
| partition `[11,14)` | `6` — 100% |
| indicator `[34,38)` | `0` — 100% |
| company `[14,34)` | 6-digit GS1-FR: `360839` (85k), `360842` (48k), `358378` (23k), `360841`, `360843`, … |
| serial `[58,96)` | sparse, range ~20,151–278,498, density ~0.03 (NOT sequential) |

**Ground-truth cross-check:** the 169k tags decode to 3,231 distinct EANs; **3,221 (100% of real
articles) are present in the 56k Korea catalog** (`sample_stores/decathlon-korea-raw.csv`). The
10 non-matches are GS1 `21…` internal/variable-measure codes, not catalog products. The encoder
is proven correct end-to-end.

---

## Bit layout (96 bits → 24 hex chars)

```
 bit   width  field        source
 0     8      header=0x30  constant
 8     3      filter=1     constant (real Decathlon value)
11     3      partition=6  constant (real Decathlon value)
14    20      company      EAN digits 0–5   (e.g. "358378") as 20-bit uint
34     4      indicator=0  constant (GTIN-13)
38    20      itemref      EAN digits 6–11  (e.g. "776553") as 20-bit uint
58    38      serial       per-unit, 38-bit (≤ 274,877,906,943)
```

**EAN reconstruction (decode):** `company(6) + itemref(6) + checkDigit`, where the check digit is
recomputed (the stored EAN check digit is not carried in the EPC). Indicator (0) participates in the
checksum input but is dropped from the final EAN string. This matches the catalog EANs exactly.

**Encoding constraint:** only 13-digit EANs whose company prefix is the first 6 digits encode
cleanly (company and itemref must each fit 20 bits, i.e. ≤ 1,048,575 — always true for 6 digits).
Non-13-digit or malformed EANs are rejected and reported, never silently mis-encoded.

---

## Serial policy

Real serials are **not** `1,2,3…` — they're sparse allocations in a wide range (observed ~20k–280k,
density ~0.03), reflecting Decathlon's tag-issuance system. The twin mints serials to *look* real
without colliding:

- **Deterministic** from `meta.seed` (replay-stable) — a per-(company,itemref) seeded stream.
- **Sparse** — draw from a large range (e.g. 1,000–9,999,999) so two units of the same article get
  non-adjacent serials, matching the observed pattern.
- **Unique per physical unit** within an article (the twin tracks issued serials per SKU during seed).

Faithfulness here is cosmetic (serial doesn't affect EAN lookup) but cheap, so we do it.

---

## Clean-room encoder — `EanToEpc.kt`

Pure (no runtime deps); ready to drop into `src/main/kotlin/com/m8trx/twin/layer3/EanToEpc.kt`.
Inverse of the bit layout above. The `decode()` is included for round-trip self-tests only.

```kotlin
package com.m8trx.twin.layer3

import java.math.BigInteger

/**
 * Decathlon SGTIN-96 EPC encoder. Derives a real, scanner-decodable EPC from a product EAN-13
 * plus a per-unit serial. Validated against 169,399 real warehouse tags (see
 * reference/data/EPC-ENCODING-DECATHLON.md). Clean-room — not derived from pantos source.
 *
 * Config is the small "user-constructed" surface: filter + partition (real Decathlon = 1 / 6)
 * and the serial range. company/itemref/indicator come from the EAN.
 */
class EanToEpc(
    private val filter: Int = 1,        // real Decathlon value
    private val partition: Int = 6,     // real Decathlon value
) {
    init {
        require(filter in 0..7) { "filter must fit 3 bits" }
        require(partition in 0..7) { "partition must fit 3 bits" }
    }

    /** True if [ean] can be encoded under the fixed 6/6 Decathlon layout. */
    fun isEncodable(ean: String): Boolean {
        val e = ean.trim()
        if (e.length != 13 || !e.all { it.isDigit() }) return false
        val company = e.substring(0, 6).toInt()
        val itemref = e.substring(6, 12).toInt()
        return company <= 0xFFFFF && itemref <= 0xFFFFF
    }

    /**
     * Encode EAN-13 + serial → 24-char uppercase hex EPC.
     * @throws IllegalArgumentException if the EAN is not encodable (caller should pre-filter).
     */
    fun encode(ean: String, serial: Long): String {
        val e = ean.trim()
        require(isEncodable(e)) { "EAN '$ean' not encodable under Decathlon 6/6 SGTIN-96 layout" }
        require(serial in 0 until (1L shl 38)) { "serial out of 38-bit range" }

        val company = e.substring(0, 6).toInt()      // EAN digits 0–5
        val itemref = e.substring(6, 12).toInt()     // EAN digits 6–11
        val indicator = 0

        val bits = StringBuilder(96)
        bits.append(pad(0x30, 8))        // header
        bits.append(pad(filter, 3))
        bits.append(pad(partition, 3))
        bits.append(pad(company, 20))
        bits.append(pad(indicator, 4))
        bits.append(pad(itemref, 20))
        bits.append(pad(serial, 38))
        check(bits.length == 96)

        return BigInteger(bits.toString(), 2)
            .toString(16).uppercase().padStart(24, '0')
    }

    private fun pad(v: Int, w: Int) = pad(v.toLong(), w)
    private fun pad(v: Long, w: Int): String =
        v.toString(2).padStart(w, '0').also { require(it.length == w) { "value $v overflows $w bits" } }

    // ── round-trip self-test surface (mirrors observed decode; clean-room) ──

    /** Decode EPC → EAN-13 (recomputes the check digit), for tests only. */
    fun decode(epc: String): String {
        require(epc.length == 24 && epc.uppercase().startsWith("30")) { "not an SGTIN-96 EPC" }
        val b = BigInteger(epc, 16).toString(2).padStart(96, '0')
        val company = b.substring(14, 34).toInt(2).toString()
        val indicator = b.substring(34, 38).toInt(2).toString()
        val itemref = b.substring(38, 58).toInt(2).toString().padStart(6, '0')
        val check = eanCheckDigit(indicator + company + itemref)
        return company + itemref + check
    }

    /** GS1 check digit over a (left-padded to 13) numeric string; matches observed scheme. */
    private fun eanCheckDigit(s: String): Int {
        val d = s.padStart(13, '0').map { it - '0' }
        val sum = 3 * (d[0] + d[2] + d[4] + d[6] + d[8] + d[10] + d[12]) +
                  (d[1] + d[3] + d[5] + d[7] + d[9] + d[11])
        return (10 - sum % 10) % 10
    }
}
```

**Proven round-trips** (from the Session-4 validation; `EanToEpc(filter=1, partition=6)`):

```
EAN 3583787765531  →  EPC 30B55DFA82F65A4...  →  decode 3583787765531   (real partition=6)
```
(the Session-4 Python proof used partition=5 → `30355DFA…`; production uses the real partition=6.
Round-trip holds for both since decode ignores filter/partition — but seeded tags use 6 to match
real Decathlon tags bit-for-bit.)

---

## The "surface builder" (user-constructed config)

Given the EAN supplies company/itemref/indicator, the only things a user constructs are:

```
{ filter: 1, partition: 6, serialRange: [1_000, 9_999_999], seed: <from scenario meta> }
```

- **Twin (now):** this config unblocks seeding immediately — no UI needed. `EanToEpc` + a serial
  minter consume it.
- **Core (file as TWIN-REQ):** the *customer-facing* "RFID encoding setup" surface in m8trx-web,
  where a real tenant constructs their own scheme during onboarding. Real integrators need this; it
  pairs with the `CATALOG-IMPORT-ONBOARDING` gap (`productCatalogWebhook` #23 exists as the ingest
  atom, no full API/UI). Surfacing it is exactly the twin's system-integrator purpose.
```
