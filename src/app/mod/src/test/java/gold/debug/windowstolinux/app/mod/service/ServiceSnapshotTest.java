package gold.debug.windowstolinux.app.mod.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class ServiceSnapshotTest {
    public record Snapshot(List<String> values) {
    }

    @Test
    void copiesNestedRecordsAndCollectionsBeforeOriginalMutation() throws Exception {
        var values = new ArrayList<>(List.of("before"));
        var original = new Snapshot(values);
        var result = (Snapshot) ServiceSnapshot.copy(original, type -> type == Snapshot.class);
        values.add("after");
        assertEquals(List.of("before"), result.values());
        assertThrows(UnsupportedOperationException.class, () -> result.values().add("changed"));
        assertNotSame(original, result);
    }

    @Test
    void rejectsLiveObjectsPrivateValuesCyclesAndExcessiveGraphs() {
        var cycle = new ArrayList<>();
        cycle.add(cycle);
        for (var value : List.of(CompletableFuture.completedFuture("done"), new Object(), cycle,
                Collections.nCopies(4097, "x"), "x".repeat(1024 * 1024 + 1)))
            assertThrows(IllegalArgumentException.class, () -> ServiceSnapshot.copy(value, type -> true));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceSnapshot.copy(new Snapshot(List.of()), type -> false));
    }
}
