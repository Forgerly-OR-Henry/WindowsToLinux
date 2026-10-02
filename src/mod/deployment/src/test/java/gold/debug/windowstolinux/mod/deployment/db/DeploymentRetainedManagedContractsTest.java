package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticUnitResolver;
import org.junit.jupiter.api.Test;

/** Migrates native managed identity and durable health through formal CLASS/H2 publications. / 经正式 CLASS/H2 发布迁移原生受管身份与持久健康策略。 */
class DeploymentRetainedManagedContractsTest extends DeploymentManagedApplicationFixture {
    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                return true;
            }

            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall s, ServerRef target, TaskSnapshot t,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }
        };
    }

    @Test
    void activeNativePublicationReusesExactOwnerButRejectsAnotherServerWithoutChangingRowOrReference()
        throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var graph = graph("legacy-demo");
            runtime.servers()
                .saveServerProfile(
                    gold.debug.windowstolinux.app.service.server.ServerProfile.fromStored(
                        new gold.debug.windowstolinux.app.db.server.StoredServerProfile("other-server",
                            "127.0.0.2", 22, "tester", "fixture-password")),
                    "fixture-secret".toCharArray());
            run(runtime, task -> {
                publishManaged(runtime, task, graph, 1);
                return success();
            });
            var references = references(runtime);
            assertFalse(references.isEmpty());
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var resolver = new DeploymentStaticUnitResolver(context.db());
                var expected = graph.components().stream().filter(value -> value.componentId().equals("web"))
                    .findFirst().orElseThrow().unit();
                assertEquals(expected, resolver.resolve(task, expected.id(), expected.server()));
                assertEquals(expected, resolver.resolve(task, expected.id(), new ServerRef("server")));
                var original = new DeploymentApplicationReader(context.db())
                    .find(new DeploymentApplicationRef("legacy-demo", new ServerRef("server")), task);
                var denied = assertThrows(ApiException.class,
                    () -> resolver.resolve(task, expected.id(), new ServerRef("other-server")));
                assertEquals(ProblemCode.INVALID_INPUT, denied.problem().code());
                assertEquals("service.deployment.server-conflict", denied.problem().eventCode());
                assertEquals(expected,
                    new DeploymentPublicationRepository(context.db()).findUnit(task, expected.id()).orElseThrow());
                assertEquals(original,
                    new DeploymentApplicationReader(context.db()).find(original.snapshot().application(), task));
                return success();
            });
            assertEquals(references, references(runtime),
                "Read-only identity reuse and rejection must preserve every original reference");
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM mod_server_reference WHERE mod_id='" + OWNER + "' AND state<>'ACTIVE'"));
            assertResourcesReleased(runtime);
        }
    }

    @Test
    void nonCanonicalStoredNativeIdentityIsRejectedAndNeverSilentlyReclaimed() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var graph = graph("legacy-demo");
            run(runtime, task -> {
                publishManaged(runtime, task, graph, 1);
                return success();
            });
            var references = references(runtime);
            assertFalse(references.isEmpty());
            run(runtime, task -> {
                sql(runtime, task,
                    "UPDATE deployment_unit SET systemd_unit_name='windowstolinux-other.service' WHERE deployment_unit_id='web'");
                var database = runtime.modContexts().get(OWNER).context().db();
                var original = new DeploymentPublicationRepository(database).findUnit(task, "web").orElseThrow();
                assertEquals("windowstolinux-other.service", original.systemdUnit());
                var denied = assertThrows(ApiException.class,
                    () -> new DeploymentStaticUnitResolver(database).resolve(task, "web", new ServerRef("server")));
                assertEquals(ProblemCode.INVALID_INPUT, denied.problem().code());
                assertEquals("service.deployment.identity-invalid", denied.problem().eventCode());
                assertEquals(original,
                    new DeploymentPublicationRepository(database).findUnit(task, "web").orElseThrow());
                return success();
            });
            assertEquals(references, references(runtime),
                "A rejected noncanonical row must not reserve or replace its references");
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM mod_server_reference WHERE mod_id='" + OWNER + "' AND state<>'ACTIVE'"));
            assertResourcesReleased(runtime);
        }
    }

    @Test
    void incompleteNativeRuntimeWithoutPublishedTemplateRejectsManagementBeforeOpeningSsh() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var graph = graph("legacy-demo");
            run(runtime, task -> {
                seed(runtime, task, graph);
                sql(runtime, task, "UPDATE deployment_unit SET runtime_payload=NULL WHERE deployment_unit_id='web'");
                return success();
            });
            var connects = new AtomicInteger();
            run(runtime, task -> {
                var actual = runtime.modContexts().get(OWNER).context();
                var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(),
                    new Class<?>[]{LinuxService.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("connect")) {
                            connects.incrementAndGet();
                            throw new AssertionError("Incomplete publication cannot open SSH");
                        }
                        return invoke(actual.linux(), method, arguments);
                    });
                var context = bind(actual, linux);
                var application = new DeploymentApplicationRef("legacy-demo", new ServerRef("server"));
                var rejected = assertThrows(ApiException.class, () -> new DeploymentApplicationExecutor(context)
                    .lifecycle(application, "b".repeat(64), DeploymentLifecycleAction.RESTART, task));
                assertEquals("DEPLOYMENT_APPLICATION_NOT_PUBLISHED", rejected.problem().eventCode());
                assertTrue(new DeploymentPublicationRepository(context.db()).findRuntime(task, "web").isEmpty());
                assertTrue(new DeploymentManagementJournal(context).find(task).isEmpty());
                return success();
            });
            assertEquals(0, connects.get());
            assertResourcesReleased(runtime);
        }
    }

    @Test
    void reopenedPlatformRestartsOriginalNativeGraphUsingItsPersistedComponentAndWholeHealth() throws Exception {
        enable();
        var initial = graph("legacy-demo");
        var graph = new DeploymentApplicationGraph(initial.applicationId(), initial.healthComponentId(),
            new DeploymentHealthCheck.Process(37, 4), initial.components());
        var persisted = new AtomicReference<DeploymentApplicationReader.ApplicationFacts>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                publishManaged(runtime, task, graph, 1);
                persisted.set(new DeploymentApplicationReader(runtime.modContexts().get(OWNER).context().db())
                    .find(new DeploymentApplicationRef("legacy-demo", new ServerRef("server")), task));
                return success();
            });
            assertResourcesReleased(runtime);
        }
        var peer = new HealthPeer(graph);
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var actual = runtime.modContexts().get(OWNER).context();
                var reader = new DeploymentApplicationReader(actual.db());
                var restored = reader.find(persisted.get().snapshot().application(), task);
                assertEquals(persisted.get(), restored,
                    "Reopen must keep the original complete release and health policy");
                assertEquals(graph.healthCheck(), restored.graph().healthCheck());
                var context = bind(actual, peer.linux());
                var result = new DeploymentApplicationExecutor(context).lifecycle(restored.snapshot().application(),
                    restored.snapshot().revisionSha256(), DeploymentLifecycleAction.RESTART, task);
                assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                assertEquals(ApplicationRunState.RUNNING, result.value().orElseThrow().after());
                assertEquals(graph, reader.find(restored.snapshot().application(), task).graph());
                assertEquals(restored.snapshot().healthPolicySha256(),
                    reader.find(restored.snapshot().application(), task).snapshot().healthPolicySha256());
                try (var connection = context.db().openConnection(task);
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery(
                         "SELECT runtime_state,autostart_state,is_ownership_verified,observation_evidence FROM deployment_unit ORDER BY deployment_unit_id")) {
                    int count = 0;
                    while (rows.next()) {
                        count++;
                        assertEquals("RUNNING", rows.getString(1));
                        assertEquals("UNKNOWN", rows.getString(2));
                        assertEquals(1, rows.getInt(3));
                        assertEquals("deployment-helper-observation-v1", rows.getString(4));
                    }
                    assertEquals(2, count);
                }
                return success();
            });
            assertEquals(List.of("web:stop", "worker:stop", "worker:start", "web:start"), peer.actions);
            assertEquals(2, peer.health.stream()
                .filter(value -> value.equals(List.of("web", "health-v1", "PROCESS", "37", "4"))).count());
            assertEquals(4,
                peer.health.stream()
                    .filter(value -> value.equals(List.of("web", "health-v1", "TCP", "20", "8080", "2"))
                        || value.equals(List.of("worker", "health-v1", "TCP", "20", "8080", "2")))
                    .count());
            assertEquals(0, peer.sessions.get());
            assertResourcesReleased(runtime);
        }
    }

    private void assertResourcesReleased(PlatformRuntime runtime) throws Exception {
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    private List<List<String>> references(PlatformRuntime runtime) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement(
                 "SELECT reference_id,business_key,server_profile_id,mod_id,schema_version,schema_generation,state,updated_at FROM mod_server_reference WHERE mod_id=? ORDER BY reference_id")) {
            statement.setString(1, OWNER);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<List<String>>();
                while (rows.next()) {
                    var fields = new ArrayList<String>();
                    for (int column = 1; column <= 8; column++)
                        fields.add(rows.getString(column));
                    result.add(List.copyOf(fields));
                }
                return List.copyOf(result);
            }
        }
    }

    private static ModContext bind(ModContext actual, LinuxService linux) {
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("linux"))
                    return linux;
                return invoke(actual, method, arguments);
            });
    }

    private static Object invoke(Object actual, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(actual, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    /** Controlled original helper receipts keep independent unit and whole-health fields. / 受控原 helper 回执保留独立单元和整应用健康字段。 */
    private static final class HealthPeer {
        final DeploymentApplicationGraph graph;

        final Map<String, Boolean> running = new HashMap<>();

        final List<String> actions = new ArrayList<>();

        final List<List<String>> health = new ArrayList<>();

        final AtomicInteger sessions = new AtomicInteger();

        private HealthPeer(DeploymentApplicationGraph graph) {
            this.graph = graph;
            graph.components().forEach(component -> running.put(component.unit().id(), true));
        }

        private LinuxService linux() {
            return new LinuxService() {
                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }

                public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                    assertEquals(new ServerRef("server"), server);
                    var lease = task.retain("controlled_ssh", "legacy-health-" + UUID.randomUUID(),
                        "Original controlled helper connection");
                    sessions.incrementAndGet();
                    return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                        new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("facts"))
                                return new RemoteFacts("linux", "x86_64", "SHA256:legacy-health");
                            if (method.getName().equals("close")) {
                                lease.close();
                                sessions.decrementAndGet();
                                return null;
                            }
                            if (method.getName().equals("execute")) {
                                assertNotNull(arguments[1]);
                                return command((CommandRequest) arguments[0]);
                            }
                            throw new AssertionError("Unexpected original remote method " + method.getName());
                        });
                }
            };
        }

        private OperationResult<CommandResult> command(CommandRequest request) {
            if (request.script().contains("/usr/bin/sha256sum --check --status"))
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            var fields = new ArrayList<String>();
            var matcher = Pattern.compile("'([^']*)'").matcher(request.script());
            while (matcher.find())
                fields.add(matcher.group(1));
            assertTrue(fields.size() >= 3, fields.toString());
            String verb = fields.get(1), unit = fields.get(2);
            var component = graph.components().stream().filter(value -> value.unit().id().equals(unit)).findFirst()
                .orElseThrow();
            String owner = component.unit().ownershipManifestSha256();
            return switch (verb) {
                case "managed-current-status" -> {
                    assertEquals(List.of(unit, "a".repeat(64), "deployment"), fields.subList(2, fields.size()));
                    yield reply("MANAGED_CURRENT=1\nKIND=deployment\nRELEASE_SHA256=" + "a".repeat(64) + "\n");
                }
                case "inspect-runtime" -> {
                    assertEquals(List.of(unit, owner), fields.subList(2, fields.size()));
                    yield reply("KIND=deployment\nMODE=DAEMON\n");
                }
                case "observe-deployment" -> {
                    assertEquals(List.of(unit, owner), fields.subList(2, fields.size()));
                    boolean active = running.get(unit);
                    yield reply(
                        "OWNER=1\nRUNNING=" + (active ? "1" : "0") + "\nENABLED=enabled\nQUERY_OK=1\nActiveState="
                            + (active ? "active" : "inactive") + "\nSubState=" + (active ? "running" : "dead")
                            + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID="
                            + (active ? "123" : "0") + "\n");
                }
                case "lifecycle-deployment" -> {
                    assertEquals(6, fields.size());
                    assertEquals(owner, fields.get(4));
                    assertEquals("a".repeat(64), fields.get(5));
                    String action = fields.get(3);
                    assertTrue(Set.of("stop", "start").contains(action));
                    actions.add(unit + ":" + action);
                    running.put(unit, action.equals("start"));
                    yield reply("LIFECYCLE=" + action + "\n");
                }
                case "application-health" -> {
                    assertEquals(5, fields.size());
                    assertEquals(owner, fields.get(3));
                    assertTrue(running.get(unit));
                    String payload = new String(Base64.getDecoder().decode(fields.get(4)), StandardCharsets.UTF_8);
                    assertTrue(payload.endsWith("\0"));
                    var values = Arrays.asList(payload.substring(0, payload.length() - 1).split("\\x00", -1));
                    var recorded = new ArrayList<String>();
                    recorded.add(unit);
                    recorded.addAll(values);
                    health.add(List.copyOf(recorded));
                    assertTrue(
                        recorded.equals(List.of(unit, "health-v1", "TCP", "20", "8080", "2")) || unit.equals("web")
                            && recorded.equals(List.of("web", "health-v1", "PROCESS", "37", "4")),
                        recorded.toString());
                    yield reply("HEALTHY=1\n");
                }
                default -> throw new AssertionError("Unexpected helper verb " + verb);
            };
        }

        private static OperationResult<CommandResult> reply(String text) {
            return new OperationResult<>(Outcome.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(0), text, "", false)), Optional.empty());
        }
    }
}
