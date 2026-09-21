package gold.debug.windowstolinux.shared.linux.transfer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DeploymentRemoteTaskScopeTest {
    @Test void recordsExactCandidateBeforeDispatchAndNeverLeaksIntoANewWorker(){
        var candidate=new RemoteWorkspace("app","a".repeat(64));var records=new ArrayList<RemoteWorkspace>();
        assertTrue(DeploymentRemoteTaskScope.current().isEmpty());
        try(var scope=new DeploymentRemoteTaskScope("task",records::add)){
            assertThrows(IllegalStateException.class,()->new DeploymentRemoteTaskScope("nested",w->{}));
            assertEquals("task",scope.preparing(candidate));assertEquals(List.of(candidate),records);
        }
        assertTrue(DeploymentRemoteTaskScope.current().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new DeploymentRemoteTaskScope("../escape",w->{}));
    }
    @Test void journalFailurePreventsReturningTheTaskForRemoteCreation(){
        try(var scope=new DeploymentRemoteTaskScope("task",w->{throw new IllegalStateException("disk full");})){
            assertThrows(IllegalStateException.class,()->scope.preparing(new RemoteWorkspace("app","b".repeat(64))));
        }
    }
}
