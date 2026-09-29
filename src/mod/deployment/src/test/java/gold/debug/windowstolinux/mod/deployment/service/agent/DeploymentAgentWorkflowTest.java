package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises real CLASS ownership, source snapshots, channels and H2; the SSH peer is controlled. / 验证真实 CLASS 归属、来源快照、频道及 H2；SSH 对等端受控。 */
class DeploymentAgentWorkflowTest extends DeploymentRuntimeFixture {
    enum Scenario { SUCCESS, WHOLE_HEALTH_FAILURE, COMMAND_UNKNOWN, PUBLISH_UNKNOWN, APPROVAL_DRIFT,
        AI_APPROVAL_DRIFT, APPROVAL_MISSING, DUPLICATE_JSON, TRAILING_JSON, CANCELLED, BUDGET }

    private final AtomicReference<String> revision = new AtomicReference<>("a".repeat(64));
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Integer> budgets = new CopyOnWriteArrayList<>();
    private final List<String> approvals = new CopyOnWriteArrayList<>();
    private Scenario scenario;

    @Override protected Path selectedSourceDirectory() { return root.resolve("input"); }

    @Override protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                    CancellationToken token) { throw new AssertionError(); }
            public TransferStallChoice transferStalled(TransferStall s, ServerRef target, TaskSnapshot t,
                    CancellationToken token) { throw new AssertionError(); }
            public String transferProgress(TransferProgress progress) { return "Controlled agent transfer"; }
            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken token) { throw new AssertionError(); }
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken token) {
                approvals.add(intent.type());
                if (scenario == Scenario.APPROVAL_DRIFT && intent.type().equals("deployment.agent.command"))
                    revision.set("b".repeat(64));
                return true;
            }
        };
    }

    /** Runs the dedicated page service through its real one-shot task and durable business commit. / 经专有页面服务执行真实一次性任务与持久业务提交。
     * @param selected tested classified scenario / 测试分类场景
     * @throws Exception on fixture or real task failure / 夹具或真实任务失败时 */
    @ParameterizedTest(name = "{0}") @EnumSource(Scenario.class)
    void originalTaskUsesActualChannelsAndRetainsUnknownEffects(Scenario selected) throws Exception {
        verifyScenario(selected);
    }

    private void verifyScenario(Scenario selected) throws Exception {
        scenario = selected;
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("README.md"), "Build api and web into protected output.\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(
                    "server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            // Replace only this fixture's idle registration with a fresh real task binding. / 仅将夹具空闲登记替换为新的真实任务绑定。
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentAgentTestPeer(selected);
            var ai = scriptedAi();
            var bound = bind(actual, tasks, peer, ai);
            var workflow = new DeploymentAgentWorkflow(bound);
            try (var registration = tasks.registerRecovery(DeploymentAgentWorkflow.TASK_TYPE, workflow)) {
                var page = new DeploymentAgentPageService(bound, workflow);
                var handle = page.submitLocal(new ServerRef("server"), new DeploymentAgentWorkflow.Request(
                        Map.of("goal", "Build the observed api and web"),
                        selected == Scenario.AI_APPROVAL_DRIFT || selected == Scenario.APPROVAL_MISSING
                                ? "AUTOMATIC" : "MANUAL_REVIEW", 65536, 100, 65536));
                waitReturned(handle);
                if (Set.of(Scenario.COMMAND_UNKNOWN, Scenario.PUBLISH_UNKNOWN, Scenario.APPROVAL_DRIFT,
                        Scenario.AI_APPROVAL_DRIFT)
                        .contains(selected)) {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertEquals(1, page.tasks().size());
                    assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                    assertTrue(peer.present.values().stream().anyMatch(Boolean::booleanValue));
                    int sends = peer.commands.get();
                    int decisions = calls.get();
                    peer.active = selected != Scenario.PUBLISH_UNKNOWN;
                    var still = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, still.state());
                    assertEquals(sends, peer.commands.get());
                    assertEquals(decisions, calls.get());
                    peer.active = false;
                    peer.recoveryKnown = true;
                    revision.set("a".repeat(64));
                    var recovered = runtime.tasks().reconcile(handle.id()).toCompletableFuture()
                            .get(20, TimeUnit.SECONDS);
                    assertEquals(TaskState.ENDED, recovered.state());
                    assertEquals(Optional.of(selected == Scenario.PUBLISH_UNKNOWN ? Outcome.SUCCEEDED : Outcome.FAILED),
                            recovered.outcome());
                    assertEquals(sends, peer.commands.get(), "Recovery cannot rerun the candidate build");
                    assertEquals(decisions, calls.get(), "Recovery cannot ask a fresh model");
                } else {
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), diagnostic(runtime, handle));
                    assertEquals(Optional.of(selected == Scenario.SUCCESS ? Outcome.SUCCEEDED
                            : selected == Scenario.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED),
                            handle.snapshot().outcome());
                    assertEquals(0, page.tasks().size());
                }
                long committed;
                try (var access = scope(runtime, true); var connection = access.openConnection();
                        var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT COUNT(*) FROM managed_application")) {
                    assertTrue(rows.next()); committed = rows.getLong(1);
                }
                assertEquals(selected == Scenario.SUCCESS || selected == Scenario.PUBLISH_UNKNOWN ? 1 : 0, committed);
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertTrue(peer.present.values().stream().noneMatch(Boolean::booleanValue));
                if (selected == Scenario.WHOLE_HEALTH_FAILURE)
                    assertEquals(List.of("demo-web", "demo-api"), peer.rollbacks);
                if (selected == Scenario.APPROVAL_DRIFT || selected == Scenario.AI_APPROVAL_DRIFT
                        || selected == Scenario.APPROVAL_MISSING || selected == Scenario.DUPLICATE_JSON
                        || selected == Scenario.TRAILING_JSON)
                    assertEquals(0, peer.commands.get());
                if (selected == Scenario.BUDGET) {
                    assertEquals(80, calls.get());
                    assertEquals(0, budgets.getLast());
                }
                if (selected == Scenario.SUCCESS)
                    assertEquals(3, peer.commands.get(), "One known failed build retries only the same candidate");
                assertTrue(approvals.stream().noneMatch(value -> value.contains("approval-model")));
            }
        }
    }

    private String diagnostic(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        var details = new StringBuilder(handle.snapshot().toString());
        try (var access = scope(runtime, true); var connection = access.openConnection()) {
            for (String query : List.of("SELECT task_state FROM deployment_agent_task WHERE task_id=?",
                    "SELECT event_type,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id",
                    "SELECT phase,outcome,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
                try (var statement = connection.prepareStatement(query)) {
                    statement.setObject(1, query.contains("platform_task_id") ? handle.id() : handle.id().toString());
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            details.append("\n");
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                details.append(rows.getMetaData().getColumnName(i)).append('=').append(rows.getString(i)).append(' ');
                        }
                    }
                }
            }
        }
        return details.toString();
    }

    private void waitReturned(TaskHandle<?> handle) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
                && System.nanoTime() < end) Thread.sleep(25);
        assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
                handle.snapshot().toString());
    }

    private ModContext bind(ModContext actual, TaskService tasks, DeploymentAgentTestPeer peer, AiService ai) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                return peer.session(task);
            }
            public List<TransferCheckpoint> pendingTransfers(TaskContext task) { return List.of(); }
        };
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "tasks" -> tasks;
                    case "linux" -> linux;
                    case "ai" -> ai;
                    default -> {
                        try { yield method.invoke(actual, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                });
    }

    private AiService scriptedAi() {
        return new AiService() {
            public String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException {
                if (purpose == AiPurpose.APPROVAL) {
                    assertTrue(scenario == Scenario.AI_APPROVAL_DRIFT || scenario == Scenario.APPROVAL_MISSING,
                            "Manual review needs no approval model");
                    if (scenario == Scenario.APPROVAL_MISSING)
                        throw new ApiException(new Problem(ProblemCode.UNAVAILABLE, "CONTROLLED_APPROVAL_MISSING",
                                "No independent reviewer configured", Map.of(), Optional.empty()));
                } else assertEquals(AiPurpose.LANGUAGE, purpose);
                return revision.get();
            }
            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, revision.get(), task);
            }
            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task)
                    throws ApiException {
                assertEquals(revision.get(), expected);
                if (request.purpose() == AiPurpose.APPROVAL) {
                    assertEquals(Scenario.AI_APPROVAL_DRIFT, scenario);
                    revision.set("b".repeat(64));
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new AiResponse(
                            "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Controlled review\"}",
                            "independent", "stop", new AiUsage(Optional.empty(), Optional.empty()))), Optional.empty());
                }
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                int turn = calls.getAndIncrement();
                JsonNode data;
                try { data = new ObjectMapper().readTree(request.messages().getLast().text()); }
                catch (Exception invalid) { throw new AssertionError(invalid); }
                budgets.add(data.path("remaining").intValue());
                if (scenario == Scenario.CANCELLED)
                    return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                String output = switch (scenario) {
                    case DUPLICATE_JSON -> "{\"tool\":\"deliver\",\"tool\":\"inspect\",\"arguments\":{}}";
                    case TRAILING_JSON -> "{\"tool\":\"inspect\",\"arguments\":{}} {}";
                    case BUDGET -> "{\"tool\":\"list\",\"arguments\":{\"path\":\".\",\"offset\":" + turn + ",\"limit\":1}}";
                    default -> proposal(turn, data);
                };
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new AiResponse(output, "controlled",
                        "stop", new AiUsage(Optional.empty(), Optional.empty()))), Optional.empty());
            }
        };
    }

    private String proposal(int turn, JsonNode data) {
        if (turn == 0) return "{\"tool\":\"read\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":10}}";
        if (turn == 1) return plan();
        if (turn == 2) return command("api");
        if (turn == 3) {
            assertTrue(data.path("observations").toString().contains("exit=1"));
            return command("api");
        }
        if (turn == 4) return "{\"tool\":\"seal\",\"arguments\":{\"component\":\"api\"}}";
        if (turn == 5) return command("web");
        if (turn == 6) return "{\"tool\":\"seal\",\"arguments\":{\"component\":\"web\"}}";
        return "{\"tool\":\"deliver\",\"arguments\":{}}";
    }

    private static String command(String component) {
        return "{\"tool\":\"command\",\"arguments\":{\"component\":\"" + component
                + "\",\"script\":\"printf artifact > $WTL_OUTPUT/run.sh\",\"seconds\":10}}";
    }

    private static String plan() {
        return "{\"tool\":\"plan\",\"arguments\":{\"applicationId\":\"demo\",\"explanation\":\"README builds both\","
                + "\"components\":[" + component("api", "[]") + "," + component("web", "[\"api\"]")
                + "],\"applicationHealth\":{\"component\":\"web\",\"health\":{\"type\":\"PROCESS\",\"timeout\":10,\"stability\":1}}}}";
    }

    private static String component(String id, String dependencies) {
        return "{\"id\":\"" + id + "\",\"dependencies\":" + dependencies
                + ",\"evidence\":[\"README.md\"],\"backend\":\"PROCESS\",\"mode\":\"DAEMON\",\"entrypoint\":\"run.sh\","
                + "\"arguments\":[],\"workingDirectory\":\"\",\"health\":{\"type\":\"PROCESS\",\"timeout\":10,\"stability\":1},"
                + "\"ports\":[],\"resources\":[],\"configuration\":[]}";
    }
}
