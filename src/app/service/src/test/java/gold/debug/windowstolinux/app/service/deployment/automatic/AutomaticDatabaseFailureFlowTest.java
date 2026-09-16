package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.*;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseRequirement;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AutomaticDatabaseFailureFlowTest {
    @TempDir Path root;

    @Test
    void authenticationCompletionAndChangedSchemaRequireFreshInputAndApproval() throws Exception {
        Instance instance = new Instance(DatabaseEngineType.POSTGRESQL, "main", "17.2", 5432,
                "postgresql.service", "/var/lib/postgresql/main", "a".repeat(64), true);
        Target target = new Target(instance, "demo", "main", "main", true, false, "b".repeat(64),
                InitializationState.EMPTY, "");
        List<String> calls = new ArrayList<>();
        List<char[]> suppliedSecrets = new ArrayList<>();
        NativeDatabasePort port = (NativeDatabasePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NativeDatabasePort.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    int count = Collections.frequency(calls, method.getName());
                    return switch (method.getName()) {
                        case "inspectDatabase" -> new Inventory(instance.engine(), List.of(instance), Optional.empty(), List.of());
                        case "startDatabase" -> instance;
                        case "inspectDatabaseTarget" -> {
                            if (count == 1) throw new NativeDatabaseException(NativeDatabaseFailureType.AUTH_REQUIRED);
                            assertArrayEquals("fixture-admin".toCharArray(), (char[]) args[4]);
                            yield target;
                        }
                        case "prepareDatabaseTarget" -> {
                            if (count == 1) throw new NativeDatabaseException(NativeDatabaseFailureType.AUTH_REQUIRED);
                            assertArrayEquals("fixture-application".toCharArray(), (char[]) args[6]);
                            yield target;
                        }
                        case "initializeDatabase" -> {
                            if (count == 1) {
                                assertEquals(false, args[4]);
                                throw new NativeDatabaseException(NativeDatabaseFailureType.STATE_CHANGED);
                            }
                            assertTrue(calls.contains("db.existingSchema"));
                            assertEquals(true, args[4]);
                            yield new Target(instance, "demo", "main", "main", true, false, "b".repeat(64),
                                    InitializationState.COMPLETE, (String) args[1]);
                        }
                        default -> throw new AssertionError(method.getName());
                    };
                });
        DeploymentRemoteSession session = (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DeploymentRemoteSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "nativeDatabases" -> port;
                    case "close" -> { calls.add("close"); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
        var interaction = new AutomaticDeploymentInteraction() {
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                throw new AssertionError("Database inputs are complete");
            }
            public boolean confirm(String key, Map<String, ?> details) {
                calls.add(key); assertEquals("db.existingSchema", key); return true;
            }
            public char[] requestSecret(String key) {
                calls.add(key);
                char[] secret = (key.equals("db.adminPassword") ? "fixture-admin" : "fixture-application").toCharArray();
                suppliedSecrets.add(secret);
                return secret;
            }
        };
        try (var persistence = DesktopPersistence.open(root.resolve("data"))) {
            var facade = new DesktopApplicationFacade(persistence, root.resolve("work"),
                    (endpoint, credential, verifier) -> { credential.clear(); return session; });
            var profile = new ServerProfile("test", "192.0.2.1", 22, "test", "ssh-test", CredentialStorageMode.MASTER_PASSWORD);
            facade.saveServerProfile(profile, profile.credentialMode(), "fixture-master".toCharArray(), "fixture-ssh".toCharArray());
            Path source = Files.createDirectory(root.resolve("source"));
            Files.writeString(source.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
            var requirement = new DatabaseRequirement("main", DatabaseEngineType.POSTGRESQL, ">=17", "main", "main",
                    "DB", "DB_PASSWORD", List.of("schema.sql"), false, "fixture");
            var assessment = new DatabaseProjectInspector.Assessment(List.of(requirement), List.of("schema.sql"), true, false);
            char[] master = "fixture-master".toCharArray();
            var result = facade.prepareAutomaticDatabases(source, "demo", profile, assessment, master, interaction, ignored -> true, ignored -> { });
            assertEquals(1, result.bindings().size());
            var saved = persistence.applicationSecrets().findRevision(result.secrets().getFirst()).orElseThrow();
            assertTrue(saved.reference().identifier().contains("."));
            assertTrue(saved.credentialKey().matches("application-secret/[a-f0-9]{64}/1"));
            try (var store = new gold.debug.windowstolinux.app.secret.Argon2AesSecretStore(
                    persistence.encryptedSecrets(), "fixture-master".toCharArray())) {
                char[] stored = store.read(saved.credentialKey()).orElseThrow();
                try { assertArrayEquals("fixture-application".toCharArray(), stored); }
                finally { Arrays.fill(stored, '\0'); }
            }
            assertEquals(2, Collections.frequency(calls, "inspectDatabaseTarget"));
            assertEquals(2, Collections.frequency(calls, "prepareDatabaseTarget"));
            assertEquals(2, Collections.frequency(calls, "initializeDatabase"));
            assertEquals(1, Collections.frequency(calls, "db.existingSchema"));
            assertTrue(calls.contains("close"));
            assertArrayEquals(new char[master.length], master);
            suppliedSecrets.forEach(secret -> assertArrayEquals(new char[secret.length], secret));
        }
    }
}
