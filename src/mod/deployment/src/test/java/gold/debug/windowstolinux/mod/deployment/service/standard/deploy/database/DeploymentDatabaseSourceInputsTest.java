package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentDatabaseSourceInputsTest {
    @TempDir
    Path root;

    /** Keeps Redis declaration separate from both SQL initialization and a JDBC datasource. / 将Redis声明与SQL初始化及JDBC数据源分别隔离。
     * @param initializer whether the rejected input declares SQL files rather than a datasource / 被拒输入是否声明SQL文件而非数据源 */
    @ParameterizedTest(name = "Redis SQL initializer={0}")
    @ValueSource(booleans = {false, true})
    void redisCannotBeMisclassifiedAsASqlDatasourceOrInitializer(boolean initializer) {
        var valid = new DatabaseRequirement("cache", DeploymentDatabaseEngineType.REDIS, "", "cache", "cache",
            "CACHE", "CACHE_PASSWORD", List.of(), false, "fixture");
        assertEquals(DeploymentDatabaseEngineType.REDIS, valid.engine());
        assertThrows(IllegalArgumentException.class, () -> new DatabaseRequirement("cache",
            DeploymentDatabaseEngineType.REDIS, "", "cache", "cache", "CACHE", "CACHE_PASSWORD",
            initializer ? List.of("schema.sql") : List.of(), !initializer, "fixture"));
    }

    /** Keeps one component database's SQL files separate from the global candidate list and other databases. / 将一个组件数据库的SQL文件与全局候选清单及其它数据库隔离。
     * @throws Exception if actual source declarations cannot be prepared or read / 无法准备或读取实际源码声明时 */
    @Test
    void declaredInitializationForOneDatabaseDoesNotInitializeEveryOtherDatabase() throws Exception {
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        Files.writeString(root.resolve("windowstolinux-db.properties"), """
            db=main,audit
            db.main.engine=POSTGRESQL
            db.main.database=main
            db.main.username=main
            db.main.environmentPrefix=MAIN
            db.main.passwordEnvironment=MAIN_PASSWORD
            db.main.initialize=schema.sql
            db.audit.engine=POSTGRESQL
            db.audit.database=audit
            db.audit.username=audit
            db.audit.environmentPrefix=AUDIT
            db.audit.passwordEnvironment=AUDIT_PASSWORD
            """);
        var assessment = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertEquals(List.of("schema.sql"), assessment.sqlCandidates());
        assertEquals(List.of("schema.sql"), assessment.databases().getFirst().initializationFiles());
        assertTrue(assessment.databases().getLast().initializationFiles().isEmpty());
        var completed = DeploymentDatabaseSourceInputs.complete(root, "demo", assessment, Map.of());
        assertEquals(List.of("main", "audit"), completed.databases().stream().map(DatabaseRequirement::id).toList());
        assertEquals(List.of("schema.sql"), completed.databases().getFirst().initializationFiles());
        assertTrue(completed.databases().getLast().initializationFiles().isEmpty());
        assertEquals(assessment.sqlCandidates(), completed.sqlCandidates());
    }

    /** Validates a declared missing SQL source through the actual pure completion API. / 通过实际纯补齐API核验已声明但缺失的SQL源码。
     * @throws Exception if the local declaration cannot be written or parsed / 无法写入或解析本地声明时 */
    @Test
    void initializationFilesAreValidatedBeforeAnyRemoteDependenciesAreNeeded() throws Exception {
        Files.writeString(root.resolve("windowstolinux-db.properties"), """
            db=main
            db.main.engine=POSTGRESQL
            db.main.database=main
            db.main.username=main
            db.main.environmentPrefix=MAIN
            db.main.passwordEnvironment=MAIN_PASSWORD
            db.main.initialize=missing.sql
            """);
        var assessment = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertEquals(List.of("missing.sql"), assessment.databases().getFirst().initializationFiles());
        assertEquals(List.of(), assessment.sqlCandidates());
        assertFalse(Files.exists(root.resolve("missing.sql")));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentDatabaseSourceInputs.complete(root, "demo", assessment, Map.of()));
    }

    @Test
    void explicitSqliteSourceRetainsSeedAndSourceBoundInitialization() throws Exception {
        Files.writeString(root.resolve("windowstolinux-db.properties"),
            "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.initialize=schema.sql\ndb.main.seed=seed.db\n");
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        Files.write(root.resolve("seed.db"), new byte[]{1, 2, 3});
        var assessment = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
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
        var assessment = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
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
        var assessment = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
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
        var valid = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertEquals("seed.db", DeploymentDatabaseSourceInputs.complete(root, "demo", valid, Map.of()).databases()
            .getFirst().sqlite().orElseThrow().seedFile());
        Files.writeString(root.resolve("first.sql"), "x".repeat(1_100_000));
        Files.writeString(root.resolve("second.sql"), "y".repeat(1_100_000));
        Files.writeString(root.resolve("windowstolinux-db.properties"),
            "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\n"
                + "db.main.initialize=first.sql,second.sql\ndb.main.seed=seed.db\n");
        var oversized = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentDatabaseSourceInputs.complete(root, "demo", oversized, Map.of()));
    }
}
