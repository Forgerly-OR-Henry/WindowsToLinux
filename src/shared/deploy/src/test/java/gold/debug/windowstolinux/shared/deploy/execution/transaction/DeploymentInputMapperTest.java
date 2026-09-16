package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.config.contract.definition.*;
import gold.debug.windowstolinux.shared.config.revision.*;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.linux.protocol.*;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentInputMapperTest {
    private static ConfigurationSnapshot snapshot() {
        return ConfigurationSnapshot.create("demo", 3, "runtime-v1", Instant.EPOCH, List.of(
                new ConfigurationEntry("Z_LABEL", ConfigurationScope.RUNTIME, new ConfigurationValue.Text("quote'\"\\ $value\ttab")),
                new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080)),
                new ConfigurationEntry("BUILD_LABEL", ConfigurationScope.BUILD, new ConfigurationValue.Text("build"))));
    }

    @Test void separatesScopesAndPreservesCanonicalBindings() {
        var snapshot = snapshot();
        assertEquals(Map.of("BUILD_LABEL", "build"), DeploymentInputMapper.build(snapshot).entries());
        var runtime = DeploymentInputMapper.runtime(snapshot);
        assertEquals(snapshot.sha256(), runtime.sha256());
        assertEquals(List.of("PORT", "Z_LABEL"), new ArrayList<>(runtime.entries().keySet()));
        assertEquals("quote'\"\\ $value\ttab", runtime.entries().get("Z_LABEL"));
        assertThrows(UnsupportedOperationException.class, () -> runtime.entries().put("OTHER", "value"));
        var onlyRuntime = ConfigurationSnapshot.create("demo", 1, "runtime-v1", Instant.EPOCH,
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080))));
        assertTrue(DeploymentInputMapper.build(onlyRuntime).entries().isEmpty());
    }

    @Test void preservesExistingSmallValuesWithoutInventingAnEntryCountLimit() {
        var entries = java.util.stream.IntStream.range(0, 130).mapToObj(index -> new ConfigurationEntry(
                "FIELD_" + index, ConfigurationScope.RUNTIME, new ConfigurationValue.Flag(true))).toList();
        var snapshot = ConfigurationSnapshot.create("demo", 1, "runtime-v1", Instant.EPOCH, entries);
        assertEquals(130, DeploymentInputMapper.runtime(snapshot).entries().size());
        assertEquals(snapshot.sha256(), DeploymentInputMapper.runtime(snapshot).sha256());
    }

    @Test void closesPayloadsOnSuccessFailureAndMismatchedRemoteEvidence() throws Exception {
        for (int outcome = 0; outcome < 3; outcome++) {
            int selected = outcome;
            List<RemoteSecretPayload> captured = new ArrayList<>();
            var session = (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, arguments) -> {
                        assertEquals("stageDeploymentInputs", method.getName());
                        @SuppressWarnings("unchecked") var values = (List<RemoteSecretPayload>) arguments[2];
                        captured.addAll(values);
                        byte[] bytes = values.getFirst().copyValue();
                        try { assertArrayEquals(new byte[] {115, 101, 99, 114, 101, 116}, bytes); }
                        finally { Arrays.fill(bytes, (byte) 0); }
                        if (selected == 1) throw new IllegalStateException("fixture staging failure");
                        return new RemoteDeploymentInputs(selected == 2 ? "0".repeat(64) : snapshot().sha256(),
                                values.stream().map(RemoteSecretPayload::digest).toList());
                    });
            try (var secret = new ResolvedSecretRevision(new SecretReference("database-password", 4), new char[]{'s','e','c','r','e','t'})) {
                if (selected == 0) {
                    var result = DeploymentInputMapper.stage(session, application(), snapshot(), List.of(secret));
                    assertEquals(secret.digest(), result.secrets().getFirst());
                } else assertThrows(IllegalStateException.class,
                        () -> DeploymentInputMapper.stage(session, application(), snapshot(), List.of(secret)));
                assertEquals(1, captured.size());
                assertThrows(IllegalStateException.class, captured.getFirst()::copyValue);
                var owned = RemoteSecretPayload.class.getDeclaredField("value");
                owned.setAccessible(true);
                assertArrayEquals(new byte[captured.getFirst().digest().byteCount()], (byte[]) owned.get(captured.getFirst()));
                assertFalse(captured.getFirst().toString().contains("secret"));
            }
        }
    }

    @Test void rejectsDuplicateBindingsAndInterruptionBeforeRemoteMutation() {
        var session = (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, args) -> { throw new AssertionError("must not reach transport"); });
        try (var secret = new ResolvedSecretRevision(new SecretReference("key-one", 1), new char[]{'x'})) {
            assertThrows(IllegalArgumentException.class,
                    () -> DeploymentInputMapper.stage(session, application(), snapshot(), List.of(secret, secret)));
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> DeploymentInputMapper.stage(session, application(), snapshot(), List.of(secret)));
            } finally { Thread.interrupted(); }
        }
    }

    private static ManagedApplication application() {
        return ManagedApplication.forManaged("demo", new ServerIdentity("server", "127.0.0.1", 22, "SHA256:fixture"), "a".repeat(64));
    }
}
