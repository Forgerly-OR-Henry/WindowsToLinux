package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.db.ai.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentPlatformFileTest extends DeploymentRuntimeFixture {
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t, CancelToken x) {
                return TransferConflictChoice.SKIP;
            }

            public TransferStallChoice transferStalled(TransferStall c, ServerRef s, TaskSnapshot t, CancelToken x) {
                return TransferStallChoice.TERMINATE;
            }

            public String transferProgress(TransferProgress p) {
                return p.stage().name();
            }

            public boolean approve(ActionIntent i, TaskSnapshot t, CancelToken x) {
                return true;
            }

            public char[] text(String a, String b, boolean s, TaskSnapshot t, CancelToken x) {
                return null;
            }
        };
    }

    @Test
    void realDeploymentModConsumesFilesCreatedAndReadThroughUnifiedPlatformCapability() throws Exception {
        Files.createDirectories(root.resolve("input"));
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var file = context.file();
            var task = context.tasks().submit(new TaskRequest("unified_file", "Unified file deployment", List.of(), true), active -> {
                var input = FilePath.local(root.resolve("input/App.java"));
                byte[] code = "class App {}".getBytes(StandardCharsets.UTF_8);
                assertEquals(Outcome.SUCCEEDED, file.writeFile(input, code, active).outcome());
                var selected = file.selectDirectory(root.resolve("input"), active);
                try (var material = new DeploymentSourcePreparer(file).prepare(selected, 1024, 100, active);
                     var archive = new DeploymentSourceArchiver(file).prepare(material, 1024 * 1024, active)) {
                    assertEquals(Set.of("App.java"), new HashSet<>(material.includedFiles()));
                    assertArrayEquals(code, file.readFile(input, 1024, active));
                    assertTrue(archive.byteCount() > 0);
                    assertEquals(1, file.operations(active).size());
                }
                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(Outcome.SUCCEEDED, task.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM file_operation WHERE owner_id='" + OWNER + "'"));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void realModContextSharesItsIndependentlyReviewedGrantWithTheUnifiedFileCapability() throws Exception {
        Files.createDirectories(root.resolve("input"));
        enable();
        var calls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(executor);
        http.createContext("/approval", exchange -> {
            try {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"approved\\\":true}\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable failure) {
                failures.add(failure);
            } finally {
                exchange.close();
            }
        });
        http.start();
        try (var runtime = open()) {
            String credential = "ai/fixture/mod-file-review";
            try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
                char[] key = "fixture-key".toCharArray();
                try {
                    store.save(credential, key);
                } finally {
                    Arrays.fill(key, '\0');
                }
            }
            runtime.persistence().aiProfiles().saveVerified(new StoredAiProviderProfile("file-review", "http://127.0.0.1:" + http.getAddress().getPort() + "/approval", "fixture-model", credential), "file-review", java.time.Instant.now(), AiCapabilityType.TEXT);
            runtime.persistence().aiProfiles().saveOutputLimit("file-review", 512);
            runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("file-review", true)));
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var file = context.file();
            Path original = Files.writeString(root.resolve("input/owned"), "owned original");
            var handle = context.tasks().submit(new TaskRequest("file_review", "Unified reviewed file", List.of(), true), task -> {
                FilePath path = FilePath.local(original);
                var intent = FileActions.delete(file.getFile(path, task), false, DeleteEffect.RECYCLE);
                var grant = context.interaction().approve(intent, task).value().orElseThrow();
                Files.writeString(original, "changed after grant");
                var failure = assertThrows(ApiException.class, () -> file.deleteFile(path, task, grant));
                assertEquals("FILE_DELETE_PERMIT", failure.problem().eventCode());
                assertEquals("changed after grant", Files.readString(original));
                assertTrue(file.operations(task).isEmpty());
                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(Outcome.SUCCEEDED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(1, calls.get());
            assertTrue(failures.isEmpty());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            http.stop(0);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
