package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.linux.connection.*;
import gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer;
import gold.debug.windowstolinux.shared.linux.error.*;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.*;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class SelinuxPreparationServiceTest {
    @Test void declinedSystemConsentNeverMutatesTheServer() {
        Target target = new Target();
        assertThrows(DeploymentApprovalException.class, () -> target.run(plan -> false));
        assertEquals(List.of(), target.mutations);
        assertEquals(target.connections, target.closed);
    }

    @Test void approvedRebootWaitsAndCommitsOnlyAfterAnotherAuthenticatedConnection() throws Exception {
        Target target = new Target();
        target.run(plan -> {
            assertEquals(LinuxSecurityState.DISABLED, plan.securityState());
            assertEquals("server-one", plan.serverId());
            return true;
        });
        assertEquals(List.of("reboot", "enforce", "commit"), target.mutations);
        assertEquals(SelinuxPreparationState.COMPLETE, target.state);
        assertEquals(target.connections, target.closed);
        target.run(plan -> fail("Complete system preparation must not ask again"));
        assertEquals(3, target.mutations.size());
    }

    @Test void absentRebootTimesOutWithoutTryingEnforcementOrAnotherReboot() {
        Target target = new Target();
        target.rebootCompletes = false;
        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () -> target.run(plan -> true));
        assertTrue(failure.getMessage().contains("Timed out"));
        assertEquals(List.of("reboot"), target.mutations);
        assertEquals(target.connections, target.closed);
    }

    @Test void aChangedHostKeyIsRejectedDespiteTheOriginalPermissiveTrustCallback() {
        Target target = new Target();
        target.changeHost = true;
        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () -> target.run(plan -> true));
        assertEquals(LinuxOperationFailureType.HOST_KEY_REJECTED.code(), failure.failure().code());
        assertEquals(List.of("reboot"), target.mutations);
        assertEquals(2, target.connections);
    }

    @Test void authenticationFailureIsNotRetriedAsRebootDelay() {
        Target target = new Target();
        target.authenticationFailure = true;
        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () -> target.run(plan -> true));
        assertEquals(LinuxOperationFailureType.AUTHENTICATION_FAILED.code(), failure.failure().code());
        assertEquals(2, target.connections);
        assertEquals(List.of("reboot"), target.mutations);
    }

    @Test void incompletePriorEnforcementCanBeConfirmedAndCommittedWithoutReboot() throws Exception {
        Target target = new Target();
        target.state = SelinuxPreparationState.ENFORCEMENT_PENDING;
        target.security = LinuxSecurityState.ENFORCING;
        target.run(plan -> true);
        assertEquals(List.of("commit"), target.mutations);
    }

    @Test void enforcementFailureStopsBeforeCommitAndKeepsItsDiagnostics() {
        Target target = new Target();
        target.enforcementFailure = true;
        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () -> target.run(plan -> true));
        assertTrue(failure.getMessage().contains("unresolved-avc"));
        assertEquals(List.of("reboot"), target.mutations);
    }

    @Test void unrelatedDistributionDoesNotAskOrChangeSystemConfiguration() throws Exception {
        Target target = new Target();
        target.applicable = false;
        target.run(plan -> fail("Unrelated target must not request SELinux changes"));
        assertTrue(target.mutations.isEmpty());
    }

    @Test void interruptedRebootWaitStopsAndPreservesTheInterruptFlag() {
        Target target = new Target();
        try {
            target.interruptAfterReboot = true;
            assertThrows(LinuxOperationException.class, () -> target.run(plan -> true));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("reboot"), target.mutations);
        } finally {
            Thread.interrupted();
        }
    }

    private static final class Target {
        final List<String> mutations = new ArrayList<>();
        int connections, closed, enforcingConnection;
        boolean rebootCompletes = true, changeHost, authenticationFailure, enforcementFailure, applicable = true, interruptAfterReboot;
        SelinuxPreparationState state = SelinuxPreparationState.UNPREPARED;
        LinuxSecurityState security = LinuxSecurityState.DISABLED;
        String boot = "11111111-1111-1111-1111-111111111111";

        void run(Predicate<SelinuxPreparationPlan> confirmation) throws Exception {
            var credential = new SshCredential.Password("test-only".toCharArray());
            try {
                new SelinuxPreparationService(Duration.ofMillis(30), Duration.ofMillis(1)).prepare(this::connect,
                        new SshEndpoint("server-one", "example.test", 22, "root"), credential,
                        (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING, confirmation);
            } finally { credential.clear(); }
        }

        LinuxRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator verifier)
                throws LinuxOperationException {
            connections++;
            credential.clear();
            HostKeyObservation key = new HostKeyObservation(connections > 1 && changeHost ? "changed" : "known", "legacy");
            if (verifier.verify(endpoint, key) == HostKeyDecision.REJECT) {
                throw LinuxOperationException.create(LinuxOperationFailureType.HOST_KEY_REJECTED, "changed key");
            }
            if (connections > 1 && authenticationFailure) {
                throw LinuxOperationException.create(LinuxOperationFailureType.AUTHENTICATION_FAILED, "authentication failed");
            }
            assertTrue(verifier.authenticated(endpoint, key));
            if (state == SelinuxPreparationState.REBOOT_PENDING && rebootCompletes) {
                state = SelinuxPreparationState.READY_TO_ENFORCE;
                security = LinuxSecurityState.PERMISSIVE;
                boot = "22222222-2222-2222-2222-222222222222";
            }
            return new Session(this, connections);
        }
    }

    private static final class Session implements LinuxRemoteSession, SelinuxEnvironmentPreparer {
        private final Target target;
        private final int connection;
        Session(Target target, int connection) { this.target = target; this.connection = connection; }
        @Override public SelinuxEnvironmentPreparer selinuxPreparation() { return this; }
        @Override public Optional<SelinuxPreparationPlan> inspect() {
            return target.applicable ? Optional.of(new SelinuxPreparationPlan("server-one", target.boot, "a".repeat(64), target.security, target.state)) : Optional.empty();
        }
        @Override public void prepareReboot(SelinuxPreparationPlan approved) {
            target.mutations.add("reboot"); target.state = SelinuxPreparationState.REBOOT_PENDING;
            if (target.interruptAfterReboot) Thread.currentThread().interrupt();
        }
        @Override public void enableEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException {
            if (target.enforcementFailure) throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED, "unresolved-avc");
            target.mutations.add("enforce"); target.enforcingConnection = connection;
            target.state = SelinuxPreparationState.ENFORCEMENT_PENDING; target.security = LinuxSecurityState.ENFORCING;
        }
        @Override public void commitEnforcement(SelinuxPreparationPlan approved) {
            assertTrue(connection > target.enforcingConnection);
            target.mutations.add("commit"); target.state = SelinuxPreparationState.COMPLETE;
        }
        @Override public void close() { target.closed++; }
        @Override public ServerCapabilityFacts collectCapabilities() { throw new AssertionError(); }
        @Override public EnvironmentSetupResult prepareEnvironment(EnvironmentSetupApproval approval) { throw new AssertionError(); }
        @Override public SourceUploadResult uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace, long max) { throw new AssertionError(); }
        @Override public HealthCheckResult checkHealth(ManagedApplication app, HealthCheck health) { throw new AssertionError(); }
        @Override public LifecycleObservation observe(ManagedApplication app) { throw new AssertionError(); }
        @Override public LifecycleObservation executeLifecycle(ManagedApplication app, LifecycleAction action, HealthCheck health) { throw new AssertionError(); }
    }
}
