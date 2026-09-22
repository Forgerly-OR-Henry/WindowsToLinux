package gold.debug.windowstolinux.shared.standard.deploy.execution.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePreparationTest {
    @TempDir
    Path root;

    private final AutomaticDeploymentInteraction noQuestions = new AutomaticDeploymentInteraction() {
        public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
            fail("Unexpected input: " + fields);
            return Optional.empty();
        }

        public boolean confirm(String key, Map<String, ?> details) {
            fail("Unexpected confirmation: " + key);
            return false;
        }

        public char[] requestSecret(String key) {
            fail("SQLite must never request a password");
            return new char[0];
        }
    };
    @Test
    void defaultFileIsPassedToApplicationWithoutAnyServerPortOrCredential() throws Exception {
        var requirement = new DatabaseRequirement("main", DatabaseEngineType.SQLITE, "", "", "", "", "", List.of(),
                false, "manifest", Optional.of(SqliteFileRequirement.fromPath(null, "APP_DATABASE_FILE", "")));
        var assessment = new DatabaseProjectInspector.Assessment(List.of(requirement), List.of(), false, false, false);
        var service = new NativeDatabasePreparationService(null);
        var completed = service.completeInputs(root, "demo", assessment, noQuestions);
        var result = service.prepare(root, "demo", "server", null, completed, noQuestions, ignored -> {
        });
        assertTrue(result.secrets().isEmpty());
        var sqlite = (ManagedDatabaseConnection.Sqlite) result.bindings().getFirst().connection();
        assertEquals("/var/opt/windowstolinux/apps/demo/databases/main/application.db",
                sqlite.physicalPath("demo", "main"));
        assertTrue(result.configuration().getFirst().value().toString().contains(sqlite.physicalPath("demo", "main")));
    }

    @Test
    void preservesRelativeAccessAndRejectsADeclaredCrossApplicationPath() throws Exception {
        for (String path : List.of("db/app.db", "/opt/windowstolinux/apps/other/db/app.db")) {
            var requirement = new DatabaseRequirement("main", DatabaseEngineType.SQLITE, "", "", "", "", "", List.of(),
                    true, "spring", Optional.of(SqliteFileRequirement.fromPath(path, "", "")));
            var assessment = new DatabaseProjectInspector.Assessment(List.of(requirement), List.of(), false, false,
                    false);
            var service = new NativeDatabasePreparationService(null);
            if (path.startsWith("/"))
                assertThrows(IllegalArgumentException.class,
                        () -> service.completeInputs(root, "demo", assessment, noQuestions));
            else {
                var result = service.prepare(root, "demo", "server", null,
                        service.completeInputs(root, "demo", assessment, noQuestions), noQuestions, ignored -> {
                        });
                assertTrue(result.configuration().getFirst().value().toString().contains("jdbc:sqlite:db/app.db"));
                assertEquals("/opt/windowstolinux/apps/demo/persistent/databases/main/app.db",
                        ((ManagedDatabaseConnection.Sqlite) result.bindings().getFirst().connection())
                                .physicalPath("demo", "main"));
            }
        }
    }
}
