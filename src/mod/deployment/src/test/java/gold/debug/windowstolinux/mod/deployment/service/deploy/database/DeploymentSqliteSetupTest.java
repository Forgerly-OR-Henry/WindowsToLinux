package gold.debug.windowstolinux.mod.deployment.service.deploy.database;

import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentSqliteSetup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.BoundedSourceChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that SQLite review is source-bound, separately confirmed and carries seed and SQL into the publication contract. / 验证 SQLite 批准绑定源码、单独确认，并将种子与 SQL 带入发布契约。 */
class DeploymentSqliteSetupTest {
    @TempDir
    Path root;

    /** A source declaration alone cannot approve initialization, and changing inspected bytes changes the proof. / 源码声明本身不能批准初始化，变更检查字节会改变凭据。 */
    @Test
    void plannedFirstInitializationNeedsSeparateConsentAndExactInspectedSource() throws Exception {
        Files.writeString(root.resolve("windowstolinux-db.properties"),
            "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.initialize=schema.sql\ndb.main.seed=seed.db\n");
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        Files.write(root.resolve("seed.db"), new byte[]{1, 2, 3});
        var source = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        var completed = DeploymentDatabaseDeclarations.complete(root, "demo", source, java.util.Map.of());
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentSqliteSetup.prepare(root, "demo", completed, false, task()));
        var prepared = DeploymentSqliteSetup.prepare(root, "demo", completed, true, task());
        var sqlite = (DeploymentDatabaseConnection.Sqlite) prepared.bindings().getFirst().connection();
        assertEquals("seed.db", sqlite.seedFile());
        assertEquals(java.util.List.of("schema.sql"), sqlite.initializationFiles());
        assertEquals("state/store.db", sqlite.accessPath());
        assertTrue(prepared.schemaReview().orElseThrow().firstDeploymentInitializationPlanned());
        assertEquals(
            SourceMutationPolicy.inspectionDigest(new BoundedSourceChecker().inspect(root, new ArrayList<>(), (root).getFileName().toString())),
            prepared.schemaReview().orElseThrow().inspectionSha256());
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE other(id integer);");
        assertNotEquals(
            SourceMutationPolicy.inspectionDigest(new BoundedSourceChecker().inspect(root, new ArrayList<>(), (root).getFileName().toString())),
            prepared.schemaReview().orElseThrow().inspectionSha256());
    }

    /** Provides only the cancellation signal required by deterministic source inspection. / 仅提供确定性源码检查所需取消信号。
     * @return non-cancelled test task / 未取消测试任务 */
    private static TaskContext task() {
        return new TaskContext() {
            public UUID id() {
                return UUID.randomUUID();
            }

            public CancelToken cancelToken() {
                return () -> false;
            }

            public void progress(String text, Optional<Double> fraction) {
            }

            public ResourceLease retain(String kind, String localId, String description) {
                throw new AssertionError();
            }
        };
    }
}
