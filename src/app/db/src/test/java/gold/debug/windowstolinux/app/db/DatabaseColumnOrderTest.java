package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import gold.debug.windowstolinux.app.db.setting.ModNavigationPreferences;

class DatabaseColumnOrderTest {
    @TempDir Path root;

    @Test
    void everyPlatformColumnMatchesIndependentOrderAndPrimaryKeysLead() throws Exception {
        try (var db = DatabaseTestFixture.open(root); var c = db.database().openConnection()) {
            var actual = new LinkedHashMap<String, List<String>>();
            try (var s = c.createStatement(); var rows = s.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='app' ORDER BY table_name,ordinal_position")) {
                while (rows.next()) actual.computeIfAbsent(rows.getString(1), key -> new ArrayList<>()).add(rows.getString(2));
            }
            var expected = new TreeMap<String, List<String>>();
            try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("column-order-v1.txt"))) {
                for (var line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                    var parts = line.split("=", 2);
                    assertNull(expected.put(parts[0], List.of(parts[1].split(","))));
                }
            }
            assertEquals(23, expected.size());
            assertEquals(expected, actual);
            for (var table : actual.entrySet()) {
                var primary = new TreeMap<Short, String>();
                try (var keys = c.getMetaData().getPrimaryKeys(null, "app", table.getKey())) {
                    while (keys.next()) primary.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
                }
                assertFalse(primary.isEmpty(), table.getKey());
                assertEquals(List.copyOf(primary.values()), table.getValue().subList(0, primary.size()), table.getKey());
            }
        }
    }

    @Test
    void workspaceOrderAppendsNewOwnersAndRejectsOldPageConversion() {
        var saved = new ModNavigationPreferences.PagePin("example.one", "workspace");
        var added = new ModNavigationPreferences.PagePin("example.two", "workspace");
        assertEquals(List.of(saved, added), ModNavigationPreferences.workspaces(List.of(saved), List.of(saved, added)));
        var old = new ModNavigationPreferences.PagePin(saved.ownerId(), "old-page");
        assertThrows(IllegalArgumentException.class, () -> ModNavigationPreferences.workspaces(List.of(old), List.of(saved)));
        assertThrows(IllegalArgumentException.class, () -> ModNavigationPreferences.workspaces(List.of(old, saved), List.of(saved)));
    }
}
