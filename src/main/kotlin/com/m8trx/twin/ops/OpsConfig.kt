package com.m8trx.twin.ops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.RecentDay
import com.m8trx.twin.layer2.StoreCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * Shared configuration for the three store-operations steps of the demo sprint (2026-10-13): `stock` (cloud counts),
 * `fixmoves` (fixture moves) and `tasks` (the day's task work). All three act as the STORE'S OWN STAFF, not as an
 * integration: the platform keeps every one of these verbs off the Connect surface by design (the count inbox, the zone
 * update and the task lifecycle are user-JWT endpoints; a service key gets `403 CONNECT_NOT_EXPOSED` or `401 User context
 * required`). So each store's work is done under the login of that store's manager (users.csv), with the shared demo
 * password supplied out of band — the same posture `connectSiteScopeAudit` takes. The one API-key read is the stock step's
 * platform-truth filter (`POST /inventory/items/details`, `inventory:read`, the tenant-wide data-plane bearer).
 *
 * Env (all optional except the password on a live run):
 *  - `M8TRX_OPS_PASSWORD` (falls back to `M8TRX_AUDIT_PASSWORD`) — the demo users' shared password. Never committed.
 *  - `M8TRX_OPS_EMAIL` — one login for every store (e.g. a tenant-admin); `M8TRX_OPS_EMAIL_<SLUG>` per store
 *    (`DEC_US_DENVER`); default = the store's `store_manager` in users.csv, else its first `assistant_manager`.
 *  - `M8TRX_CONNECT_API_BASE` (https://dev.m8trx.com/server/api/v2) · `M8TRX_HASURA_URL` (https://mother.m8trx.com/v2/v1/graphql)
 *  - `M8TRX_CHAIN_DIR` (reference/data/chain) · `M8TRX_STREAM_SOLD_LOG` (.twin-state/sold-epcs.txt — the state dir is its parent)
 *  - `M8TRX_OPS_DATE` (store-local date, default today) · `M8TRX_OPS_SEED` (20261016) · `M8TRX_OPS_STORES` (csv, default all ten)
 *  - `M8TRX_OPS_ALLOW_FUTURE` (false) — run steps whose planned time is still ahead
 *  - `M8TRX_OPS_ALLOW_PAST` (false) — a LIVE run for a date before today. Off by default because these rows are stamped
 *    `now()` by the platform (stocktake_session.started_at, task.created_at, zone_history.recorded_at): a past date would
 *    land as today, all at once.
 */
data class OpsConfig(
    val apiBase: String,
    val hasuraUrl: String,
    val chainDir: Path,
    val stateDir: Path,
    val date: LocalDate,
    val seed: Long,
    val live: Boolean,
    val allowFuture: Boolean,
    val allowPast: Boolean,
    val stores: List<String>,
    val password: String?,
    val dataBearer: String?,
    val now: Instant = Instant.now(),
    private val emailOverrides: Map<String, String> = emptyMap(),
    private val globalEmail: String? = null,
) {
    val keys: ChainKeys by lazy { ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)) }
    val siteIds: Map<String, String> by lazy { DatedStream.readSiteIds(chainDir.resolve("site_ids.csv")) }
    val staff: Staff by lazy { Staff.load(chainDir.resolve("staff/users.csv")) }

    fun siteId(slug: String): String = siteIds[slug] ?: error("no site id for $slug in ${chainDir.resolve("site_ids.csv")}")

    /** The login that does [slug]'s work: an explicit override, else the store's manager from users.csv. */
    fun actorEmail(slug: String): String = emailOverrides[slug] ?: globalEmail ?: staff.manager(slug)
        ?: error("no store_manager / assistant_manager for $slug in users.csv — set M8TRX_OPS_EMAIL_${envKey(slug)}")

    fun requirePassword(): String = password
        ?: error("M8TRX_OPS_PASSWORD is not set — the demo users' shared password (out of band; never in the repo)")

    /** Store-local today, the date the platform would stamp. */
    fun localToday(slug: String): LocalDate = now.atZone(keys.store(slug).zone).toLocalDate()

    /** A live run for a date before the store's today is refused unless [allowPast] — see the class note. */
    fun pastRefusal(slug: String): String? = if (live && !allowPast && date.isBefore(localToday(slug))) {
        "date $date is before $slug's today (${localToday(
            slug,
        )}) and the platform stamps these rows now() — set M8TRX_OPS_ALLOW_PAST=true to run it anyway"
    } else {
        null
    }

    companion object {
        fun envKey(slug: String) = slug.uppercase().replace('-', '_')

        fun fromEnv(prefix: String): OpsConfig {
            fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
            fun flag(k: String) = env(k)?.toBooleanStrictOrNull() ?: false
            val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG") ?: ".twin-state/sold-epcs.txt")
            val chainDir = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
            val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
            val stores = (env("M8TRX_OPS_STORES") ?: env("${prefix}_STORES"))?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: keys.stores.map { it.slug }
            return OpsConfig(
                apiBase = env("M8TRX_CONNECT_API_BASE") ?: "https://dev.m8trx.com/server/api/v2",
                hasuraUrl = env("M8TRX_HASURA_URL") ?: "https://mother.m8trx.com/v2/v1/graphql",
                chainDir = chainDir,
                stateDir = soldLog.parent ?: Path.of(".twin-state"),
                date = (env("${prefix}_DATE") ?: env("M8TRX_OPS_DATE"))?.let(LocalDate::parse) ?: LocalDate.now(),
                seed = (env("${prefix}_SEED") ?: env("M8TRX_OPS_SEED"))?.toLongOrNull() ?: RecentDay.HISTORY_SEED,
                live = flag("${prefix}_LIVE"),
                allowFuture = flag("M8TRX_OPS_ALLOW_FUTURE") || flag("${prefix}_ALLOW_FUTURE"),
                allowPast = flag("M8TRX_OPS_ALLOW_PAST") || flag("${prefix}_ALLOW_PAST"),
                stores = stores,
                password = env("M8TRX_OPS_PASSWORD") ?: env("M8TRX_AUDIT_PASSWORD"),
                dataBearer = env("M8TRX_TWIN_SERVICE_BEARER") ?: env("M8TRX_TWIN_BEARER"),
                emailOverrides = keys.stores.mapNotNull { s -> env("M8TRX_OPS_EMAIL_${envKey(s.slug)}")?.let { s.slug to it } }.toMap(),
                globalEmail = env("M8TRX_OPS_EMAIL"),
            )
        }
    }
}

