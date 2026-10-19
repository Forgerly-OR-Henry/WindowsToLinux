package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.MavenBuildWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the API fixture's exact production assembly before any paid model call. */
class DeploymentAssistedApprovalFixtureTest {
    @TempDir
    Path root;

    @Test
    void representativeIntentKeepsDeploymentEffectsWhileTransportIsIndependentlyUnavailable() {
        String workflow = DeploymentAssistedApprovalBudgetLiveTest.representativeWorkflow("task-board");
        assertTrue(workflow.startsWith("Deploy selected task-board"));
        assertTrue(workflow.contains("restricted non-root candidate build"));
        assertTrue(workflow.contains("not a fresh preparation observation"));
        assertFalse(workflow.contains("authorizes no remote dispatch"));
        var delegated = new java.util.concurrent.atomic.AtomicBoolean();
        var actual = (gold.debug.windowstolinux.appapi.mod.ModContext) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class}, (proxy, method, args) -> {
                delegated.set(true);
                throw new AssertionError("No underlying capability may be requested");
            });
        var isolated = new DeploymentAssistedApprovalBudgetLiveTest().noRemoteContext(actual, "task-board");
        assertThrows(AssertionError.class, isolated::linux);
        assertFalse(delegated.get());
    }

    @Test
    void mavenFixtureUsesProductionAssemblyNonemptyMarkersAndWholeInput() throws Exception {
        Path directory = root.toAbsolutePath();
        Files.createDirectories(directory.resolve("backend"));
        Files.writeString(directory.resolve("backend/pom.xml"), "<project><properties><java.version>21</java.version></properties></project>");
        UUID id = UUID.randomUUID();
        var snapshot = (FileSnapshot) Proxy.newProxyInstance(FileSnapshot.class.getClassLoader(), new Class<?>[]{FileSnapshot.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "directory" -> directory;
                case "sha256" -> "a".repeat(64);
                default ->
                    throw new AssertionError("No live capabilities in fixture assembly test: " + method.getName());
            });
        var material = new DeploymentSourceMaterial("task-board", snapshot, List.of("backend/pom.xml"), List.of());
        var archive = new DeploymentSourceArchive(snapshot, "b".repeat(64), 1, 1, id, "a".repeat(64), "backend");
        var layout = DeploymentRemoteLayout.original();
        try (var request = DeploymentAssistedApprovalBudgetLiveTest.maven(material, archive, "wtl-api-task-board", layout, new ServerId("fixture"))) {
            String invocation = DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
            var facts = DeploymentBuildOutputProtocol.reviewFacts(invocation, request.stdin(), layout);
            assertEquals("wtl-api-task-board", facts.get("application"));
            assertTrue(invocation.contains(Base64.getEncoder().encodeToString("BUILD_COMPATIBILITY=toolchain".getBytes(StandardCharsets.UTF_8))), "Actual BuildMatchPolicy.groups, not an empty marker list");
            String body = new String(request.stdin(), StandardCharsets.UTF_8);
            assertTrue(body.contains("BUILD_FAILURE_LINE="));
            assertTrue(body.contains("WTL_JAVA_HOME"));
            assertTrue(body.contains("java-0e6db83ffe5fbb34adf155ce772477836ca6b5db1fd84531b1d200ef575fdd84"));
            assertTrue(body.contains("maven_package mvn"));
            assertTrue(MavenBuildWriter.bootstrapReviewInstructions(body).isPresent());
            var evidence = MavenBuildWriter.bootstrapEvidence(body).orElseThrow();
            int offset = (Integer) evidence.get("utf8ByteOffset"), length = (Integer) evidence.get("utf8ByteLength");
            String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(request.stdin(), offset, offset + length)));
            assertEquals(evidence.get("fragmentSha256"), digest);
        }
        try (var request = DeploymentAssistedApprovalBudgetLiveTest.node(layout)) {
            assertTrue(request.script().contains("'NODE' '24'"));
        }
    }
}
