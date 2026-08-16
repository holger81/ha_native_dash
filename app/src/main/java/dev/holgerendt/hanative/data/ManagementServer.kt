package dev.holgerendt.hanative.data

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URLDecoder
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

    private val failures = AtomicInteger(0)
    @Volatile private var lockedUntil = 0L

    init {
        makeSecure(sslSocketFactory, null)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifBlank { "/" }
        return when {
            session.method == Method.GET && (uri == "" || uri == "/") -> html(page(savedUrlProvider(), null, false))
            session.method == Method.GET && uri == "/screenshot" -> handleScreenshot(session)
            session.method == Method.POST && uri == "/setup" -> handleSetup(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleScreenshot(session: IHTTPSession): Response {
        pinError(pinFrom(session))?.let { message ->
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, message)
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
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val params = formParams(session, files)
        val pin = params["pin"].orEmpty().replace(" ", "")
        val url = params["url"].orEmpty()
        val token = params["token"].orEmpty()

        pinError(pin)?.let { message ->
            return html(page(url, message, false), Response.Status.FORBIDDEN)
        }
        if (url.isBlank() || token.isBlank()) {
            return html(page(url, "URL and token are required.", false), Response.Status.BAD_REQUEST)
        }
        val result = onSubmit(pin, url, token)
        return if (result.isSuccess) {
            html(page(url, "Saved. The wall panel is connecting to Home Assistant.", true))
        } else {
            html(page(url, result.exceptionOrNull()?.message ?: "Home Assistant rejected the connection.", false), Response.Status.BAD_REQUEST)
        }
    }

    private fun pinFrom(session: IHTTPSession): String {
        val header = session.headers["x-management-pin"].orEmpty()
        val query = session.parameters["pin"]?.firstOrNull().orEmpty()
        return header.ifBlank { query }.replace(" ", "")
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
                h2 { font-size: 1.05rem; font-weight: 650; margin: 18px 0 6px; }
                p { color:#cfc9c0; line-height:1.4; }
                label { display:block; margin:14px 0 6px; font-size:.9rem; }
                input, textarea { width:100%; box-sizing:border-box; border:0; border-radius:14px;
                  padding:14px; font-size:16px; background:#2c2c2c; color:#fff; }
                textarea { min-height: 120px; font-family: ui-monospace, monospace; }
                button { width:100%; margin-top:18px; border:0; border-radius:14px; padding:14px;
                  font-size:16px; font-weight:650; background:#ffc107; color:#111; }
                .ok { background:#1b5e20; color:#dcfcdc; padding:12px 14px; border-radius:12px; }
                .err { background:#5d1a1a; color:#ffd0d0; padding:12px 14px; border-radius:12px; }
                .meta { color:#cfc9c0; font-size:.85rem; margin:0 0 8px; }
                .live-wrap { margin: 16px 0 8px; }
                .live { background:#000; border-radius:14px; overflow:hidden; min-height:220px; }
                .live img { width:100%; height:auto; display:block; }
              </style>
            </head>
            <body>
              <main>
                <h1>Greatroom Wall</h1>
                <p>Enter the PIN shown on the wall panel (4–8 digits), then paste your Home Assistant URL and long-lived access token. The same PIN unlocks a live view of the current screen. This page is HTTPS with a device-generated certificate — accept the browser warning once.</p>
                $notice
                <form method="post" action="/setup" autocomplete="off">
                  <label for="pin">PIN from wall panel</label>
                  <input id="pin" name="pin" inputmode="numeric" pattern="[0-9]{4,8}" minlength="4" maxlength="8" required />
                  <section class="live-wrap">
                    <h2>Live screen</h2>
                    <p id="live-status" class="meta">Enter the PIN to view the live screen.</p>
                    <div class="live"><img id="live" alt="Wall panel screen" hidden /></div>
                  </section>
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
                  var pinInput = document.getElementById('pin');
                  var timer = 0;
                  var blobUrl = '';
                  var blockedPin = '';
                  var INTERVAL = 1500;

                  function setStatus(text, isErr) {
                    status.textContent = text;
                    status.className = isErr ? 'err' : 'meta';
                  }

                  function pinValue() {
                    return (pinInput.value || '').replace(/\s/g, '');
                  }

                  async function refresh() {
                    var pin = pinValue();
                    if (pin.length < 4 || pin.length > 8) {
                      blockedPin = '';
                      img.hidden = true;
                      if (blobUrl) { URL.revokeObjectURL(blobUrl); blobUrl = ''; }
                      setStatus('Enter the PIN to view the live screen.', false);
                      return;
                    }
                    if (pin === blockedPin) return;
                    try {
                      var res = await fetch('/screenshot', {
                        headers: { 'X-Management-Pin': pin },
                        cache: 'no-store'
                      });
                      if (res.status === 403) {
                        blockedPin = pin;
                        img.hidden = true;
                        setStatus((await res.text()) || 'PIN does not match the wall panel.', true);
                        return;
                      }
                      blockedPin = '';
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
                    }
                  }

                  function schedule() {
                    clearInterval(timer);
                    refresh();
                    timer = setInterval(refresh, INTERVAL);
                  }

                  pinInput.addEventListener('input', schedule);
                  schedule();
                })();
              </script>
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
