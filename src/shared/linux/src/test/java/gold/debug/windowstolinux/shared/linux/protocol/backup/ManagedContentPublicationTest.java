package gold.debug.windowstolinux.shared.linux.protocol.backup;

import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.*;

class ManagedContentPublicationTest {
    @Test
    void permitsSqliteAliasWithinManagedFileDirectoryInEitherOrder() {
        var files = binding("uploads", "data", FILE);
        var database = binding("db", "data/files.db", DATABASE);
        assertDoesNotThrow(() -> new ManagedContentPublication("demo", "backend", List.of(files, database)));
        assertDoesNotThrow(() -> new ManagedContentPublication("demo", "backend", List.of(database, files)));
    }

    @Test
    void stillRejectsDuplicateAliasesAndOverlappingDirectories() {
        var files = binding("uploads", "data", FILE);
        assertThrows(IllegalArgumentException.class, () -> new ManagedContentPublication("demo", "backend",
                List.of(files, binding("db", "data", DATABASE))));
        assertThrows(IllegalArgumentException.class, () -> new ManagedContentPublication("demo", "backend",
                List.of(files, binding("nested", "data/chunks", FILE))));
    }

    private static RemoteManagedFileBinding binding(String id, String path,
            ManagedStorageLocation.StorageResourceType kind) {
        return new RemoteManagedFileBinding(id,
                new ComponentDataPath(path, ComponentDataPath.AccessMode.READ_WRITE, "test", true),
                ManagedStorageLocation.defaults(), kind, kind == DATABASE ? "files.db" : "");
    }
}
