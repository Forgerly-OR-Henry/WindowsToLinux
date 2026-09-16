package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort;
import gold.debug.windowstolinux.shared.linux.error.*;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ExternalApplicationUseCaseTest {
    @TempDir Path directory;
    private static char[] master() { return "fixture-master-password".toCharArray(); }
    private DiscoveredApplication candidate(String hash, boolean managed) {
        return new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.SYSTEMD, "demo.service", hash), "Demo", RuntimeState.STOPPED, true, true, managed);
    }

    @Test void rechecksEachOperationAndDoesNotForgeManagedOwnership() throws Exception {
        var remote = new AtomicReference<>(candidate("a".repeat(64), false)); AtomicInteger calls = new AtomicInteger();
        ExternalApplicationPort port = new ExternalApplicationPort() {
            public ExternalApplicationScan scan() { return new ExternalApplicationScan(List.of(remote.get()), List.of(ExternalScanIssueType.DOCKER_PERMISSION)); }
            public DiscoveredApplication execute(ExternalApplicationTarget target, LifecycleAction action) throws LinuxOperationException {
                calls.incrementAndGet();
                if (!remote.get().target().equals(target)) throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED, "fixture target replaced");
                return remote.get();
            }
        };
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            credential.clear();
            return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DeploymentRemoteSession.class},
                    (proxy, method, args) -> switch (method.getName()) { case "externalApplications" -> port; case "close" -> null; default -> throw new AssertionError(method.getName()); });
        };
        try (var db = DesktopPersistence.open(directory)) {
            var servers = new ServerUseCaseFacade(db.servers(), new DesktopSecretStoreService(db.encryptedSecrets()), gateway);
            var profile = new ServerProfile("server", "example.test", 22, "tester", "ssh/server/password", CredentialStorageMode.MASTER_PASSWORD);
            servers.save(profile, profile.credentialMode(), master(), "fixture-ssh-password".toCharArray());
            var useCase = new ExternalApplicationUseCase(db.externalApplications(), db.managedApplications(), servers, gateway, new ServerOperationLockRegistry());
            var scan = useCase.scan("server", master(), ignored -> true); assertEquals(List.of(ExternalScanIssueType.DOCKER_PERMISSION), scan.issues());
            char[] input = master(); String key = useCase.adopt(scan, remote.get(), input, ignored -> true); assertArrayEquals(new char[input.length], input);
            assertEquals(key, useCase.adopt(scan, remote.get(), master(), ignored -> true));
            assertEquals(RuntimeState.STOPPED, useCase.execute(key, LifecycleAction.REFRESH_STATUS, master(), ignored -> true).state());
            remote.set(candidate("b".repeat(64), false));
            assertThrows(LinuxOperationException.class, () -> useCase.execute(key, LifecycleAction.START, master(), ignored -> true));
            var next = useCase.scan("server", master(), ignored -> true);
            String replacement = useCase.adopt(next, remote.get(), master(), ignored -> true); assertNotEquals(key, replacement);
            db.managedApplications().save(gold.debug.windowstolinux.shared.model.managed.ManagedApplication.forManaged("native",
                    new gold.debug.windowstolinux.shared.model.server.ServerIdentity(profile.id(), profile.host(), 22, "SHA256:fixture"), "d".repeat(64)));
            remote.set(new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.DOCKER, "e".repeat(64), "e".repeat(64)),
                    "windowstolinux-native", RuntimeState.STOPPED, true, true, true));
            var known = useCase.scan("server", master(), ignored -> true); assertTrue(known.adoptable(remote.get()));
            assertEquals("managed:native", useCase.adopt(known, remote.get(), master(), ignored -> true));
            int before = calls.get();
            db.servers().saveServerProfile(new ServerProfile(profile.id(), "other.test", 22, profile.username(), profile.credentialKey(), profile.credentialMode()).stored());
            assertThrows(LinuxOperationException.class, () -> useCase.execute(replacement, LifecycleAction.START, master(), ignored -> true));
            assertEquals(before, calls.get(), "changed endpoint must fail before remote operations");
            remote.set(candidate("b".repeat(64), true)); var marked = useCase.scan("server", master(), ignored -> true);
            assertFalse(marked.adoptable(remote.get()));
            assertThrows(LinuxOperationException.class, () -> useCase.adopt(marked, remote.get(), master(), ignored -> true));
            assertEquals(1, db.managedApplications().list().size()); assertEquals(1, db.externalApplications().list().size());
        }
    }
}
