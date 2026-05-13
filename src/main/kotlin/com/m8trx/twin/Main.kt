package com.m8trx.twin

import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer0.objLocation
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.m8trx.twin.Main")

fun main() {
    val config = TwinConfig.fromEnv()
    log.info("M8TRX Twin starting — tenant={} space={}", config.tenantId, config.spaceId)

    val nats = NatsEmitter(config)

    // Smoke: publish one objLocation to verify wire-up end-to-end.
    // Open dev.m8trx.com/m8trx/visionai and you should see a pin appear.
    val body = ObjLocation(
        objectId = "twin-smoke-001",
        x = 5000.0,
        y = 8000.0,
        isMale = true,
        layoutId = config.spaceId,
    )
    nats.objLocation(body, ts = System.currentTimeMillis(), id = "twin-smoke-001-first")
    log.info("smoke objLocation published — check VisionAI canvas for pin at (5000, 8000)")

    Thread.sleep(2_000)
    nats.close()
    log.info("done")
}
