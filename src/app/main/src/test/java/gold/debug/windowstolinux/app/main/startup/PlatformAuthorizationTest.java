package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService.Launch;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformAuthorizationTest {
    @TempDir
    Path root;

    static final ActionTarget APP = new ActionTarget(SERVER.id(), "application", "/srv/app-a");

    static final ActionTarget OTHER = new ActionTarget(SERVER.id(), "application", "/srv/app-b");

    static ActionIntent action(CommandRequest request, String operation, ActionTarget target) {
        return RemoteActions.command(SERVER, "revision", request)
                .withSemantics(new ActionSemantics(operation, Set.of(target)));
    }

    static long prompts(Reviews reviews) {
        return reviews.approved.stream().filter(intent -> intent.type().equals("platform.ssh.execute")).count();
    }

    static OperationResult<Void> repeat(PlatformRuntime.ModCapabilities.RemoteCapabilities capabilities,
            TaskContext task) throws Exception {
        try (var session = capabilities.linux().connect(SERVER, task)) {
            for (int i = 0; i < 3; i++) {
                var command = command("builtin printf 'restart app-a'");
                var grant = capabilities.interaction().approve(action(command, "application.restart", APP), task)
                        .value().orElseThrow();
                assertEquals("restart app-a", session.execute(command, grant).value().orElseThrow().stdout());
                assertThrows(ApiException.class, () -> session.execute(command, grant));
            }
        }
        return success(null);
    }

    @Test
    void realSshWorkflowUsesThreeOneZeroPromptsAndEachNewWorkflowStartsFresh() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                for (var mode : AuthorizationMode.values()) {
                    runtime.authorization().saveMode(mode);
                    long before = prompts(reviews);
                    assertEquals(Outcome.SUCCEEDED,
                            result(capabilities.launcher().submit(
                                    new Launch<>(request("server"), Set.of(APP), task -> repeat(capabilities, task))))
                                    .outcome());
                    assertEquals(mode == AuthorizationMode.ONCE ? 3 : mode == AuthorizationMode.GENERAL ? 1 : 0,
                            prompts(reviews) - before);
                }
                assertEquals(9, remote.commands.get());
                assertEquals(1,
                        reviews.approved.stream().filter(intent -> intent.type().equals("platform.ssh.trust")).count());
                runtime.authorization().saveMode(AuthorizationMode.GENERAL);
                for (int i = 0; i < 2; i++) {
                    long before = prompts(reviews);
                    assertEquals(Outcome.SUCCEEDED,
                            result(capabilities.launcher().submit(
                                    new Launch<>(request("server"), Set.of(APP), task -> repeat(capabilities, task))))
                                    .outcome());
                    assertEquals(1, prompts(reviews) - before);
                }
                assertEquals(15, remote.commands.get());
            }
        }
    }

    @Test
    void explicitTargetsOperationMeaningAndExactGrantPreventScopeExpansion() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = capabilities.launcher()
                        .submit(new Launch<>(request("server"), Set.of(APP, OTHER), task -> {
                            try (var session = capabilities.linux().connect(SERVER, task)) {
                                var command = command("builtin printf 'exact'");
                                var grant = capabilities.interaction()
                                        .approve(action(command, "application.restart", APP), task).value()
                                        .orElseThrow();
                                assertThrows(ApiException.class,
                                        () -> session.execute(command("builtin printf 'changed'"), grant));
                                assertThrows(ApiException.class,
                                        () -> session.execute(command, new ApprovalGrant(grant.id())));
                                assertEquals(0, remote.commands.get());
                                assertEquals(Outcome.SUCCEEDED, session.execute(command, grant).outcome());
                                for (var next : List.of(action(command, "application.restart", OTHER),
                                        action(command, "application.stop", APP))) {
                                    var nextGrant = capabilities.interaction().approve(next, task).value()
                                            .orElseThrow();
                                    assertEquals(Outcome.SUCCEEDED, session.execute(command, nextGrant).outcome());
                                }
                                assertEquals(3, prompts(reviews));
                                for (var target : List.of(new ActionTarget(SERVER.id(), "application", "/srv/app-c"),
                                        new ActionTarget("foreign-server", "application", APP.id()))) {
                                    var denied = assertThrows(ApiException.class, () -> capabilities.interaction()
                                            .approve(action(command, "application.restart", target), task));
                                    assertEquals("AUTHORIZATION_TARGET", denied.problem().eventCode());
                                }
                                assertEquals(3, prompts(reviews));
                                return success(null);
                            }
                        }));
                assertEquals(Outcome.SUCCEEDED, result(handle).outcome());
                assertEquals(3, remote.commands.get());
            }
        }
    }

    @Test
    void preferenceChangesDoNotAlterRunningScopeAndFullDoesNotAuthorizeOrdinaryTasks() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                runtime.authorization().saveMode(AuthorizationMode.ONCE);
                assertEquals(Outcome.SUCCEEDED,
                        result(capabilities.launcher().submit(new Launch<>(request("server"), Set.of(APP), task -> {
                            runtime.authorization().saveMode(AuthorizationMode.FULL);
                            return repeat(capabilities, task);
                        }))).outcome());
                assertEquals(3, prompts(reviews));
                assertEquals(Outcome.SUCCEEDED, result(runtime.tasks().bind(OWNER, VERSION, false)
                        .submit(request("server"), task -> repeat(capabilities, task))).outcome());
                assertEquals(6, prompts(reviews));
                assertEquals(Outcome.SUCCEEDED,
                        result(capabilities.launcher().submit(new Launch<>(request("server"), Set.of(APP), task -> {
                            try (var session = capabilities.linux().connect(SERVER, task)) {
                                var command = command("builtin printf unclassified");
                                var grant = capabilities.interaction()
                                        .approve(RemoteActions.command(SERVER, "revision", command), task).value()
                                        .orElseThrow();
                                assertEquals(Outcome.SUCCEEDED, session.execute(command, grant).outcome());
                            }
                            return repeat(capabilities, task);
                        }))).outcome());
                assertEquals(7, prompts(reviews));
                assertEquals(10, remote.commands.get());
            }
        }
    }

    @Test
    void rejectedReviewIsNotCachedAndConcurrentEquivalentReviewsShareOnlyFirstApproval() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = capabilities.launcher().submit(new Launch<>(request("server"), Set.of(APP), task -> {
                    try (var session = capabilities.linux().connect(SERVER, task);
                            var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                        var command = command("builtin printf accepted");
                        var intent = action(command, "application.restart", APP);
                        reviews.policy = ignored -> false;
                        assertEquals(Outcome.CANCELLED, capabilities.interaction().approve(intent, task).outcome());
                        reviews.policy = ignored -> true;
                        var start = new CountDownLatch(1);
                        var futures = new ArrayList<Future<ApprovalGrant>>();
                        for (int i = 0; i < 3; i++)
                            futures.add(workers.submit(() -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return capabilities.interaction().approve(intent, task).value().orElseThrow();
                            }));
                        start.countDown();
                        var grants = new HashSet<ApprovalGrant>();
                        for (var future : futures) {
                            var grant = future.get(8, TimeUnit.SECONDS);
                            assertTrue(grants.add(grant));
                            assertEquals(Outcome.SUCCEEDED, session.execute(command, grant).outcome());
                        }
                        assertEquals(2, prompts(reviews));
                        return success(null);
                    }
                }));
                assertEquals(Outcome.SUCCEEDED, result(handle).outcome());
                assertEquals(3, remote.commands.get());
            }
        }
    }

    @Test
    void fullWorkflowStillRequiresFileConflictChoiceAndRejectsTargetsOutsideLaunch() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.authorization().saveMode(AuthorizationMode.FULL);
            Files.writeString(remote.files.resolve("file"), "old");
            var source = Files.writeString(root.resolve("source"), "new");
            var choices = new java.util.concurrent.atomic.AtomicInteger();
            var reviews = new Reviews();
            reviews.conflictPolicy = conflict -> {
                choices.incrementAndGet();
                return TransferConflictChoice.TERMINATE;
            };
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var handle = capabilities.launcher().submit(new Launch<>(request("server"), Set.of(APP), task -> {
                    assertThrows(ApiException.class, () -> capabilities.interaction()
                            .approve(action(command("true"), "application.restart", OTHER), task));
                    try (var session = capabilities.linux().connect(SERVER, task)) {
                        var transfer = new TransferRequest(source, "/file", 3);
                        var grant = capabilities.interaction()
                                .approve(RemoteActions
                                        .transfer(SERVER, "revision", transfer,
                                                digest("new".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true)
                                        .withSemantics(new ActionSemantics("application.upload", Set.of(APP))), task)
                                .value().orElseThrow();
                        assertEquals(Outcome.CANCELLED, session.upload(transfer, grant).outcome());
                    }
                    return success(null);
                }));
                assertEquals(Outcome.SUCCEEDED, result(handle).outcome());
                assertEquals(1, choices.get());
                assertEquals("old", Files.readString(remote.files.resolve("file")));
                assertEquals(0, reviews.approved.stream().filter(intent -> intent.type().equals("platform.sftp.upload"))
                        .count());
                assertEquals(1,
                        reviews.approved.stream().filter(intent -> intent.type().equals("platform.ssh.trust")).count());
            }
        }
    }

    @Test
    void retainedOriginalTaskRecoveryCannotReviveFinishedWorkflowPermission() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.authorization().saveMode(AuthorizationMode.FULL);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var grant = new java.util.concurrent.atomic.AtomicReference<ApprovalGrant>();
                var lease = new java.util.concurrent.atomic.AtomicReference<ResourceLease>();
                var intent = action(command("true"), "application.restart", APP);
                var handle = capabilities.launcher().submit(new Launch<>(request("server"), Set.of(APP), task -> {
                    grant.set(capabilities.interaction().approve(intent, task).value().orElseThrow());
                    lease.set(task.retain("fixture", "retained", "Actual retained cleanup"));
                    return success(null);
                }));
                state(handle, TaskState.FINISHING);
                var interaction = (gold.debug.windowstolinux.app.service.task.PlatformInteractionService) capabilities
                        .interaction();
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                    assertEquals("WORKFLOW_SCOPE_ENDED",
                            assertThrows(ApiException.class, () -> interaction.reviewed(grant.get(), task)).problem()
                                    .eventCode());
                    var renewed = interaction.approve(intent, task).value().orElseThrow();
                    interaction.consume(renewed, intent, task);
                    lease.get().close();
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture()
                            .get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(1, prompts(reviews));
                capabilities.launcher().close();
                assertThrows(ApiException.class, () -> capabilities.launcher()
                        .submit(new Launch<>(request("server"), Set.of(APP), task -> success(null))));
            }
        }
    }

    @Test
    void preferenceSurvivesActualReopenIndependentlyOfAiAndInvalidDataDoesNotGrantFullAccess() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            assertEquals(AuthorizationMode.GENERAL, runtime.authorization().mode());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM desktop_preference WHERE preference_key='platform_authorization_mode'"));
            runtime.persistence().preferences().save("deploy_approval", "MANUAL_REVIEW");
            runtime.authorization().saveMode(AuthorizationMode.FULL);
            assertEquals("MANUAL_REVIEW", runtime.persistence().preferences().find("deploy_approval").orElseThrow());
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            assertThrows(gold.debug.windowstolinux.app.service.contract.result.ApplicationServiceException.class,
                    runtime.authorization()::mode);
            runtime.security().unlock(PASSWORD.toCharArray());
            assertEquals(AuthorizationMode.FULL, runtime.authorization().mode());
            runtime.persistence().preferences().save("platform_authorization_mode", "invalid");
            assertThrows(SQLException.class, runtime.authorization()::mode);
            assertEquals("invalid",
                    runtime.persistence().preferences().find("platform_authorization_mode").orElseThrow());
            runtime.authorization().saveMode(AuthorizationMode.GENERAL);
            assertEquals("MANUAL_REVIEW", runtime.persistence().preferences().find("deploy_approval").orElseThrow());
        }
    }
}
