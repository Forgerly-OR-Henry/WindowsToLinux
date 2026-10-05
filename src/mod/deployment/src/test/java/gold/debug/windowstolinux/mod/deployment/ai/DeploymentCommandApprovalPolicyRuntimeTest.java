package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentProjectChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import org.apache.sshd.server.channel.ChannelSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual root tasks, model HTTP approval and SSH grant consumption; no installer runs. */
class DeploymentCommandApprovalPolicyRuntimeTest extends DeploymentRuntimeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ServerId SERVER = new ServerId("hd16-server");

    private static final String OPERATION = "deployment.publication.candidate-cleanup.v1";

    private Peer active;

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice transferConflict(gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict conflict, ServerId server, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No transfer expected");
            }

            public gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice transferStalled(gold.debug.windowstolinux.appapi.linux.transfer.TransferStall stall, ServerId server, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No transfer stall expected");
            }

            public String transferProgress(gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress progress) {
                throw new AssertionError("No transfer progress expected");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                if (active == null) throw new AssertionError("Real peer must be bound before interaction");
                active.humans.add(intent);
                if (intent.type().equals("platform.ssh.execute")) {
                    assertEquals(intent.exactAction(), active.approvedActions.getLast(), "Independent AI must finish before the host question");
                    active.events.add("HUMAN:" + digest(intent.exactAction()));
                    return !active.rejectCommands;
                }
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No secret dialog expected");
            }
        };
    }

    static ActionIntent intent(CommandRequest request, String revision, String operation) {
        return RemoteActions.command(SERVER, revision, request).withSemantics(new ActionSemantics(operation,
            Set.of(new ActionTarget(SERVER.id(), "deployment-directory", "/srv/hd16/owned-parent"))));
    }

    static CommandRequest command(String step) {
        return new CommandRequest("builtin printf '" + step + "'", new byte[0], Duration.ofSeconds(5), 8192);
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }

    void configure(PlatformRuntime runtime, Peer peer) throws Exception {
        active = peer;
        runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(SERVER.id(), "127.0.0.1",
            peer.ssh.server().getPort(), "tester", "hd16-ssh")), "fixture-secret".toCharArray());
        try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) {
            credentials.save("hd16-model", "fixture-model-key".toCharArray());
        }
        for (String role : List.of("language", "approval")) {
            runtime.persistence().aiProfiles().saveVerified(new StoredAiModelProfile(role, peer.endpoint(),
                "fixture/" + role, "hd16-model"), role, Instant.now(), AiCapabilityType.TEXT);
            runtime.persistence().aiProfiles().saveOutputLimit(role, 1600);
        }
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("language", true)));
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("approval", true)));
    }

    void await(TaskHandle<?> handle, Peer peer, AtomicReference<Throwable> callbackFailure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!handle.completion().toCompletableFuture().isDone() && handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
            Thread.sleep(10);
        if (callbackFailure.get() != null)
            throw new AssertionError("Actual original root assertion failed: " + handle.snapshot(), callbackFailure.get());
        peer.healthy();
        assertEquals(TaskState.ENDED, handle.snapshot().state(), handle.snapshot().toString());
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
    }

    @ParameterizedTest
    @CsvSource({"MANUAL_REVIEW,ONCE", "MANUAL_REVIEW,GENERAL", "AUTOMATIC,ONCE", "AUTOMATIC,GENERAL", "AUTOMATIC,FULL", "FULL_CONTROL,GENERAL", "FULL_CONTROL,FULL"})
    void sevenBusinessCombinationsAlwaysReviewActualCommandsAndReuseOnlyHumanQuestions(String approval, String authorization) throws Exception {
        enable();
        try (var peer = new Peer(root.resolve("peer")); var runtime = open()) {
            configure(runtime, peer);
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("hd16-contract", "Actual command authorization policies", List.of(SERVER.id()), false), task -> {
                try {
                    String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    try (var workflow = DeploymentWorkflowAuthorization.open(context, task, SERVER, revision, approval, authorization, "source=original;target=owned-parent");
                         var session = context.linux().connect(SERVER, task)) {
                        for (int step = 0; step < 4; step++) {
                            try (var request = command("step-" + step)) {
                                var actual = intent(request, "business-" + step, OPERATION);
                                peer.expect(actual, request, step < 2 ? "NORMAL" : "HIGH");
                                if (step >= 2 && approval.equals("AUTOMATIC") && authorization.equals("FULL")) {
                                    assertThrows(DeploymentLowRiskRequiredException.class,
                                        () -> DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, approval, authorization));
                                } else {
                                    var grant = DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, approval, authorization).value().orElseThrow();
                                    assertEquals(TaskResult.SUCCEEDED, session.execute(request, grant).outcome());
                                }
                            }
                        }
                        int expectedQuestions = approval.equals("MANUAL_REVIEW") ? authorization.equals("ONCE") ? 4 : 1
                            : approval.equals("AUTOMATIC") ? authorization.equals("ONCE") ? 2 : authorization.equals("GENERAL") ? 1 : 0 : 0;
                        assertEquals(expectedQuestions, peer.commandQuestions());
                        assertEquals(approval.equals("MANUAL_REVIEW") || authorization.equals("FULL") ? 0 : 1, peer.handoffs());
                        assertEquals(4, peer.approvedActions.size(), "Every distinct actual command requires a fresh independent approval");
                        assertEquals(4, new HashSet<>(peer.approvedActions).size());
                        if (approval.equals("AUTOMATIC") && authorization.equals("FULL")) {
                            assertEquals(2, peer.sends.size(), "HIGH proposals are known unsent refusals");
                            try (var replacement = command("different-low-plan")) {
                                var actual = intent(replacement, "replacement", OPERATION);
                                peer.expect(actual, replacement, "NORMAL");
                                var grant = DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, replacement, task, revision, approval, authorization).value().orElseThrow();
                                assertEquals(TaskResult.SUCCEEDED, session.execute(replacement, grant).outcome());
                            }
                            assertEquals(5, peer.approvedActions.size());
                            assertEquals(3, peer.sends.size());
                        } else assertEquals(4, peer.sends.size());
                    }
                    return success(null);
                } catch (Throwable error) {
                    failure.set(error);
                    throw error;
                }
            });
            await(handle, peer, failure);
            peer.assertSendOrdering();
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            active = null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ONCE", "GENERAL", "FULL"})
    void automaticHighRefusalNeverSendsOrCachesDenialAndDifferentLowProposalNeedsAnotherAiReview(String authorization) throws Exception {
        enable();
        try (var peer = new Peer(root.resolve("peer")); var runtime = open()) {
            configure(runtime, peer);
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("hd16-refusal", "Actual high refusal", List.of(SERVER.id()), false), task -> {
                try {
                    String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    try (var workflow = DeploymentWorkflowAuthorization.open(context, task, SERVER, revision, "AUTOMATIC", authorization, "original-high-proposal");
                         var session = context.linux().connect(SERVER, task)) {
                        peer.rejectCommands = true;
                        for (int step = 0; step < 2; step++)
                            try (var request = command("denied-high-" + step)) {
                                var actual = intent(request, "high-" + step, OPERATION);
                                peer.expect(actual, request, "HIGH");
                                var rejected = assertThrows(DeploymentLowRiskRequiredException.class, () -> DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, "AUTOMATIC", authorization));
                                assertEquals("DEPLOYMENT_LOWER_RISK_REQUIRED", rejected.problem().eventCode());
                            }
                        assertEquals(0, peer.sends.size());
                        assertEquals(authorization.equals("FULL") ? 0 : 2, peer.commandQuestions(), "A refusal never establishes GENERAL reuse");
                        assertFalse(task.cancelToken().requested());
                        try (var request = command("new-low-alternative")) {
                            var actual = intent(request, "low-new-plan", OPERATION);
                            peer.expect(actual, request, "NORMAL");
                            var grant = DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, "AUTOMATIC", authorization).value().orElseThrow();
                            assertEquals(TaskResult.SUCCEEDED, session.execute(request, grant).outcome());
                        }
                        assertEquals(3, peer.approvedActions.size());
                        assertEquals(1, peer.sends.size());
                    }
                    return success(null);
                } catch (Throwable error) {
                    failure.set(error);
                    throw error;
                }
            });
            await(handle, peer, failure);
            peer.assertSendOrdering();
        } finally {
            active = null;
        }
    }

    @Test
    void registeredRootDestructionIsDeniedBeforeIndependentAiOrHumanOrDispatch() throws Exception {
        enable();
        try (var peer = new Peer(root.resolve("peer")); var runtime = open()) {
            configure(runtime, peer);
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("hd16-static", "Static before every approval", List.of(SERVER.id()), false), task -> {
                try {
                    String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    for (String script : List.of("rm -rf /", "rm -rf /*", "cd / && rm -rf *",
                        "dd if=payload of=/dev/sda", "mkfs.ext4 -F /dev/nvme0n1", "wipefs -a /dev/mapper/system", "printf x > /dev/mmcblk0")) {
                        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(5), 8192)) {
                            var actual = intent(request, "root-denial", OPERATION);
                            var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, "AUTOMATIC", "GENERAL"));
                            assertEquals("DEPLOYMENT_COMMAND_STATIC_DENIED", denied.problem().eventCode());
                        }
                    }
                    assertEquals(0, peer.facts.size());
                    assertEquals(0, peer.humans.size());
                    assertEquals(0, peer.sends.size());
                    return success(null);
                } catch (Throwable error) {
                    failure.set(error);
                    throw error;
                }
            });
            await(handle, peer, failure);
        } finally {
            active = null;
        }
    }

    @Test
    void completeInstallerAndExecutableStdinAreReviewedWhileProtectedBinaryStaysOpaqueAndOversizeStopsBeforeHttp() throws Exception {
        enable();
        try (var peer = new Peer(root.resolve("peer")); var runtime = open()) {
            configure(runtime, peer);
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("hd16-evidence", "Exact fixed executable evidence", List.of(SERVER.id()), false), task -> {
                try {
                    String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    try (var workflow = DeploymentWorkflowAuthorization.open(context, task, SERVER, revision, "FULL_CONTROL", "FULL", "installer-evidence-only")) {
                        String installer = DeploymentHelperInstallerScript.render();
                        String quotedHelper = "'" + DeploymentManagedHelperBundle.renderScript().replace("'", "'\"'\"'") + "'";
                        String tracked = DeploymentHelperInstallerScript.renderTracked(task.id(), UUID.randomUUID(), "a".repeat(64), digest(installer));
                        for (String body : List.of(installer, tracked))
                            try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", body.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072)) {
                                String operation = body.equals(installer) ? "deployment.helper.install.v1" : "deployment.helper.install.tracked.v1";
                                var actual = intent(request, "installer", operation);
                                peer.expect(actual, request, "HIGH");
                                assertEquals(TaskResult.SUCCEEDED, DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, "FULL_CONTROL", "FULL").outcome());
                                var facts = peer.facts.getLast();
                                assertEquals("EXECUTABLE_SCRIPT", facts.path("inputKind").asText());
                                assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), facts.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8));
                                assertFalse(facts.has("installedHelperScript"));
                                assertTrue(body.contains(quotedHelper));
                            }
                        var hostCalls = new java.util.concurrent.atomic.AtomicInteger();
                        var observedHost = (gold.debug.windowstolinux.appapi.task.action.InteractionService) java.lang.reflect.Proxy.newProxyInstance(
                            context.interaction().getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},
                            (proxy, method, arguments) -> {
                                if (Set.of("approve", "approveRequired").contains(method.getName()))
                                    hostCalls.incrementAndGet();
                                try {
                                    return method.invoke(context.interaction(), arguments);
                                } catch (java.lang.reflect.InvocationTargetException error) {
                                    throw error.getCause();
                                }
                            });
                        String addition = "\nprintf '%s\\n' '目录 and quotes: '\"'\"'value'\nrm -rf /tmp/fixture-owned\n";
                        String changedNested = installer.replace(quotedHelper, "'" + (DeploymentManagedHelperBundle.renderScript() + addition).replace("'", "'\"'\"'") + "'");
                        assertNotEquals(installer, changedNested);
                        for (String body : List.of(installer + addition, changedNested)) {
                            peer.modelReply = "DENY";
                            try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", body.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072)) {
                                var actual = intent(request, "changed-installer", "deployment.helper.install.v1");
                                peer.expect(actual, request, "HIGH");
                                var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, observedHost, actual, request, task, revision, "FULL_CONTROL", "FULL"));
                                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
                                assertArrayEquals(request.stdin(), peer.facts.getLast().path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        for (String reply : List.of("INVALID", "UNAVAILABLE")) {
                            peer.modelReply = reply;
                            try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", installer.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072)) {
                                var actual = intent(request, "unavailable-review", "deployment.helper.install.v1");
                                peer.expect(actual, request, "HIGH");
                                if (reply.equals("INVALID"))
                                    assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, observedHost, actual, request, task, revision, "FULL_CONTROL", "FULL"));
                                else {
                                    var failed = DeploymentFixedCommandApproval.approve(context, observedHost, actual, request, task, revision, "FULL_CONTROL", "FULL");
                                    assertEquals(TaskResult.FAILED, failed.outcome());
                                    assertTrue(failed.value().isEmpty());
                                }
                            }
                        }
                        assertEquals(0, hostCalls.get(), "Denied, malformed or unavailable independent review cannot obtain any host grant");
                        peer.modelReply = "APPROVE";
                        byte[] secret = new byte[]{0, (byte) 0xff, 0x41, 0x42, 0x43};
                        try (var request = new CommandRequest("builtin printf protected-data", secret, Duration.ofSeconds(5), 8192)) {
                            var actual = intent(request, "secret-frame", "deployment.publication.secret.v1");
                            peer.expect(actual, request, "NORMAL");
                            assertEquals(TaskResult.SUCCEEDED, DeploymentFixedCommandApproval.approve(context, context.interaction(), actual, request, task, revision, "FULL_CONTROL", "FULL").outcome());
                            var facts = peer.facts.getLast();
                            assertEquals("OPAQUE_BOUND_DATA", facts.path("inputKind").asText());
                            assertFalse(facts.has("completeExecutableInput"));
                            assertTrue(facts.path("exactAction").asText().contains("stdinBytes=5\nstdinSha256="));
                            assertFalse(facts.toString().contains(Base64.getEncoder().encodeToString(secret)));
                        }
                        int before = peer.facts.size();
                        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", ("#" + "x".repeat(1048576)).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5), 8192)) {
                            var actual = intent(request, "oversize", "deployment.build.run.v1");
                            assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, observedHost, actual, request, task, revision, "FULL_CONTROL", "FULL"));
                        }
                        assertEquals(before, peer.facts.size(), "Over-budget executable evidence cannot call independent AI");
                        assertEquals(0, hostCalls.get(), "Over-budget executable evidence cannot obtain a host grant");
                        assertEquals(0, peer.sends.size(), "Evidence tests never install or send the helper");
                    }
                    return success(null);
                } catch (Throwable error) {
                    failure.set(error);
                    throw error;
                }
            });
            await(handle, peer, failure);
        } finally {
            active = null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC"})
    void generalSiblingDirectoryRemovalReusesHumanOnlyWithinActualCandidateAndParent(String approval) throws Exception {
        agentQuestions(approval, (channel, first, second, peer, revision) -> {
            long questions = peer.commandQuestions();
            int calls = peer.facts.size(), sends = peer.sends.size();
            for (String script : List.of("rm -rf 'cache/a'", "rm -rf 'cache/b'", "rm -rf 'other/a'")) {
                expectRun(peer, first, revision, script);
                assertTrue(channel.command(first, revision, script, Duration.ofSeconds(5)).succeeded());
            }
            assertEquals(questions + 2, peer.commandQuestions(), "Same parent siblings share only the human question; another parent does not");
            expectRun(peer, second, revision, "rm -rf 'cache/a'");
            assertTrue(channel.command(second, revision, "rm -rf 'cache/a'", Duration.ofSeconds(5)).succeeded());
            assertEquals(questions + 3, peer.commandQuestions(), "Another actual opened candidate needs its own question");
            assertEquals(calls + 4, peer.facts.size(), "Every literal deletion still reaches independent AI");
            assertEquals(sends + 4, peer.sends.size());
            var reviewed = peer.facts.subList(calls, peer.facts.size());
            assertEquals(4, reviewed.stream().map(value -> value.path("exactAction").asText()).distinct().count());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC"})
    void generalCompoundAndDynamicRemovalKeepExactInputBoundQuestions(String approval) throws Exception {
        agentQuestions(approval, (channel, first, second, peer, revision) -> {
            long questions = peer.commandQuestions();
            int calls = peer.facts.size(), sends = peer.sends.size();
            for (String script : List.of("rm -rf 'cache/a' && printf 'first'", "rm -rf 'cache/b' && printf 'second'",
                "rm -rf \"cache/${first}\"", "rm -rf \"cache/${second}\"")) {
                expectRun(peer, first, revision, script);
                assertTrue(channel.command(first, revision, script, Duration.ofSeconds(5)).succeeded());
            }
            assertEquals(questions + 4, peer.commandQuestions(), "Unproven compound or dynamic ranges cannot reuse a parent question");
            assertEquals(calls + 4, peer.facts.size());
            assertEquals(sends + 4, peer.sends.size());
            assertEquals(4, peer.facts.subList(calls, peer.facts.size()).stream().map(value -> value.path("exactAction").asText()).distinct().count());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_REVIEW", "AUTOMATIC"})
    void generalPatchReusesHumanForSameFileWhileEveryChangedPayloadGetsIndependentAi(String approval) throws Exception {
        agentQuestions(approval, (channel, first, second, peer, revision) -> {
            long questions = peer.commandQuestions();
            int calls = peer.facts.size(), sends = peer.sends.size();
            String current = revision;
            for (int index = 0; index < 4; index++) {
                var candidate = index == 3 ? second : first;
                String originalRevision = index == 3 ? second.revision() : current;
                var patch = new DeploymentAgentPatch(index == 2 ? "src/Other.java" : "src/Main.java", digest("before-" + index), originalRevision,
                    List.of(new DeploymentAgentPatch.Edit(1, List.of("old-" + index), List.of("new-" + index))));
                byte[] payload = JSON.writeValueAsBytes(patch);
                try (var request = new CommandRequest(agentScript("agent-patch", candidate.workspace(), peer.taskId, originalRevision,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))), payload, Duration.ofSeconds(45), 4096)) {
                    peer.expectAgent(request, peer.businessRevision, "HIGH");
                    String changed = channel.patch(candidate, patch);
                    assertNotEquals(originalRevision, changed, "Each patch uses its actual new source receipt");
                    if (index != 3)
                        current = changed;
                }
                assertEquals(questions + (index < 2 ? 1 : index), peer.commandQuestions(), "Human reuse is limited to the actual candidate and exact file");
            }
            assertEquals(calls + 4, peer.facts.size());
            assertEquals(sends + 4, peer.sends.size());
            assertEquals(4, peer.facts.subList(calls, peer.facts.size()).stream().map(value -> value.path("exactAction").asText()).distinct().count(),
                "Changed edits and before digests must never cache independent AI approval");
        });
    }

    private void agentQuestions(String approval, AgentQuestionWork work) throws Exception {
        enable();
        try (var peer = new Peer(root.resolve("peer")); var runtime = open()) {
            configure(runtime, peer);
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("hd16-agent-questions", "Actual candidate question scopes", List.of(SERVER.id()), false), task -> {
                try {
                    String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
                    peer.taskId = task.id();
                    peer.businessRevision = revision;
                    peer.sourceRevision = digest("controlled-original-source");
                    try (var workflow = DeploymentWorkflowAuthorization.open(context, task, SERVER, revision, approval, "GENERAL", "source=controlled-original-source");
                         var session = context.linux().connect(SERVER, task)) {
                        var reviewed = new DeploymentCommandReviewScope(context, context.interaction(), task, approval, "GENERAL", () -> revision);
                        var channel = new DeploymentAgentProjectChannel(session, reviewed.interaction(), SERVER, task, revision, () -> revision);
                        var prepared = new ArrayList<DeploymentAgentDeliveryPort.Prepared>();
                        for (String archive : List.of(digest("archive-one"), digest("archive-two"))) {
                            var workspace = new DeploymentRemoteWorkspace("question-app", archive);
                            try (var request = new CommandRequest(agentScript("agent-open", workspace, task.id(), archive), new byte[0], Duration.ofMinutes(2), 4096)) {
                                peer.expectAgent(request, revision, "NORMAL");
                                prepared.add(new DeploymentAgentDeliveryPort.Prepared(workspace, channel.open(workspace, "ordinary")));
                            }
                        }
                        assertNotEquals(prepared.get(0).workspace().candidateId(), prepared.get(1).workspace().candidateId());
                        work.run(channel, prepared.get(0), prepared.get(1), peer, peer.sourceRevision);
                        assertEquals(approval.equals("MANUAL_REVIEW") ? 0 : 1, peer.handoffs());
                    }
                    return success(null);
                } catch (Throwable error) {
                    failure.set(error);
                    throw error;
                }
            });
            await(handle, peer, failure);
            peer.assertSendOrdering();
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            active = null;
        }
    }

    private static void expectRun(Peer peer, DeploymentAgentDeliveryPort.Prepared candidate, String revision, String script) {
        try (var request = new CommandRequest(agentScript("agent-run", candidate.workspace(), peer.taskId, revision, "5", "1048576", "ordinary"),
            script.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(25), 1048576)) {
            peer.expectAgent(request, peer.businessRevision, "HIGH");
        }
    }

    private static String agentScript(String verb, DeploymentRemoteWorkspace workspace, UUID task, String... arguments) {
        var values = new ArrayList<>(List.of(DeploymentManagedHelperBundle.PATH, verb, workspace.applicationId(), workspace.candidateId(), task.toString()));
        values.addAll(List.of(arguments));
        return values.stream().map(value -> "'" + value + "'").collect(java.util.stream.Collectors.joining(" "));
    }

    private interface AgentQuestionWork {
        void run(DeploymentAgentProjectChannel channel, DeploymentAgentDeliveryPort.Prepared first,
                 DeploymentAgentDeliveryPort.Prepared second, Peer peer, String revision) throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        final HttpServer http;

        final ExecutorService executor = Executors.newSingleThreadExecutor();

        final LoopbackSshServer ssh;

        final Queue<Expected> expected = new ConcurrentLinkedQueue<>();

        final List<JsonNode> facts = new CopyOnWriteArrayList<>();

        final List<String> approvedActions = new CopyOnWriteArrayList<>();

        final List<ActionIntent> humans = new CopyOnWriteArrayList<>();

        final List<String> sends = new CopyOnWriteArrayList<>();

        final List<String> sendBindings = new CopyOnWriteArrayList<>();

        final Queue<Expected> reviewedWire = new ConcurrentLinkedQueue<>();

        final List<String> events = new CopyOnWriteArrayList<>();

        final AtomicReference<Throwable> failure = new AtomicReference<>();

        volatile boolean rejectCommands;

        volatile String modelReply = "APPROVE";

        String sourceRevision;

        String businessRevision;

        UUID taskId;

        Peer(Path root) throws Exception {
            Files.createDirectories(root);
            ssh = new LoopbackSshServer(root, server -> {
                server.setPasswordAuthenticator((user, password, session) -> user.equals("tester") && password.equals("fixture-secret"));
                server.setCommandFactory((channel, bootstrap) -> {
                    assertTrue(bootstrap.contains("read -r -N") && bootstrap.contains("/dev/fd/3"), "Actual SSH must retain the platform's separate binary frame");
                    return new FramePeer();
                });
            });
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/chat", exchange -> {
                try {
                    var sent = JSON.readTree(exchange.getRequestBody());
                    assertEquals("Bearer fixture-model-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("fixture/approval", sent.path("model").asText(), "Actual independent APPROVAL route must be used");
                    var actual = JSON.readTree(sent.path("messages").get(1).path("content").asText());
                    assertTrue(sent.path("messages").get(0).path("content").asText().contains("including executable bodies encoded in shell literals, printf or heredocs"));
                    long messageBytes = 0;
                    for (var message : sent.path("messages"))
                        messageBytes += message.path("content").asText().getBytes(StandardCharsets.UTF_8).length;
                    assertTrue(messageBytes <= 1048576, "Actual encoded message text remains within the platform bound");
                    var original = expected.remove();
                    assertEquals(original.intent.exactAction(), actual.path("exactAction").asText());
                    assertEquals(original.intent.revision(), actual.path("businessRevision").asText());
                    if (original.intent.semantics().isPresent())
                        assertEquals(original.intent.semantics().orElseThrow().operation(), actual.path("operation").asText());
                    else
                        assertEquals("deployment.agent.helper.v1", actual.path("operation").asText());
                    assertEquals(SERVER.id(), actual.path("targets").get(0).asText());
                    assertEquals(original.actionHash, digest(actual.path("exactAction").asText()));
                    assertEquals("WTL-SSH-COMMAND-1\ntimeout=" + original.timeout + "\noutputLimitBytes=" + original.outputLimit
                            + "\nstdinBytes=" + original.inputBytes + "\nstdinSha256=" + original.inputHash + "\nscript:\n" + original.script,
                        actual.path("exactAction").asText(), "AI evidence must bind actual script, complete data digest and exact execution budgets");
                    if (actual.has("completeExecutableInput"))
                        assertEquals(original.input, actual.path("completeExecutableInput").asText());
                    if (sourceRevision != null && (original.script.contains("'agent-run'") || original.script.contains("'agent-patch'"))) {
                        assertEquals(original.script.contains("'agent-patch'") ? "SOURCE_PATCH" : "EXECUTABLE_SCRIPT", actual.path("inputKind").asText());
                        assertEquals(original.input, actual.required("completeExecutableInput").textValue(), "Every actual Agent payload must be sent in full to independent AI");
                    }
                    facts.add(actual);
                    approvedActions.add(original.intent.exactAction());
                    reviewedWire.add(original);
                    events.add("AI:" + original.actionHash);
                    var decision = JSON.createObjectNode().put("decision", modelReply.equals("DENY") ? "DENY" : "APPROVE").put("risk", original.risk).put("reason", "Controlled exact evidence response");
                    if (modelReply.equals("INVALID")) decision.put("unexpected", true);
                    var reply = JSON.createObjectNode();
                    reply.putArray("choices").addObject().put("finish_reason", "stop").putObject("message").put("role", "assistant").put("content", decision.toString());
                    reply.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20);
                    byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(modelReply.equals("UNAVAILABLE") ? 503 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    exchange.close();
                }
            });
            http.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/chat";
        }

        void expect(ActionIntent intent, CommandRequest request, String risk) {
            byte[] input = request.stdin();
            try {
                String inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
                expected.add(new Expected(intent, new String(input, StandardCharsets.UTF_8), risk, digest(intent.exactAction()), request.script(), request.timeout(), request.outputLimitBytes(), input.length, inputHash));
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            } finally {
                Arrays.fill(input, (byte) 0);
            }
        }

        void expectAgent(CommandRequest request, String revision, String risk) {
            expect(RemoteActions.command(SERVER, revision, request), request, risk);
        }

        long commandQuestions() {
            return humans.stream().filter(intent -> intent.type().equals("platform.ssh.execute")).count();
        }

        long handoffs() {
            return humans.stream().filter(intent -> intent.type().equals("deployment.workflow.authority")).count();
        }

        void healthy() {
            if (failure.get() != null) throw new AssertionError("Actual independent HTTP peer failed", failure.get());
            assertTrue(expected.isEmpty(), "Every expected actual request must reach independent AI");
        }

        void assertSendOrdering() {
            for (String binding : sendBindings)
                assertTrue(events.indexOf("AI:" + binding) < events.indexOf("SEND:" + binding));
        }

        public void close() throws Exception {
            http.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            ssh.close();
        }

        record Expected(ActionIntent intent, String input, String risk, String actionHash, String script,
                        Duration timeout,
                        long outputLimit, int inputBytes, String inputHash) {
        }

        /** Controlled SSH executes no installer; it validates the actual platform frame before reporting completion. */
        private final class FramePeer implements org.apache.sshd.server.command.Command {
            private InputStream input;

            private OutputStream output;

            private org.apache.sshd.server.ExitCallback callback;

            private Thread worker;

            public void setInputStream(InputStream value) {
                input = value;
            }

            public void setOutputStream(OutputStream value) {
                output = value;
            }

            public void setErrorStream(OutputStream value) {
            }

            public void setExitCallback(org.apache.sshd.server.ExitCallback value) {
                callback = value;
            }

            public void start(ChannelSession channel, org.apache.sshd.server.Environment environment) {
                worker = new Thread(() -> {
                    try {
                        var length = new ByteArrayOutputStream();
                        for (int next; (next = input.read()) != '\n'; ) {
                            if (next < '0' || next > '9' || length.size() >= 8)
                                throw new IOException("Invalid actual SSH frame");
                            length.write(next);
                        }
                        int count = Integer.parseInt(length.toString(StandardCharsets.US_ASCII));
                        if (count < 1 || count > 16 * 1024 * 1024)
                            throw new IOException("Actual SSH frame exceeds budget");
                        byte[] bytes = input.readNBytes(count);
                        assertEquals(count, bytes.length);
                        String script = new String(bytes, StandardCharsets.UTF_8);
                        byte[] payload = input.readAllBytes();
                        String inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
                        var original = reviewedWire.stream().filter(value -> value.script.equals(script) && value.inputBytes == payload.length && value.inputHash.equals(inputHash))
                            .findFirst().orElseThrow(() -> new AssertionError("Actual transmitted script and complete payload require their own independent review"));
                        assertTrue(reviewedWire.remove(original), "One independent review belongs to one actual send");
                        if (sourceRevision != null)
                            assertArrayEquals(original.input.getBytes(StandardCharsets.UTF_8), payload, "Actual helper stdin must equal the independently reviewed complete payload");
                        sends.add(script);
                        sendBindings.add(original.actionHash);
                        events.add("SEND:" + original.actionHash);
                        String response = sourceRevision != null && script.contains("'agent-open'") ? "SOURCE_REVISION=" + sourceRevision + "\n"
                            : sourceRevision != null && script.contains("'agent-patch'") ? "SOURCE_REVISION=" + digest("controlled-patch:" + inputHash) + "\n"
                            : "controlled exact command completed";
                        output.write(response.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        callback.onExit(0);
                    } catch (Throwable error) {
                        failure.set(error);
                        callback.onExit(1);
                    }
                }, "hd16-exact-ssh-frame");
                worker.start();
            }

            public void destroy(ChannelSession channel) throws Exception {
                if (input != null) input.close();
                if (worker != null && worker != Thread.currentThread()) {
                    worker.join(5000);
                    assertFalse(worker.isAlive(), "Actual SSH peer worker must terminate");
                }
            }
        }
    }
}
