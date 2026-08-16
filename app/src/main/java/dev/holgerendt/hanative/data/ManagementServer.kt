package dev.holgerendt.hanative.data

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocketFactory

class ManagementServer(
    private val port: Int = PORT,
    private val pinProvider: () -> String,
    private val savedUrlProvider: () -> String,
    private val screenshotProvider: () -> ScreenCapture.Jpeg,
    private val onSubmit: (pin: String, url: String, token: String) -> Result<Unit>,
    sslSocketFactory: SSLServerSocketFactory,
) : NanoHTTPD(port) {

    private data class AdminSession(val expiresAt: Long, val pin: String)

    private val failures = AtomicInteger(0)
    @Volatile private var lockedUntil = 0L
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, AdminSession>()

    init {
        makeSecure(sslSocketFactory, null)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifBlank { "/" }
        return when {
            session.method == Method.GET && (uri == "" || uri == "/") -> handleHome(session)
            session.method == Method.POST && uri == "/login" -> handleLogin(session)
            session.method == Method.GET && uri == "/logout" -> handleLogout(session)
            session.method == Method.POST && uri == "/logout" -> handleLogout(session)
            session.method == Method.GET && uri == "/screenshot" -> handleScreenshot(session)
            session.method == Method.POST && uri == "/setup" -> handleSetup(session)
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
        val pin = formParams(session, files)["pin"].orEmpty().replace(" ", "")
        pinError(pin)?.let { message ->
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

    private fun pinError(pin: String): String? {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
            return "Too many attempts. Wait a moment and try again."
        }
        if (pin != pinProvider()) {
            val count = failures.incrementAndGet()
            if (count >= 5) {
                lockedUntil = now + 30_000
                failures.set(0)
            }
            return "PIN does not match the wall panel."
        }
        failures.set(0)
        return null
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
                  <button class="inline" type="button" id="reload">Reload</button>
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
