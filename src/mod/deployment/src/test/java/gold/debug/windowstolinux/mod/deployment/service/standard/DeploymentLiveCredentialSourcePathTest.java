package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentLiveCredentialSourcePathTest {
    @TempDir Path local;

    @Test
    void currentWorkspaceNormalRemainsDefaultSource() throws Exception {
        Path workspace = Files.createDirectory(local.resolve("r16f"));
        Path normal = normal(workspace);
        Path business = Files.createDirectories(workspace.resolve("test/data/agent-live-new-business"));

        assertEquals(normal, DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business, "", normal.toString()));
        assertEquals(normal, DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business, "",
            normal.resolve("../" + normal.getFileName()).toString()));
    }

    @Test
    void externalNormalRequiresSelectedWorkspaceAndIndependentBusinessState() throws Exception {
        Path workspace = Files.createDirectory(local.resolve("r16f"));
        Path sourceWorkspace = Files.createDirectory(local.resolve("r15f"));
        Path normal = normal(sourceWorkspace);
        Path currentNormal = normal(workspace);
        Path business = Files.createDirectories(workspace.resolve("test/data/agent-live-new-business"));

        assertThrows(AssertionError.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business, "", normal.toString()));
        assertEquals(normal, DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business, sourceWorkspace.toString(), normal.toString()));
        assertThrows(AssertionError.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business, sourceWorkspace.toString(), currentNormal.toString()));
        assertThrows(AssertionError.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, normal, sourceWorkspace.toString(), normal.toString()));
    }

    @Test
    void prefixEscapesAndBusinessStateNamesAreRejected() throws Exception {
        Path workspace = Files.createDirectory(local.resolve("r16f"));
        Path sourceWorkspace = Files.createDirectory(local.resolve("r15f"));
        Path normal = normal(sourceWorkspace);
        Path dataRoot = normal.getParent();
        Path business = Files.createDirectories(workspace.resolve("test/data/agent-live-new-business"));
        List<Path> rejected = List.of(
            Files.createDirectories(sourceWorkspace.resolve("src/app/db/data-neighbor/agent-live-credential-local")),
            normal(Files.createDirectory(local.resolve("r15f-neighbor"))),
            Files.createDirectories(dataRoot.getParent().resolve("agent-live-credential-local")),
            Files.createDirectory(dataRoot.resolve("agent-live-task-board-failed")),
            Files.createDirectory(dataRoot.resolve("agent-live-ftp-unknown")),
            Files.createDirectory(dataRoot.resolve("agent-live-reset-preparation")));

        for (Path path : rejected)
            assertThrows(AssertionError.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business, sourceWorkspace.toString(), path.toString()));
        assertThrows(AssertionError.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business,
            sourceWorkspace.toString(), dataRoot.resolve("../agent-live-credential-local").toString()));
    }

    @Test
    void redirectedWorkspaceAndSourceDirectoriesAreRejected() throws Exception {
        Path workspace = Files.createDirectory(local.resolve("r16f"));
        Path sourceWorkspace = Files.createDirectory(local.resolve("r15f"));
        Path normal = normal(sourceWorkspace);
        Path business = Files.createDirectories(workspace.resolve("test/data/agent-live-new-business"));
        Path linkedWorkspace = local.resolve("linked-workspace");
        Path linkedNormal = normal.getParent().resolve("agent-live-credential-linked");
        junction(linkedWorkspace, sourceWorkspace);
        try {
            assertThrows(Exception.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business,
                linkedWorkspace.toString(), linkedWorkspace.resolve("src/app/db/data").resolve(normal.getFileName()).toString()));
            junction(linkedNormal, normal);
            try {
                assertThrows(Exception.class, () -> DeploymentStaticLiveAcceptanceTest.credentialSourceRoot(workspace, business,
                    sourceWorkspace.toString(), linkedNormal.toString()));
            } finally {
                Files.delete(linkedNormal);
            }
        } finally {
            Files.delete(linkedWorkspace);
        }
    }

    private Path normal(Path workspace) throws Exception {
        return Files.createDirectories(workspace.resolve("src/app/db/data/agent-live-credential-local")).toRealPath();
    }

    private void junction(Path link, Path target) throws Exception {
        var builder = new ProcessBuilder("C:\\Windows\\System32\\cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
            .directory(local.toFile()).redirectErrorStream(true);
        builder.environment().put("TEMP", System.getProperty("java.io.tmpdir"));
        builder.environment().put("TMP", System.getProperty("java.io.tmpdir"));
        var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Synthetic junction creation must finish");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            assertEquals(0, process.exitValue(), () -> "Synthetic junction creation failed: " + output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Synthetic junction process must exit");
            }
        }
    }
}
