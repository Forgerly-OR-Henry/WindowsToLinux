package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains;

class DeploymentHelperReviewContractTest {
    @Test
    void renderedToolchainWrapperPreservesCanonicalOuterStdinOnDescriptorThree(@TempDir Path temporary) throws Exception {
        String source = DeploymentManagedHelperBundle.renderScript();
        String opening = "prepare_official_toolchains() {\n";
        int start = source.indexOf(opening);
        assertTrue(start >= 0);
        int headerEnd = source.indexOf('\n', start + opening.length());
        String header = source.substring(start, headerEnd + 1);
        String invocation = "/usr/bin/python3 -I - \"$@\" 3<&0 <<'WTL_OFFICIAL_TOOLCHAINS'";
        assertEquals(opening + "  " + invocation + "\n", header);
        String ending = "WTL_OFFICIAL_TOOLCHAINS\n}\n";
        int end = source.indexOf(ending, headerEnd);
        assertTrue(end > headerEnd);
        String wrapper = source.substring(start, end + ending.length());

        var operation = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String task = "22222222-2222-4222-8222-222222222222";
        var arguments = List.of("prepare", "DOTNET", "10", "10.0.300", "1780", "-");
        var seed = new DeploymentToolchainDiagnostics.Request(operation, task, "s", "a".repeat(64), "b".repeat(64),
            DeploymentRemoteLayout.original(), arguments);
        int serverLength = 673 - seed.bytes().length + 1;
        assertTrue(serverLength > 0 && serverLength <= 512);
        var request = new DeploymentToolchainDiagnostics.Request(operation, task, "s".repeat(serverLength),
            seed.fingerprintSha256(), seed.planRevision(), seed.layout(), arguments);
        byte[] input = request.bytes();
        assertEquals(673, input.length);
        assertEquals(request, DeploymentToolchainDiagnostics.decode(input));

        var delivered = readThroughWrapper(temporary, "original", wrapper, request);
        assertEquals(0, delivered.exit(), delivered.error());
        assertArrayEquals(input, delivered.output());
        assertEquals(request.sha256(), DeploymentTrackedToolchains.sha256(delivered.output()));
        for (var changed : List.of(wrapper.replace("3<&0 ", ""),
            wrapper.replace("3<&0 <<'WTL_OFFICIAL_TOOLCHAINS'", "<<'WTL_OFFICIAL_TOOLCHAINS' 3<&0"))) {
            var rejected = readThroughWrapper(temporary, "changed", changed, request);
            assertFalse(Arrays.equals(input, rejected.output()), "Dropping or reordering the descriptor copy must lose the original request");
            assertNotEquals(request.sha256(), DeploymentTrackedToolchains.sha256(rejected.output()));
        }
        assertTrue(DeploymentHelperReviewContract.text().contains(invocation), "The source-bound contract must name the actual descriptor bridge");
    }

    private static ShellResult readThroughWrapper(Path temporary, String name, String wrapper,
                                                   DeploymentToolchainDiagnostics.Request request) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required");
        Path script = temporary.resolve(name + ".sh");
        Files.writeString(script, "controlled_reader() { cat >/dev/null; cat <&3; }\n"
            + wrapper.replaceFirst("(?m)^  /usr/bin/python3 -I - ", "  controlled_reader -I - ")
            + "prepare_official_toolchains \"$@\"\n", StandardCharsets.UTF_8);
        String path = script.toAbsolutePath().toString().replace('\\', '/');
        if (path.length() > 2 && path.charAt(1) == ':') path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", path,
            "prepare-diagnosed", request.operationId().toString(), request.sha256()).start();
        try {
            try (var input = process.getOutputStream()) { input.write(request.bytes()); }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Controlled descriptor reader timed out");
            return new ShellResult(process.exitValue(), process.getInputStream().readAllBytes(),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        }
    }

    private record ShellResult(int exit, byte[] output, String error) { }

    @Test
    void contractRequiresItsIndependentlyPinnedSourceAndCompleteGuard() {
        assertTrue(DeploymentHelperReviewContract.text().contains(DeploymentManagedHelperBundle.EXPECTED_SHA256));
        assertDoesNotThrow(() -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentManagedHelperBundle.buildEntrySha256()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION + 1,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentManagedHelperBundle.buildEntrySha256()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            "a".repeat(64), DeploymentManagedHelperBundle.buildEntrySha256()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, "a".repeat(64)));
        String invocation = "'" + DeploymentManagedHelperBundle.PATH + "' probe";
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation);
        assertEquals(invocation, DeploymentHelperReviewContract.verifiedInvocation(guarded));
        String bounded = DeploymentManagedHelperBundle.verifiedCommand("WTL_SYSTEMD_QUERY_SECONDS=45 " + invocation);
        assertEquals(invocation, DeploymentHelperReviewContract.verifiedInvocation(bounded));
        assertTrue(bounded.contains("WTL_SYSTEMD_QUERY_SECONDS=45 "), "The complete approved request retains its budget");
        for (String budget : java.util.List.of("0", "045", "7201", "45;", "$untrusted", "45  "))
            assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.verifiedInvocation(
                DeploymentManagedHelperBundle.verifiedCommand("WTL_SYSTEMD_QUERY_SECONDS=" + budget + " " + invocation)));
        for (String changed : java.util.List.of(invocation, guarded.replace("-perm /022", "-perm /000"),
            guarded.replace(DeploymentManagedHelperBundle.EXPECTED_SHA256, "b".repeat(64)),
            guarded.replace("' probe", "' unknown-command"), guarded.replace("' probe", "' $untrusted")))
            assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.verifiedInvocation(changed));
    }
}
