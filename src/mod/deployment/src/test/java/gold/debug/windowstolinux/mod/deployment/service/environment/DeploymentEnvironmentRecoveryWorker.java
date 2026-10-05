package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.ui.page.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;

/** Deliberately exits with original UNKNOWN evidence, then exercises the real Mod recovery registration in another process. / 刻意带原 UNKNOWN 依据退出，再于另一进程执行真实 Mod 恢复登记。 */
public final class DeploymentEnvironmentRecoveryWorker extends DeploymentRuntimeFixture {
    public static void main(String[] args) {
        try {
            var worker = new DeploymentEnvironmentRecoveryWorker();
            worker.root = Path.of(args[1]);
            if (args[0].startsWith("produce"))
                worker.produce(Integer.parseInt(args[2]), args[0].equals("produce-cancel"), args[3]);
            else if (args[0].equals("recover-cancel")) worker.recoverCancelled();
            else worker.recover();
            System.out.println("ENVIRONMENT_" + args[0].toUpperCase(Locale.ROOT) + "_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                if (method.getName().equals("approve")) return true;
                throw new AssertionError("Unexpected recovery prompt");
            });
    }

    void produce(int port, boolean cancelled, String fingerprint) throws Exception {
        enable();
        var runtime = open();
        start(runtime, true);
        var actual = runtime.modContexts().get(OWNER).context();
        runtime.tasks().revokeOwnerServices(OWNER, VERSION);
        var tasks = runtime.tasks().bind(OWNER, VERSION, false);
        var fresh = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> method.getName().equals("tasks") ? tasks : method.invoke(actual, args));
        var peer = cancelled ? new DeploymentEnvironmentWorkflowTest.Peer("ubuntu-24.04", fingerprint)
            : new DeploymentEnvironmentWorkflowTest.Peer("ubuntu-24.04");
        peer.losePackages = !cancelled;
        peer.receiptMissing = !cancelled;
        peer.failLoginAfterPackages = cancelled;
        peer.helperInstalled = !cancelled;
        var context = peer.wrap(fresh);
        var server = new ServerId("environment-restart-server");
        runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", port,
            "root", "restart-fixture-password")), "restart-fixture-password".toCharArray());
        runtime.persistence().servers().saveServer(new StoredServerIdentity(server.id(), "127.0.0.1", port, peer.fingerprint));
        var info = new ServerInfo(server, "Original controlled peer", "127.0.0.1", port, "root", Optional.of(peer.fingerprint), Optional.empty());
        var handle = new DeploymentEnvironmentWorkflow(context).submit(info);
        DeploymentEnvironmentWorkflowTest.awaitUnknown(handle);
        assertEquals(1, peer.sent("packages"));
        Files.writeString(root.resolve("original-task.txt"), handle.id().toString());
        Files.writeString(root.resolve("original-environment.json"), snapshot(runtime, handle.id()));
        if (cancelled) {
            handle.requestCancel();
            assertTrue(new TaskLedger(runtime.persistence().database()).queries().cancellationRequested(handle.id()));
        }
        assertFalse(handle.completion().toCompletableFuture().isDone());
    }

    void registerOriginalRecovery(PlatformRuntime runtime) throws Exception {
        var report = runtime.startDevelopmentMods(workspace(), new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, javax.swing.JComponent component) {
                throw new AssertionError("Recovery has no page");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
        assertTrue(report.running().isEmpty());
        assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(value -> value.eventCode()).toList());
    }

    void recover() throws Exception {
        UUID task = UUID.fromString(Files.readString(root.resolve("original-task.txt")));
        var runtime = open();
        registerOriginalRecovery(runtime);
        String before = Files.readString(root.resolve("original-environment.json"));
        assertEquals(before, snapshot(runtime, task));
        for (int i = 0; i < 2; i++) {
            var observed = runtime.tasks().reconcile(task).toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(TaskState.UNKNOWN, observed.state());
            assertEquals(before, snapshot(runtime, task));
        }
        Files.writeString(root.resolve("recovered-environment.json"), snapshot(runtime, task));
    }

    void recoverCancelled() throws Exception {
        UUID task = UUID.fromString(Files.readString(root.resolve("original-task.txt")));
        var original = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("original-environment.json")));
        var runtime = open();
        registerOriginalRecovery(runtime);
        assertTrue(new TaskLedger(runtime.persistence().database()).queries().cancellationRequested(task));
        var observed = runtime.tasks().reconcile(task).toCompletableFuture().get(25, TimeUnit.SECONDS);
        assertEquals(TaskState.ENDED, observed.state());
        assertEquals(Optional.of(gold.debug.windowstolinux.appapi.task.result.TaskResult.CANCELLED), observed.outcome());
        try (var connection = runtime.persistence().database().openConnection();
             var query = connection.prepareStatement("SELECT details,truncated FROM app.runtime_log WHERE category='platform_task' AND task_id=? AND subject_mod_id=? AND mod_version=? AND outcome='CANCELLED'")) {
            query.setObject(1, task);
            query.setString(2, OWNER);
            query.setString(3, VERSION);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(2));
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                var result = json.readTree(json.readTree(rows.getString(1)).path("result").textValue());
                assertEquals("ENVIRONMENT_CANCELLED_AFTER_KNOWN_EFFECTS", result.path("problem").path("eventCode").textValue());
                var facts = result.path("problem").path("context");
                for (String key : List.of("operation", "stage", "bodySha", "requestSha"))
                    assertEquals(original.path(key).textValue(), facts.path(key).textValue());
                assertEquals("PACKAGES_INSTALLED", facts.path("stage").textValue());
                assertEquals("SUCCEEDED", facts.path("packagePhase").textValue());
                assertEquals(original.path("observation").path("packageRequest").textValue(), facts.path("packageRequest").textValue());
                Files.writeString(root.resolve("cancelled-summary.json"), result.toString());
                assertFalse(rows.next());
            }
        }
    }

    static String snapshot(PlatformRuntime runtime, UUID task) throws Exception {
        return new TaskAuthorCheckpoint(runtime.persistence().database()).find(task, OWNER, VERSION,
            DeploymentEnvironmentJournal.KEY).orElseThrow().snapshot().payload();
    }
}
