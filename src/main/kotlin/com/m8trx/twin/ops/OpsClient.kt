package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64

/**
 * The user-JWT plane for the store-operations steps: REST verbs on `{apiBase}` and Hasura reads on `{hasuraUrl}`, each
 * call as one named store user. Nothing here writes outside the platform's own endpoints; the Hasura use is READ-ONLY
 * (queries, never mutations — the twin posture).
 *
 * LOGINS (the platform rate-limits `POST /auth/login` to 10 a minute per client IP, and a chain-wide pass needs about 30):
 *  - a JWT is kept per login in `{stateDir}/ops/jwt/<email>.jwt` (file mode 600, directory 700) and reused until an hour
 *    before its `exp` (the platform issues 10-day tokens), so a normal pass logs nobody in;
 *  - a token the platform no longer accepts (401 — a rotated signing key, a changed password) is dropped and the call
 *    retried once on a fresh login;
 *  - logins are paced at [LoginPacer.MAX_PER_MINUTE] a minute; a `429` is never cached: wait [RATE_LIMIT_WAIT_MS] and
 *    try once more, then give up on THAT login only (null — the caller fails its store, not the pass);
 *  - any other refusal (a wrong password) is remembered for the run so it is not retried call after call.
 *
 * Every request body is a plain map with the platform's own keys (the count inbox is camelCase, the task surface is
 * snake_case, the zone update camelCase) — what goes on the wire is exactly what is written here, nulls dropped.
 */
