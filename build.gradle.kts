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
    version.set("1.5.0")
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
