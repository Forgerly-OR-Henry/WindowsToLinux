package gold.debug.windowstolinux.shared.model.managed;

import static gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManagedStorageLocationTest {
    @Test
    void defaultsHaveSeparateFhsRoots() {
        var location = ManagedStorageLocation.defaults();
        assertEquals("/etc/opt/windowstolinux/apps/demo/files/settings",
                location.resolve("demo", CONFIGURATION, "settings"));
        assertEquals("/var/opt/windowstolinux/apps/demo/files/uploads", location.resolve("demo", FILE, "uploads"));
        assertEquals("/var/opt/windowstolinux/apps/demo/databases/main", location.resolve("demo", DATABASE, "main"));
    }

    @Test
    void relativePathsRemainInStableApplicationStorage() {
        assertEquals("/opt/windowstolinux/apps/demo/persistent/files/uploads",
                ManagedStorageLocation.custom("./assets/uploads").resolve("demo", FILE, "uploads"));
        assertEquals("/opt/windowstolinux/apps/demo/user-data", ManagedStorageLocation
                .custom("/opt/windowstolinux/apps/demo/user-data").resolve("demo", FILE, "uploads"));
    }

    @Test
    void boundariesCannotBeConfusedWithPrefixesOrDefaultMarkers() {
        for (String path : new String[]{"/opt/windowstolinux/apps/demo", "/opt/windowstolinux/apps/demo-other/data",
                "/opt/windowstolinux/apps/other/data", "/var/opt/windowstolinux/apps/demo/files/data",
                "/opt/windowstolinux/apps/demo/releases/data", "/opt/windowstolinux/apps/demo/current/data",
                "/opt/windowstolinux/apps/demo/persistent/data", "/opt/windowstolinux/apps/demo/.owner"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ManagedStorageLocation.custom(path).resolve("demo", FILE, "data"), path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ManagedStorageLocation(ManagedStorageLocation.StorageLocationType.DEFAULT, "/etc"));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedStorageLocation.unresolved().resolve("demo", FILE, "data"));
    }

    @Test
    void rejectsTraversalVariablesAndShellSyntax() {
        for (String path : new String[]{"../data", "data/../other", "data//other", "${HOME}/data", "C:/data", "a\\b",
                "a;touch", "/", ".", ""})
            assertThrows(IllegalArgumentException.class, () -> ManagedStorageLocation.custom(path), path);
    }
}
