package gold.debug.windowstolinux.mod.deployment.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentRemoteLayoutCaptureTest extends DeploymentRuntimeFixture {
    private static final String KEY = "deployment.config.remote-layout.v1";

    @Test
    void releasedOriginalLayoutSurvivesChangedConfigurationAndRecovery() throws Exception {
        enable();
        var captured = configuredLayout("/srv/original");
        var changed = new AtomicReference<DeploymentRemoteLayout>();
        try (var runtime = open()) {
            start(runtime, true);
            var tasks = runtime.modContexts().get(OWNER).context().tasks();
            var handle = tasks.submit(new TaskRequest("fixture.layout.capture", "Layout", List.of(), true), task -> {
                DeploymentConfigCapture.remoteLayout(tasks, task, captured);
                var original = tasks.findCheckpoint(task, KEY).orElseThrow().snapshot();
                assertTrue(original.released());
                changed.set(configuredLayout("/srv/changed"));
                assertNotEquals(captured, changed.get());
                assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.remoteLayout(tasks, task, changed.get()),
                    "An original released key cannot be recreated with changed roots");
                assertEquals(original, tasks.findCheckpoint(task, KEY).orElseThrow().snapshot());
                assertEquals(captured, DeploymentConfigCapture.remoteLayout(tasks, task));
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty());
            });
            try (var registration = tasks.registerRecovery("fixture.layout.capture", (previous, task) -> {
                assertEquals(handle.id(), task.id());
                var restored = DeploymentConfigCapture.remoteLayout(tasks, task);
                assertEquals(captured, restored);
                assertEquals("/srv/original/state/work/demo-0123456789abcdef", restored.candidateRoot("demo-0123456789abcdef"));
                assertNotEquals(changed.get().digest(), restored.digest());
                assertTrue(tasks.findCheckpoint(task, KEY).orElseThrow().snapshot().released());
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            })) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (handle.snapshot().state() != TaskState.UNKNOWN) {
                    assertTrue(System.nanoTime() < deadline, "Original task must return UNKNOWN within the fixture bound");
                    Thread.sleep(10);
                }
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(Optional.of(TaskResult.SUCCEEDED), reconcileReady(runtime, handle).outcome());
                assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
            }
        }
    }

    @Test
    void missingEvidenceRequiresExplicitLegacyChoiceAndForeignTaskPayloadIsRejected() throws Exception {
        enable();
        var changed = configuredLayout("/srv/new-config");
        try (var runtime = open()) {
            start(runtime, true);
            var tasks = runtime.modContexts().get(OWNER).context().tasks();
            var handle = tasks.submit(new TaskRequest("fixture.layout.missing", "Layout", List.of(), true), task -> {
                assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.remoteLayout(tasks, task));
                var explicitLegacy = new DeploymentRemoteLayout(DeploymentConfig.RemotePaths.original());
                assertEquals(DeploymentRemoteLayout.original(), explicitLegacy);
                assertNotEquals(changed, explicitLegacy);
                assertTrue(tasks.findCheckpoint(task, KEY).isEmpty(), "Explicit legacy choice cannot create missing evidence");
                try (var ignored = tasks.beginCheckpoint(task, KEY, evidence(UUID.randomUUID(), changed))) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.remoteLayout(tasks, task));
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void checkpointDecoderRejectsTaskVersionSchemaDigestAndByteLimitDrift() {
        UUID task = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var layout = DeploymentRemoteLayout.original();
        String payload = evidence(task, layout);
        assertEquals(layout, DeploymentConfigCapture.decodeRemoteLayout(task, payload));
        for (String invalid : List.of("", "null", "[]", "{}", payload + " {}", payload + " ".repeat(4096),
            payload.replaceFirst("\"formatVersion\":1", "\"formatVersion\":2"),
            payload.replaceFirst("\"formatVersion\":1", "\"formatVersion\":1.0"),
            payload.replaceFirst("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            payload.replaceFirst("\"formatVersion\":1", "\"formatVersion\":2147483648"),
            payload.replaceFirst("\"formatVersion\":1", "\"formatVersion\":null"),
            payload.replace(task.toString(), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            payload.replace("\"task\":\"" + task + "\"", "\"task\":null"),
            payload.replace("\"task\":\"" + task + "\"", "\"task\":12"),
            payload.replace("\"task\":\"" + task + "\",", ""),
            payload.replace("\"layout\":" + layout.canonical(), "\"layout\":null"),
            payload.replace("\"layout\":" + layout.canonical(), "\"layout\":[]"),
            payload.replace("\"layout\":" + layout.canonical(), "\"layout\":\"default\""),
            payload.replace("\"layoutSha256\":\"" + layout.digest() + "\"", "\"layoutSha256\":null"),
            payload.replace("\"layoutSha256\":\"" + layout.digest() + "\"", "\"layoutSha256\":12"),
            payload.replace(",\"layoutSha256\":\"" + layout.digest() + "\"", ""),
            payload.replace(layout.digest(), layout.digest().toUpperCase(java.util.Locale.ROOT)),
            payload.replace(layout.digest(), "0".repeat(64)),
            payload.replace("/var/lib/windowstolinux", "/srv/other-state"),
            payload.replace("\"formatVersion\":1,\"task\"", "\"extra\":0,\"formatVersion\":1,\"task\""),
            payload.replaceFirst("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")))
            assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.decodeRemoteLayout(task, invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.decodeRemoteLayout(task, null));
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigCapture.decodeRemoteLayout(null, payload));
        var multibyte = assertThrows(IllegalArgumentException.class,
            () -> DeploymentConfigCapture.decodeRemoteLayout(task, "{\"padding\":\"" + "界".repeat(1600) + "\"}"));
        assertTrue(multibyte.getMessage().contains("exceeds limits"));
    }

    private static String evidence(UUID task, DeploymentRemoteLayout layout) {
        return "{\"formatVersion\":1,\"task\":\"" + task + "\",\"layout\":" + layout.canonical()
            + ",\"layoutSha256\":\"" + layout.digest() + "\"}";
    }

    private static DeploymentRemoteLayout configuredLayout(String prefix) throws Exception {
        String yaml;
        try (var input = DeploymentRemoteLayoutCaptureTest.class.getResourceAsStream(DeploymentConfigLoader.RESOURCE)) {
            assertNotNull(input);
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        yaml = yaml.replace("helper-directory: /usr/local/lib/windowstolinux", "helper-directory: " + prefix + "/helper")
            .replace("applications-root: /opt/windowstolinux/apps", "applications-root: " + prefix + "/apps")
            .replace("state-root: /var/lib/windowstolinux", "state-root: " + prefix + "/state")
            .replace("configuration-root: /etc/opt/windowstolinux/apps", "configuration-root: " + prefix + "/config")
            .replace("data-root: /var/opt/windowstolinux/apps", "data-root: " + prefix + "/data");
        var config = DeploymentConfigLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new DeploymentRemoteLayout(config.paths());
    }
}
