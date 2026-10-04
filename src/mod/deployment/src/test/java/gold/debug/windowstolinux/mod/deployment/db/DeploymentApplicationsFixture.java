package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/** Actual CLASS application tasks, complete H2 publication seeds and a framed SSH scanner peer. / 实际CLASS应用任务、完整H2发布种子与分帧SSH扫描对等端。 */
abstract class DeploymentApplicationsFixture extends DeploymentGraphFixture {
    final ScannerPeer peer = new ScannerPeer();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                return true;
            }

            public TransferConflictChoice transferConflict(TransferConflict c, ServerId s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall s, ServerId target, TaskSnapshot t,
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

    LoopbackSshServer ssh() throws Exception {
        return new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                (user, password, session) -> user.equals("tester") && password.equals("fixture-secret"));
            server.setCommandFactory((channel, command) -> new ScannerCommand());
        });
    }

    void server(PlatformRuntime runtime, int port, String username) throws Exception {
        runtime.servers().saveServerProfile(
            gold.debug.windowstolinux.app.service.server.ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", port, username, "fixture-secret")),
            "fixture-secret".toCharArray());
    }

    ModContext context(PlatformRuntime runtime) {
        return runtime.modContexts().get(OWNER).context();
    }

    <T> OperationResult<T> await(TaskHandle<T> handle) throws Exception {
        awaitState(handle);
        assertEquals(TaskState.ENDED, handle.snapshot().state(), handle.snapshot().toString());
        assertEquals(OWNER, handle.snapshot().ownerId());
        assertEquals(VERSION, handle.snapshot().ownerVersion());
        if (peer.failure.get() != null)
            throw new AssertionError("Actual scanner peer failed", peer.failure.get());
        return handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    void awaitState(TaskHandle<?> handle) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
            && System.nanoTime() < end)
            Thread.sleep(20);
        assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
            handle.snapshot().toString());
        if (peer.failure.get() != null)
            throw new AssertionError("Actual scanner peer failed", peer.failure.get());
    }

    <T> TaskHandle<T> rootTask(PlatformRuntime runtime, TaskWork<T> work) throws ApiException {
        return context(runtime).tasks().submit(
            new TaskRequest("applications-test", "Actual CLASS application acceptance", List.of("server"), true),
            work);
    }

    void assertNoActiveResources(PlatformRuntime runtime) throws Exception {
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    String seedManaged(PlatformRuntime runtime, String application, boolean website, int revision) throws Exception {
        var observed = new AtomicReference<String>();
        var handle = rootTask(runtime, task -> {
            var original = graph(application);
            var components = new ArrayList<DeploymentApplicationGraph.Part>();
            for (var component : original.components()) {
                var unit = DeploymentUnitIdentity.forManaged(application + "-" + component.componentId(),
                    new ServerId("server"), "a".repeat(64));
                var current = component.runtime();
                var workload = current.workload();
                if (website && component.componentId().equals(original.healthComponentId())) {
                    workload = new DeploymentWorkload(workload.mode(), workload.reviewed(), workload.command(),
                        workload.workingDirectory(),
                        List.of(new DeploymentEndpoint("public-http", DeploymentEndpoint.ProtocolType.HTTP,
                            "0.0.0.0", 8080, 8080, DeploymentEndpoint.ExposureType.EXTERNAL,
                            "https://demo.debug.gold/")),
                        workload.verification(), workload.expectedOutput(), workload.client(), workload.inputs(),
                        workload.companions(), workload.buildDirectory(), workload.workers());
                }
                var runtimeValue = new DeploymentUnitRuntimeConfig(current.healthCheck(),
                    current.userAccessUrl(), current.identityPolicy(), workload);
                components.add(new DeploymentApplicationGraph.Part(component.componentId(), unit, runtimeValue,
                    component.dependencies(), component.reviewedRuntime(), component.dataPaths(),
                    component.resources()));
            }
            var graph = new DeploymentApplicationGraph(application, original.healthComponentId(),
                original.healthCheck(), components);
            var publication = new DeploymentPublish(graph, publications(graph, revision, List.of()));
            UUID id = UUID.randomUUID();
            var plan = new DeploymentPublishPlan(id, Optional.empty(), application, new ServerId("server"),
                DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64),
                DeploymentOriginalPublishCommit.references(new ServerId("server"), publication, Map.of()),
                Instant.ofEpochMilli(System.currentTimeMillis()));
            var commit = new DeploymentOriginalPublishCommit(context(runtime));
            commit.begin(plan, publication, task, "deployment-publication");
            assertEquals(TaskResult.SUCCEEDED, commit.commitVerified(task, id, publication).outcome());
            assertEquals(TaskResult.SUCCEEDED,
                commit.finishKnown(task,
                        new DeploymentExecutionStore(context(runtime).db()).find(task, id).orElseThrow())
                    .outcome());
            observed.set(new DeploymentApplicationReader(context(runtime).db())
                .find(new gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId(application,
                    new ServerId("server")), task)
                .snapshot().revisionSha256());
            return success();
        });
        assertEquals(TaskResult.SUCCEEDED, await(handle).outcome());
        return observed.get();
    }

    /** Executes fixture-only database faults separately from author service capabilities. / 独立于作者服务能力执行夹具数据库故障。 */
    void schemaFault(PlatformRuntime runtime, String sql) throws Exception {
        try (var access = scope(runtime, false);
             var connection = access.openConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    final class ScannerPeer {
        final AtomicInteger reads = new AtomicInteger(), effects = new AtomicInteger();

        final AtomicReference<Throwable> failure = new AtomicReference<>();

        final AtomicBoolean loseStartAck = new AtomicBoolean();

        volatile String fingerprint = "a".repeat(64), state = "STOPPED";

        volatile String expectedArgumentFingerprint = "a".repeat(64);

        volatile boolean managed;

        volatile List<String> scanIssues = List.of();

        String response(List<String> args) {
            if (args.equals(List.of("SCAN")))
                reads.incrementAndGet();
            else {
                assertEquals(4, args.size());
                assertEquals("SYSTEMD", args.get(0));
                assertEquals("example.service", args.get(1));
                assertEquals(expectedArgumentFingerprint, args.get(2));
                if (args.get(3).equals("REFRESH_STATUS"))
                    reads.incrementAndGet();
                else {
                    assertEquals("START", args.get(3));
                    effects.incrementAndGet();
                    state = "RUNNING";
                }
            }
            String identity = Base64.getEncoder().encodeToString("example.service".getBytes(StandardCharsets.UTF_8));
            String name = Base64.getEncoder().encodeToString("External service".getBytes(StandardCharsets.UTF_8));
            return "APP\tSYSTEMD\t" + identity + "\t" + fingerprint + "\t" + name + "\t" + state
                + (managed ? "\t0\t0\t1\n" : "\t1\t1\t0\n")
                + (args.equals(List.of("SCAN"))
                ? scanIssues.stream().map(issue -> "ISSUE\t" + issue + "\n")
                .collect(java.util.stream.Collectors.joining())
                : "")
                + "END\t1\n";
        }
    }

    private final class ScannerCommand implements Command {
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
                            throw new IOException("Invalid actual frame");
                        length.write(next);
                    }
                    int count = Integer.parseInt(length.toString(StandardCharsets.US_ASCII));
                    if (count < 1 || count > 16_777_216)
                        throw new IOException("Oversize actual frame");
                    byte[] bytes = input.readNBytes(count);
                    assertEquals(count, bytes.length);
                    String command = new String(bytes, StandardCharsets.UTF_8);
                    assertTrue(command.startsWith("exec python3 - "));
                    var args = new ArrayList<String>();
                    var matcher = Pattern.compile("'([^']*)'").matcher(command);
                    while (matcher.find())
                        args.add(matcher.group(1));
                    try (var scanner = DeploymentExternalApplications.class
                        .getResourceAsStream("external-applications.py")) {
                        assertNotNull(scanner);
                        assertArrayEquals(scanner.readAllBytes(), input.readAllBytes(),
                            "Actual archived Python must reach the SSH peer unchanged");
                    }
                    String response = peer.response(args);
                    if (args.size() == 4 && args.get(3).equals("START")
                        && peer.loseStartAck.compareAndSet(true, false)) {
                        channel.getSession().close(true);
                        return;
                    }
                    output.write(response.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
                    peer.failure.set(failure);
                    callback.onExit(1);
                }
            }, "deployment-applications-scanner");
            worker.start();
        }

        public void destroy(ChannelSession channel) throws Exception {
            if (input != null)
                input.close();
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                assertFalse(worker.isAlive());
            }
        }
    }
}
