package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in bounded approval-only comparison; never admits a server action. / 有界可选纯审批对照，绝不准入服务器动作。 */
@EnabledIfSystemProperty(named = "managed.runtime.approvalCacheLive", matches = "true")
class DeploymentApprovalCacheLiveTest extends DeploymentRuntimeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long BUDGET = 100000;

    private final List<Map<String, Object>> observations = new ArrayList<>();

    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), protection());
        if (runtime.security().securityState().configured()) runtime.security().unlock(PASSWORD.toCharArray());
        else runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(interactionPrompts());
        runtime.setSourcePicker((title, task, cancel) -> {
            throw new AssertionError("No source selection permitted in approval-only comparison");
        });
        runtime.started();
        return runtime;
    }

    private static DatabaseProtection protection() {
        var current = new CurrentUserProtection();
        return new DatabaseProtection() {
            public byte[] protect(byte[] bytes) {
                return current.protect(bytes);
            }

            public byte[] unprotect(byte[] bytes) {
                return current.unprotect(bytes);
            }
        };
    }

    @Test
    void describesSelectedEndpointWithoutReadingCredentialsOrCallingApi() throws Exception {
        var source = Path.of(System.getProperty("managed.approvalCache.sourceState")).toRealPath();
        assertTrue(source.startsWith(workspace().resolve("src/app/db/data").toRealPath()));
        try (var saved = PlatformRuntime.open(source.resolve("data"), protection())) {
            saved.security().unlock(PASSWORD.toCharArray());
            var model = saved.persistence().aiProfiles().configuredFor(AiPurposeType.APPROVAL).getFirst();
            var endpoint = java.net.URI.create(model.profile().endpoint());
            assertNull(endpoint.getUserInfo());
            assertNull(endpoint.getQuery());
            System.out.println("APPROVAL_CACHE_ENDPOINT endpoint=" + endpoint + " model=" + model.profile().model()
                + " reasoningEffort=" + model.profile().reasoningEffort() + " credentialRead=false apiCalls=0 payload=SYNTHETIC_PROBE");
        }
    }

    @Test
    void comparesThreePairsUsingThePreviouslyConfiguredApprovalModel() throws Exception {
        String source = System.getProperty("managed.approvalCache.sourceState", "");
        assertFalse(source.isBlank(), "A retained live model runtime must be explicitly selected");
        enable();
        try (var runtime = open()) {
            importSelectedModel(runtime, Path.of(source).toRealPath());
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest("approval-cache-comparison.v1", "Approval cache comparison", List.of(), true), task -> {
                String revision = context.ai().configurationRevision(AiPurpose.APPROVAL, task);
                long consumed = 0;
                for (int pair = 1; pair <= 3; pair++) {
                    AiRequest original = capture(context, task, revision, pair);
                    int ceiling = 4096;
                    for (String group : List.of("A", "B")) {
                        var messages = new ArrayList<>(original.messages());
                        if (group.equals("A")) Collections.swap(messages, 1, 2);
                        messages.add(new AiMessage(AiRole.USER, "Return the exact requested JSON review decision.", List.of()));
                        var request = new AiRequest(AiPurpose.APPROVAL, messages, ceiling, Duration.ofSeconds(60), true);
                        long inputBound = messages.stream().mapToLong(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum() + 2048;
                        if (consumed + inputBound + ceiling > BUDGET) {
                            System.out.println("APPROVAL_CACHE_STOP reason=INSUFFICIENT_RESERVED_BUDGET consumed=" + consumed);
                            return success(null);
                        }
                        long started = System.nanoTime();
                        var result = context.ai().call(request, revision, task);
                        var row = observation(group, pair, request, result);
                        row.put("startedAt", java.time.Instant.now().minusMillis(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).toString());
                        row.put("attempt", observations.size() + 1);
                        row.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        observations.add(row);
                        System.out.println("APPROVAL_CACHE_OBSERVATION " + JSON.writeValueAsString(row));
                        var input = count(row, "inputTokens");
                        var output = count(row, "outputTokens");
                        if (input.isEmpty() || output.isEmpty() || count(row, "cachedInputTokens").isEmpty() || count(row, "cacheMissInputTokens").isEmpty()) {
                            System.out.println("APPROVAL_CACHE_STOP reason=MISSING_USAGE reserved=" + (inputBound + ceiling));
                            return success(null);
                        }
                        consumed += input.orElseThrow() + output.orElseThrow();
                        if (consumed > BUDGET)
                            throw new AssertionError("Provider counts exceeded the reserved conservative budget");
                        if (result.outcome() != TaskResult.SUCCEEDED || !result.value().orElseThrow().finishReason().equals("stop")) {
                            System.out.println("APPROVAL_CACHE_STOP reason=INCOMPLETE_RESPONSE consumed=" + consumed);
                            return success(null);
                        }
                        try {
                            DeploymentReviewDecision.parse(JSON.readTree(result.value().orElseThrow().text()));
                        } catch (java.io.IOException | IllegalArgumentException invalid) {
                            System.out.println("APPROVAL_CACHE_STOP reason=INVALID_REVIEW_FORMAT consumed=" + consumed);
                            return success(null);
                        }
                    }
                }
                return success(null);
            });
            var completed = handle.completion().toCompletableFuture().get(7, TimeUnit.MINUTES);
            assertEquals(TaskResult.SUCCEEDED, completed.outcome(), completed.problem().toString());
            assertTrue(observations.size() <= 6);
            System.out.println("APPROVAL_CACHE_SUMMARY " + JSON.writeValueAsString(summary()));
        }
    }

    private void importSelectedModel(PlatformRuntime runtime, Path source) throws Exception {
        assertTrue(source.startsWith(workspace().resolve("src/app/db/data").toRealPath()), "Use an existing product test runtime");
        try (var saved = PlatformRuntime.open(source.resolve("data"), protection())) {
            saved.security().unlock(PASSWORD.toCharArray());
            var candidates = saved.persistence().aiProfiles().configuredFor(AiPurposeType.APPROVAL);
            assertFalse(candidates.isEmpty());
            var model = candidates.getFirst();
            assertEquals("live-approval", model.profile().id());
            assertEquals(System.getProperty("managed.approvalCache.expectedEndpoint"), model.profile().endpoint(), "Recheck the explicitly verified destination before opening credentials");
            assertEquals(System.getProperty("managed.approvalCache.expectedModel"), model.profile().model(), "Keep the selected model unchanged");
            try (var sourceOperation = saved.security().enterOperation(); var sourceKeys = saved.security().openCredentials();
                 var destinationOperation = runtime.security().enterOperation(); var destinationKeys = runtime.security().openCredentials()) {
                char[] key = sourceKeys.read(model.profile().credentialKey()).orElseThrow();
                try {
                    destinationKeys.save(model.profile().credentialKey(), key);
                } finally {
                    Arrays.fill(key, '\0');
                }
            }
            runtime.persistence().aiProfiles().saveVerified(model.profile(), model.name(), model.textVerifiedAt().orElseThrow(), AiCapabilityType.TEXT);
            runtime.persistence().aiProfiles().saveOutputLimit(model.profile().id(), saved.persistence().aiProfiles().outputLimit(model.profile().id()).orElseThrow());
            runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment(model.profile().id())));
            System.out.println("APPROVAL_CACHE_MODEL model=" + model.profile().model() + " reasoningEffort=" + model.profile().reasoningEffort());
        }
    }

    private AiRequest capture(ModContext actual, TaskContext task, String revision, int pair) throws Exception {
        var captured = new AtomicReference<AiRequest>();
        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
            if (method.getName().equals("configurationRevision")) return revision;
            if (!method.getName().equals("call")) throw new AssertionError("Unexpected model capability");
            assertNull(captured.getAndSet((AiRequest) args[0]), "Capture must not retry");
            return success(new AiResponse("{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Offline request capture\"}",
                "offline", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
        });
        var noLogs = (LogService) Proxy.newProxyInstance(LogService.class.getClassLoader(), new Class<?>[]{LogService.class},
            (proxy, method, args) -> method.getName().equals("register") ? new LogTarget(UUID.randomUUID()) : null);
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ai")) return ai;
            if (method.getName().equals("logs")) return noLogs;
            if (method.getName().equals("ui")) return actual.ui();
            throw new AssertionError("Only model, locale and diagnostics are permitted");
        });
        var host = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(), new Class<?>[]{InteractionService.class},
            (proxy, method, args) -> {
                throw new AssertionError("No execution approval or host interaction permitted");
            });
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + DeploymentManagedHelperBundle.PATH + "' probe"),
            new byte[0], Duration.ofSeconds(30), 8192)) {
            var intent = RemoteActions.command(new gold.debug.windowstolinux.appapi.server.model.ServerId("cache-fixture-server"), "cache-pair-" + pair, request)
                .withSemantics(new ActionSemantics("deployment.helper.probe.v1", Set.of(new ActionTarget("cache-fixture-server", "deployment-helper", DeploymentManagedHelperBundle.PATH))));
            var refused = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, host, intent, request, task, revision, "AUTOMATIC", true));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", refused.problem().eventCode());
        }
        return Objects.requireNonNull(captured.get());
    }

    private static Map<String, Object> observation(String group, int pair, AiRequest request, OperationResult<AiResponse> result) {
        var row = new LinkedHashMap<String, Object>();
        row.put("group", group);
        row.put("pair", pair);
        row.put("outcome", result.outcome().name());
        row.put("purpose", "APPROVAL");
        row.put("operation", "deployment.helper.probe.v1");
        row.put("requestedMaximumOutputTokens", request.maxOutputTokens().map(Object::toString).orElse("UNKNOWN"));
        row.put("maximumOutputTokens", result.value().flatMap(AiResponse::effectiveMaxOutputTokens).map(Object::toString)
            .orElse(result.problem().map(p -> p.context().getOrDefault("effectiveMaxOutputTokens", "UNKNOWN")).orElse("UNKNOWN")));
        var prefix = new StringBuilder();
        for (var message : request.messages()) {
            if (message.role() != AiRole.SYSTEM) break;
            prefix.append(message.role()).append(':').append(message.text().length()).append(':').append(message.text());
        }
        try {
            row.put("fixedPrefixSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(prefix.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        row.put("requestBytes", request.messages().stream().mapToLong(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum());
        var response = result.value();
        var fields = result.problem().map(Problem::context).orElse(Map.of());
        put(row, "inputTokens", response.flatMap(value -> value.usage().inputTokens()), fields);
        put(row, "outputTokens", response.flatMap(value -> value.usage().outputTokens()), fields);
        put(row, "cachedInputTokens", response.flatMap(value -> value.usage().cachedInputTokens()), fields);
        put(row, "cacheMissInputTokens", response.flatMap(value -> value.usage().cacheMissInputTokens()), fields);
        put(row, "reasoningOutputTokens", response.flatMap(value -> value.usage().reasoningOutputTokens()), fields);
        row.put("finishReason", response.map(AiResponse::finishReason).orElse(fields.getOrDefault("finishReason", "UNKNOWN")));
        return row;
    }

    private static void put(Map<String, Object> row, String key, Optional<Long> value, Map<String, String> problem) {
        if (value.isPresent()) row.put(key, value.orElseThrow());
        else if (problem.getOrDefault(key, "").matches("[0-9]{1,18}")) row.put(key, Long.parseLong(problem.get(key)));
        else row.put(key, "UNKNOWN");
    }

    private static Optional<Long> count(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
    }

    private Map<String, Object> summary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("calls", observations.size());
        for (String group : List.of("A", "B")) {
            var rows = observations.stream().filter(row -> row.get("group").equals(group)).toList();
            var totals = new LinkedHashMap<String, Object>();
            for (String key : List.of("inputTokens", "outputTokens", "cachedInputTokens", "cacheMissInputTokens", "reasoningOutputTokens"))
                totals.put(key, rows.stream().allMatch(row -> count(row, key).isPresent())
                    ? rows.stream().mapToLong(row -> count(row, key).orElseThrow()).sum() : "UNKNOWN");
            summary.put(group, totals);
        }
        summary.put("historicalCacheCause", "UNVERIFIED");
        return summary;
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }
}
