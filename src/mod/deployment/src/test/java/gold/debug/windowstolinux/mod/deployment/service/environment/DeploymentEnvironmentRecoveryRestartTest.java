package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.junit.jupiter.api.Test;

class DeploymentEnvironmentRecoveryRestartTest extends DeploymentRuntimeFixture {
    @Test
    void originalUnknownPersistsAcrossExitAndChangedWireKeyCannotAuthenticateOrReplay() throws Exception {
        var authentications = new AtomicInteger();
        var commands = new AtomicInteger();
        try (var listener = new LoopbackSshServer(Files.createDirectories(root.resolve("wire-peer")), server -> {
            server.setPasswordAuthenticator((user, password, session) -> {
                authentications.incrementAndGet();
                return false;
            });
            server.setCommandFactory((channel, command) -> {
                commands.incrementAndGet();
                throw new AssertionError("No original credentials or commands may reach a changed wire key");
            });
        })) {
            run("produce", listener.server().getPort());
            run("recover", listener.server().getPort());
            assertEquals(0, authentications.get());
            assertEquals(0, commands.get());
            assertEquals(Files.readString(root.resolve("original-environment.json")), Files.readString(root.resolve("recovered-environment.json")));
        }
    }

    @Test
    void realCancellationPersistsAcrossExitAndOriginalRegisteredRecoveryAuthenticatesWithoutSendingCommands() throws Exception {
        var authentications = new AtomicInteger();
        var commands = new AtomicInteger();
        try (var listener = new LoopbackSshServer(Files.createDirectories(root.resolve("cancel-wire-peer")), server -> {
            server.setPasswordAuthenticator((user, password, session) -> {
                authentications.incrementAndGet();
                return user.equals("root") && password.equals("restart-fixture-password");
            });
            server.setCommandFactory((channel, command) -> {
                commands.incrementAndGet();
                throw new AssertionError("Cancelled original recovery must send zero commands");
            });
        })) {
            var key = listener.server().getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
            run("produce-cancel", listener.server().getPort(), fingerprint);
            assertEquals(0, authentications.get());
            run("recover-cancel", listener.server().getPort(), fingerprint);
            assertEquals(1, authentications.get());
            assertEquals(0, commands.get());
            assertTrue(Files.isRegularFile(root.resolve("cancelled-summary.json")));
        }
    }

    void run(String phase, int port) throws Exception {
        run(phase, port, "unused");
    }

    void run(String phase, int port, String fingerprint) throws Exception {
        Path output = root.resolve("environment-restart-" + phase + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Djdk.net.unixdomain.tmpdir=" + root, "-cp", System.getProperty("java.class.path"),
            DeploymentEnvironmentRecoveryWorker.class.getName(), phase, root.toString(), Integer.toString(port), fingerprint)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(75, TimeUnit.SECONDS), "Original environment worker timed out");
            String diagnostic = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(diagnostic.contains("ENVIRONMENT_" + phase.toUpperCase(java.util.Locale.ROOT) + "_VERIFIED"));
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }
}
