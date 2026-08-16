package dev.holgerendt.hanative.data

import fi.iki.elonen.NanoHTTPD
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

class ManagementServer(
    private val port: Int = PORT,
    private val pinProvider: () -> String,
    private val savedUrlProvider: () -> String,
    private val onSubmit: (pin: String, url: String, token: String) -> Result<Unit>,
) : NanoHTTPD(port) {

    private val failures = AtomicInteger(0)
    @Volatile private var lockedUntil = 0L

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifBlank { "/" }
        return when {
            session.method == Method.GET && (uri == "" || uri == "/") -> html(page(savedUrlProvider(), null, false))
            session.method == Method.POST && uri == "/setup" -> handleSetup(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleSetup(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val params = formParams(session, files)
        val pin = params["pin"].orEmpty().replace(" ", "")
        val url = params["url"].orEmpty()
        val token = params["token"].orEmpty()

        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
        return html(page(url, "Too many attempts. Wait a moment and try again.", false), Response.Status.FORBIDDEN)
        }
        if (pin != pinProvider()) {
            val count = failures.incrementAndGet()
            if (count >= 5) {
                lockedUntil = now + 30_000
                failures.set(0)
            }
            return html(page(url, "PIN does not match the wall panel.", false), Response.Status.FORBIDDEN)
        }
        if (url.isBlank() || token.isBlank()) {
            return html(page(url, "URL and token are required.", false), Response.Status.BAD_REQUEST)
        }
        val result = onSubmit(pin, url, token)
        return if (result.isSuccess) {
            failures.set(0)
            html(page(url, "Saved. The wall panel is connecting to Home Assistant.", true))
        } else {
            html(page(url, result.exceptionOrNull()?.message ?: "Home Assistant rejected the connection.", false), Response.Status.BAD_REQUEST)
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

    private fun page(url: String, message: String?, success: Boolean): String {
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
              <style>
                :root { color-scheme: dark; }
                body { font-family: -apple-system, system-ui, sans-serif; background:#111; color:#f3f1ec;
                       margin:0; padding:24px; }
                main { max-width: 480px; margin: 0 auto; }
                h1 { font-size: 1.4rem; font-weight: 650; }
                p { color:#cfc9c0; line-height:1.4; }
                label { display:block; margin:14px 0 6px; font-size:.9rem; }
                input, textarea { width:100%; box-sizing:border-box; border:0; border-radius:14px;
                  padding:14px; font-size:16px; background:#2c2c2c; color:#fff; }
                textarea { min-height: 120px; font-family: ui-monospace, monospace; }
                button { width:100%; margin-top:18px; border:0; border-radius:14px; padding:14px;
                  font-size:16px; font-weight:650; background:#ffc107; color:#111; }
                .ok { background:#1b5e20; color:#dcfcdc; padding:12px 14px; border-radius:12px; }
                .err { background:#5d1a1a; color:#ffd0d0; padding:12px 14px; border-radius:12px; }
              </style>
            </head>
            <body>
              <main>
                <h1>Greatroom Wall</h1>
                <p>Enter the PIN shown on the wall panel, then paste your Home Assistant URL and long-lived access token.</p>
                $notice
                <form method="post" action="/setup" autocomplete="off">
                  <label for="pin">PIN from wall panel</label>
                  <input id="pin" name="pin" inputmode="numeric" pattern="[0-9]*" maxlength="6" required />
                  <label for="url">Home Assistant URL</label>
                  <input id="url" name="url" value="${escape(url.ifBlank { "http://homeassistant.local:8123" })}" autocapitalize="off" required />
                  <label for="token">Long-lived access token</label>
                  <textarea id="token" name="token" required placeholder="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…"></textarea>
                  <button type="submit">Save on wall panel</button>
                </form>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        const val PORT = 8765
    }
}
