package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDataPath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import org.junit.jupiter.api.Test;

/** Tests strict resource-binding persistence without secret values. / 测试不含秘密值的严格资源绑定持久化。 */
class DeploymentResourceCodecTest {
    private final DeploymentResourceCodec codec = new DeploymentResourceCodec();
    @Test
    void redisBindingIsPersistedAlongsideSqlWithoutOmittingItsSecretReference() throws Exception {
        var connection = new DeploymentDatabaseConnection.Server(DeploymentDatabaseEngineType.REDIS, "127.0.0.1", 6379,
                "cache", "cache_user", new DeploymentSecretReference("cache-password", 1), false);
        var bindings = new DeploymentResourceBindings(List.of(),
                Optional.of(List.of(new DeploymentDatabaseBinding("cache", connection))));
        assertEquals(bindings, codec.read(DeploymentCodecCompatibility.write(bindings)));
    }

    @Test
    void roundTripsCanonicalFileAndDatabaseBindings() throws Exception {
        DeploymentResourceBindings bindings = new DeploymentResourceBindings(
                List.of(new DeploymentFileBinding("file-uploads",
                        new DeploymentDataPath("uploads", DeploymentDataPath.AccessMode.READ_WRITE, "uploads-v1",
                                false))),
                Optional.of(List.of(
                        new DeploymentDatabaseBinding("orders",
                                new DeploymentDatabaseConnection.Server(DeploymentDatabaseEngineType.POSTGRESQL,
                                        "db.internal", 5432, "orders", "application",
                                        new DeploymentSecretReference("database-password", 7), true)),
                        new DeploymentDatabaseBinding("cache",
                                new DeploymentDatabaseConnection.Sqlite("application.db")))));

        assertEquals(bindings, codec.read(DeploymentCodecCompatibility.write(bindings)));
    }

    @Test
    void retainsSqliteLocationSeedAndInitializationAndRejectsOldResourceFormat() throws Exception {
        var sqlite = new DeploymentDatabaseConnection.Sqlite("shop.db",
                gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentStorageLocation
                        .custom("db/shop.db"),
                "db/shop.db", "seed/shop.db", List.of("schema.sql"));
        var binding = new DeploymentResourceBindings(List.of(),
                Optional.of(List.of(new DeploymentDatabaseBinding("main", sqlite))));
        byte[] bytes = DeploymentCodecCompatibility.write(binding);
        assertEquals(binding, codec.read(bytes));
        bytes[4] = 1;
        assertThrows(IOException.class, () -> codec.read(bytes));
    }

    @Test
    void preservesUnknownAndExplicitlyEmptyDatabaseReviewAsDifferentStates() throws Exception {
        DeploymentResourceBindings unknown = new DeploymentResourceBindings(List.of(), Optional.empty());
        DeploymentResourceBindings empty = new DeploymentResourceBindings(List.of(), Optional.of(List.of()));

        assertEquals(unknown, codec.read(DeploymentCodecCompatibility.write(unknown)));
        assertEquals(empty, codec.read(DeploymentCodecCompatibility.write(empty)));
        assertNotEquals(Arrays.toString(DeploymentCodecCompatibility.write(unknown)),
                Arrays.toString(DeploymentCodecCompatibility.write(empty)));
    }

    @Test
    void rejectsTrailingOrTruncatedDocuments() throws Exception {
        byte[] valid = DeploymentCodecCompatibility
                .write(new DeploymentResourceBindings(List.of(), Optional.of(List.of())));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);

        assertThrows(IOException.class, () -> codec.read(trailing));
        assertThrows(IOException.class, () -> codec.read(truncated));
    }

    @Test
    void rejectsUnsafeConnectionPathAndHostSyntax() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentDatabaseConnection.Sqlite("../outside.db"));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentDatabaseConnection.Server(DeploymentDatabaseEngineType.POSTGRESQL, "db host", 5432,
                        "orders", "application", new DeploymentSecretReference("database-password", 1), true));
    }
}
