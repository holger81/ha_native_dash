package dev.holgerendt.hanative.data

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocketFactory

class ManagementServer(
    private val port: Int = PORT,
    private val pinProvider: () -> String,
    private val savedUrlProvider: () -> String,
    private val screenshotProvider: () -> ScreenCapture.Jpeg,
    private val onSubmit: (pin: String, url: String, token: String) -> Result<Unit>,
    private val onCommand: (KioskCommand) -> Unit,
    private val kioskStateProvider: () -> KioskSnapshot,
    sslSocketFactory: SSLServerSocketFactory,
) : NanoHTTPD(port) {

    private data class AdminSession(val expiresAt: Long, val pin: String)

    private class FailureState {
        var count = 0
        var lockedUntil = 0L
        var lastAttempt = 0L
    }

    private val failures = ConcurrentHashMap<String, FailureState>()
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, AdminSession>()

    init {
        makeSecure(sslSocketFactory, null)
    }

    fun onPinChanged() {
        failures.clear()
        sessions.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifBlank { "/" }
        return when {
            session.method == Method.GET && (uri == "" || uri == "/") -> handleHome(session)
            session.method == Method.POST && uri == "/login" -> handleLogin(session)
            session.method == Method.GET && uri == "/logout" -> handleLogout(session)
            session.method == Method.POST && uri == "/logout" -> handleLogout(session)
            session.method == Method.GET && uri == "/screenshot" -> handleScreenshot(session)
            session.method == Method.OPTIONS && (uri == "/api/command" || uri == "/api/state") ->
                corsPreflight(session)
            session.method == Method.GET && uri == "/api/state" -> handleKioskState(session)
            (session.method == Method.GET || session.method == Method.POST) && uri == "/api/command" ->
                handleKioskCommand(session)
            session.method == Method.POST && uri == "/setup" -> handleSetup(session)
            session.method == Method.POST && uri == "/wake" -> handleWake(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleHome(session: IHTTPSession): Response {
        return if (authenticated(session)) {
            html(adminPage(savedUrlProvider(), null, false))
        } else {
            html(loginPage(null))
        }
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val params = formParams(session, files)
        val pin = (params["pin"] ?: pinFromBody(files["postData"].orEmpty())).orEmpty().replace(" ", "")
        if (pin.isBlank()) {
            return html(loginPage("Enter the PIN shown on the wall panel."), Response.Status.BAD_REQUEST)
        }
        pinError(pin, clientKey(session))?.let { message ->
            return html(loginPage(message), Response.Status.FORBIDDEN)
        }
        val token = newSessionToken()
        val now = System.currentTimeMillis()
        pruneSessions(now)
        sessions[token] = AdminSession(now + SESSION_TTL_MS, pinProvider())
        return redirectHome(sessionCookieHeader(token, SESSION_TTL_MS / 1000))
    }

    private fun handleLogout(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            runCatching { session.parseBody(HashMap()) }
        }
        sessionToken(session)?.let { sessions.remove(it) }
        return redirectHome(clearSessionCookieHeader())
    }

    private fun handleScreenshot(session: IHTTPSession): Response {
        if (!authenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Login required")
        }
        val shot = runCatching { screenshotProvider() }.getOrElse {
            ScreenCapture.errorJpeg(it.message ?: "Screenshot failed")
        }
        return jpegResponse(
            if (shot.bytes.isNotEmpty()) shot
            else ScreenCapture.errorJpeg(shot.error ?: "Screenshot failed"),
        )
    }

    private fun handleKioskState(session: IHTTPSession): Response {
        authorizeApi(session)?.let { return json(session, Response.Status.UNAUTHORIZED, """{"ok":false,"error":"${escape(it)}"}""") }
        return json(session, Response.Status.OK, snapshotJson())
    }

    private fun handleKioskCommand(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        if (session.method == Method.POST) {
            runCatching { session.parseBody(files) }
        }
        val params = mutableMapOf<String, String>()
        session.parameters.forEach { (key, values) ->
            values.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { params[key] = it }
        }
        formParams(session, files).forEach { (key, value) ->
            if (value.isNotBlank()) params[key] = value
        }
        val body = files["postData"].orEmpty()
        if (body.trimStart().startsWith("{")) {
            params.putAll(flattenBody(body))
        }
        authorizeApi(session, pinFromBody(body))?.let {
            return json(session, Response.Status.UNAUTHORIZED, """{"ok":false,"error":"${escape(it)}"}""")
        }
        if (!KioskCommands.panelAllowed(params)) {
            return json(session, Response.Status.OK, """{"ok":true,"ignored":true,"reason":"panel mismatch"}""")
        }
        val command = if (body.trimStart().startsWith("{")) {
            KioskCommands.fromJson(body) ?: KioskCommands.fromParams(params)
        } else {
            KioskCommands.fromParams(params)
        }
        if (command == null) {
            return json(
                session,
                Response.Status.BAD_REQUEST,
                """{"ok":false,"error":"Unknown command. Use cmd=wake, cmd=sleep, cmd=camera, cmd=navigate&path=#camerafront_view, or cmd=home."}""",
            )
        }
        onCommand(command)
        return json(session, Response.Status.OK, snapshotJson())
    }

    private fun handleWake(session: IHTTPSession): Response {
        if (!authenticated(session)) {
            return html(loginPage("Log in with the PIN first."), Response.Status.FORBIDDEN)
        }
        runCatching { session.parseBody(HashMap()) }
        onCommand(KioskCommand.Wake)
        return html(adminPage(savedUrlProvider(), "Waking the wall display.", true))
    }

    private fun snapshotJson(): String {
        val snap = kioskStateProvider()
        val popupJson = snap.popup?.let { "\"${escape(it)}\"" } ?: "null"
        return """{"ok":true,"popup":$popupJson,"connected":${snap.connected},"asleep":${snap.screenAsleep},"panel":"${KioskCommands.PANEL_ID}"}"""
    }

    private fun authorizeApi(session: IHTTPSession, bodyPin: String? = null): String? {
        if (authenticated(session)) return null
        return apiPinError(session, bodyPin)
    }

    private fun flattenBody(body: String): Map<String, String> {
        val obj = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        fun take(source: org.json.JSONObject) {
            source.keys().forEach { key ->
                val value = source.opt(key) ?: return@forEach
                if (value !is org.json.JSONObject && value !== org.json.JSONObject.NULL) {
                    out[key] = value.toString()
                }
            }
        }
        take(obj)
        obj.optJSONObject("data")?.let(::take)
        return out
    }

    private fun apiPinError(session: IHTTPSession, bodyPin: String? = null): String? {
        val headerPin = session.headers["x-ha-pin"]
            ?: session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val pin = (headerPin ?: bodyPin).orEmpty().replace(" ", "")
        return pinError(pin, clientKey(session))
    }

    private fun pinFromBody(body: String): String? {
        if (body.isBlank()) return null
        return if (body.trimStart().startsWith("{")) {
            flattenBody(body)["pin"]
        } else {
            body.split("&").firstNotNullOfOrNull { part ->
                val idx = part.indexOf('=')
                if (idx < 0) return@firstNotNullOfOrNull null
                val key = URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8.name())
                if (key != "pin") return@firstNotNullOfOrNull null
                URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8.name())
            }
        }
    }

    private fun clientKey(session: IHTTPSession): String = session.remoteIpAddress ?: "unknown"

    private fun corsPreflight(session: IHTTPSession): Response {
        val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        addCors(response, session)
        if (corsAllowed(session)) {
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-HA-PIN")
        }
        return response
    }

    private fun json(session: IHTTPSession, status: Response.Status, body: String): Response {
        val response = newFixedLengthResponse(status, "application/json", body)
        response.addHeader("Cache-Control", "no-store")
        addCors(response, session)
        return response
    }

    private fun addCors(response: Response, session: IHTTPSession) {
        if (!corsAllowed(session)) return
        response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
        response.addHeader("Vary", "Origin")
    }

    private fun corsAllowed(session: IHTTPSession): Boolean {
        val origin = session.headers["origin"] ?: return false
        val host = session.headers["host"] ?: return false
        val sep = origin.indexOf("://")
        return sep > 0 && origin.substring(sep + 3).trimEnd('/') == host
    }

    private fun handleSetup(session: IHTTPSession): Response {
        if (!authenticated(session)) {
            return html(loginPage("Log in with the PIN first."), Response.Status.FORBIDDEN)
        }
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val params = formParams(session, files)
        val url = params["url"].orEmpty()
        val token = params["token"].orEmpty()

        if (url.isBlank() || token.isBlank()) {
            return html(adminPage(url, "URL and token are required.", false), Response.Status.BAD_REQUEST)
        }
        val result = onSubmit(pinProvider(), url, token)
        return if (result.isSuccess) {
            html(adminPage(url, "Saved. The wall panel is connecting to Home Assistant.", true))
        } else {
            html(
                adminPage(url, result.exceptionOrNull()?.message ?: "Home Assistant rejected the connection.", false),
                Response.Status.BAD_REQUEST,
            )
        }
    }

    private fun authenticated(session: IHTTPSession): Boolean {
        val token = sessionToken(session) ?: return false
        val record = sessions[token] ?: return false
        val now = System.currentTimeMillis()
        if (now >= record.expiresAt || record.pin != pinProvider()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun sessionToken(session: IHTTPSession): String? {
        val header = session.headers["cookie"].orEmpty()
        return header.split(';').asSequence().map { it.trim() }.firstNotNullOfOrNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) return@firstNotNullOfOrNull null
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name == COOKIE_NAME && value.isNotBlank()) value else null
        }
    }

    private fun newSessionToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pruneSessions(now: Long) {
        val currentPin = pinProvider()
        sessions.entries.removeIf { now >= it.value.expiresAt || it.value.pin != currentPin }
    }

    private fun pinError(pin: String, client: String): String? {
        val now = System.currentTimeMillis()
        if (pin == pinProvider()) {
            failures.remove(client)
            return null
        }
        if (failures.size > MAX_TRACKED_CLIENTS) {
            failures.entries.removeIf {
                it.value.lockedUntil < now && now - it.value.lastAttempt > FAILURE_EXPIRY_MS
            }
        }
        val state = failures.computeIfAbsent(client) { FailureState() }
        synchronized(state) {
            if (now < state.lockedUntil) {
                return LOCKOUT_MESSAGE
            }
            state.lastAttempt = now
            state.count += 1
            return if (state.count >= MAX_FAILURES) {
                state.count = 0
                state.lockedUntil = now + LOCKOUT_MS
                LOCKOUT_MESSAGE
            } else {
                "PIN does not match the wall panel."
            }
        }
    }

    private fun formParams(session: IHTTPSession, files: Map<String, String>): Map<String, String> {
        val fromSession = session.parameters.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        if (fromSession.values.any { it.isNotBlank() }) return fromSession
        val body = files["postData"].orEmpty()
        if (body.isBlank()) return emptyMap()
        return body.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8.name())
            val value = URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8.name())
            key to value
        }.toMap()
    }

    private fun html(body: String, status: Response.Status = Response.Status.OK): Response {
        val response = newFixedLengthResponse(status, "text/html; charset=utf-8", body)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun redirectHome(setCookie: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
        response.addHeader("Location", "/")
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Set-Cookie", setCookie)
        return response
    }

    private fun jpegResponse(shot: ScreenCapture.Jpeg): Response {
        val bytes = shot.bytes
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        shot.error?.let { message ->
            response.addHeader("X-Screenshot-Error", message.replace(Regex("[\\r\\n]+"), " ").take(180))
        }
        return response
    }

    private fun loginPage(message: String?): String {
        val notice = errorNotice(message)
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>Greatroom Wall login</title>
              <style>$CSS</style>
            </head>
            <body>
              <main>
                <h1>Greatroom Wall</h1>
                <p>Log in with the PIN shown on the wall panel (4–8 digits). After login, a live screen view and the token form stay available without re-entering the PIN. This page is HTTPS with a device-generated certificate — accept the browser warning once.</p>
                $notice
                <form method="post" action="/login" autocomplete="on">
                  <label for="pin">PIN</label>
                  <input id="pin" name="pin" type="password" inputmode="numeric" pattern="[0-9]{4,8}" minlength="4" maxlength="8" autocomplete="current-password" required autofocus />
                  <button type="submit">Log in</button>
                </form>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun adminPage(url: String, message: String?, success: Boolean): String {
        val notice = when {
            message == null -> ""
            success -> """<p class="ok">${escape(message)}</p>"""
            else -> """<p class="err">${escape(message)}</p>"""
        }
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>Greatroom Wall setup</title>
              <style>$CSS</style>
            </head>
            <body>
              <main>
                <div class="top">
                  <h1>Greatroom Wall</h1>
                  <form method="post" action="/logout">
                    <button class="ghost" type="submit">Log out</button>
                  </form>
                </div>
                <p>Paste your Home Assistant URL and long-lived access token. The live view uses your login session — the PIN is not sent on screenshot refreshes.</p>
                $notice
                <section class="live-wrap">
                  <h2>Live screen</h2>
                  <p id="live-status" class="meta">Loading live screen…</p>
                  <div class="live"><img id="live" alt="Wall panel screen" hidden /></div>
                  <div class="actions">
                    <button class="inline" type="button" id="reload">Reload</button>
                    <button class="inline" type="button" id="wake">Wake display</button>
                  </div>
                </section>
                <form method="post" action="/setup" autocomplete="off">
                  <label for="url">Home Assistant URL</label>
                  <input id="url" name="url" value="${escape(url.ifBlank { "http://homeassistant.local:8123" })}" autocapitalize="off" required />
                  <label for="token">Long-lived access token</label>
                  <textarea id="token" name="token" required placeholder="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…"></textarea>
                  <button type="submit">Save on wall panel</button>
                </form>
              </main>
              <script>
                (function () {
                  var img = document.getElementById('live');
                  var status = document.getElementById('live-status');
                  var reload = document.getElementById('reload');
                  var wake = document.getElementById('wake');
                  var timer = 0;
                  var blobUrl = '';
                  var INTERVAL = 1500;
                  var inFlight = false;

                  function setStatus(text, isErr) {
                    status.textContent = text;
                    status.className = isErr ? 'err' : 'meta';
                  }

                  async function refresh(manual) {
                    if (inFlight && !manual) return;
                    inFlight = true;
                    if (manual) setStatus('Reloading…', false);
                    try {
                      var res = await fetch('/screenshot', { credentials: 'same-origin', cache: 'no-store' });
                      if (res.status === 401 || res.status === 403) {
                        img.hidden = true;
                        setStatus((await res.text()) || 'Session expired. Log in again.', true);
                        window.location.href = '/logout';
                        return;
                      }
                      var err = res.headers.get('X-Screenshot-Error');
                      var blob = await res.blob();
                      if (blobUrl) URL.revokeObjectURL(blobUrl);
                      blobUrl = URL.createObjectURL(blob);
                      img.src = blobUrl;
                      img.hidden = false;
                      if (err) setStatus(err, true);
                      else setStatus('Live · updates every 1.5s', false);
                    } catch (e) {
                      setStatus('Could not reach the wall panel.', true);
                    } finally {
                      inFlight = false;
                    }
                  }

                  reload.addEventListener('click', function () { refresh(true); });
                  wake.addEventListener('click', async function () {
                    wake.disabled = true;
                    setStatus('Waking display…', false);
                    try {
                      var res = await fetch('/api/command', {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        body: 'cmd=wake'
                      });
                      if (res.status === 401 || res.status === 403) {
                        window.location.href = '/logout';
                        return;
                      }
                      var data = await res.json();
                      if (!data.ok) {
                        setStatus(data.error || 'Wake failed', true);
                        return;
                      }
                      setTimeout(function () { refresh(true); }, 700);
                    } catch (e) {
                      setStatus('Could not reach the wall panel.', true);
                    } finally {
                      wake.disabled = false;
                    }
                  });
                  refresh(false);
                  timer = setInterval(function () { refresh(false); }, INTERVAL);
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun errorNotice(message: String?): String =
        if (message == null) "" else """<p class="err">${escape(message)}</p>"""

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        const val PORT = 8765
        private const val COOKIE_NAME = "mgmt_session"
        private const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 30_000L
        private const val FAILURE_EXPIRY_MS = 10 * 60_000L
        private const val MAX_TRACKED_CLIENTS = 256
        private const val LOCKOUT_MESSAGE = "Too many attempts. Wait a moment and try again."
        private const val CSS = """
                :root { color-scheme: dark; }
                body { font-family: -apple-system, system-ui, sans-serif; background:#111; color:#f3f1ec;
                       margin:0; padding:24px; }
                main { max-width: 480px; margin: 0 auto; }
                h1 { font-size: 1.4rem; font-weight: 650; }
                h2 { font-size: 1.05rem; font-weight: 650; margin: 18px 0 6px; }
                p { color:#cfc9c0; line-height:1.4; }
                label { display:block; margin:14px 0 6px; font-size:.9rem; }
                input, textarea { width:100%; box-sizing:border-box; border:0; border-radius:14px;
                  padding:14px; font-size:16px; background:#2c2c2c; color:#fff; }
                textarea { min-height: 120px; font-family: ui-monospace, monospace; }
                button { width:100%; margin-top:18px; border:0; border-radius:14px; padding:14px;
                  font-size:16px; font-weight:650; background:#ffc107; color:#111; }
                button.ghost { width:auto; margin:0; padding:10px 14px; font-size:14px;
                  background:#3a3a3a; color:#f3f1ec; }
                button.inline { width:auto; margin-top:10px; padding:10px 16px; font-size:14px; }
                .actions { display:flex; gap:10px; flex-wrap:wrap; }
                .top { display:flex; align-items:center; justify-content:space-between; gap:12px; }
                .top h1 { margin:0; }
                .ok { background:#1b5e20; color:#dcfcdc; padding:12px 14px; border-radius:12px; }
                .err { background:#5d1a1a; color:#ffd0d0; padding:12px 14px; border-radius:12px; }
                .meta { color:#cfc9c0; font-size:.85rem; margin:0 0 8px; }
                .live-wrap { margin: 16px 0 8px; }
                .live { background:#000; border-radius:14px; overflow:hidden; min-height:220px; }
                .live img { width:100%; height:auto; display:block; }
        """

        private fun sessionCookieHeader(token: String, maxAgeSec: Long): String =
            "$COOKIE_NAME=$token; Path=/; Max-Age=$maxAgeSec; HttpOnly; Secure; SameSite=Strict"

        private fun clearSessionCookieHeader(): String =
            "$COOKIE_NAME=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict"
    }
}

data class KioskSnapshot(
    val popup: String?,
    val connected: Boolean,
    val screenAsleep: Boolean,
)
