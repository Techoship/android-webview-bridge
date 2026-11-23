package com.techoship.webviewbridge.sample

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techoship.webviewbridge.SavedDownload
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the sample page in a real WebView and checks what reaches the device: saved files and
 * their exact bytes, print routing, origin isolation and the billing round trip.
 */
@RunWith(AndroidJUnit4::class)
class BridgeTest {
    @get:Rule
    val rule = ActivityScenarioRule(MainActivity::class.java)

    private val saved = ArrayList<SavedDownload>()

    @Before
    fun waitForBridge() {
        waitFor("bridge on the page") { eval("typeof window.TechoshipBridge") == "\"object\"" }
    }

    @After
    fun deleteSavedFiles() {
        val resolver = activity().contentResolver
        saved.forEach { runCatching { resolver.delete(it.uri, null, null) } }
    }

    @Test
    fun blobRevokedRightAfterTheClickIsStillSaved() {
        eval(
            """
            (function () {
              var url = URL.createObjectURL(new Blob(['a,b\n1,2\n'], { type: 'text/csv' }));
              var a = document.createElement('a');
              a.href = url; a.download = 'revoked.csv';
              a.click();
              URL.revokeObjectURL(url);
            })()
            """,
        )
        val file = nextDownload()
        assertTrue(file.fileName, file.fileName.startsWith("revoked") && file.fileName.endsWith(".csv"))
        assertEquals("text/csv", file.mimeType)
        assertArrayEquals("a,b\n1,2\n".toByteArray(), read(file))
    }

    @Test
    fun aMultiChunkFileArrivesByteForByte() {
        val size = 3 * 1024 * 1024 + 123
        eval(
            """
            (function () {
              var bytes = new Uint8Array($size);
              for (var i = 0; i < bytes.length; i++) bytes[i] = (i * 31) % 251;
              window.TechoshipBridge.download(new Blob([bytes]), 'pattern.bin');
            })()
            """,
        )
        val file = nextDownload()
        assertEquals(size.toLong(), file.sizeBytes)
        val expected = ByteArray(size) { ((it.toLong() * 31) % 251).toByte() }
        assertArrayEquals(expected, read(file))
    }

    /** Older WebViews have no binary messages; chunks then travel as base64. Drive that path directly. */
    @Test
    fun base64ChunksAreAssembledInOrder() {
        eval(
            """
            (function () {
              var b = window.TechoshipBridge;
              b.call('download.begin', { name: 'chunks.txt', mime: 'text/plain', size: 11 }).then(function (s) {
                return b.call('download.chunk', { id: s.id, data: btoa('hello ') })
                  .then(function () { return b.call('download.chunk', { id: s.id, data: btoa('world') }); })
                  .then(function () { return b.call('download.end', { id: s.id }); });
              });
            })()
            """,
        )
        val file = nextDownload()
        assertEquals("hello world", String(read(file)))
    }

    @Test
    fun aFileLargerThanItsDeclaredSizeIsRejected() {
        eval(
            """
            window.__oversize = null;
            (function () {
              var b = window.TechoshipBridge;
              b.call('download.begin', { name: 'liar.txt', mime: 'text/plain', size: 3 }).then(function (s) {
                return b.call('download.chunk', { id: s.id, data: btoa('more than three bytes') });
              }).catch(function (e) { window.__oversize = e.code; });
            })()
            """,
        )
        waitFor("the oversize rejection") { eval("window.__oversize") != "null" }
        assertEquals("\"too_large\"", eval("window.__oversize"))
        val failure = activity().failedDownloads.poll(5, TimeUnit.SECONDS)
        assertTrue(failure.toString(), failure != null && failure.startsWith("too_large"))
    }

    @Test
    fun aDataLinkInThePageIsSavedWithItsName() {
        eval("document.getElementById('json-link').click()")
        val file = nextDownload()
        assertTrue(file.fileName, file.fileName.startsWith("settings") && file.fileName.endsWith(".json"))
        assertEquals("""{"theme":"dark","language":"en"}""", String(read(file)))
    }

    @Test
    fun windowPrintIsRoutedToTheApp() {
        activity().interceptPrint = true
        eval("window.print()")
        assertEquals("WebView Bridge demo", activity().printRequests.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun pagesFromOtherOriginsGetNoBridge() {
        onMain { it.webView.loadDataWithBaseURL("https://example.com/", "<title>other</title>", "text/html", "utf-8", null) }
        waitFor("the other page to load") { eval("location.origin + '|' + document.title") == "\"https://example.com|other\"" }
        assertEquals("\"undefined\"", eval("typeof window.TechoshipBridge"))
        assertEquals("\"undefined\"", eval("typeof window.AndroidBilling"))
        assertEquals("\"undefined\"", eval("typeof window.__techoshipBridgePort"))
    }

    @Test
    fun billingAnswersTheRoundTrip() {
        assertEquals("true", eval("window.TechoshipBridge.has('billing')"))
        // Whether Play is signed in on this device or not, the call must settle with a result
        // or a coded error, never hang.
        eval(
            """
            window.__billingResult = null;
            window.AndroidBilling.connect().then(
              function (r) { window.__billingResult = 'ok:' + r.ready; },
              function (e) { window.__billingResult = 'error:' + e.code; });
            """,
        )
        waitFor("billing.connect to settle", 30) { eval("window.__billingResult") != "null" }
        val result = eval("window.__billingResult")
        assertTrue(result, result.startsWith("\"ok:") || result.startsWith("\"error:"))
    }

    @Test
    fun anUnknownMethodIsRejectedWithACode() {
        eval("window.__unknown = null; window.TechoshipBridge.call('nope').catch(function (e) { window.__unknown = e.code; })")
        waitFor("the rejection") { eval("window.__unknown") != "null" }
        assertEquals("\"unknown_method\"", eval("window.__unknown"))
    }

    // ---- Helpers ----

    private fun activity(): MainActivity {
        var found: MainActivity? = null
        rule.scenario.onActivity { found = it }
        return checkNotNull(found)
    }

    private fun onMain(action: (MainActivity) -> Unit) = rule.scenario.onActivity(action)

    /** Runs JavaScript in the page and returns the JSON-encoded result. */
    private fun eval(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        onMain { it.webView.evaluateJavascript(script.trimIndent()) { value -> result = value ?: "null"; latch.countDown() } }
        assertTrue("JavaScript did not return", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(what: String, seconds: Long = 15, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun nextDownload(): SavedDownload {
        val activity = activity()
        val file = activity.savedDownloads.poll(30, TimeUnit.SECONDS)
        assertNotNull("No download was saved; failures: ${activity.failedDownloads}", file)
        saved += file!!
        return file
    }

    private fun read(file: SavedDownload): ByteArray =
        checkNotNull(activity().contentResolver.openInputStream(file.uri)).use { it.readBytes() }
}
