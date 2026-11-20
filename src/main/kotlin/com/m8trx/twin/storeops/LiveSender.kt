package com.m8trx.twin.storeops

import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ItemReceiveResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The ONLY class here that talks to a server. It is not built by any dry-run path: the constructor is private and [create] refuses
 * unless the live flag is true, and every build counts itself in [LiveClients.constructed] (the self-test asserts the dry runs leave
 * that at zero). UNTESTED against a server in this slice: its first real use is the reset night, with Bob.
 *
 *  - receive: `ConnectClient.receiveItems` with `M8TRX_TWIN_SERVICE_BEARER` (A4: the data-plane bearer holds `inventory:create`; the
 *    key `ConnectConfig` prefers, `M8TRX_TWIN_BEARER`, is the Denver read key and gets 403);
 *  - move / sale / planogram: `WebhookClient.push` with the data type (auth mode `M8TRX_STORE_OPS_AUTH` = `api_key` default | `hmac`);
 *  - health: a plain GET of [Health.URL] (`M8TRX_HEALTH_URL` overrides);
 *  - count steps (user-JWT store-manager calls): not wired here, so such an item is answered 501 and halts the lane, loudly.
 */
class LiveSender private constructor(
    private val config: ConnectConfig,
    private val placeholders: Placeholders,
    private val auth: WebhookClient.AuthMode,
    private val healthUrl: String,
    /** Reconcile lane: a webhook 2xx whose body reports a refused item is [Outcome.ItemErrors] (halts), not [Outcome.Accepted]. */
    private val strictWebhook: Boolean = false,
    /** Webhook sends (moves, directives) wait up to 60 s; a timeout is [Outcome.Unreachable] and backs off. */
    private val http: ConnectHttp = ConnectHttp(requestTimeout = java.time.Duration.ofSeconds(WEBHOOK_TIMEOUT_S)),
    private val receiveHttp: ConnectHttp = ConnectHttp(requestTimeout = java.time.Duration.ofSeconds(RECEIVE_TIMEOUT_S)),
    private val healthHttp: ConnectHttp = ConnectHttp(requestTimeout = java.time.Duration.ofSeconds(10)),
) : OpsSender {
    init {
        LiveClients.constructed++
    }

    /** Reconcile lane: called with every webhook 2xx body, so the runner can log the full answers. */
    @Volatile
    var onAnswer: ((SendItem, String?) -> Unit)? = null

    private val webhook by lazy { WebhookClient(config, http) }
    private val bearer by lazy {
        ConnectClient(config.copy(serviceBearer = System.getenv("M8TRX_TWIN_SERVICE_BEARER")?.takeIf { it.isNotBlank() }), receiveHttp)
    }

    override fun health(): Pair<Int, String?> = try {
        when (val r = healthHttp.send("GET", healthUrl)) {
            is ConnectResponse.Ok -> r.status to r.rawBody
            is ConnectResponse.Err -> r.status to r.rawBody
        }
    } catch (e: IOException) {
        0 to null
    }

    override fun send(item: SendItem): Outcome = try {
        if (!placeholders.resolvable(item.payload)) {
            Outcome.Http(422) // an unresolved @site / @backroom_space must never go out
        } else {
            val body = placeholders.resolve(item.payload)
            val r = when (item.route) {
                Route.BEARER_RECEIVE -> bearer.receiveItems(ConnectMappers.camel.treeToValue(body, ItemReceiveRequest::class.java))
                Route.WEBHOOK_MOVEMENT -> webhook.push(WebhookDataType.INVENTORY_MOVEMENT, body, auth)
                Route.WEBHOOK_SALE -> webhook.push(WebhookDataType.SALE_EVENT, body, auth)
                Route.WEBHOOK_PLANOGRAM -> webhook.push(WebhookDataType.PLANOGRAM_DIRECTIVE, body, auth)
                Route.USER_COUNT -> null
            }
            when (r) {
                null -> Outcome.Http(501)

                is ConnectResponse.Ok -> when {
                    item.route != Route.BEARER_RECEIVE && onAnswer != null -> {
                        onAnswer?.invoke(item, r.rawBody)
                        WebhookAnswer.refusal(r.rawBody)?.takeIf {
                            strictWebhook
                        }?.let { Outcome.ItemErrors(listOf(ItemError(null, "webhook answer: $it"))) }
                            ?: Outcome.Accepted
                    }

                    item.route == Route.BEARER_RECEIVE -> receiveOutcome(item, r.rawBody)

                    strictWebhook -> WebhookAnswer.refusal(r.rawBody)?.let { Outcome.ItemErrors(listOf(ItemError(null, "webhook answer: $it"))) }
                        ?: Outcome.Accepted

                    else -> Outcome.Accepted
                }

                is ConnectResponse.Err -> Outcome.Http(r.error.status, r.error.retryAfterSeconds)
            }
        }
    } catch (e: IOException) {
        Outcome.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        /**
         * A receive answers 200 `{"received":N,"created":M,"errors":[{epc,reason}]}`: parsed to [Outcome.ReceiveAnswer] (the runner decides per
         * error code). An answer that does not parse, or fewer `received` than EPCs sent with no `errors[]` entry, is [Outcome.ItemErrors].
         */
        fun receiveOutcome(item: SendItem, rawBody: String): Outcome {
            val res = try {
                ConnectMappers.camel.readValue(rawBody, ItemReceiveResult::class.java)
            } catch (e: IOException) {
                return Outcome.ItemErrors(listOf(ItemError(null, "unparseable: receive answer is not the expected JSON: ${rawBody.take(200)}")))
            }
            val answer = Outcome.ReceiveAnswer(res.received, res.created, res.errors.map { ItemError(it.epc, it.reason) })
            // cross-check: received = tags sent - distinct error tags, and every error tag is one of the request's
            ReceiveCheck.mismatch(item.epcs, answer)?.let { return Outcome.ItemErrors(listOf(ItemError(null, "mismatch: $it"))) }
            return answer
        }

        /** Receive requests wait up to 150 s: above the 120 s front door, so a 504 is the front door's, never our own cut. */
        const val RECEIVE_TIMEOUT_S = 150L
        const val WEBHOOK_TIMEOUT_S = 60L

        /** The reconcile sender's client: webhook routes only, no store placeholders (every body carries real ids), strict about refused items. */
        fun createForRecon(live: Boolean): LiveSender {
            require(live) { "LiveSender is built only on a live run (M8TRX_RECON_LIVE=true); a dry run never constructs it" }
            val auth = if (System.getenv("M8TRX_STORE_OPS_AUTH")?.lowercase() ==
                "hmac"
            ) {
                WebhookClient.AuthMode.HMAC
            } else {
                WebhookClient.AuthMode.API_KEY
            }
            return LiveSender(
                ConnectConfig.fromEnv(),
                Placeholders(null, null),
                auth,
                System.getenv("M8TRX_HEALTH_URL")?.takeIf { it.isNotBlank() } ?: Health.URL,
                strictWebhook = true,
            )
        }

        fun create(live: Boolean, slug: String, chainDir: Path, stateDir: Path, backroomZones: Map<String, String> = emptyMap()): LiveSender {
            require(live) { "LiveSender is built only on a live run (M8TRX_STORE_OPS_LIVE=true); a dry run never constructs it" }
            val site = DatedStream.readSiteIds(chainDir.resolve("site_ids.csv"))[slug] ?: error("no site id for $slug")
            val space = stateDir.resolve("receive/stockroom-spaces.csv").takeIf { Files.exists(it) }?.let { f ->
                Files.readAllLines(f).drop(1).map { it.split(",") }.firstOrNull { it[0] == slug }?.getOrNull(1)
            } ?: error("no stockroom space id for $slug in ${stateDir.resolve("receive/stockroom-spaces.csv")}")
            val auth = if (System.getenv("M8TRX_STORE_OPS_AUTH")?.lowercase() ==
                "hmac"
            ) {
                WebhookClient.AuthMode.HMAC
            } else {
                WebhookClient.AuthMode.API_KEY
            }
            return LiveSender(
                ConnectConfig.fromEnv(),
                Placeholders(site, space, backroomZones),
                auth,
                System.getenv("M8TRX_HEALTH_URL")?.takeIf { it.isNotBlank() } ?: Health.URL,
            )
        }
    }
}
