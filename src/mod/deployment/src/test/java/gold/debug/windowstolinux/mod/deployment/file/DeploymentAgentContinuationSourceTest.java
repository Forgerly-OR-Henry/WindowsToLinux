package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentContinuationSourceTest extends DeploymentRuntimeFixture {
    private record Captured(DeploymentAgentContinuationSource capsule, UUID material, String hash, Path source,
                            Path archive) {
    }

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    private Captured capture(PlatformRuntime runtime, long maximumBytes) throws Exception {
        var actual = runtime.modContexts().get(OWNER).context();
        var value = new AtomicReference<Captured>();
        var error = new AtomicReference<Throwable>();
        var handle = actual.tasks().submit(new TaskRequest("capsule-capture", "Capture authorized source only", List.of(), true), task -> {
            try {
                var selection = actual.file().sources().selectDirectory(selectedSourceDirectory(), task);
                try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, maximumBytes, 100, task);
                     var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, maximumBytes, task)) {
                    var request = new DeploymentAgentInputs.Request(Map.of("goal", "Bounded source custody"), "MANUAL_REVIEW", false,
                        maximumBytes, 100, maximumBytes);
                    value.set(new Captured(DeploymentAgentContinuationSource.capture(material, request, task), material.snapshot().id(),
                        material.snapshot().sha256(), material.snapshot().directory(), archive.snapshot().directory()));
                }
                actual.file().closeTaskResources(task);
                return success();
            } catch (Exception | AssertionError failure) {
                error.set(failure);
                throw failure;
            }
        });
        var ended = handle.completion().toCompletableFuture().get(45, TimeUnit.SECONDS);
        if (error.get() != null) throw new AssertionError("Authentic source capture failed", error.get());
        assertEquals(TaskResult.SUCCEEDED, ended.outcome());
        assertFalse(Files.exists(value.get().source()));
        assertFalse(Files.exists(value.get().archive()));
        noFiles(runtime);
        return value.get();
    }

    @Test
    void freshChildGrantSurvivesOriginalClosureAndUsesTheOriginalLargeSourceLimits() throws Exception {
        Files.createDirectories(selectedSourceDirectory().resolve("src"));
        try (var output = Files.newOutputStream(selectedSourceDirectory().resolve("src/authorized.txt"))) {
            byte[] block = new byte[8192];
            Arrays.fill(block, (byte) 'x');
            for (int index = 0; index < 2560; index++) output.write(block);
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var captured = capture(runtime, 24L * 1024 * 1024);
            assertTrue(captured.capsule().bytes() > 16L * 1024 * 1024, "No former implicit capsule ceiling is introduced");
            Path privateRoot = directory(captured.capsule());
            var actual = runtime.modContexts().get(OWNER).context();
            var error = new AtomicReference<Throwable>();
            var child = actual.tasks().submit(new TaskRequest("capsule-child", "New child source grant", List.of(), true), task -> {
                try (var material = captured.capsule().rebind(actual.file(), task)) {
                    assertNotEquals(captured.material(), material.snapshot().id());
                    assertEquals(captured.hash(), material.snapshot().sha256());
                    assertEquals(List.of("src/authorized.txt"), material.includedFiles());
                    assertEquals(20L * 1024 * 1024, Files.size(material.snapshot().directory().resolve("src/authorized.txt")));
                    assertFalse(material.snapshot().directory().equals(captured.source()));
                    captured.capsule().close();
                    assertFalse(Files.exists(privateRoot));
                    return success();
                } catch (Exception | AssertionError failure) {
                    error.set(failure);
                    throw failure;
                }
            });
            assertEquals(TaskResult.SUCCEEDED, child.completion().toCompletableFuture().get(45, TimeUnit.SECONDS).outcome());
            if (error.get() != null) throw new AssertionError("Fresh child grant failed", error.get());
            noFiles(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "size", "extra", "missing", "replacement", "hard-link", "extra-directory"})
    void changedCustodyNeverOffersPartialOrForeignSource(String change) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("authorized.txt"), "authorized original source\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var captured = capture(runtime, 1024);
            var capsule = captured.capsule();
            Path privateRoot = directory(capsule), member = privateRoot.resolve("authorized.txt");
            Path foreign = root.resolve("foreign-preserved.txt");
            Files.writeString(foreign, "FOREIGN_CONTENT_MUST_NOT_BECOME_SOURCE");
            try {
                switch (change) {
                    case "hash" -> Files.writeString(member, "changed original source\n");
                    case "size" -> Files.writeString(member, "x".repeat(1025));
                    case "extra" -> Files.writeString(privateRoot.resolve("extra.txt"), "not authorized");
                    case "extra-directory" -> Files.createDirectory(privateRoot.resolve("unowned"));
                    case "missing" -> Files.delete(member);
                    case "replacement" -> {
                        Files.delete(member);
                        Files.copy(foreign, member);
                    }
                    case "hard-link" -> {
                        Files.delete(member);
                        Files.createLink(member, foreign);
                    }
                    default -> throw new AssertionError(change);
                }
                assertThrows(Exception.class, capsule::verify);
                var actual = runtime.modContexts().get(OWNER).context();
                var child = actual.tasks().submit(new TaskRequest("capsule-rejected-child", "Refuse changed source before grant", List.of(), true), task -> {
                    assertThrows(Exception.class, () -> capsule.rebind(actual.file(), task));
                    return success();
                });
                assertEquals(TaskResult.SUCCEEDED, child.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                assertEquals("FOREIGN_CONTENT_MUST_NOT_BECOME_SOURCE", Files.readString(foreign));
                noFiles(runtime);
            } finally {
                capsule.close();
            }
            assertFalse(Files.exists(privateRoot));
            assertTrue(Files.exists(foreign));
        }
    }

    @Test
    void replacedPrivateRootCannotDeleteTheReplacementOrSharedBytes() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("authorized.txt"), "source\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var capsule = capture(runtime, 1024).capsule();
            Path original = directory(capsule), moved = original.resolveSibling(original.getFileName() + "-retained-by-test");
            Files.move(original, moved);
            Files.createDirectory(original);
            Path foreign = Files.writeString(original.resolve("replacement.txt"), "PRESERVE_REPLACEMENT");
            try {
                assertThrows(Exception.class, capsule::verify);
                assertThrows(Exception.class, capsule::close);
                assertEquals("PRESERVE_REPLACEMENT", Files.readString(foreign));
            } finally {
                Files.delete(foreign);
                Files.delete(original);
                Files.move(moved, original);
                capsule.close();
            }
            assertFalse(Files.exists(original));
            noFiles(runtime);
        }
    }

    @Test
    void closedOrMissingCustodyAndEscapingManifestNeverReopenOldGrants() throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("authorized.txt"), "source\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var capsule = capture(runtime, 1024).capsule();
            capsule.close();
            assertThrows(Exception.class, capsule::verify);
            var actual = runtime.modContexts().get(OWNER).context();
            var handle = actual.tasks().submit(new TaskRequest("capsule-path-rejection", "Reject path before custody", List.of(), true), task -> {
                assertThrows(Exception.class, () -> capsule.rebind(actual.file(), task));
                var selection = actual.file().sources().selectDirectory(selectedSourceDirectory(), task);
                try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1024, 100, task)) {
                    var escaped = new DeploymentSourceMaterial("invalid member metadata", material.snapshot(), List.of("../foreign.txt"), List.of());
                    assertThrows(Exception.class, () -> DeploymentAgentContinuationSource.capture(escaped,
                        new DeploymentAgentInputs.Request(Map.of(), "MANUAL_REVIEW", false, 1024, 100, 1024), task));
                }
                return success();
            });
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            noFiles(runtime);
        }
    }

    private static Path directory(DeploymentAgentContinuationSource capsule) throws Exception {
        var field = DeploymentAgentContinuationSource.class.getDeclaredField("directory");
        field.setAccessible(true);
        return (Path) field.get(capsule);
    }

    private static OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    private void noFiles(PlatformRuntime runtime) throws Exception {
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }
}
