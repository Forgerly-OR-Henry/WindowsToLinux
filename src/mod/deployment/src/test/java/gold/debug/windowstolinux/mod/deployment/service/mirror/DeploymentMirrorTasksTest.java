package gold.debug.windowstolinux.mod.deployment.service.mirror;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentMirrorStore;

class DeploymentMirrorTasksTest extends DeploymentRuntimeFixture {
    private static final ServerId TARGET = new ServerId("mirror-fixture-server");

    private ModContext noNetwork(ModContext original, AtomicInteger attempts) {
        ServerService servers = (ServerService)Proxy.newProxyInstance(ServerService.class.getClassLoader(), new Class<?>[]{ServerService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("get")) return new ServerInfo(TARGET, "Fixture", "192.0.2.1", 22, "root", Optional.empty(), Optional.empty());
                throw new AssertionError("Unexpected server access: " + method.getName());
            });
        return (ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("linux")) { attempts.incrementAndGet(); throw new AssertionError("Network must not be used"); }
            if (method.getName().equals("servers")) return servers;
            try { return method.invoke(original, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    private MirrorSettings fixed(boolean custom) {
        var catalog = MirrorCatalog.load();
        var points = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
        for (var point : DownloadPoint.values()) {
            String provider = catalog.providersFor(point).stream().filter(p -> p.official()).findFirst().orElseThrow().id();
            points.put(point, List.of(custom ? new MirrorSettings.Row("", "http://127.0.0.1:18181/" + point.name().toLowerCase(Locale.ROOT) + "/", false, false, "")
                : new MirrorSettings.Row(provider, "", false, false, "")));
        }
        return new MirrorSettings(1, points, Map.of());
    }

    private void work(ModContext context, TaskWork<Void> body) throws Exception {
        var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var handle = context.tasks().submit(new TaskRequest("mirror_task_test", "Mirror task snapshot", List.of(), false), task -> {
            try { return body.run(task); }
            catch (Exception | AssertionError failure) {
                observed.set(failure);
                return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.IO_FAILURE, "MIRROR_TEST_BODY_FAILED", "Mirror test body failed", Map.of(), Optional.empty())));
            } finally { DeploymentMirrorTasks.forget(task); }
        });
        var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
        if (observed.get() != null) throw new AssertionError(observed.get());
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
    }

    @Test void fixedSingleSourceMakesNoIpRequestsAndRecoveryKeepsTheOriginalPolicy() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var attempts = new AtomicInteger();
            var context = noNetwork(runtime.modContexts().get(OWNER).context(), attempts);
            work(context, task -> {
                var store = new DeploymentMirrorStore(context.db());
                store.save(task, fixed(true));
                var service = new DeploymentMirrorTasks(context);
                try {
                    service.capture(task, TARGET);
                    String original = DeploymentMirrorTasks.policy(task);
                    var resolved = ResolvedMirrorPolicy.fromJson(original, task.id().toString(), TARGET.id());
                    assertTrue(resolved.points().values().stream().allMatch(points -> points.size() == 1 && points.getFirst().custom()));
                    String command = "printf '%s\\n' 'task-specific source'";
                    String wrapped = DeploymentMirrorTasks.preparationCommand(command, task);
                    assertEquals(command, DeploymentMirrorTasks.preparation(wrapped).orElseThrow().original());
                    assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorTasks.preparation(
                        wrapped.replace("root-preparation-required", "changed-preparation")));
                    assertTrue(context.tasks().findCheckpoint(task, DeploymentMirrorTasks.CHECKPOINT + ".part.0").isPresent());
                    assertEquals("MIRROR_UNCONSTRAINED_SCRIPT", assertThrows(ApiException.class,
                        () -> DeploymentMirrorTasks.requireProjectScript(task)).problem().eventCode());
                    byte[] frame = gold.debug.windowstolinux.mod.deployment.linux.database.DeploymentNativeDatabaseWire.frame(Map.of("engine", "POSTGRESQL"));
                    var inspectionCalls = new AtomicInteger();
                    var session = (gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class}, (proxy, method, args) -> {
                            assertEquals("execute", method.getName()); inspectionCalls.incrementAndGet();
                            var request = (gold.debug.windowstolinux.appapi.linux.command.CommandRequest)args[0];
                            assertArrayEquals(frame, request.stdin());
                            var preparation = DeploymentMirrorTasks.preparation(request.script()).orElseThrow();
                            assertEquals(original, preparation.policy()); assertEquals(600, preparation.budgetSeconds());
                            assertEquals("'" + gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original().helperPath() + "' native-db inspect",
                                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(request.script(),
                                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()));
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                                Optional.of(0), "fixture\n", "", false)), Optional.empty());
                        });
                    var interaction = (gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class}, (proxy, method, args) -> {
                            assertEquals("approve", method.getName());
                            assertEquals("deployment.native-db.inspect.v1", ((gold.debug.windowstolinux.appapi.task.action.ActionIntent)args[0]).semantics().orElseThrow().operation());
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), Optional.empty());
                        });
                    try {
                        assertEquals(TaskResult.SUCCEEDED, new gold.debug.windowstolinux.mod.deployment.linux.database.DeploymentNativeDatabaseWire(
                            session, interaction, TARGET, task).send("inspect", frame, "a".repeat(64), "POSTGRESQL", () -> {}).outcome());
                        assertEquals(1, inspectionCalls.get());
                    } finally { Arrays.fill(frame, (byte)0); }
                    store.save(task, fixed(false));
                    DeploymentMirrorTasks.forget(task);
                    service.restore(task);
                    assertEquals(original, DeploymentMirrorTasks.policy(task));
                    assertEquals(0, attempts.get());
                } finally { DeploymentMirrorTasks.forget(task); }
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        }
    }

    @Test void declinedInitialPrivacyDoesNotDetectOrCreatePolicy() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var attempts = new AtomicInteger();
            var context = noNetwork(runtime.modContexts().get(OWNER).context(), attempts);
            work(context, task -> {
                DeploymentMirrorTasks.privacyPrompt(() -> false);
                var service = new DeploymentMirrorTasks(context);
                var failure = assertThrows(ApiException.class, () -> service.capture(task, TARGET));
                assertEquals("MIRROR_PRIVACY_CONSENT_REQUIRED", failure.problem().eventCode());
                assertTrue(context.tasks().findCheckpoint(task, DeploymentMirrorTasks.CHECKPOINT).isEmpty());
                assertEquals("", DeploymentMirrorTasks.policy(task));
                assertEquals(0, attempts.get());
                assertFalse(new DeploymentMirrorStore(context.db()).read(task).rows(DownloadPoint.APT_DEBIAN).getFirst().privacyConsent());
                assertThrows(IllegalStateException.class, () -> DeploymentMirrorTasks.preparationCommand("exit 0", task));
                DeploymentMirrorTasks.forget(task);
                service.restore(task);
                assertThrows(IllegalStateException.class, () -> DeploymentMirrorTasks.preparationCommand("exit 0", task));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        }
    }

    @Test void systemCompatibilityActivatesOnlyTheTargetsRepositoryWithoutGeolocation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var original = noNetwork(runtime.modContexts().get(OWNER).context(), new AtomicInteger());
            var calls = new AtomicInteger();
            var session = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    assertEquals("execute", method.getName()); calls.incrementAndGet();
                    String input = new String(((gold.debug.windowstolinux.appapi.linux.command.CommandRequest)args[0]).stdin(), java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(input.contains("/etc/os-release")); assertFalse(input.contains("curl"));
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                        Optional.of(0), "ubuntu\n", "", false)), Optional.empty());
                });
            var linux = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.linux.LinuxService.class},
                (proxy, method, args) -> { assertEquals("connect", method.getName()); assertEquals(TARGET, args[0]); return session; });
            var interaction = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},
                (proxy, method, args) -> {
                    assertEquals("approve", method.getName());
                    var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent)args[0];
                    assertEquals("deployment.mirror.compatibility.v1", intent.semantics().orElseThrow().operation());
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), Optional.empty());
                });
            var context = (ModContext)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("linux")) return linux;
                if (method.getName().equals("interaction")) return interaction;
                try { return method.invoke(original, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            work(context, task -> {
                var fixed = fixed(true);
                new DeploymentMirrorStore(context.db()).save(task, new MirrorSettings(1, Map.of(
                    DownloadPoint.APT_UBUNTU, fixed.rows(DownloadPoint.APT_UBUNTU),
                    DownloadPoint.NODE_PACKAGE, fixed.rows(DownloadPoint.NODE_PACKAGE)), Map.of()));
                DeploymentMirrorTasks.privacyPrompt(() -> { throw new AssertionError("Other distributions must not activate automatic matching"); });
                var service = new DeploymentMirrorTasks(context);
                service.capture(task, TARGET, Set.of(DownloadPoint.APT_DEBIAN, DownloadPoint.APT_UBUNTU, DownloadPoint.NODE_PACKAGE));
                var policy = ResolvedMirrorPolicy.fromJson(DeploymentMirrorTasks.policy(task), task.id().toString(), TARGET.id());
                assertEquals(1, policy.points().get(DownloadPoint.APT_UBUNTU).size());
                assertTrue(policy.points().get(DownloadPoint.APT_DEBIAN).isEmpty());
                assertTrue(policy.country().isEmpty()); assertEquals(1, calls.get());
                DeploymentMirrorTasks.forget(task); service.restore(task);
                assertEquals(policy.toJson(), DeploymentMirrorTasks.policy(task));
                assertEquals(1, calls.get());
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        }
    }

    @Test void unusedAutomaticPurposesDoNotProbeAndAdmissionKeepsTheOriginalPreferences() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var attempts = new AtomicInteger();
            var context = noNetwork(runtime.modContexts().get(OWNER).context(), attempts);
            work(context, task -> {
                var store = new DeploymentMirrorStore(context.db());
                var row = fixed(true).rows(DownloadPoint.NODE_PACKAGE);
                store.save(task, new MirrorSettings(1, Map.of(DownloadPoint.NODE_PACKAGE, row), Map.of()));
                var service = new DeploymentMirrorTasks(context);
                DeploymentMirrorTasks.privacyPrompt(() -> { throw new AssertionError("Unused defaults must not request consent"); });
                try {
                    service.prepare(task, TARGET);
                    store.save(task, MirrorSettings.defaults());
                    service.capture(task, TARGET, Set.of(DownloadPoint.NODE_PACKAGE));
                    var policy = ResolvedMirrorPolicy.fromJson(DeploymentMirrorTasks.policy(task), task.id().toString(), TARGET.id());
                    assertTrue(policy.country().isEmpty());
                    assertEquals(1, policy.points().get(DownloadPoint.NODE_PACKAGE).size());
                    assertTrue(policy.points().get(DownloadPoint.NODE_PACKAGE).getFirst().custom());
                    assertTrue(policy.points().get(DownloadPoint.JAVA_METADATA).isEmpty());
                    assertDoesNotThrow(() -> gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.bootstrapPrefix(policy.toJson()));
                    assertEquals(0, attempts.get());
                } finally { DeploymentMirrorTasks.forget(task); }
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        }
    }

    @Test void mirrorInstallEvidenceRetainsCompleteInstallerAndOriginalHelperGuard() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = noNetwork(runtime.modContexts().get(OWNER).context(), new AtomicInteger());
            work(context, task -> {
                new DeploymentMirrorStore(context.db()).save(task, fixed(true));
                var service = new DeploymentMirrorTasks(context);
                try {
                    service.capture(task, TARGET);
                    var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                    String original = "'" + layout.helperPath() + "' probe";
                    String guarded = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(original, layout);
                    String prepared = DeploymentMirrorTasks.preparationCommand(guarded, task);
                    assertEquals(original, gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.verifiedInvocation(prepared, layout));
                    String installer = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.withMirrors(
                        gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render(layout), DeploymentMirrorTasks.policy(task));
                    byte[] bytes = installer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest("exec /bin/bash --noprofile --norc -s", bytes,
                        java.time.Duration.ofHours(2), 131072)) {
                        var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(TARGET, "mirror-test", request).withSemantics(
                            new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("deployment.helper.install.v1",
                                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(TARGET, layout)));
                        var evidence = new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol(context).inspect(intent, request, layout, task);
                        var transport = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(evidence.get("mirrorTransport"));
                        assertEquals(gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.runtimeSource(), transport.path("runtimeSource").asText());
                        assertEquals(task.id().toString(), transport.path("frozenPolicy").path("task").asText());
                        var value = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(evidence.get("completeExecutableInput"));
                        assertEquals(installer, gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.reconstruct(value));
                        gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentInstallReviewIntent.upgrade(intent, request, TARGET, "mirror-test", layout);
                        assertTrue(value.path("segments").get(0).path("text").asText().contains("WTL_MIRROR_TASK="));
                    } finally { Arrays.fill(bytes, (byte)0); }
                } finally { DeploymentMirrorTasks.forget(task); }
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        }
    }

    @Test void regionOverrideUsesTheConfiguredCountryWithoutAnExternalProbe() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var attempts = new AtomicInteger();
            var context = noNetwork(runtime.modContexts().get(OWNER).context(), attempts);
            work(context, task -> {
                var points = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
                for (var point : DownloadPoint.values()) points.put(point, List.of(MirrorSettings.Row.auto(true)));
                new DeploymentMirrorStore(context.db()).save(task, new MirrorSettings(1, points, Map.of(TARGET.id(), "CN")));
                try {
                    new DeploymentMirrorTasks(context).capture(task, TARGET);
                    var resolved = ResolvedMirrorPolicy.fromJson(DeploymentMirrorTasks.policy(task), task.id().toString(), TARGET.id());
                    assertEquals("CN", resolved.country());
                    assertTrue(resolved.points().get(DownloadPoint.APT_DEBIAN).size() > 3);
                    String executable = gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.wrapBuild(
                        "printf 'mirror-bound-build\\n'\n", DeploymentMirrorTasks.policy(task), 60);
                    gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.requireFixedAllowed(executable);
                    assertEquals("printf 'mirror-bound-build\\n'\n", DeploymentMirrorTasks.buildInput(executable).orElseThrow().original());
                    assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorTasks.buildInput(executable.replace("WTL_MIRROR_BUDGET_SECONDS=60 ", "WTL_MIRROR_BUDGET_SECONDS=0 ")));
                    var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                    byte[] input = executable.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
                    String invocation = "'" + layout.helperPath() + "' 'agent-run' 'fixture' 'candidate' '" + task.id()
                        + "' '" + "a".repeat(64) + "' '60' '1048576' 'ordinary' '" + digest + "' '-'";
                    try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
                        gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(invocation, layout), input,
                        java.time.Duration.ofSeconds(80), 1048576)) {
                        var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(TARGET, "fixture", request).withSemantics(
                            new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("deployment.agent.helper.v1", Set.of(
                                new gold.debug.windowstolinux.appapi.task.action.ActionTarget(TARGET.id(), "task", task.id().toString()))));
                        var facts = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol.facts(intent, request, layout);
                        assertEquals(executable, facts.get("completeExecutableInput"));
                        assertEquals(gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime.runtimeSource(),
                            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts.get("mirrorTransport")).path("runtimeSource").asText());
                    } finally { Arrays.fill(input, (byte)0); }
                    DeploymentMirrorTasks.requireProjectScript(task);
                    assertEquals(0, attempts.get());
                } finally { DeploymentMirrorTasks.forget(task); }
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        }
    }

    @Test void exitDetectionExecutesOnTheTargetAndDoesNotPersistIpv4OrIpv6Addresses() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var original = noNetwork(runtime.modContexts().get(OWNER).context(), new AtomicInteger());
            for (String ip : List.of("8.8.8.8", "2001:4860:4860::8888")) {
                var calls = new AtomicInteger();
                var session = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) return null;
                        if (!method.getName().equals("execute")) throw new AssertionError(method.getName());
                        calls.incrementAndGet();
                        var request = (gold.debug.windowstolinux.appapi.linux.command.CommandRequest)args[0];
                        String input = new String(request.stdin(), java.nio.charset.StandardCharsets.UTF_8);
                        assertTrue(input.contains("curl --disable --noproxy '*'"));
                        assertFalse(input.contains("apt-get"));
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(
                            Optional.of(0), ip + "\n", "", false)), Optional.empty());
                    });
                var linux = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.linux.LinuxService.class},
                    (proxy, method, args) -> { assertEquals("connect", method.getName()); assertEquals(TARGET, args[0]); return session; });
                var interaction = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},
                    (proxy, method, args) -> {
                        assertEquals("approve", method.getName());
                        var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent)args[0];
                        assertEquals(List.of(TARGET.id()), intent.serverIds());
                        assertEquals("deployment.mirror.ip.v1", intent.semantics().orElseThrow().operation());
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), Optional.empty());
                    });
                var context = (ModContext)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("linux")) return linux;
                    if (method.getName().equals("interaction")) return interaction;
                    try { return method.invoke(original, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
                work(context, task -> {
                    var rows = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
                    for (var point : DownloadPoint.values()) rows.put(point, List.of(MirrorSettings.Row.auto(true)));
                    new DeploymentMirrorStore(context.db()).save(task, new MirrorSettings(1, rows, Map.of()));
                    try {
                        new DeploymentMirrorTasks(context).capture(task, TARGET);
                        String json = DeploymentMirrorTasks.policy(task);
                        var resolved = ResolvedMirrorPolicy.fromJson(json, task.id().toString(), TARGET.id());
                        assertEquals(ip.contains(":") ? "CA" : "US", resolved.country()); // DB-IP Lite 2026-11 fixture classifications.
                        assertEquals(1, calls.get());
                        assertFalse(json.contains(ip));
                    } finally { DeploymentMirrorTasks.forget(task); }
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            }
        }
    }
}
