package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.linux.recovery.BrowserProcess;
import gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRecoveryRestartTest {
    @TempDir
    Path root;

    @Test
    void reopeningReconcilesOnlyExplicitPhysicalClosureAndNeverReplaysOrDiscardsUnconfirmedWork() throws Exception {
        for (String scenario : List.of("closed", "starting", "started", "mixed", "malformed")) {
            var directory = root.resolve(scenario);
            var profile = PlatformServerServiceTest.profile("server", 1);
            var leases = new ArrayList<UUID>();
            UUID task;
            String owner = PlatformTaskAdmission.PLATFORM_OWNER;
            try (var db = PlatformDatabase.open(directory, PlatformStartupTest.PROTECTION)) {
                var persistence = new PlatformPersistence(db);
                try (var security = new DesktopSecurityService(persistence.security())) {
                    security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
                    try (var operation = security.enterOperation(); var credentials = security.openCredentials()) {
                        credentials.save(profile.credentialKey(), "fixture-secret".toCharArray());
                    }
                }
                persistence.servers().saveServerProfile(profile.stored());
                var ledger = new TaskLedger(db);
                task = ledger.enqueue(UUID.randomUUID(), owner, "0.0.1-original",
                        new TaskRequest(PlatformRecoveryService.TASK_TYPE, "Original rescue", List.of(profile.id()),
                                true),
                        List.of(new TaskResource("MOD", owner, owner, false),
                                new TaskResource("SERVER", profile.id(), owner, true)));
                assertTrue(ledger.tryStart(task));
                if (Set.of("closed", "mixed").contains(scenario)) {
                    var before = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(),
                            Optional.empty());
                    var resource = new TaskResource("PROCESS", "browser-v1:" + before.process(), owner, false);
                    leases.add(ledger.retain(task, resource, before.encode("browser.v1")));
                    // This fixture allocated no physical browser; confirmed closure has no remaining process. / 此夹具未分配物理浏览器，确认关闭后无剩余进程。
                    ledger.confirmBrowserClosed(task, resource, before);
                }
                if (!scenario.equals("closed")) {
                    var before = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(),
                            Optional.empty());
                    ResourceRecovery facts = before.encode("browser.v1");
                    if (!scenario.equals("starting")) {
                        var current = ProcessHandle.current();
                        var oldStart = current.info().startInstant().orElseThrow().minusSeconds(1);
                        assertFalse(new BrowserProcess(current.pid(), oldStart).alive());
                        // Deliberately mismatched historical birth proves this is not that root, not that its driver exited. / 历史开始时间刻意不同，只证明不是原根进程，不能证明驱动退出。
                        facts = new ProcessRecovery(before.boot(), before.process(), OptionalLong.of(current.pid()),
                                Optional.of(oldStart)).encode("browser.v1");
                    }
                    if (scenario.equals("malformed")) {
                        var fields = new HashMap<>(facts.fields());
                        fields.put("unverified", "true");
                        facts = new ResourceRecovery("browser.closed.v1", fields);
                    }
                    leases.add(ledger.retain(task,
                            new TaskResource("PROCESS", "browser-v1:" + before.process(), owner, false), facts));
                }
            }
            try (var runtime = PlatformRuntime.open(directory, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
                var service = runtime.recovery();
                assertEquals(1, service.pending().size());
                assertThrows(ApiException.class, () -> service.session(task));
                assertThrows(ApiException.class, () -> service.reconcile(task, false));
                try {
                    var result = service.reconcile(task, true).toCompletableFuture().get(8, TimeUnit.SECONDS);
                    assertEquals("0.0.1-original", result.ownerVersion());
                    if (scenario.equals("closed")) {
                        assertEquals(TaskState.ENDED, result.state());
                        assertEquals(Optional.of(Outcome.FAILED), result.outcome());
                        assertEquals(0, count(runtime.persistence().database(),
                                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    } else {
                        assertEquals(TaskState.UNKNOWN, result.state());
                        assertEquals(leases.size(), TaskBrowserSessionTest.claims(runtime));
                        assertEquals(ProblemCode.BUSY,
                                assertThrows(ApiException.class, () -> service.start(profile)).problem().code());
                        assertThrows(SQLException.class, () -> runtime.servers().delete(profile.id()));
                    }
                    assertEquals(scenario.equals("closed") ? 0 : 1,
                            count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
                    assertEquals(scenario.equals("closed") ? 1 : 0,
                            count(runtime.persistence().database(),
                                    "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task' AND task_id='"
                                            + task + "'"));
                    assertEquals(0,
                            count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_event"));
                    assertEquals(0, count(runtime.persistence().database(),
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CONNECTION'"));
                } finally {
                    // Remove only synthetic fixture claims after assertions so the test runtime can close. / 断言后仅移除模拟夹具申请，便于关闭测试运行时。
                    if (!service.pending().isEmpty()) {
                        var ledger = new TaskLedger(runtime.persistence().database());
                        for (var lease : leases)
                            ledger.release(task, lease);
                        assertEquals(TaskState.ENDED,
                                service.reconcile(task, true).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                    }
                }
            }
        }
    }
}
