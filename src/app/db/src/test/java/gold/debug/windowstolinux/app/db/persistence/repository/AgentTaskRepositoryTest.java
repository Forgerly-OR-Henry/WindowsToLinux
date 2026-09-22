package gold.debug.windowstolinux.app.db.persistence.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.model.agent.AgentTaskState;
import gold.debug.windowstolinux.shared.model.deployment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTaskRepositoryTest {
    @TempDir
    Path directory;
    @Test
    void legacyAgentSemanticsArePreservedByTransactionalMigration() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            db.agentTasks().create("old", "server", "a".repeat(64), DeploymentAutomationMode.AGENT,
                    AgentApprovalMode.MANUAL_REVIEW, "b".repeat(64), "deployment-v1", "approval-v1");
        }
        try (var connection = java.sql.DriverManager
                .getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db"));
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX deployment_agent_operation_evidence");
            statement.execute("ALTER TABLE deployment_agent_task DROP COLUMN execution_semantics_version");
            statement.execute("PRAGMA user_version=19");
        }
        try (var db = DesktopPersistence.open(directory)) {
            var old = db.agentTasks().recent().stream().filter(r -> r.get("id").equals("old")).findFirst()
                    .orElseThrow();
            assertEquals("1", old.get("execution_semantics_version"));
            assertEquals("AGENT", old.get("automation_mode"));
            db.agentTasks().create("new", "server", "a".repeat(64), DeploymentAutomationMode.AGENT,
                    AgentApprovalMode.FULL_CONTROL, "b".repeat(64), "autonomous-v2", "approval-v1");
            assertEquals("2", db.agentTasks().recent().stream().filter(r -> r.get("id").equals("new")).findFirst()
                    .orElseThrow().get("execution_semantics_version"));
            assertEquals("INTERRUPTED", old.get("state"));
        }
    }

    @Test
    void commandAndPatchIntentsRemainBlockedAcrossRestartAndUnrelatedReceipts() throws Exception {
        for (String kind : List.of("COMMAND", "PATCH")) {
            Path database = directory.resolve(kind);
            try (var db = DesktopPersistence.open(database)) {
                var records = db.agentTasks();
                records.create("task", "server", "a".repeat(64), DeploymentAutomationMode.AGENT,
                        AgentApprovalMode.AUTOMATIC, "b".repeat(64), "v2", "v2");
                records.event("task", kind + "_EXECUTING", "write", "a".repeat(64));
            }
            try (var db = DesktopPersistence.open(database)) {
                var records = db.agentTasks();
                assertEquals(List.of("task"), records.unresolved("server"));
                records.event("task", "RECONCILIATION_QUERY", "write", "candidate=absent");
                records.event("task", (kind.equals("COMMAND") ? "PATCH" : "COMMAND") + "_RESULT", "write", "known");
                assertEquals(List.of("task"), records.unresolved("server"));
                records.event("task", kind + "_RESULT", "write", "known");
                assertTrue(records.unresolved("server").isEmpty());
            }
        }
    }

    @Test
    void restartInterruptsUnfinishedTasksWithoutReplayingOrLosingEvents() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var records = db.agentTasks();
            records.create("task", "server", "a".repeat(64), DeploymentAutomationMode.AGENT,
                    AgentApprovalMode.AUTOMATIC, "b".repeat(64), "deployment-v1", "approval-v1");
            records.event("task", "ACTION_PROPOSED", "op", "DEPLOY_TRANSACTION:1:" + "c".repeat(64));
            records.event("task", "EXECUTING", "op", "c".repeat(64));
            records.state("task", AgentTaskState.PAUSED);
            assertThrows(IllegalArgumentException.class,
                    () -> records.event("task", "raw prompt", "op", "not accepted"));
        }
        try (var db = DesktopPersistence.open(directory)) {
            var records = db.agentTasks();
            assertEquals("INTERRUPTED", records.recent().getFirst().get("state"));
            assertEquals(3, records.events("task").size());
            assertEquals(List.of("ACTION_PROPOSED", "EXECUTING", "STATE"),
                    records.events("task").stream().map(e -> e.get("event_type")).toList());
        }
    }

    @Test
    void unknownOutcomeRemainsUnknownAcrossRestart() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var r = db.agentTasks();
            r.create("task", "server", "a".repeat(64), DeploymentAutomationMode.AGENT, AgentApprovalMode.FULL_CONTROL,
                    "b".repeat(64), "deployment-v1", "approval-v1");
            r.state("task", AgentTaskState.UNKNOWN);
        }
        try (var db = DesktopPersistence.open(directory)) {
            assertEquals("UNKNOWN", db.agentTasks().recent().getFirst().get("state"));
        }
    }

    @Test
    void anUnfinishedDispatchBlocksANewTaskEvenWhenTerminalStateWasWronglyRecorded() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var r = db.agentTasks();
            r.create("task", "server", "a".repeat(64), DeploymentAutomationMode.AGENT, AgentApprovalMode.AUTOMATIC,
                    "b".repeat(64), "deployment-v1", "approval-v1");
            assertTrue(r.unresolved("server").isEmpty());
            r.event("task", "EXECUTING", "op", "c".repeat(64));
            r.state("task", AgentTaskState.FAILED);
            assertEquals(List.of("task"), r.unresolved("server"));
            assertTrue(r.unresolved("other").isEmpty());
            r.event("task", "REMOTE_TARGET", "", "d".repeat(64));
            assertEquals(List.of("task"), r.unresolved("other-profile", "d".repeat(64)));
            r.event("task", "RECONCILIATION_QUERY", "op", "candidate=absent");
            assertEquals(List.of("task"), r.unresolved("server"));
            r.event("task", "RESULT", "different-action", "known");
            assertEquals(List.of("task"), r.unresolved("server"));
            r.event("task", "RESULT", "op", "known");
            assertTrue(r.unresolved("server").isEmpty());
        }
    }
}
