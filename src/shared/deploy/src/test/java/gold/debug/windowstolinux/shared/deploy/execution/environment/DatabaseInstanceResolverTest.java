package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;


import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.*;
import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseInstanceResolverTest {
    private final List<String> actions = new ArrayList<>();
    private List<Instance> instances = List.of();
    private boolean approve = true, stale = false, waiting = false;
    private int confirmations;
    private final PackageCandidate candidate = new PackageCandidate("postgresql","17.2","17.2");
    private static Instance instance(String id, String version, boolean running) {
        return new Instance(DatabaseEngineType.POSTGRESQL,id,version,5432,"postgresql.service","/var/lib/postgresql/"+id,"a".repeat(64),running);
    }
    private Instance resolve(String version) throws Exception {
        return DatabaseInstanceResolver.resolve(port(),"test",new DatabaseRequirement("main",DatabaseEngineType.POSTGRESQL,
                version,"demo","demo","DB","DB_PASSWORD",List.of(),false,"fixture"), interaction(), ignored -> { });
    }
    private NativeDatabasePort port() {
        return (NativeDatabasePort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{NativeDatabasePort.class},(proxy,method,args) -> {
            actions.add(method.getName());
            return switch (method.getName()) {
                case "inspectDatabase" -> new Inventory(DatabaseEngineType.POSTGRESQL,instances,Optional.of(candidate),List.of());
                case "installDatabase" -> {
                    if (stale) { stale = false; throw new NativeDatabaseException(NativeDatabaseFailureType.STATE_CHANGED); }
                    waiting = !instances.isEmpty();
                    instances = List.of(instance("target","17.2",!waiting));
                    yield new Inventory(DatabaseEngineType.POSTGRESQL,instances,Optional.of(candidate),List.of());
                }
                case "startDatabase" -> {
                    if (waiting) throw new NativeDatabaseException(NativeDatabaseFailureType.MANUAL_RESTORE_REQUIRED);
                    Instance old = (Instance)args[0]; yield instance(old.id(),old.version(),true);
                }
                case "confirmDatabaseRestored" -> { waiting = false; yield instance("target","17.2",true); }
                default -> throw new AssertionError(method.getName());
            };
        });
    }
    private AutomaticDeploymentInteraction interaction() {
        return new AutomaticDeploymentInteraction() {
            public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields) {
                return Optional.of(Map.of(fields.getFirst().id(),fields.getFirst().choices().getLast()));
            }
            public boolean confirm(String key, Map<String,?> details) { return approve; }
            public boolean confirmDatabaseReplacement(Map<String,?> details) {
                confirmations++; assertEquals("test",details.get("server")); assertEquals("17.2",details.get("target")); return approve;
            }
            public char[] requestSecret(String key) { return new char[0]; }
        };
    }
    @Test void absentInstallsThenStarts() throws Exception {
        assertTrue(resolve(">=16").running()); assertEquals(1,Collections.frequency(actions,"installDatabase"));
    }
    @Test void stoppedCompatibleNewerInstanceIsReusedWithoutInstallation() throws Exception {
        instances = List.of(instance("existing","17.1",false));
        assertEquals("existing",resolve(">=16").id()); assertFalse(actions.contains("installDatabase"));
    }
    @Test void multipleInstancesRequireSelection() throws Exception {
        instances = List.of(instance("first","17.1",true),instance("second","17.1",false));
        assertEquals("second",resolve(">=16").id()); assertFalse(actions.contains("installDatabase"));
    }
    @Test void declinedReplacementDoesNotMutate() {
        instances = List.of(instance("old","14.1",true)); approve = false;
        assertThrows(CancellationException.class, () -> resolve(">=17")); assertFalse(actions.contains("installDatabase"));
    }
    @Test void incompatibleMultipleInstancesDoNotOfferAnUnexecutableReplacementApproval() {
        instances = List.of(instance("first", "14.1", true), instance("second", "14.2", false)); approve = false;
        assertThrows(CancellationException.class, () -> resolve(">=17"));
        assertEquals(0, confirmations); assertFalse(actions.contains("installDatabase"));
    }
    @Test void staleReplacementRequiresFreshApprovalAndVerifiedRestoration() throws Exception {
        instances = List.of(instance("old","14.1",true)); stale = true;
        assertEquals("17.2",resolve(">=17").version()); assertEquals(2,confirmations);
        assertTrue(actions.contains("confirmDatabaseRestored"));
    }
    @Test void persistedRestoreWaitCannotBeBypassedByCompatibleRunningVersion() {
        instances = List.of(instance("target","17.2",true)); waiting = true; approve = false;
        var failure = assertThrows(NativeDatabaseException.class, () -> resolve(">=17"));
        assertEquals(NativeDatabaseFailureType.MANUAL_RESTORE_REQUIRED,failure.reason()); assertFalse(actions.contains("installDatabase"));
    }
}
