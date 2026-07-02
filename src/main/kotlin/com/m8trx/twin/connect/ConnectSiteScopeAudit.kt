package com.m8trx.twin.connect

import com.m8trx.twin.connect.sim.SiteScopeAuditDriver
import com.m8trx.twin.connect.sim.SiteScopeAuditDriver.CohortUser
import com.m8trx.twin.connect.sim.SiteScopeAuditDriver.ReadProbe
import com.m8trx.twin.connect.sim.SiteScopeAuditDriver.SiteTarget
import org.slf4j.LoggerFactory

/**
 * Site-scope confinement audit (Strand V / SECHARDEN) — the coordinator-invocable acceptance gate.
 * Logs in the (coordinator-provisioned) test cohort as real users and probes site confinement across the
 * token / store-picker / Hasura-read planes, emits a RED/GREEN matrix, and optionally stress-runs at scale.
 * Public-surface + user-JWT only — the psql cohort-shaping stays coordinator/core-side (twin HARD RULE).
 *
 * Runs RED on today's state (the baseline) and turns GREEN per fix strand:
 * picker→Strand 1, reads→Strand 3, writes→Strand 2. A plane still RED after its strand = a real miss.
 *
 * Env: M8TRX_AUDIT_PASSWORD (**required** — shared cohort pw, out-of-band, never in repo),
 * M8TRX_HASURA_URL (default `mother.m8trx.com/v2/v1/graphql`), M8TRX_AUDIT_SCALE (N concurrent scoped
 * users, default 0 = off), M8TRX_AUDIT_WRITE_TARGETS (set once core provisions throwaway victim/test-task
 * targets — else the confused-deputy writes stay GATED). Run: `./gradlew connectSiteScopeAudit`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectSiteScopeAudit")

fun main() {
    val config = ConnectConfig.fromEnv()
    val password = System.getenv("M8TRX_AUDIT_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_AUDIT_PASSWORD is not set — the shared demo cohort password (supply out-of-band, never in repo)")
    val hasuraUrl = env("M8TRX_HASURA_URL", "https://mother.m8trx.com/v2/v1/graphql")
    val scaleUsers = envInt("M8TRX_AUDIT_SCALE", 0)
    val writeTargets = System.getenv("M8TRX_AUDIT_WRITE_TARGETS")?.isNotBlank() ?: false

    // Cohort + cross-site targets = the labeled M8trxDemo test fixtures (coordinator-provisioned).
    val cohort = listOf(
        CohortUser("andrew(Denver-mgr)", "andrew.wilson@decathlon-demo.com", "84f2a1c1-fb0a-41b2-9e0d-c9102a22ca7e", "Denver", siteScoped = true),
        CohortUser("anais(Bordeaux)", "anais.faure@decathlon-demo.com", "02e75ed0-39e2-4329-9961-d0aa49609618", "Bordeaux", siteScoped = true),
        CohortUser("alice(HQ)", "alice.roux@decathlon-demo.com", null, "HQ", siteScoped = false),
    )
    val crossSites = listOf(
        SiteTarget("Seoul", "7d03e0ce-7b4e-4659-9d49-4bdd2daf21c2"),
        SiteTarget("NewYork", "62f95632-2937-42ef-b8a5-8308658c5f61"),
        SiteTarget("Bordeaux", "02e75ed0-39e2-4329-9961-d0aa49609618"),
    )
    // Cross-site READ probes. site_id-direct where confirmed (space/item/stocktake/reader); nested for the
    // rest (zone/fixture/scan_event) — the harness reports ERR for any filter that doesn't resolve (tunable).
    val probes = listOf(
        ReadProbe("space") { "{site_id:{_eq:\"$it\"}}" },
        ReadProbe("item") { "{site_id:{_eq:\"$it\"}}" },
        ReadProbe("zone") { "{space:{site_id:{_eq:\"$it\"}}}" },
        ReadProbe("fixture") { "{zone:{space:{site_id:{_eq:\"$it\"}}}}" },
        ReadProbe("scan_event") { "{zone:{space:{site_id:{_eq:\"$it\"}}}}" },
        ReadProbe("stocktake_session") { "{site_id:{_eq:\"$it\"}}" },
        ReadProbe("reader") { "{site_id:{_eq:\"$it\"}}" },
    )

    log.info(
        "Site-scope audit → cohort={} crossSites={} scale={} writes={}",
        cohort.map { it.label },
        crossSites.map { it.name },
        scaleUsers,
        if (writeTargets) "targets-set" else "GATED",
    )
    val report = SiteScopeAuditDriver(config, hasuraUrl, password).run(cohort, crossSites, probes, writeTargets, scaleUsers)
    printMatrix(report)
    log.info("Audit done — {} RED / {} GREEN / {} ERR / {} GATED", report.red, report.green, report.err, report.gated)
}

/** Emit the matrix as a markdown table (copy into the results doc the coordinator absorbs). */
private fun printMatrix(report: SiteScopeAuditDriver.Report) {
    val sb = StringBuilder("\n| plane | subject | detail | observed | verdict |\n|---|---|---|---|---|\n")
    report.rows.forEach { sb.append("| ${it.plane} | ${it.subject} | ${it.detail} | ${it.observed} | ${it.verdict} |\n") }
    println(sb)
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull() ?: default
