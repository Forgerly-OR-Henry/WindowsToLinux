package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests strict resource-binding persistence without secret values. / 测试不含秘密值的严格资源绑定持久化。 */
class ManagedResourcePersistenceCodecTest {
    private final ManagedResourcePersistenceCodec codec = new ManagedResourcePersistenceCodec();
    @Test void redisBindingIsPersistedAlongsideSqlWithoutOmittingItsSecretReference() throws Exception {
        var connection = new ManagedDatabaseConnection.Server(ManagedDatabaseEngineType.REDIS,"127.0.0.1",6379,"cache","cache_user",
                new SecretReference("cache-password",1),false);
        var bindings = new ManagedComponentResourceBindings(List.of(),Optional.of(List.of(new ManagedDatabaseBinding("cache",connection))));
        assertEquals(bindings,codec.read(codec.write(bindings)));
    }

    @Test
    void roundTripsCanonicalFileAndDatabaseBindings() throws Exception {
        ManagedComponentResourceBindings bindings = new ManagedComponentResourceBindings(List.of(
                new ManagedFileBinding("file-uploads", new ComponentDataPath("uploads",
                        ComponentDataPath.AccessMode.READ_WRITE, "uploads-v1", false))), Optional.of(List.of(
                new ManagedDatabaseBinding("orders", new ManagedDatabaseConnection.Server(
                        ManagedDatabaseEngineType.POSTGRESQL, "db.internal", 5432, "orders", "application",
                        new SecretReference("database-password", 7), true)),
                new ManagedDatabaseBinding("cache", new ManagedDatabaseConnection.Sqlite("application.db")))));

        assertEquals(bindings, codec.read(codec.write(bindings)));
    }

    @Test
    void preservesUnknownAndExplicitlyEmptyDatabaseReviewAsDifferentStates() throws Exception {
        ManagedComponentResourceBindings unknown = new ManagedComponentResourceBindings(List.of(), Optional.empty());
        ManagedComponentResourceBindings empty = new ManagedComponentResourceBindings(List.of(), Optional.of(List.of()));

        assertEquals(unknown, codec.read(codec.write(unknown)));
        assertEquals(empty, codec.read(codec.write(empty)));
        assertNotEquals(Arrays.toString(codec.write(unknown)), Arrays.toString(codec.write(empty)));
    }

    @Test
    void rejectsTrailingOrTruncatedDocuments() throws Exception {
        byte[] valid = codec.write(new ManagedComponentResourceBindings(List.of(), Optional.of(List.of())));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);

        assertThrows(IOException.class, () -> codec.read(trailing));
        assertThrows(IOException.class, () -> codec.read(truncated));
    }

    @Test
    void rejectsUnsafeConnectionPathAndHostSyntax() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedDatabaseConnection.Sqlite("../outside.db"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDatabaseConnection.Server(
                ManagedDatabaseEngineType.POSTGRESQL, "db host", 5432, "orders", "application",
                new SecretReference("database-password", 1), true));
    }
}