/** users.csv — the chain's people by store: the manager who logs in, and the floor staff a task can go to. */
class Staff(val rows: List<Row>) {
    data class Row(val email: String, val name: String, val role: String, val site: String, val active: Boolean)

    /** `store_manager` first, else the first `assistant_manager` (by email, for a stable choice). */
    fun manager(slug: String): String? = rows.filter { it.site == slug && it.active }.let { rs ->
        rs.firstOrNull { it.role == "store_manager" }?.email ?: rs.filter { it.role == "assistant_manager" }.minByOrNull { it.email }?.email
    }

    /** Email by display name at a store — how a task's assignee (a name from the platform) gets its own login. */
    fun emailByName(slug: String, name: String): String? =
        rows.firstOrNull { it.site == slug && it.active && it.name.equals(name, ignoreCase = true) }?.email

    companion object {
        fun load(file: Path): Staff {
            if (!Files.exists(file)) return Staff(emptyList())
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            val h = StoreCatalog.splitCsv(lines.first())
            fun i(n: String) = h.indexOf(n)
            return Staff(
                lines.drop(1).map { StoreCatalog.splitCsv(it) }.map {
                    Row(it[i("email")], it[i("display_name")], it[i("role")], it[i("site")], it[i("status")] == "active")
                },
            )
        }
    }
}
