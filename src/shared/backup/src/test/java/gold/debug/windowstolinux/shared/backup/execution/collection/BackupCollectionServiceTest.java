package gold.debug.windowstolinux.shared.backup.execution.collection;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import gold.debug.windowstolinux.shared.backup.contract.definition.*;
import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupCollectionServiceTest {
    @TempDir Path directory;
    private final List<String> calls = new ArrayList<>();
    private final Map<String, RuntimeState> states = new HashMap<>(Map.of("store", RuntimeState.RUNNING, "api", RuntimeState.RUNNING));
    private String stopFailure, startFailure;
    private boolean collectFailure, cleanupFailure, maintenanceFailure, interrupted;
    private String operation = "backup-" + "a".repeat(32);
    private BackupCollectionRequest request(boolean owned) {
        var start = List.of("store", "api"); var reverse = List.of("api", "store");
        var plan = new MultiComponentDeploymentPlan("demo", List.of(List.of("store"), List.of("api")), reverse, start, start, reverse,
                Map.of("store", "demo-store", "api", "demo-api"), Map.of("store", List.of(), "api", List.of("store")));
        var health = new HealthCheck.Tcp(18080, 10, 1);
        var components = start.stream().map(id -> new BackupCollectionRequest.Component(id,
                ManagedApplication.forManaged(id, new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture"), "a".repeat(64)),
                new DeploymentRuntimeSpecification.NodeService(22, health), "b".repeat(64),
                new ManagedComponentResourceBindings(List.of(), Optional.of(List.of())))).toList();
        return new BackupCollectionRequest(plan, components, operation, owned, new ApplicationHealthGate("api", health),
                Set.copyOf(start), Optional.empty(), 4L << 30);
    }
    private BackupCollectionResult collect(boolean owned) throws Exception {
        return new BackupCollectionService(BackupArchivePolicy.defaults()).collect(request(owned), session(),
                new BackupCollectionMaterialPort() {
                    @Override public Path member(String name) throws IOException { Path path=directory.resolve(name); Files.createDirectories(path.getParent()); return path; }
                    @Override public OutputStream open(Path path) throws IOException { return Files.newOutputStream(path); }
                }, new BackupCollectionInteraction() {
                    @Override public void checkCancelled() throws InterruptedException {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("fixture cancellation");
                    }
                    @Override public void collecting(String id) { }
                });
    }
    @Test void collectsInDependencyOrderAndRestoresOnlyOriginallyRunningComponents() throws Exception {
        states.put("store", RuntimeState.STOPPED);
        var result=collect(true);
        assertEquals(2,result.materials().size());
        assertEquals(RuntimeState.STOPPED,result.originalStates().get("store").runtimeState());
        assertFalse(calls.contains("stop:store")); assertFalse(calls.contains("start:store"));
        assertTrue(calls.indexOf("collect:store") < calls.indexOf("collect:api"));
        assertTrue(calls.indexOf("health:api") < calls.indexOf("end:api"));
        assertEquals("discard", calls.getLast());
    }
    @Test void partialStopFailureStillRecoversEveryComponentWhoseStopWasAttempted() throws Exception {
        stopFailure="store";
        assertThrows(LinuxOperationException.class, () -> collect(true));
        assertTrue(calls.indexOf("stop:api") < calls.indexOf("stop:store"));
        assertTrue(calls.indexOf("start:store") < calls.indexOf("start:api"));
        assertFalse(calls.stream().anyMatch(s -> s.startsWith("collect:")));
        assertEquals(Set.of(RuntimeState.RUNNING),new HashSet<>(states.values()));
        assertTrue(calls.contains("end:store"));
    }
    @Test void cancellationClearsTheFlagForRecoveryAndRestoresItBeforeReturning() throws Exception {
        interrupted=true;
        try {
            assertThrows(InterruptedException.class, () -> collect(true));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(calls.contains("start:store")); assertTrue(calls.contains("start:api"));
            assertTrue(calls.contains("end:api")); assertTrue(calls.contains("discard"));
        } finally { Thread.interrupted(); }
    }
    @Test void failedRecoveryRetainsMarkersAndRemoteMaterialAndBothFailures() throws Exception {
        collectFailure=true; startFailure="store";
        var failure=assertThrows(BackupException.class, () -> collect(true));
        assertEquals(BackupFailureType.COLLECTION_RECOVERY_FAILED,failure.failure().definition());
        assertEquals(FailureRecoveryDisposition.UNVERIFIED,failure.failure().recoveryDisposition());
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",failure.failure().operationIdentity().toString());
        assertTrue(failure.getCause().getCause() instanceof LinuxOperationException);
        assertEquals(1,failure.getCause().getSuppressed().length);
        assertTrue(calls.contains("start:api"));
        assertFalse(calls.contains("discard")); assertFalse(calls.stream().anyMatch(s -> s.startsWith("end:")));
    }
    @Test void cleanupErrorsAreSuppressedWithoutReplacingCollectionFailure() throws Exception {
        collectFailure=true; cleanupFailure=true;
        var failure=assertThrows(LinuxOperationException.class, () -> collect(true));
        assertEquals(LinuxOperationFailureType.BACKUP_ARTIFACT_CREATION_FAILED,failure.failure().definition());
        assertEquals(3,failure.getSuppressed().length);
        assertTrue(calls.contains("end:api")); assertTrue(calls.contains("end:store")); assertTrue(calls.contains("discard"));
    }
    @Test void callerOwnedMaintenanceIsNeverReleased() throws Exception {
        collect(false);
        assertTrue(calls.contains("begin:store"));
        assertFalse(calls.stream().anyMatch(s -> s.startsWith("end:")));
    }
    @Test void acquisitionFailureReleasesOnlyAcknowledgedMarkersWithoutStopping() throws Exception {
        maintenanceFailure=true;
        assertThrows(LinuxOperationException.class, () -> collect(true));
        assertTrue(calls.contains("end:store")); assertFalse(calls.contains("end:api"));
        assertFalse(calls.stream().anyMatch(s -> s.startsWith("stop:") || s.startsWith("start:")));
    }
    private DeploymentRemoteSession session() throws Exception {
        byte[] content=pax(); String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DeploymentRemoteSession.class,
                gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort.class,
                gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort.class},(proxy,method,args) -> {
            String id=args!=null && args.length>0 && args[0] instanceof ManagedApplication app ? app.id() : "";
            return switch(method.getName()) {
                case "backupArtifacts", "databaseOperations" -> proxy;
                case "collectCapabilities" -> new ServerCapabilityFacts("Ubuntu 24.04","x86_64",true,true,true,true,true,true,true,true,
                        ManagedHelperProtocolVersion.CURRENT,16L<<30,"fixture");
                case "collectDeploymentCapabilities" -> linuxFacts();
                case "observeDeployment" -> observation((ManagedApplication)args[0],states.get(id));
                case "beginMaintenance", "endMaintenance" -> {
                    assertEquals(operation,args[1]);
                    boolean begin=method.getName().equals("beginMaintenance");calls.add((begin?"begin:":"end:")+id);
                    if (begin && maintenanceFailure && id.equals("api") || !begin && cleanupFailure) throw remoteFailure();
                    yield null;
                }
                case "executeDeploymentLifecycle" -> {
                    boolean stop=args[2]==LifecycleAction.STOP; calls.add((stop?"stop:":"start:")+id);
                    assertFalse(Thread.currentThread().isInterrupted(),"recovery must not inherit collection cancellation");
                    states.put(id,stop?RuntimeState.STOPPED:RuntimeState.RUNNING);
                    if (stop && id.equals(stopFailure) || !stop && id.equals(startFailure)) throw remoteFailure();
                    yield observation((ManagedApplication)args[0],states.get(id));
                }
                case "checkDeploymentHealth" -> { calls.add("health:"+id);yield new HealthCheckResult(true,"fixture"); }
                case "createBackupArtifact" -> {
                    var request=(RemoteBackupArtifactRequest)args[0];calls.add("collect:"+request.componentId());
                    if(collectFailure)throw remoteFailure();
                    yield new RemoteBackupArtifact(operation,"artifact-"+"b".repeat(32),request.kind(),content.length,sha);
                }
                case "copyBackupArtifact" -> {
                    ((OutputStream)args[1]).write(content);if(interrupted)Thread.currentThread().interrupt();yield null;
                }
                case "discardBackupOperation" -> { calls.add("discard");yield new RemoteStepResult(!cleanupFailure,false,"fixture"); }
                default -> throw new AssertionError(method.getName());
            };
        });
    }
    private static LinuxOperationException remoteFailure() {
        return LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_CREATION_FAILED,"fixture");
    }
    private static LifecycleObservation observation(ManagedApplication application, RuntimeState state) {
        return new LifecycleObservation(application, state, AutostartState.ENABLED, true,
                Instant.parse("2026-08-22T00:00:00Z"), "fixture");
    }

    private static LinuxCapabilityFacts linuxFacts() {
        return new LinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04", "x86_64", "apt", "amd64",
                true, false, false, false, Set.of(21), Set.of(22), true, true, Set.of("3.12"), true,
                Map.of(), Map.of(), false, false, CpuMicroarchitectureLevel.X86_64_V3, Set.of("sse4_2"),
                new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                        LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE), "fixture");
    }

    private static byte[] pax() throws Exception {
        byte[] payload = "managed-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes)) {
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry("payload.txt");
            entry.setSize(payload.length);
            entry.setModTime(0L);
            archive.putArchiveEntry(entry);
            archive.write(payload);
            archive.closeArchiveEntry();
            archive.finish();
        }
        return bytes.toByteArray();
    }

}
