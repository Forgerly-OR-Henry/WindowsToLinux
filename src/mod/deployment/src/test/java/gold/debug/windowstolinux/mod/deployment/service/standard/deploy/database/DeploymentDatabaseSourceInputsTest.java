package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentDatabaseSourceInputsTest {
    @TempDir
    Path root;

    @Test
    void explicitSqliteSourceRetainsSeedAndSourceBoundInitialization() throws Exception {
        Files.writeString(root.resolve("windowstolinux-db.properties"),
                "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.initialize=schema.sql\ndb.main.seed=seed.db\n");
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        Files.write(root.resolve("seed.db"), new byte[]{1, 2, 3});
        var assessment = new DatabaseProjectInspector().inspect(root);
        var completed = DeploymentDatabaseSourceInputs.complete(root, "demo", assessment, Map.of());
        assertFalse(completed.unknownDatabase());
        assertEquals("state/store.db", completed.databases().getFirst().sqlite().orElseThrow().accessPath());
        assertEquals("seed.db", completed.databases().getFirst().sqlite().orElseThrow().seedFile());
        assertEquals("schema.sql", completed.databases().getFirst().initializationFiles().getFirst());
        Files.delete(root.resolve("seed.db"));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentDatabaseSourceInputs.complete(root, "demo", assessment, Map.of()));
    }

    @Test
    void unknownFamilyRequiresExplicitTwoStepChoiceAndCompleteConnection() throws Exception {
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        var assessment = new DatabaseProjectInspector().inspect(root);
        assertTrue(assessment.unknownDatabase());
        assertEquals("database.main.engine",
                DeploymentDatabaseSourceInputs.questions(assessment, "demo", Map.of()).getFirst().id());
        var engine = Map.of("database.main.engine", "POSTGRESQL");
        assertTrue(DeploymentDatabaseSourceInputs.questions(assessment, "demo", engine).stream()
                .anyMatch(field -> field.id().equals("database.main.username")));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentDatabaseSourceInputs.complete(root, "demo", assessment, engine));
        var complete = DeploymentDatabaseSourceInputs.complete(root, "demo", assessment,
                Map.of("database.main.engine", "POSTGRESQL", "database.main.database", "demo_main",
                        "database.main.username", "demo_main", "database.main.environmentPrefix", "APP_DB",
                        "database.main.passwordEnvironment", "APP_DB_PASSWORD", "database.main.initialize",
                        "schema.sql"));
        assertEquals(DeploymentDatabaseEngineType.POSTGRESQL, complete.databases().getFirst().engine());
        assertEquals("schema.sql", complete.databases().getFirst().initializationFiles().getFirst());
    }

    @Test
    void sourceMutationCannotBeDismissedByEmptyInitialization() throws Exception {
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        var assessment = new DatabaseProjectInspector().inspect(root);
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentDatabaseSourceInputs.complete(root, "demo", assessment, Map.of("database.main.engine",
                        "SQLITE", "database.main.path", "DEFAULT", "database.main.pathEnvironment", "DATABASE_PATH")));
    }

    /** Keeps the old seed-file scope while bounding the combined UTF-8 SQL, including separator bytes. / 保留旧种子文件范围并限制含分隔字节的组合 UTF-8 SQL。
     * @throws Exception if local source setup fails / 本机源码准备失败时 */
    @Test
    void largeSeedIsAllowedButCombinedSqlMustFit() throws Exception {
        Files.write(root.resolve("seed.db"), new byte[2 * 1024 * 1024 + 1]);
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        Files.writeString(root.resolve("windowstolinux-db.properties"),
                "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\n"
                        + "db.main.initialize=schema.sql\ndb.main.seed=seed.db\n");
        var valid = new DatabaseProjectInspector().inspect(root);
        assertEquals("seed.db", DeploymentDatabaseSourceInputs.complete(root, "demo", valid, Map.of()).databases()
                .getFirst().sqlite().orElseThrow().seedFile());
        Files.writeString(root.resolve("first.sql"), "x".repeat(1_100_000));
        Files.writeString(root.resolve("second.sql"), "y".repeat(1_100_000));
        Files.writeString(root.resolve("windowstolinux-db.properties"),
                "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\n"
                        + "db.main.initialize=first.sql,second.sql\ndb.main.seed=seed.db\n");
        var oversized = new DatabaseProjectInspector().inspect(root);
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentDatabaseSourceInputs.complete(root, "demo", oversized, Map.of()));
    }
}
