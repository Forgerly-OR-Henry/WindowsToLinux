package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.BoundedSourceChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedAnalysis;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Three independent source-advice tasks with the production parser and no Linux capability. */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentAssistedAnalysisLiveTest extends DeploymentStaticLiveAcceptanceTest {
    @Test
    void analyzesThreeSurveySourcesWithoutSsh() throws Exception {
        var enabled = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField("aiEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(this, true);
        root = workspace().resolve("src/app/db/data/ai-live-analysis-survey-" + Long.toUnsignedString(System.nanoTime(), 36));
        enable();
        var sshAccesses = new AtomicInteger();
        var outcomes = new ArrayList<TaskResult>();
        try (var runtime = open()) {
            start(runtime, true);
            for (String name : List.of("saveServer", "configureLiveModel")) {
                var method = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod(name, PlatformRuntime.class);
                method.setAccessible(true);
                method.invoke(this, runtime);
            }
            var monitor = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class,
                gold.debug.windowstolinux.app.config.AppConfig.Ai.class);
            monitor.setAccessible(true);
            var actual = (ModContext) monitor.invoke(this, runtime.modContexts().get(OWNER).context(), runtime.startup().config().ai());
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("linux")) {
                        sshAccesses.incrementAndGet();
                        throw new AssertionError("API-only source analysis cannot request Linux");
                    }
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            for (int sample = 1; sample <= 3; sample++) {
                var handle = context.tasks().submit(new TaskRequest("assisted-source-analysis-api",
                    "Analyze frozen Survey source technical inputs; no deployment or remote permission", List.of("static-live"), true), task -> {
                    Path source = workspace().resolve("test/multi-language/success-survey-scoring");
                    var rejections = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason>();
                    var inspected = new BoundedSourceChecker().inspect(source, rejections, source.getFileName().toString());
                    assertTrue(rejections.isEmpty(), rejections::toString);
                    var names = inspected.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).sorted().toList();
                    var selection = context.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceMaterial(source.getFileName().toString(),
                        context.file().snapshots().snapshot(selection, new SnapshotRequest(names, 16 * 1024 * 1024), task), names, List.of())) {
                        var analysis = new DeploymentAssistedAnalysis(context,
                            new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()),
                            context.ai().configurationRevision(gold.debug.windowstolinux.appapi.ai.model.AiPurpose.LANGUAGE, task));
                        var advice = analysis.analyze(List.of(
                                new DeploymentReviewField("frontend/version", "Node version", "", "", List.of("24.22.0")),
                                new DeploymentReviewField("frontend/workspaceMinimumBytes", "Build minimum bytes", "", "", List.of()),
                                new DeploymentReviewField("frontend/workspacePreferredBytes", "Build preferred bytes", "", "", List.of())),
                            Map.of("phase", "WORKSPACE_BUDGET_BEFORE_FINAL_CONFIRMATION"), task);
                        assertEquals("24.22.0", advice.values().get("frontend/version"));
                        assertTrue(advice.locations().keySet().stream().allMatch(key -> key.matches("source/[0-9]+")));
                        assertFalse(advice.locations().isEmpty());
                        System.out.println("ASSISTED_ANALYSIS_API_ADVICE task=" + task.id() + " fields=" + advice.values().keySet()
                            + " evidenceCount=" + advice.locations().size());
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
                System.out.println("ASSISTED_ANALYSIS_API_TASK sample=" + sample + " task=" + handle.id() + " state=" + root);
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.MINUTES);
                outcomes.add(result.outcome());
                System.out.println("ASSISTED_ANALYSIS_API_RESULT sample=" + sample + " outcome=" + result.outcome() + " problem=" + result.problem());
            }
        }
        assertEquals(0, sshAccesses.get());
        assertTrue(outcomes.stream().allMatch(result -> result == TaskResult.SUCCEEDED), outcomes::toString);
    }
}
