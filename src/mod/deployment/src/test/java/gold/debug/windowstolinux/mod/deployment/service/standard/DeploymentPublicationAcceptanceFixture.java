package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.run.ModRuntimeSession;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shares actual source-backed publication input, never lifecycle assertions. / 共用真实来源发布输入，不替代生命周期断言。 */
abstract class DeploymentPublicationAcceptanceFixture extends DeploymentRuntimeFixture {
    /** Waits only for host refusal before any reconciliation dispatch, within the original bound. / 仅等待未派发的宿主拒绝结束，不扩大原限时。 */
    protected static TaskSnapshot reconcileReady(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            long remaining = until - System.nanoTime();
            assertTrue(remaining > 0, "The original callback must return within the existing reconciliation bound");
            try {
                return runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
            } catch (ApiException refusal) {
                if (refusal.problem().code() != ProblemCode.BUSY || !refusal.problem().eventCode().equals("TASK_RECOVERY_BUSY"))
                    throw refusal;
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                Thread.sleep(20);
            }
        }
    }

    protected String technicalVersion = "1.24";

    protected boolean allowCorrection;

    protected int corrections;

    protected java.util.function.Consumer<ActionIntent> approval = ignored -> {
    };

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                                                           TaskSnapshot task, CancelToken cancel) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                                                       CancelToken cancel) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                return "Transferred " + progress.completedBytes();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancel) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                approval.accept(intent);
                return true;
            }
        };
    }

    protected DeploymentStaticWorkflow.Reviewer reviewer(int count) {
        return new DeploymentStaticWorkflow.Reviewer() {
            public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String sha,
                                                                              List<DiscoveredProjectComponent> components, TaskContext task) {
                assertEquals(count, components.size());
                var choices = new LinkedHashMap<String, DeploymentStaticWorkflow.Choice>();
                components.forEach(component -> choices.put(component.id(),
                    new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())));
                return success(new DeploymentStaticWorkflow.Selection(
                    count == 1 ? components.getFirst().id() : "running-application", choices,
                    count == 1 ? Optional.empty() : Optional.of(components.getFirst().id())));
            }

            public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server, String sha,
                                                                                         List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                var answers = new LinkedHashMap<String, DeploymentStaticWorkflow.Answers>();
                int port = 8080;
                for (var prompt : prompts) {
                    var values = new LinkedHashMap<>(prompt.defaults());
                    values.put("primary", prompt.componentId());
                    values.put("secondary", "main.go");
                    values.put("version", technicalVersion);
                    values.put("jvmTarget", "");
                    values.put("port", Integer.toString(port++));
                    values.put("healthMode", "TCP");
                    values.put("exposure", "INTERNAL");
                    answers.put(prompt.componentId(), new DeploymentStaticWorkflow.Answers(values, List.of(),
                        new DeploymentBuildLimits(600, 32, 512, 65536, 128L * 1024 * 1024, false), false, true));
                }
                return success(answers);
            }

            public OperationResult<DeploymentStaticWorkflow.Answers> reviewBuildCorrection(ServerRef server, String sha,
                                                                                           DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers original,
                                                                                           TaskContext task) {
                assertTrue(allowCorrection, "Unknown health cannot reopen technical inputs");
                corrections++;
                var values = new LinkedHashMap<>(original.values());
                values.put("version", "1.25");
                return success(new DeploymentStaticWorkflow.Answers(values, original.files(), original.limits(),
                    original.containerRisk(), original.adapterRisk()));
            }

            public OperationResult<Boolean> confirm(ServerRef server, String sha, Map<String, String> archives,
                                                    Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                    Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStaticWorkflow.GitReview> git,
                                                    TaskContext task) {
                assertEquals(count, archives.size());
                return success(true);
            }
        };
    }

    protected void source(int count) throws Exception {
        for (int index = 0; index < count; index++) {
            var directory = count == 1
                ? selectedSourceDirectory()
                : selectedSourceDirectory().resolve("component" + index);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("go.mod"), "module example.test/component" + index + "\n\ngo 1.24\n");
            Files.writeString(directory.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(directory.resolve("main.go"), "package main\nfunc main() {}\n");
            Files.writeString(directory.resolve("windowstolinux-application.properties"),
                "version=1\nhealth.mode=TCP\n");
        }
    }

    protected static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