class OpsClient(
    private val apiBase: String,
    private val hasuraUrl: String,
    private val password: () -> String,
    private val http: ConnectHttp = ConnectHttp(),
    private val stateDir: Path? = null,
    private val clock: () -> Instant = Instant::now,
    private val sleepMs: (Long) -> Unit = Thread::sleep,
    private val pacer: LoginPacer = LoginPacer(),
) {
    private val log = LoggerFactory.getLogger(OpsClient::class.java)
    private val mapper = ConnectMappers.camel
    private val tokens = HashMap<String, String>()
    private val refused = HashSet<String>()
    private val emailOf = HashMap<String, String>()
    private val fromDisk = HashSet<String>()

    /** How many real `POST /auth/login` calls this client made (the proof that reuse works). */
    var logins: Int = 0
        private set

    /** JWT for [email]: memory, then the on-disk cache while valid, then a paced login; null when the login is refused (logged). */
    fun jwt(email: String): String? {
        tokens[email]?.let { return it }
        if (email in refused) return null
        readCached(email)?.let {
            tokens[email] = it
            emailOf[it] = email
            fromDisk += it
            return it
        }
        return login(email)
    }

    private fun login(email: String): String? {
        var attempt = 0
        while (true) {
            attempt++
            sleepMs(pacer.waitMs(clock().toEpochMilli()))
            pacer.record(clock().toEpochMilli())
            logins++
            val body = mapper.writeValueAsBytes(mapOf("email" to email, "password" to password()))
            when (val r = http.send("POST", "$apiBase/auth/login", mapOf("Content-Type" to "application/json"), body)) {
                is ConnectResponse.Ok -> {
                    val token = mapper.readTree(r.rawBody).path("accessToken").asText("").ifBlank { null }
                    if (token == null) {
                        log.warn("login {} — 2xx but no accessToken", email)
                        refused += email
                        return null
                    }
                    tokens[email] = token
                    emailOf[token] = email
                    writeCached(email, token)
                    return token
                }

                is ConnectResponse.Err -> {
                    if (r.error.status == 429) {
                        if (attempt == 1) {
                            log.warn("login {} rate-limited (429) — waiting {} s and trying once more", email, RATE_LIMIT_WAIT_MS / 1000)
                            sleepMs(RATE_LIMIT_WAIT_MS)
                            continue
                        }
                        log.error("login {} rate-limited (429) twice — giving up on this login for now (not remembered)", email)
                        return null
                    }
                    log.warn("login {} FAILED — status={} code={}", email, r.error.status, r.error.code)
                    refused += email
                    return null
                }
            }
        }
    }

    private fun cacheFile(email: String): Path? =
        stateDir?.resolve("ops")?.resolve("jwt")?.resolve(email.replace(Regex("[^A-Za-z0-9._@+-]"), "_") + ".jwt")

    private fun readCached(email: String): String? {
        val f = cacheFile(email) ?: return null
        val token = runCatching { if (Files.exists(f)) Files.readString(f).trim() else null }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val exp = expiry(token) ?: return null
        return token.takeIf { exp.isAfter(clock().plusSeconds(REUSE_MARGIN_S)) }
    }

    private fun writeCached(email: String, token: String) {
        val f = cacheFile(email) ?: return
        if (expiry(token) == null) return // no exp claim: nothing to judge reuse by, so nothing is kept
        runCatching {
            val dir = f.parent
            if (!Files.exists(dir)) {
                Files.createDirectories(dir.parent)
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            }
            Files.deleteIfExists(f)
            Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(f, token)
        }.onFailure { log.warn("could not keep the login for {} ({}) — it will be re-done next pass", email, it.message) }
    }

    private fun drop(token: String) {
        val email = emailOf.remove(token) ?: return
        tokens.remove(email)
        fromDisk.remove(token)
        cacheFile(email)?.let { runCatching { Files.deleteIfExists(it) } }
    }

    /** The platform refused a token it was holding in the cache: forget it and log in afresh (null when that fails). */
    private fun refresh(token: String): String? {
        val email = emailOf[token] ?: return null
        log.warn("token for {} rejected (401) — dropped from the cache, logging in afresh", email)
        drop(token)
        return jwt(email)
    }

    fun send(jwt: String, method: String, path: String, body: Map<String, Any?>? = null): ConnectResponse {
        val bytes = body?.let { mapper.writeValueAsBytes(it.filterValues { v -> v != null }) }
        fun once(token: String): ConnectResponse {
            val headers = buildMap {
                put("Authorization", "Bearer $token")
                if (bytes != null) put("Content-Type", "application/json")
            }
            return http.send(method, apiBase + path, headers, bytes)
        }
        val r = once(jwt)
        // a cached token the platform no longer accepts: one fresh login, one retry (a token minted this run is never retried)
        if (r is ConnectResponse.Err && r.error.status == 401 && jwt in fromDisk) refresh(jwt)?.let { return once(it) }
        return r
    }

    fun json(r: ConnectResponse): JsonNode = mapper.readTree(r.rawBody.ifBlank { "{}" })

    /** A Hasura READ as [jwt]; returns `data` or throws with the first GraphQL error (a refused read is never an empty answer). */
    fun query(jwt: String, gql: String, variables: Map<String, Any?> = emptyMap()): JsonNode {
        val body = mapper.writeValueAsBytes(mapOf("query" to gql, "variables" to variables))
        fun once(token: String) = http.send("POST", hasuraUrl, mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"), body)
        var r = once(jwt)
        if (r is ConnectResponse.Err && jwt in fromDisk && (r.error.status == 401 || r.error.rawBody.contains("invalid-jwt"))) {
            refresh(jwt)?.let { r = once(it) }
        }
        return when (val res = r) {
            is ConnectResponse.Ok -> {
                val node = mapper.readTree(res.rawBody)
                val errors = node.path("errors")
                if (errors.isArray && errors.size() > 0) error("graphql: ${errors[0].path("message").asText()}")
                node.path("data")
            }

            is ConnectResponse.Err -> error("graphql HTTP ${res.error.status} ${res.error.code ?: ""}")
        }
    }

    fun describe(r: ConnectResponse): String = when (r) {
        is ConnectResponse.Ok -> "${r.status}"
        is ConnectResponse.Err -> "${r.error.status} ${r.error.code ?: ""} ${r.error.message ?: r.error.rawBody.take(160)}".trim()
    }.also { if (r is ConnectResponse.Err) log.debug("refused: {}", it) }

    companion object {
        const val RATE_LIMIT_WAIT_MS = 60_000L

        /** A cached token is reused until this long before its `exp`. */
        const val REUSE_MARGIN_S = 3600L

        /** The `exp` claim of a JWT, or null when it is not a decodable JWT or carries none. */
        fun expiry(token: String): Instant? = runCatching {
            val payload = token.split(".")[1]
            val node = ConnectMappers.camel.readTree(String(Base64.getUrlDecoder().decode(payload + "=".repeat((4 - payload.length % 4) % 4))))
            node.path("exp").takeIf { it.isNumber }?.asLong()?.let(Instant::ofEpochSecond)
        }.getOrNull()
    }
}

/**
 * Spaces real logins so no more than [MAX_PER_MINUTE] fall inside any sliding 60 seconds (the platform allows 10 a minute
 * per client IP, shared with every human login that leaves through the same address). Pure in the clock it is given.
 */
class LoginPacer(private val maxPerMinute: Int = MAX_PER_MINUTE) {
    private val stamps = ArrayDeque<Long>()

    /** Milliseconds to wait before a login at [nowMs] keeps the window within the limit (0 = go now). */
    fun waitMs(nowMs: Long): Long {
        while (stamps.isNotEmpty() && nowMs - stamps.first() >= WINDOW_MS) stamps.removeFirst()
        return if (stamps.size < maxPerMinute) 0L else stamps.first() + WINDOW_MS - nowMs
    }

    fun record(atMs: Long) {
        stamps.addLast(atMs)
    }

    companion object {
        const val MAX_PER_MINUTE = 6
        const val WINDOW_MS = 60_000L
    }
}
