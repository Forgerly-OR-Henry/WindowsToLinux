package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.BoundedSourceChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.MavenBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Three independent production reviews per case; no SSH capability, and grants never leave their fresh workflow. */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentAssistedApprovalBudgetLiveTest extends DeploymentStaticLiveAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();

    private final List<Map<String, Object>> observations = new ArrayList<>();

    private final AtomicInteger sshAccesses = new AtomicInteger();

    @ParameterizedTest
    @ValueSource(strings = {"task-board", "survey-scoring"})
    void reviewsThreeIndependentTasksWithoutSsh(String project) throws Exception {
        var enabled = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField("aiEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(this, true);
        root = workspace().resolve("src/app/db/data/ai-live-budget-" + project + "-" + Long.toUnsignedString(System.nanoTime(), 36));
        enable();
        var results = new ArrayList<Map<String, Object>>();
        try (var runtime = open()) {
            start(runtime, true);
            for (String name : List.of("saveServer", "configureLiveModel")) {
                var configure = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod(name, PlatformRuntime.class);
                configure.setAccessible(true);
                configure.invoke(this, runtime);
            }
            var monitor = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class,
                gold.debug.windowstolinux.app.config.AppConfig.Ai.class);
            monitor.setAccessible(true);
            var actual = (ModContext) monitor.invoke(this, runtime.modContexts().get(OWNER).context(), runtime.startup().config().ai());
            var context = noRemoteContext(actual, project);
            for (int sample = 1; sample <= 3; sample++) {
                var handle = context.tasks().submit(new TaskRequest("assisted-api-budget-" + project,
                        "Independent API-only representative approval; no server command or deployment", List.of("static-live"), true),
                    task -> review(context, project, task));
                System.out.println("ASSISTED_API_REVIEW_TASK kind=" + project + " sample=" + sample + " task=" + handle.id() + " state=" + root);
                var result = handle.completion().toCompletableFuture().get(22, TimeUnit.MINUTES);
                var calls = observations.stream().filter(row -> row.get("task").equals(handle.id().toString())).toList();
                boolean known = !calls.isEmpty() && calls.stream().allMatch(row -> Boolean.TRUE.equals(row.get("usageKnown")));
                long output = calls.stream().mapToLong(row -> ((Number) row.get("outputTokens")).longValue()).sum();
                boolean passed = result.outcome() == TaskResult.SUCCEEDED && known && output <= 24576 && sshAccesses.get() == 0;
                var row = Map.<String, Object>of("project", project, "sample", sample, "task", handle.id().toString(), "state", root.toString(),
                    "outcome", result.outcome().name(), "outputTokens", output, "usageKnown", known, "gatePassed", passed, "calls", calls);
                results.add(row);
                System.out.println("ASSISTED_API_BUDGET_RESULT " + json.writeValueAsString(row));
            }
            Path directory = Path.of(System.getProperty("managed.live.deliveryDirectory"));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(project + "-api.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results),
                StandardOpenOption.CREATE_NEW);
            assertEquals(0, sshAccesses.get());
            assertTrue(results.stream().allMatch(row -> Boolean.TRUE.equals(row.get("gatePassed"))), results::toString);
        }
    }

    @Test
    void reviewsThreeNode24TasksWithoutSsh() throws Exception {
        reviewsThreeIndependentTasksWithoutSsh("survey-scoring");
    }

    private OperationResult<Void> review(ModContext context, String project, TaskContext task) {
        var server = new ServerId("static-live");
        try {
            String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
            var scope = new DeploymentAssistedScope(context, task, revision, "AUTOMATIC", true);
            var layout = DeploymentRemoteLayout.original();
            try (var authority = DeploymentWorkflowAuthorization.open(context, task, server, revision, "AUTOMATIC", true,
                "Deploy selected " + project + " on the selected server, including official prerequisite resolution/download/digest checks/managed installation or guarded SYSTEM reuse, restricted non-root candidate build with dependency hooks and candidate-local Maven bootstrap. This fresh API-only representative review authorizes no remote dispatch; candidate and live prerequisite presence are not claimed; representative Java binding values come from the retained firstpass receipt, not a fresh preparation observation. The helper must recheck them if executed. No grant from this task may be reused by deployment.")) {
                scope.workflowOpened(authority);
                Path source = workspace().resolve("test/multi-language/success-" + project);
                var rejections = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason>();
                var inspected = new BoundedSourceChecker().inspect(source, rejections, source.getFileName().toString());
                assertTrue(rejections.isEmpty(), rejections::toString);
                var names = inspected.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).sorted().toList();
                var selection = context.file().sources().selectDirectory(source, task);
                try (var material = new DeploymentSourceMaterial(source.getFileName().toString(),
                    context.file().snapshots().snapshot(selection, new SnapshotRequest(names, 16 * 1024 * 1024), task), names, List.of());
                     var archive = new DeploymentSourceArchiver(context.file()).prepare(material, project.equals("task-board") ? "backend" : "frontend", 16 * 1024 * 1024, task)) {
                    scope.sourceCaptured(server, material, task);
                    scope.archiveCaptured(material, archive, new gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.toolchain.ToolchainSpecChecker()
                        .inspect(material.snapshot().directory().resolve(archive.relativeRoot())), task);
                    String application = "wtl-api-" + project + "-" + task.id().toString().substring(0, 8);
                    try (var request = project.equals("task-board") ? maven(material, archive, application, layout, server) : node(layout)) {
                        var workspace = new DeploymentRemoteWorkspace(application, archive.sha256(), layout);
                        var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(
                            project.equals("task-board") ? "deployment.build.run.v1" : "deployment.toolchain.prepare.v1",
                            Set.of(new ActionTarget(server.id(), "deployment-build", project.equals("task-board") ? workspace.candidateRoot() : layout.toolchains()))));
                        var result = ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, request, task);
                        // The unused host grant remains inside this scope; no Linux/session capability is requested.
                        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
                    }
                }
            }
        } catch (Exception | AssertionError failure) {
            var problem = failure instanceof ApiException api ? api.problem() : new Problem(ProblemCode.INVALID_INPUT,
                "ASSISTED_API_BUDGET_FIXTURE_FAILED", "API-only review failed without remote dispatch",
                Map.of("causeType", failure.getClass().getSimpleName()), Optional.empty());
            System.out.println("ASSISTED_API_REVIEW_FAILURE task=" + task.id() + " problem=" + problem + " cause=" + failure);
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem));
        }
    }

    static CommandRequest maven(DeploymentSourceMaterial material, DeploymentSourceArchive archive, String application,
                                DeploymentRemoteLayout layout, ServerId server) throws Exception {
        var facts = new DeploymentProjectFacts(material.snapshot().directory().resolve("backend"), application,
            DeploymentProjectKind.SPRING_BOOT, DeploymentBuildToolType.MAVEN, List.of(), List.of(), List.of())
            .withToolchains(new gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.toolchain.ToolchainSpecChecker().inspect(material.snapshot().directory().resolve("backend")));
        var limits = DeploymentConfigLoader.current().build().limits();
        var workspace = new DeploymentRemoteWorkspace(application, archive.sha256(), layout);
        var runtime = new DeploymentRuntimeDefinition.SpringBoot("21", new DeploymentHealthCheck.Process(5, 1));
        var requirement = facts.toolchainRequirements().stream().filter(value -> value.ecosystem()
            == gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.JAVA).findFirst().orElseThrow();
        // Representative literal binding from retained task-board-01 receipt, never evidence of current live preparation.
        var tools = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains(
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.defaults().revision(), List.of(
            new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.Selection(requirement,
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(requirement.ecosystem(), "21.0.12.1+1").orElseThrow(),
                layout.toolchains() + "/versions/java-0e6db83ffe5fbb34adf155ce772477836ca6b5db1fd84531b1d200ef575fdd84",
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.OriginType.SYSTEM,
                "system:/usr/lib/jvm/java-21-openjdk-amd64/bin/java", "244c33fad7a87b0b26578180fd95a1fa8bf8008540221712258a4f778502bb8c")));
        String binding = Base64.getEncoder().encodeToString(gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.encode(tools).getBytes(StandardCharsets.UTF_8));
        var reviewed = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest(server, facts, material, archive,
            gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot.create(application, 1, "api-only", java.time.Instant.EPOCH, List.of()),
            List.of(), new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(List.of(), Optional.of(List.of())),
            runtime, Optional.empty(), limits,
            new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent(application, server.id(), material.snapshot().sha256(), archive.sha256(), java.time.Instant.now()), false, true, layout);
        var assembler = Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardBuildRunner")
            .getDeclaredMethod("script", String.class, reviewed.getClass(), gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildEnvironment.class, tools.getClass());
        assembler.setAccessible(true);
        String body = (String) assembler.invoke(null, new MavenBuildWriter().render(facts, runtime, workspace, limits), reviewed,
            new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildEnvironment(application, Map.of()), tools);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var markers = Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.BuildMatchPolicy").getDeclaredMethod("groups");
        markers.setAccessible(true);
        var groups = (List<List<String>>) markers.invoke(null);
        assertFalse(groups.isEmpty());
        assertTrue(groups.stream().allMatch(group -> !group.isEmpty()));
        String collector = DeploymentBuildOutputProtocol.script(workspace, limits.timeoutSeconds(), limits.maxProcesses(),
            limits.maxMemoryMiB(), limits.maxOutputBytes(), "ordinary", digest, binding, groups);
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, layout), bytes,
            Duration.ofSeconds(limits.timeoutSeconds() + 15L), 65536);
    }

    static CommandRequest node(DeploymentRemoteLayout layout) {
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' prepare-toolchains 'prepare' 'NODE' '24' '-' '1777' '-'", layout), new byte[0], Duration.ofSeconds(1792), 65536);
    }

    private ModContext noRemoteContext(ModContext actual, String project) {
        var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
            if (!method.getName().equals("call")) return invoke(method, actual.ai(), args);
            var request = Arrays.stream(args).filter(AiRequest.class::isInstance).map(AiRequest.class::cast).findFirst().orElseThrow();
            var task = Arrays.stream(args).filter(TaskContext.class::isInstance).map(TaskContext.class::cast).findFirst().orElseThrow();
            assertEquals(AiPurpose.APPROVAL, request.purpose());
            assertEquals(32768, request.maxOutputTokens().orElseThrow());
            assertEquals(Duration.ofSeconds(600), request.timeout());
            var result = (OperationResult<AiResponse>) invoke(method, actual.ai(), args);
            var attempts = result.value().map(AiResponse::generationAttempts).orElseGet(() -> result.problem().map(AiGenerationAttempt::fromProblem).orElse(List.of()));
            boolean known = !attempts.isEmpty() && attempts.stream().allMatch(attempt -> attempt.usage().outputTokens().isPresent());
            long output = attempts.stream().mapToLong(attempt -> attempt.usage().outputTokens().orElse(0L)).sum();
            var row = Map.<String, Object>of("task", task.id().toString(), "outputTokens", output, "usageKnown", known,
                "attempts", AiGenerationAttempt.attach(new Problem(ProblemCode.UNAVAILABLE, "API_TRACE", "Physical attempts", Map.of(), Optional.empty()), attempts).context(), "finish", result.value().map(AiResponse::finishReason).orElse("UNKNOWN"));
            observations.add(row);
            System.out.println("ASSISTED_API_PHYSICAL_CALL " + json.writeValueAsString(row));
            if (project.equals("survey-scoring"))
                assertTrue(attempts.stream().allMatch(attempt -> attempt.encodedRequestBytes() <= 40 * 1024));
            return result;
        });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ai")) return ai;
            if (method.getName().equals("linux")) {
                sshAccesses.incrementAndGet();
                throw new AssertionError("API-only review must never access Linux/SSH");
            }
            return invoke(method, actual, args);
        });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
