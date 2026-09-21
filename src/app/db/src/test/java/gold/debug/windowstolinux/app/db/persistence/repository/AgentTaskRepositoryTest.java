package gold.debug.windowstolinux.app.db.persistence.repository;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.model.agent.AgentTaskState;
import gold.debug.windowstolinux.shared.model.deployment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentTaskRepositoryTest {
    @TempDir Path directory;
    @Test void restartInterruptsUnfinishedTasksWithoutReplayingOrLosingEvents()throws Exception{
        try(var db=DesktopPersistence.open(directory)){
            var records=db.agentTasks();records.create("task","server","a".repeat(64),DeploymentAutomationMode.AGENT,AgentApprovalMode.AUTOMATIC,"b".repeat(64),"deployment-v1","approval-v1");
            records.event("task","ACTION_PROPOSED","op","DEPLOY_TRANSACTION:1:"+"c".repeat(64));
            records.event("task","EXECUTING","op","c".repeat(64));records.state("task",AgentTaskState.PAUSED);
            assertThrows(IllegalArgumentException.class,()->records.event("task","raw prompt","op","not accepted"));
        }
        try(var db=DesktopPersistence.open(directory)){
            var records=db.agentTasks();assertEquals("INTERRUPTED",records.recent().getFirst().get("state"));
            assertEquals(3,records.events("task").size());assertEquals(List.of("ACTION_PROPOSED","EXECUTING","STATE"),records.events("task").stream().map(e->e.get("event_type")).toList());
        }
    }
    @Test void unknownOutcomeRemainsUnknownAcrossRestart()throws Exception{
        try(var db=DesktopPersistence.open(directory)){var r=db.agentTasks();r.create("task","server","a".repeat(64),DeploymentAutomationMode.AGENT,AgentApprovalMode.FULL_CONTROL,"b".repeat(64),"deployment-v1","approval-v1");r.state("task",AgentTaskState.UNKNOWN);}
        try(var db=DesktopPersistence.open(directory)){assertEquals("UNKNOWN",db.agentTasks().recent().getFirst().get("state"));}
    }
    @Test void anUnfinishedDispatchBlocksANewTaskEvenWhenTerminalStateWasWronglyRecorded()throws Exception{
        try(var db=DesktopPersistence.open(directory)){
            var r=db.agentTasks();r.create("task","server","a".repeat(64),DeploymentAutomationMode.AGENT,AgentApprovalMode.AUTOMATIC,"b".repeat(64),"deployment-v1","approval-v1");
            assertTrue(r.unresolved("server").isEmpty());r.event("task","EXECUTING","op","c".repeat(64));r.state("task",AgentTaskState.FAILED);
            assertEquals(List.of("task"),r.unresolved("server"));assertTrue(r.unresolved("other").isEmpty());
            r.event("task","RECONCILIATION_QUERY","op","candidate=absent");assertEquals(List.of("task"),r.unresolved("server"));
            r.event("task","RESULT","different-action","known");assertEquals(List.of("task"),r.unresolved("server"));
            r.event("task","RESULT","op","known");assertTrue(r.unresolved("server").isEmpty());
        }
    }
}
