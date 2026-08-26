plugins {
    application
    alias(deps.plugins.kotlinJvm)
    alias(deps.plugins.ktlint)
}

application {
    mainClass.set("com.m8trx.twin.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.nats:jnats:${deps.versions.jnats.get()}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${deps.versions.jackson.get()}")
    implementation("com.fasterxml.jackson.core:jackson-databind:${deps.versions.jackson.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${deps.versions.coroutines.get()}")
    implementation("ch.qos.logback:logback-classic:${deps.versions.logback.get()}")
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.8.0")
    android.set(false)
    outputToConsole.set(true)
}

// Outbound receiver runner (Connect §9, C3) — stands up OutboundReceiver on a LAN-reachable address
// for M8TRX's OutboundWebhookDispatcher to POST a signed stocktake_result. Blocks until killed.
tasks.register<JavaExec>("connectOutboundReceiver") {
    group = "verification"
    description = "Run the §9 OutboundReceiver on a LAN address (verify sig + dedupe + 200/401/500). Blocks until killed."
    mainClass.set("com.m8trx.twin.connect.ConnectOutboundReceiverKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Offline self-tests for the M8TRX Connect simulators (CORE-REQ-003) — no core dependency.
tasks.register<JavaExec>("connectSelfTest") {
    group = "verification"
    description = "Run the M8TRX Connect simulator offline self-tests (HMAC, DTO casing, OutboundReceiver loopback)."
    mainClass.set("com.m8trx.twin.connect.ConnectHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Live smoke — fire one sale_event at the configured Connect webhook (env-driven; reads M8TRX_* vars).
tasks.register<JavaExec>("connectLiveSmoke") {
    group = "verification"
    description = "Fire one live sale_event at the configured Connect webhook through the twin's WebhookClient."
    mainClass.set("com.m8trx.twin.connect.ConnectLiveSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Multi-site behavioral smoke (BACKEND S188 hand-off) — fires TWO sale_events: a normal EPC sale
// (→ PROCESS) and an unknown external store_id (→ QUARANTINE + unmapped integration_site_xref row).
tasks.register<JavaExec>("connectMultiSiteSmoke") {
    group = "verification"
    description = "Fire the multi-site smoke pair (normal EPC → PROCESS, store_id SMOKE-9999 → QUARANTINE) at the Connect webhook."
    mainClass.set("com.m8trx.twin.connect.MultiSiteSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Live sale-stream driver (COORD S11 hand-off) — drive a realistic, paced EPC sale_event stream
// through the proven webhook plane from seeded in-stock Denver floor inventory (fills cockpit + analytics).
tasks.register<JavaExec>("connectSaleStream") {
    group = "verification"
    description = "Drive a paced live sale_event stream at the Connect webhook from seeded Denver floor EPCs (webhook-plane only)."
    mainClass.set("com.m8trx.twin.connect.SaleStreamKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Multi-site scan sweep (S11) — §6 RFID scan-presence stream via the Bearer (scan:submit).
// SAFE: dry-run by default (plans batches, sends nothing); M8TRX_SCAN_LIVE=true to actually POST /scans.
tasks.register<JavaExec>("connectScanSweep") {
    group = "verification"
    description = "Plan/drive a multi-site RFID scan-presence stream (§6 POST /scans). Dry-run unless M8TRX_SCAN_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ScanStreamKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Self-verify (Connect §6 read-side) — read back EPC state via the Bearer (inventory:read); read-only.
tasks.register<JavaExec>("connectSelfVerify") {
    group = "verification"
    description = "Read back server-side EPC state (sold/in_stock) via /api/v2/inventory/items/details — closes the loop, fires nothing."
    mainClass.set("com.m8trx.twin.connect.ConnectSelfVerifyKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Planogram-directive drive (Mode 3 — PLANOGRAM-RESOLVED-DESIGN §6) — posts each store's
// m8trx_standard planogram as a directive_kind='planogram' envelope at the inbound-directive channel.
// SAFE: dry-run by default (builds + logs the envelope, sends nothing). M8TRX_PLANOGRAM_LIVE=true to
// send — additionally gated on core shipping the channel (mig 152a / fork #11).
tasks.register<JavaExec>("connectPlanogramDrive") {
    group = "verification"
    description = "Drive store planogram directives (Mode 3, directive_kind='planogram'). Dry-run unless M8TRX_PLANOGRAM_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ConnectPlanogramDriveKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Inventory-movement drive (remediation demo, Backend's AS-PROPOSED contract services S178) — relocates
// from-location EPCs onto a compliance fixture so a drifted compliance_target can climb back to compliant.
// SAFE: dry-run by default (builds + logs the movement envelope, sends nothing). M8TRX_MOVEMENT_LIVE=true
// to send — additionally HOLD FIRE until Backend confirms the inventory_movement ingester deployed.
tasks.register<JavaExec>("connectMovementDrive") {
    group = "verification"
    description = "Drive an inventory_movement relocation (remediation demo). Dry-run unless M8TRX_MOVEMENT_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ConnectMovementDriveKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Item-receive drive (compliance-remediation demo, #7 receive→relocate) — fires the existing
// DeviceDriver.receive (§6 Bearer data-plane, POST /inventory/items/receive) to receive an EPC
// list into inventory at a target site/space.
// SAFE: dry-run by default (builds + logs the request envelope, sends nothing). M8TRX_RECEIVE_LIVE=true
// to send — HOLD until you have a real Denver space UUID + Bearer (receive CREATES inventory server-side).
tasks.register<JavaExec>("connectReceiveDrive") {
    group = "verification"
    description = "Receive items into inventory (§6 Bearer data-plane). Dry-run unless M8TRX_RECEIVE_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ConnectReceiveDriveKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// §A alarm-chain drive — twin as a THIRD-PARTY EAS GATE VENDOR pushing alarms in over Connect §8.1
// (the seventh inbound data-type). §A of the Connect definition of done is the one item where "done"
// means something TRAVERSED the path: external source → alert row → routed to an LP role → visible on
// /alerts → dispositioned. Sends A1 (published §8.1 shape) → A1 byte-identical (dedupe) → A2 (DESIGN §2
// shape, distinct dedupe_key), then re-probes every documented diagnostic and reports the step it
// stopped at. A 200 is a receipt, never evidence — ingest is @Async and can still dead-letter.
// SAFE: dry-run by default (prints the exact bytes). M8TRX_ALARM_LIVE=true to send.
tasks.register<JavaExec>("connectAlarmDrive") {
    group = "verification"
    description = "Drive the §8.1 alarm chain as an outside EAS vendor. Dry-run unless M8TRX_ALARM_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ConnectAlarmDriveKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Full-loop drive (CORE-REQ-005 part 1) — composes the built P0 drivers into ONE parameterized run of the
// path-(b) demo-nucleus loop: (opt) directive → sale-drift → items/details assert (SOLD) → movement/scan
// remediate → items/details assert (present) → per-target compliance expectation (the backend session's oracle).
// SAFE: dry-run by default (plans + logs the whole loop + expectation, sends nothing). M8TRX_LOOP_LIVE=true
// drives it (needs M8TRX_TWIN_WEBHOOK_KEY + M8TRX_TWIN_BEARER in .env). Re-runnable per surface smoke + at scale.
tasks.register<JavaExec>("connectFullLoop") {
    group = "verification"
    description = "Drive the full path-(b) loop (directive→drift→assert→remediate→assert). Dry-run unless M8TRX_LOOP_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ConnectFullLoopKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Launch-quality stress drive (CORE-REQ-005 part 2) — concurrent multi-arm sale_event storm across the retail
// stores (scale · concurrency · NoScope/store-xref/site-scoped arms · dedup+unmapped probes · breakage report).
// SAFE: dry-run by default (reports the campaign volume, sends nothing). M8TRX_STRESS_LIVE=true hammers the
// tenant — ONLY after a coordinated clear-to-hammer (needs M8TRX_TWIN_WEBHOOK_KEY + M8TRX_TWIN_BEARER in .env).
tasks.register<JavaExec>("connectStress") {
    group = "verification"
    description = "Drive the at-scale sale-load stress test (concurrent, multi-arm). Dry-run unless M8TRX_STRESS_LIVE=true."
    mainClass.set("com.m8trx.twin.connect.ConnectStressKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Multi-site chain-activity stream (COORD S11 "broaden the fill") — weighted sale/restock/pricing/
// catalog mix across all 10 stores on the webhook plane (no Bearer needed).
tasks.register<JavaExec>("connectChainActivity") {
    group = "verification"
    description = "Drive a multi-store webhook-plane activity mix (sale/restock/pricing/catalog) at the Connect webhook."
    mainClass.set("com.m8trx.twin.connect.ChainActivityStreamKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Site-scope confinement audit (Strand V / SECHARDEN) — coordinator-invocable acceptance gate. Logs in the
// labeled test cohort as real users (public login → JWT) and probes site confinement across token / store-
// picker / Hasura-read planes → RED/GREEN matrix; optional at-scale stress. Public-surface only (no psql;
// psql cohort-shaping stays coordinator/core-side per the twin HARD RULE). Confused-deputy WRITES stay GATED
// until core provisions throwaway targets. Needs M8TRX_AUDIT_PASSWORD (+ M8TRX_HASURA_URL / M8TRX_AUDIT_SCALE).
tasks.register<JavaExec>("connectSiteScopeAudit") {
    group = "verification"
    description = "Probe site-scope confinement (token/picker/reads) as cohort users → RED/GREEN matrix. Needs M8TRX_AUDIT_PASSWORD."
    mainClass.set("com.m8trx.twin.connect.ConnectSiteScopeAuditKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Layer-1 people/impression conformance harness (BRIEF-TWIN-SPINE 3.2/3.3). Offline: no network, no
// credentials, no mother UUIDs — runs against the committed layout.json. Proves an emit stream would
// actually satisfy core's fixture-impression rule BEFORE it is fired live, because the failure mode is
// silent (below ~1 Hz, both clocks reset every sample and nothing can ever fire).
tasks.register<JavaExec>("peopleSelfTest") {
    group = "verification"
    description = "Assert the people-emit stream satisfies core's impression rule (distance + dwell + view, >1Hz floor)."
    mainClass.set("com.m8trx.twin.layer1.PeopleSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Drive core's REAL fixture-impression pipeline over NATS into the twin edge (BRIEF-TWIN-SPINE 3.2).
// Prints the ImpressionOracle prediction BEFORE publishing so it cannot be retrofitted to the result.
// SAFE BY DEFAULT — dry-run; M8TRX_PEOPLE_LIVE=true fires. Two interlocks guard the production office
// edge on the same host: NATS server_name must be 'edge-twin-denver', and the office space is denylisted.
tasks.register<JavaExec>("connectPeopleDrive") {
    group = "verification"
    description = "Publish objLocation at Xovis fidelity into the twin edge; predict impressions first, then compare."
    mainClass.set("com.m8trx.twin.layer1.PeopleDriveKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Layer-3 scenario harness (BRIEF-TWIN-SPINE 3.3). Generates a whole day headlessly at rate=+inf and
// asserts the STORE-OPERATING-MODEL §1 reconciliation identity, determinism from seed, and — the part that
// matters — that the emitted dwell streams would actually fire impressions on the real pipeline. The oracle
// was validated 7/7 against the live twin edge, so its offline verdict is trustworthy. No network.
tasks.register<JavaExec>("scenarioSelfTest") {
    group = "verification"
    description = "Generate a day offline; assert §1 reconciliation, determinism, and that dwell fires impressions."
    mainClass.set("com.m8trx.twin.layer3.ScenarioSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Drive a whole generated store day into the twin edge (BRIEF-TWIN-SPINE 3.3 -> 3.2). The day is generated
// and oracle-checked OFFLINE first, so the expected impression count is known before publishing. PACING:
// episode deltas replay at REAL time and only inter-episode gaps are divided — compressing episodes would
// push dwell under the 5000ms threshold and yield zero impressions from a run that looks healthy.
// SAFE BY DEFAULT — dry-run; M8TRX_DAY_LIVE=true fires. Same two edge interlocks as connectPeopleDrive.
tasks.register<JavaExec>("connectDayDrive") {
    group = "verification"
    description = "Generate a full store day, verify offline, then replay it live with gap-only compression."
    mainClass.set("com.m8trx.twin.layer3.DayDriveKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

tasks.register<JavaExec>("lossAudit") {
    group = "verification"
    description = "Size the impression-cache working set of the S15 runs; localise the fullday-0728 persistence loss."
    mainClass.set("com.m8trx.twin.layer3.LossAuditKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

tasks.register<JavaExec>("impressionWatch") {
    group = "verification"
    description = "Twin's in-code wire counter for the people plane; dedupes by id and writes a citable CSV. Blocks until killed."
    mainClass.set("com.m8trx.twin.layer1.ImpressionWatcherKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

tasks.register<JavaExec>("oracleDump") {
    group = "verification"
    description = "Dump per-impression oracle predictions for a drive slice, for the oracle-vs-actual diff."
    mainClass.set("com.m8trx.twin.layer3.OracleDumpKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// ── Connect §6.5 READ half (live 2026-07-30, PR #210) ───────────────────────────────────────────
// Reachable != callable: @ConnectExposed opens the endpoint, but the KEY still needs the capability.
// Pre-SEC-3 keys hold no vision_ai:* and no task:read, so probe before assuming a 403 is a core bug.
tasks.register<JavaExec>("connectReadProbe") {
    group = "verification"
    description = "Which of the four §6.5 reads does THIS key hold? Names the missing capability. Read-only."
    mainClass.set("com.m8trx.twin.connect.ConnectReadProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Reads back twin's OWN persisted impressions. There is no cursor, so the window IS the cursor:
// this walks the range in slices and halves any slice the server marks truncated. Diffing against an
// oracleDump measures MODEL + TRANSPORT combined — pair with impressionWatch to attribute a gap.
tasks.register<JavaExec>("impressionVerify") {
    group = "verification"
    description = "Read back persisted impressions via POST /visionai/impressions/query; optional oracle diff. Read-only."
    mainClass.set("com.m8trx.twin.connect.ImpressionVerifyKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// ── THE CONNECT SHIP GATE (Bob's ruling, 2026-07-31) ────────────────────────────────────────────
// M8TRX's paid API surface has no automated regression coverage: every CI security suite drives a
// human JWT against frontEnd RLS or greps source. None drives a Connect key against a Connect
// endpoint. Until a CI deploy gate with live keys exists, THIS is the gate. Green before ship.
// Coverage gaps are printed alongside failures — a run that silently skips an endpoint is the
// false-green class this exists to prevent. Exit 0 = pass; 1 = fail OR indeterminate.
tasks.register<JavaExec>("connectAcceptance") {
    group = "verification"
    description = "Connect ship gate: §6.5 site-scope confinement (incl. the omitted-site rule), typed refusals, reachability. Read-only."
    mainClass.set("com.m8trx.twin.connect.ConnectAcceptanceKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}
