package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Reopens actual unknown tool preparation without replaying the original candidate or consenting again. / 重开真实未知工具准备，不重放原候选或重新征求同意。 */
class DeploymentAssistedEnvironmentRecoveryRestartTest extends DeploymentRuntimeFixture {
    enum BoundaryType {
        PREPARE, BIND
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BoundaryType.class)
    void originalEnvironmentUnknownRetainsItsFormalIntentAndResourcesAcrossBoot(BoundaryType boundary)
        throws Exception {
        var connections = new AtomicInteger();
        var commands = new AtomicInteger();
        var requests = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/chat", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"unexpected recovery model call\"},\"finish_reason\":\"stop\"}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        });
        http.start();
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.addSessionListener(new SessionListener() {
                @Override
                public void sessionCreated(Session session) {
                    connections.incrementAndGet();
                }
            });
            server.setPasswordAuthenticator((user, password, session) -> false);
            server.setCommandFactory((channel, command) -> {
                commands.incrementAndGet();
                throw new AssertionError("Unknown environment recovery cannot send SSH: " + command);
            });
        })) {
            runWorker("produce", boundary, ssh.server().getPort(), http.getAddress().getPort(), "LEGACY");
            assertEquals(0, connections.get(), "The producer uses only its exact controlled receipt peer");
            runWorker("recover", boundary, ssh.server().getPort(), http.getAddress().getPort(), "LEGACY");
            assertEquals(0, connections.get(), "The real registered cross-boot recovery must reject before connecting");
            assertEquals(0, commands.get());
            assertEquals(0, requests.get(),
                "The reopened real AI capability has a usable route but must send zero HTTP");
            assertTrue(Files.isRegularFile(root.resolve("environment-" + boundary + "-before.json")));
            assertTrue(Files.isRegularFile(root.resolve("environment-" + boundary + "-after.json")));
        } finally {
            http.stop(0);
        }
    }

    @ParameterizedTest(name = "tracked original {0}")
    @EnumSource(BoundaryType.class)
    void trackedOriginalEffectsAreObservedAndFailedBusinessIsSettledAcrossBoot(BoundaryType boundary) throws Exception {
        tracked(boundary, "TRACKED");
    }

    @ParameterizedTest(name = "changed wire {0}")
    @EnumSource(BoundaryType.class)
    void trackedDifferentAuthenticatedHostSendsNoCommandsOrModelCalls(BoundaryType boundary) throws Exception {
        tracked(boundary, "HOST_CONFLICT");
    }

    @org.junit.jupiter.api.Test
    void unacknowledgedOriginalCleanupIsOnlyObservedAfterAnotherBoot() throws Exception {
        tracked(BoundaryType.PREPARE, "CLEANUP_LOST");
    }

    @org.junit.jupiter.api.Test
    void activeOriginalProcessGroupRemainsUnknownWithoutCleanup() throws Exception {
        tracked(BoundaryType.PREPARE, "ACTIVE_INTERRUPTED");
    }

    private void tracked(BoundaryType boundary, String scenario) throws Exception {
        var connections = new AtomicInteger();
        var requests = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/chat", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http.start();
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.addSessionListener(new SessionListener() {
                @Override public void sessionCreated(Session session) { connections.incrementAndGet(); }
            });
            server.setPasswordAuthenticator((user, password, session) -> false);
            server.setCommandFactory((channel, command) -> { throw new AssertionError("Controlled transport must carry the original reviewed request: " + command); });
        })) {
            runWorker("produce", boundary, ssh.server().getPort(), http.getAddress().getPort(), scenario);
            runWorker("recover", boundary, ssh.server().getPort(), http.getAddress().getPort(), scenario);
            if (scenario.equals("CLEANUP_LOST"))
                runWorker("recover-again", boundary, ssh.server().getPort(), http.getAddress().getPort(), scenario);
            assertEquals(0, connections.get(), "All sends use the approved controlled Linux capability");
            assertEquals(0, requests.get(), "All independent APPROVAL calls use the controlled AI capability; LANGUAGE never calls");
            assertTrue(Files.isRegularFile(root.resolve("environment-" + boundary + "-before.json")));
            assertTrue(Files.isRegularFile(root.resolve("environment-" + boundary + "-after.json")));
        } finally { http.stop(0); }
    }

    private void runWorker(String phase, BoundaryType boundary, int port, int httpPort, String scenario) throws Exception {
        Path output = root.resolve("environment-" + boundary + "-" + phase + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Djdk.net.unixdomain.tmpdir=" + root, "-cp", System.getProperty("java.class.path"),
            DeploymentAssistedEnvironmentRecoveryWorker.class.getName(), phase, root.toString(), boundary.name(),
            Integer.toString(port), Integer.toString(httpPort), scenario).redirectErrorStream(true)
            .redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(75, TimeUnit.SECONDS), "Original environment worker timed out: " + phase);
            String diagnostic = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(diagnostic.contains("ENVIRONMENT_" + phase.toUpperCase() + "_VERIFIED"), diagnostic);
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned original worker did not exit");
                }
            }
        }
    }
}
