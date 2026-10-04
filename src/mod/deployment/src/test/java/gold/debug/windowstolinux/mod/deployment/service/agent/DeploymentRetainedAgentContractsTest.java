package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks the real model-purpose admission before user source or remote work. / 在用户来源和远端工作前核对真实模型用途准入。 */
class DeploymentRetainedAgentContractsTest extends DeploymentRuntimeFixture {
    enum ConfigType {
        LANGUAGE_MISSING_MANUAL("MANUAL_REVIEW", false, null), LANGUAGE_MISSING_AUTOMATIC("AUTOMATIC", false,
            true), LANGUAGE_MISSING_FULL("FULL_CONTROL", false, true), APPROVAL_MISSING_AUTOMATIC("AUTOMATIC", true,
            null), APPROVAL_MISSING_FULL("FULL_CONTROL", true, null), APPROVAL_DISABLED_AUTOMATIC(
            "AUTOMATIC", true, false), APPROVAL_DISABLED_FULL("FULL_CONTROL", true,
            false), MANUAL_LANGUAGE_ONLY("MANUAL_REVIEW", true, null);

        final String policy;

        final boolean language;

        final Boolean approval;

        ConfigType(String policy, boolean language, Boolean approval) {
            this.policy = policy;
            this.language = language;
            this.approval = approval;
        }
    }

    private final AtomicInteger pickerCalls = new AtomicInteger();

    @Override
    protected Path selectedSourceDirectory() {
        pickerCalls.incrementAndGet();
        return null; // Actual picker cancellation, after model admission. / 模型准入后的真实选择器取消。
    }

    @ParameterizedTest(name = "original policy configuration {0}")
    @EnumSource(ConfigType.class)
    void requiredPurposesRejectBeforeSourceCaptureArchiveSshOrSendWhileManualNeedsOnlyLanguage(
        ConfigType selected) throws Exception {
        enable();
        var httpCalls = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        http.setExecutor(executor);
        http.createContext("/chat", exchange -> {
            httpCalls.incrementAndGet();
            exchange.getRequestBody().close();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        http.start();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile.fromStored(
                    new StoredServerProfile("server", "127.0.0.1", 22, "deploy", "fixture-password")),
                "fixture-password".toCharArray());
            String credential = "ai/legacy/admission";
            try (var operation = runtime.security().enterOperation();
                 var store = runtime.security().openCredentials()) {
                char[] key = "fixture-ai-key".toCharArray();
                try {
                    store.save(credential, key);
                } finally {
                    Arrays.fill(key, '\0');
                }
            }
            runtime.persistence().aiProfiles()
                .saveVerified(new StoredAiModelProfile("model",
                        "http://127.0.0.1:" + http.getAddress().getPort() + "/chat", "fixture/model", credential),
                    "model", Instant.now(), AiCapabilityType.TEXT);
            runtime.persistence().aiProfiles().saveOutputLimit("model", 1600);
            if (selected.language)
                runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
                    List.of(new AiPurposeAssignment("model", true)));
            if (selected.approval != null)
                runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL,
                    List.of(new AiPurposeAssignment("model", selected.approval)));

            var selections = new AtomicInteger();
            var captures = new AtomicInteger();
            var workspaces = new AtomicInteger();
            var connections = new AtomicInteger();
            var sends = new AtomicInteger();
            var purposes = new CopyOnWriteArrayList<String>();
            var actual = runtime.modContexts().get(OWNER).context();
            var source = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "selectDirectory" -> selections.incrementAndGet();
                    case "snapshot" -> captures.incrementAndGet();
                    case "workspace" -> workspaces.incrementAndGet();
                    default -> {
                    }
                }
                return invoke(actual.file(), method, arguments);
            });
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
                new Class<?>[]{LinuxService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("connect"))
                        connections.incrementAndGet();
                    return invoke(actual.linux(), method, arguments);
                });
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(),
                new Class<?>[]{AiService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("call"))
                        sends.incrementAndGet();
                    if (method.getName().equals("configurationRevision"))
                        purposes.add(arguments[0].toString());
                    return invoke(actual.ai(), method, arguments);
                });
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "file" -> source;
                        case "linux" -> linux;
                        case "ai" -> ai;
                        default -> invoke(actual, method, arguments);
                    };
                });
            var request = new DeploymentAgentWorkflow.Request(Map.of("goal", "Actual original task"), selected.policy,
                65536, 100, 65536);
            var handle = new DeploymentAgentWorkflow(bound)
                .submitLocal(new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), request);
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskState.ENDED, handle.snapshot().state(), handle.snapshot().toString());
            assertEquals(OWNER, handle.snapshot().ownerId());
            assertEquals(VERSION, handle.snapshot().ownerVersion());
            if (selected == ConfigType.MANUAL_LANGUAGE_ONLY) {
                assertEquals(TaskResult.CANCELLED, result.outcome(), result.toString());
                assertEquals(1, selections.get());
                assertEquals(1, pickerCalls.get());
                assertEquals(List.of("LANGUAGE"), purposes, "Manual mode never requires the approval purpose");
            } else {
                assertEquals(TaskResult.FAILED, result.outcome(), result.toString());
                assertEquals(ProblemCode.UNAVAILABLE, result.problem().orElseThrow().code());
                assertEquals("AI_PURPOSE_UNAVAILABLE", result.problem().orElseThrow().eventCode());
                assertEquals(0, selections.get());
                assertEquals(0, pickerCalls.get());
                assertEquals(selected.language ? List.of("LANGUAGE", "APPROVAL") : List.of("LANGUAGE"), purposes);
            }
            assertEquals(0, captures.get());
            assertEquals(0, workspaces.get(), "No capture or archive workspace before admission");
            assertEquals(0, connections.get());
            assertEquals(0, sends.get());
            assertEquals(0, httpCalls.get());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                for (String table : List.of("deployment_agent_task", "deployment_execution"))
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1));
                    }
            }
        } finally {
            http.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Object invoke(Object actual, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(actual, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
