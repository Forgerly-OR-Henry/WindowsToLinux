package gold.debug.windowstolinux.app.service.execution.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.SecurityTestFixture;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.*;
import gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalApplicationUseCaseTest extends SecurityTestFixture {
    @TempDir
    Path directory;
    private static char[] master() {
        return "fixture-master-password".toCharArray();
    }

    private DiscoveredApplication candidate(String hash, boolean managed) {
        return new DiscoveredApplication(
                new ExternalApplicationTarget(ExternalApplicationKind.SYSTEMD, "demo.service", hash), "Demo",
                RuntimeState.STOPPED, true, true, managed);
    }

    @Test
    void rechecksEachOperationAndDoesNotForgeManagedOwnership() throws Exception {
        var remote = new AtomicReference<>(candidate("a".repeat(64), false));
        AtomicInteger calls = new AtomicInteger();
        ExternalApplicationPort port = new ExternalApplicationPort() {
            public ExternalApplicationScan scan() {
                return new ExternalApplicationScan(List.of(remote.get()),
                        List.of(ExternalScanIssueType.DOCKER_PERMISSION));
            }

            public DiscoveredApplication execute(ExternalApplicationTarget target, LifecycleAction action)
                    throws LinuxOperationException {
                calls.incrementAndGet();
                if (!remote.get().target().equals(target))
                    throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED,
                            "fixture target replaced");
                return remote.get();
            }
        };
        DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
            credential.clear();
            return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "externalApplications" -> port;
                        case "close" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
        };
        try (var db = DesktopPersistence.open(directory)) {
            var servers = new ServerUseCaseFacade(db.servers(), SecurityTestFixture.secrets(db), gateway);
            var profile = new ServerProfile("server", "example.test", 22, "tester", "ssh/server/password");
            servers.save(profile, "fixture-ssh-password".toCharArray());
            var useCase = new ExternalApplicationUseCase(db.externalApplications(), db.managedApplications(), servers,
                    gateway, new ServerOperationLockRegistry());
            var scan = useCase.scan("server", ignored -> true);
            assertEquals(List.of(ExternalScanIssueType.DOCKER_PERMISSION), scan.issues());
            char[] input = master();
            String key = useCase.adopt(scan, remote.get(), ignored -> true);
            assertEquals(key, useCase.adopt(scan, remote.get(), ignored -> true));
            assertEquals(RuntimeState.STOPPED,
                    useCase.execute(key, LifecycleAction.REFRESH_STATUS, ignored -> true).state());
            remote.set(candidate("b".repeat(64), false));
            assertThrows(LinuxOperationException.class,
                    () -> useCase.execute(key, LifecycleAction.START, ignored -> true));
            var next = useCase.scan("server", ignored -> true);
            String replacement = useCase.adopt(next, remote.get(), ignored -> true);
            assertNotEquals(key, replacement);
            db.managedApplications()
                    .save(gold.debug.windowstolinux.shared.model.managed.ManagedApplication.forManaged("native",
                            new gold.debug.windowstolinux.shared.model.server.ServerIdentity(profile.id(),
                                    profile.host(), 22, "SHA256:fixture"),
                            "d".repeat(64)));
            remote.set(new DiscoveredApplication(
                    new ExternalApplicationTarget(ExternalApplicationKind.DOCKER, "e".repeat(64), "e".repeat(64)),
                    "windowstolinux-native", RuntimeState.STOPPED, true, true, true));
            var known = useCase.scan("server", ignored -> true);
            assertTrue(known.adoptable(remote.get()));
            assertEquals("managed:native", useCase.adopt(known, remote.get(), ignored -> true));
            int before = calls.get();
            assertThrows(java.sql.SQLException.class, () -> db.servers().saveServerProfile(
                    new ServerProfile(profile.id(), "other.test", 22, profile.username(), profile.credentialKey())
                            .stored()));
            assertEquals(before, calls.get(), "changed endpoint must fail before remote operations");
            remote.set(candidate("b".repeat(64), true));
            var marked = useCase.scan("server", ignored -> true);
            assertFalse(marked.adoptable(remote.get()));
            assertThrows(LinuxOperationException.class, () -> useCase.adopt(marked, remote.get(), ignored -> true));
            assertEquals(1, db.managedApplications().list().size());
            assertEquals(1, db.externalApplications().list().size());
        }
    }
}
