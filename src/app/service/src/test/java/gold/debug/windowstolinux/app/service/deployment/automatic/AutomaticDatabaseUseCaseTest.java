package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticDatabaseUseCaseTest {
    @TempDir Path root;

    @Test void declaredInitializationForOneDatabaseDoesNotInitializeEveryOtherDatabase() throws Exception {
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        var assessment = new DatabaseProjectInspector.Assessment(List.of(requirement("main", List.of("schema.sql")),
                requirement("audit", List.of())), List.of("schema.sql"), true, false);
        var completed = useCase().completeInputs(root, "demo", assessment, new char[0], interaction(false));
        assertEquals(List.of("schema.sql"), completed.databases().getFirst().initializationFiles());
        assertTrue(completed.databases().getLast().initializationFiles().isEmpty());
    }

    @Test void initializationFilesAreValidatedBeforeAnyRemoteDependenciesAreNeeded() {
        var assessment = new DatabaseProjectInspector.Assessment(List.of(requirement("main", List.of("missing.sql"))), List.of(), true, false);
        assertThrows(IllegalArgumentException.class, () -> useCase().completeInputs(root, "demo", assessment, new char[0], interaction(false)));
    }

    @Test void cancellingUnknownDatabaseSelectionClearsUnlockCopyWithoutConnecting() {
        char[] master = "fixture-unlock".toCharArray();
        assertThrows(CancellationException.class, () -> useCase().completeInputs(root, "demo",
                new DatabaseProjectInspector.Assessment(List.of(), List.of(), true, true), master, interaction(true)));
        assertArrayEquals(new char[master.length], master);
    }

    @Test void redisCannotBeMisclassifiedAsASqlDatasourceOrInitializer() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseRequirement("cache", DatabaseEngineType.REDIS,
                "", "cache", "cache", "CACHE", "CACHE_PASSWORD", List.of("schema.sql"), false, "fixture"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseRequirement("cache", DatabaseEngineType.REDIS,
                "", "cache", "cache", "CACHE", "CACHE_PASSWORD", List.of(), true, "fixture"));
    }

    private static DatabaseRequirement requirement(String id, List<String> sql) {
        return new DatabaseRequirement(id, DatabaseEngineType.POSTGRESQL, "", id, id,
                id.toUpperCase(Locale.ROOT), id.toUpperCase(Locale.ROOT) + "_PASSWORD", sql, false, "fixture");
    }
    private static AutomaticDatabaseUseCase useCase() {
        var ai = (AiApplicationFacade) Proxy.newProxyInstance(AiApplicationFacade.class.getClassLoader(),
                new Class<?>[]{AiApplicationFacade.class}, (proxy, method, args) -> Optional.empty());
        return new AutomaticDatabaseUseCase(null, null, null, null, null, ai);
    }
    private static AutomaticDeploymentInteraction interaction(boolean cancel) {
        return new AutomaticDeploymentInteraction() {
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                assertTrue(cancel, "declared database inputs must not be asked again"); return Optional.empty();
            }
            public boolean confirm(String key, Map<String, ?> details) { throw new AssertionError("source planning has no remote risk"); }
            public char[] requestSecret(String key) { throw new AssertionError("source planning must not ask for DB credentials"); }
        };
    }
}
