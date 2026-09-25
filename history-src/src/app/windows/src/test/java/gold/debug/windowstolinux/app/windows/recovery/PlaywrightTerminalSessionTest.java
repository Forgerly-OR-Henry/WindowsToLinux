package gold.debug.windowstolinux.app.windows.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class PlaywrightTerminalSessionTest {
    @Test
    void textCanvasBindingTabChangesAndCleanupUseARealBrowser() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html><html><body>
                    <pre data-wtl-terminal tabindex="0" style="width:640px;height:180px">Ubuntu fixture $</pre>
                    <canvas width="640" height="180" tabindex="0"></canvas>
                    <script>
                    const terminal=document.querySelector('pre'); let line='';
                    document.querySelector('canvas').getContext('2d').fillText('CentOS VNC fixture $',20,40);
                    terminal.addEventListener('keydown', e => {
                      if(e.key==='Enter') { terminal.textContent='Received: '+line;
                        if(line==='newtab') window.open('about:blank','other');
                        if(line==='detach') terminal.remove(); line='';
                      } else if(e.key.length===1) line+=e.key;
                    });
                    </script></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) {
                out.write(html);
            }
        });
        server.start();
        PlaywrightTerminalSession browser = new PlaywrightTerminalSession(true,
                "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            browser.open();
            var targets = browser.targets();
            assertEquals(2, targets.size());
            long text = browser.bind(targets.getFirst().id());
            assertTrue(browser.observe(text).text().contains("Ubuntu"));
            assertEquals(0, browser.observe(text).image().length);
            browser.submit(text, "newtab");
            browser.submit(text, "still-bound");
            assertEquals("Received: still-bound", browser.observe(text).text());
            browser.invalidate();
            assertFalse(browser.valid(text));
            long stale = text;
            var staleFailure = assertThrows(BrowserRecoveryException.class, () -> browser.submit(stale, "stale"));
            assertEquals(BrowserRecoveryFailureType.OPERATION_FAILED.code(), staleFailure.failure().code());
            assertEquals("windows", staleFailure.failure().definition().domain());
            assertNotNull(staleFailure.failure().operationIdentity());
            assertFalse(staleFailure.failure().toString().contains("terminal-changed"));
            targets = browser.targets();
            long canvas = browser.bind(
                    targets.stream().filter(v -> v.description().endsWith("canvas")).findFirst().orElseThrow().id());
            assertTrue(browser.observe(canvas).text().isEmpty());
            assertTrue(browser.observe(canvas).image().length > 0);
            text = browser
                    .bind(targets.stream().filter(v -> v.description().endsWith("pre")).findFirst().orElseThrow().id());
            browser.submit(text, "detach");
            assertFalse(browser.valid(text));
        } finally {
            browser.close();
            server.stop(0);
        }
        assertFalse(browser.valid(1));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().equals("ssh-rescue-browser")));
    }
}
