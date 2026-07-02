package com.m8trx.twin.connect.sim

import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.GqlResult
import com.m8trx.twin.connect.HasuraClient
import com.m8trx.twin.connect.UserAuthClient
import com.m8trx.twin.connect.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strand V — site-scope confinement + stress harness (CORE-REQ-005 / SECHARDEN). Proves a site-scoped user
 * is confined across the enforcement planes twin can reach from the **public surface + user JWT** (no psql):
 *
 *   - **token** — does the JWT carry a site claim? (absent = un-confined at the token layer)
 *   - **store-picker** — how many sites can the user see? (a Denver site-manager should see only Denver)
 *   - **read** — cross-site Hasura reads (`space/item/zone/…` @ another site) must return `[]`
 *   - **write** — confused-deputy cross-site writes (GATED until core provisions throwaway targets)
 *   - **scale** — N concurrent scoped logins+reads → resolver/RLS latency under load
 *
 * Runs RED first (proves the leak on today's state) and turns GREEN per fix strand. Re-runnable, single
 * entrypoint — the coordinator's per-strand acceptance gate. Shape-agnostic: it probes whatever shape the
 * (coordinator-provisioned) cohort currently has and reports the matrix.
 */
class SiteScopeAuditDriver(private val config: ConnectConfig, hasuraUrl: String, private val password: String) {
    private val log = LoggerFactory.getLogger(SiteScopeAuditDriver::class.java)
    private val auth = UserAuthClient(config)
    private val hasura = HasuraClient(hasuraUrl)

    data class CohortUser(val label: String, val email: String, val homeSiteId: String?, val homeSiteName: String, val siteScoped: Boolean)

    data class SiteTarget(val name: String, val id: String)

    /** A read probe = a Hasura table + a builder of its `where` json for a given site UUID. */
    data class ReadProbe(val table: String, val where: (String) -> String)

    enum class Verdict { RED, GREEN, ERR, GATED, INFO }

    data class Row(val plane: String, val subject: String, val detail: String, val observed: String, val verdict: Verdict)

    data class Report(val rows: List<Row>) {
        val red get() = rows.count { it.verdict == Verdict.RED }
        val green get() = rows.count { it.verdict == Verdict.GREEN }
        val err get() = rows.count { it.verdict == Verdict.ERR }
        val gated get() = rows.count { it.verdict == Verdict.GATED }
    }

    fun run(
        cohort: List<CohortUser>,
        crossSites: List<SiteTarget>,
        probes: List<ReadProbe>,
        writeTargetsProvided: Boolean,
        scaleUsers: Int,
    ): Report {
        val rows = ArrayList<Row>()
        val sessions = LinkedHashMap<String, UserSession>()
        log.info("[audit] === Strand V site-scope confinement matrix (LIVE, public-surface) ===")

        // ---- token plane ------------------------------------------------------------------------
        for (u in cohort) {
            val s = auth.login(u.email, password)
            if (s == null) {
                rows += Row("token", u.label, "login", "FAILED", Verdict.ERR)
                continue
            }
            sessions[u.label] = s
            val v = when {
                !u.siteScoped -> Verdict.INFO
                s.siteClaimPresent -> Verdict.GREEN
                else -> Verdict.RED
            }
            rows += Row("token", u.label, "site_claim", if (s.siteClaimPresent) "present(${s.allowedSiteIds.size})" else "ABSENT", v)
            log.info(
                "[audit][token] {} caps={} role={} tenant={} site_claim={} → {}",
                u.label,
                s.capabilityCount,
                s.role,
                s.tenantId,
                if (s.siteClaimPresent) "present" else "ABSENT",
                v,
            )
        }

        // ---- store-picker plane -----------------------------------------------------------------
        for (u in cohort) {
            val s = sessions[u.label] ?: continue
            val (obs, v) = when (val r = hasura.countAll(s.accessToken, "site")) {
                is GqlResult.Count -> "${r.n} sites" to when {
                    !u.siteScoped -> Verdict.INFO
                    r.n > 1 -> Verdict.RED
                    else -> Verdict.GREEN
                }
                is GqlResult.Error -> r.message.take(60) to Verdict.ERR
            }
            rows += Row("picker", u.label, "sites_visible", obs, v)
            log.info("[audit][picker] {} → {} ({})", u.label, obs, v)
        }

        // ---- read plane: site-scoped users vs cross-site targets --------------------------------
        for (u in cohort.filter { it.siteScoped }) {
            val s = sessions[u.label] ?: continue
            for (site in crossSites.filter { it.id != u.homeSiteId }) {
                for (p in probes) {
                    val (obs, v) = when (val r = hasura.countWhere(s.accessToken, p.table, p.where(site.id))) {
                        is GqlResult.Count -> when {
                            r.n < 0 -> "bad-count" to Verdict.ERR
                            r.n > 0 -> "${r.n} rows" to Verdict.RED
                            else -> "0 rows" to Verdict.GREEN
                        }
                        is GqlResult.Error -> r.message.take(50) to Verdict.ERR
                    }
                    rows += Row("read", "${u.label}→${site.name}", p.table, obs, v)
                    log.info("[audit][read] {} @ {} × {} → {} ({})", u.label, site.name, p.table, obs, v)
                }
            }
            // allowed sanity — the user's HOME site must return rows (confinement must not over-deny)
            if (u.homeSiteId != null) {
                val obs = when (val r = hasura.countWhere(s.accessToken, "item", "{site_id:{_eq:\"${u.homeSiteId}\"}}")) {
                    is GqlResult.Count -> "${r.n} rows"
                    is GqlResult.Error -> r.message.take(40)
                }
                rows += Row("read", "${u.label}→${u.homeSiteName}(home)", "item(allowed)", obs, Verdict.INFO)
                log.info("[audit][read] {} @ home {} × item (allowed, expect>0) → {}", u.label, u.homeSiteName, obs)
            }
        }

        // ---- write plane (confused-deputy) — GATED until core provisions throwaway targets -------
        if (writeTargetsProvided) {
            log.warn("[audit][write] write-probe targets provided but the write suite is not yet wired — ping coord; skipping")
            rows += Row("write", "confused-deputy", "suite", "targets set, suite TBD", Verdict.GATED)
        } else {
            for (probe in listOf("POST /rules/fire", "PUT /tenant-role", "POST /permission-sets", "task-lifecycle")) {
                rows += Row("write", "confused-deputy", probe, "GATED (no throwaway targets)", Verdict.GATED)
            }
            log.info(
                "[audit][write] GATED — needs coord-provisioned throwaway victim-user + per-site test-tasks; no cross-site write fires until then",
            )
        }

        // ---- scale plane ------------------------------------------------------------------------
        if (scaleUsers > 0) {
            cohort.firstOrNull { it.siteScoped }?.let { runScale(it, crossSites, scaleUsers, rows) }
        }

        val rep = Report(rows)
        log.info("[audit] === MATRIX: {} RED · {} GREEN · {} ERR · {} GATED (of {} rows) ===", rep.red, rep.green, rep.err, rep.gated, rows.size)
        return rep
    }

    /** N concurrent (login → picker → one cross-site read) — measures resolver + site-RLS cost under load. */
    private fun runScale(u: CohortUser, crossSites: List<SiteTarget>, n: Int, rows: MutableList<Row>) {
        val target = crossSites.firstOrNull { it.id != u.homeSiteId } ?: return
        val concurrency = minOf(n, 16)
        val latencies = Collections.synchronizedList(ArrayList<Long>())
        val ok = AtomicInteger()
        val err = AtomicInteger()
        log.info("[audit][scale] driving {} concurrent(≤{}) scoped login+read as {}…", n, concurrency, u.label)
        runBlocking {
            val sem = Semaphore(concurrency)
            (1..n).map {
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val t0 = System.nanoTime()
                        val s = auth.login(u.email, password)
                        if (s == null) {
                            err.incrementAndGet()
                            return@withPermit
                        }
                        hasura.countAll(s.accessToken, "site")
                        hasura.countWhere(s.accessToken, "item", "{site_id:{_eq:\"${target.id}\"}}")
                        latencies.add((System.nanoTime() - t0) / 1_000_000)
                        ok.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        val sorted = latencies.sorted()
        val p50 = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
        val p95 = if (sorted.isEmpty()) 0 else sorted[minOf(sorted.size - 1, sorted.size * 95 / 100)]
        rows += Row("scale", "${u.label}×$n", "login+picker+cross-read", "ok=${ok.get()} err=${err.get()} p50=${p50}ms p95=${p95}ms", Verdict.INFO)
        log.info("[audit][scale] done — ok={} err={} p50={}ms p95={}ms", ok.get(), err.get(), p50, p95)
    }
}
