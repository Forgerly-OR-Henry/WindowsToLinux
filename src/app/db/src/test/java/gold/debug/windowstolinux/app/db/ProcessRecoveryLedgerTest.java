package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.task.ProcessRecovery;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRecoveryLedgerTest {
    @TempDir
    Path root;

    @Test
    void originalCreationCheckpointIsOneWayAtomicAndSurvivesReopen() throws Exception {
        UUID task;
        ProcessRecovery observed;
        TaskResource resource;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            var original = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(), Optional.empty());
            observed = new ProcessRecovery(original.boot(), original.process(), OptionalLong.of(123),
                Optional.of(Instant.now()));
            resource = new TaskResource("PROCESS", "process-v1:" + original.process(), "one", false);
            ledger.retain(task, resource, original.encode(), false);
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                    "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_checkpoint CHECK(resource_kind<>'PROCESS' OR recovery_json NOT LIKE '%STARTED%')");
            }
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task, resource, original, observed));
            assertEquals(original.encode(), ledger.queries().recoveries(task, "PROCESS", "process-v1:").get(resource));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_checkpoint");
            }
            ledger.checkpointProcess(task, resource, original, observed);
            ledger.checkpointProcess(task, resource, original, observed);
            var different = new ProcessRecovery(original.boot(), original.process(), OptionalLong.of(124),
                observed.started());
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task, resource, original, different));
            assertThrows(SQLException.class,
                () -> ledger.checkpointProcess(UUID.randomUUID(), resource, original, observed));
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task,
                new TaskResource("PROCESS", resource.id(), "other", false), original, observed));
            assertFalse(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(task).state());
            assertEquals(observed,
                ProcessRecovery.decode(ledger.queries().recoveries(task, "PROCESS", "process-v1:").get(resource)));
        }
    }

    @Test
    void browserFactsKeepTheirNamespaceAndOriginalPidAcrossDatabaseReopen() throws Exception {
        UUID task;
        ProcessRecovery started;
        TaskResource browser;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            var before = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(), Optional.empty());
            var current = ProcessHandle.current();
            started = new ProcessRecovery(before.boot(), before.process(), OptionalLong.of(current.pid()),
                current.info().startInstant());
            browser = new TaskResource("PROCESS", "browser-v1:" + before.process(), "one", false);
            ledger.retain(task, browser, before.encode("browser.v1"), false);
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task, browser, before, started));
            ledger.checkpointBrowser(task, browser, before, started);
            ledger.checkpointBrowser(task, browser, before, started);
            var different = new ProcessRecovery(before.boot(), before.process(), OptionalLong.of(current.pid()),
                Optional.of(started.started().orElseThrow().plusSeconds(1)));
            assertThrows(SQLException.class, () -> ledger.checkpointBrowser(task, browser, before, different));
            assertThrows(IllegalArgumentException.class, () -> ProcessRecovery.decode(started.encode("browser.v1")));
            assertThrows(IllegalArgumentException.class, () -> ProcessRecovery.decode(started.encode(), "browser.v1"));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(task).state());
            assertEquals(started, ProcessRecovery.decode(ledger.queries().recoveries(task, "PROCESS", "browser-v1:").get(browser),
                "browser.v1"));
        }
    }

    @Test
    void browserClosureCheckpointIsAtomicOneWayAndBoundToTheOriginalPhysicalBoot() throws Exception {
        UUID task;
        ProcessRecovery before, started;
        TaskResource browser;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            before = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(), Optional.empty());
            started = new ProcessRecovery(before.boot(), before.process(), OptionalLong.of(123),
                Optional.of(Instant.now()));
            browser = new TaskResource("PROCESS", "browser-v1:" + before.process(), "one", false);
            ledger.retain(task, browser, before.encode("browser.v1"), false);
            ledger.checkpointBrowser(task, browser, before, started);
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                    "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_closed CHECK(recovery_json NOT LIKE '%browser.closed.v1%')");
            }
            assertThrows(SQLException.class, () -> ledger.confirmBrowserClosed(task, browser, before));
            assertEquals(started.encode("browser.v1"), ledger.queries().recoveries(task, "PROCESS", "browser-v1:").get(browser));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_closed");
            }
            assertThrows(SQLException.class, () -> ledger.confirmBrowserClosed(UUID.randomUUID(), browser, before));
            assertThrows(SQLException.class, () -> ledger.confirmBrowserClosed(task,
                new TaskResource("PROCESS", browser.id(), "other", false), before));
            ledger.confirmBrowserClosed(task, browser, before);
            ledger.confirmBrowserClosed(task, browser, before);
            assertEquals(started.encodeBrowserClosed(), ledger.queries().recoveries(task, "PROCESS", "browser-v1:").get(browser));
            assertThrows(SQLException.class, () -> ledger.checkpointBrowser(task, browser, before, started));
            assertThrows(IllegalArgumentException.class,
                () -> ProcessRecovery.decodeBrowserClosed(started.encode("browser.v1")));
            var invalid = new HashMap<>(started.encodeBrowserClosed().fields());
            invalid.put("unverified", "true");
            assertThrows(IllegalArgumentException.class,
                () -> ProcessRecovery.decodeBrowserClosed(new ResourceRecovery("browser.closed.v1", invalid)));
            var unstarted = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(), Optional.empty());
            var unused = new TaskResource("PROCESS", "browser-v1:" + unstarted.process(), "one", false);
            var lease = ledger.retain(task, unused, unstarted.encode("browser.v1"), false);
            ledger.confirmBrowserClosed(task, unused, unstarted);
            ledger.release(task, lease);
            ledger.confirmBrowserClosed(task, unused, unstarted);
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(task).state());
            assertEquals(started, ProcessRecovery
                .decodeBrowserClosed(ledger.queries().recoveries(task, "PROCESS", "browser-v1:").get(browser)));
            assertThrows(SQLException.class, () -> ledger.confirmBrowserClosed(task, browser, before));
        }
    }

    @Test
    void incompleteOrNoncanonicalIdentityIsNeverGuessed() {
        var original = new ProcessRecovery(UUID.randomUUID(), UUID.randomUUID(), OptionalLong.empty(),
            Optional.empty());
        assertEquals(original, ProcessRecovery.decode(original.encode()));
        for (var change : List.of(Map.entry("phase", "READY"), Map.entry("pid", "1"),
            Map.entry("bootId", "1-1-1-1-1"))) {
            var fields = new HashMap<>(original.encode().fields());
            fields.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class,
                () -> ProcessRecovery.decode(new ResourceRecovery("process.v1", fields)));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new ProcessRecovery(original.boot(), original.process(), OptionalLong.of(1), Optional.empty()));
    }
}
