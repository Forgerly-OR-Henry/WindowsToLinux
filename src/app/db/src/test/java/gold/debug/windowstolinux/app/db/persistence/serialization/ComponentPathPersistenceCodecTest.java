package gold.debug.windowstolinux.app.db.persistence.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import org.junit.jupiter.api.Test;

class ComponentPathPersistenceCodecTest {
    private final ComponentPathPersistenceCodec codec = new ComponentPathPersistenceCodec();

    @Test
    void roundTripsCanonicalReviewedPathsAndAnExplicitEmptyInventory() throws Exception {
        List<ComponentDataPath> paths = List.of(
                new ComponentDataPath("uploads", ComponentDataPath.AccessMode.READ_WRITE, "uploads-v2", false),
                new ComponentDataPath("cache/index", ComponentDataPath.AccessMode.READ_ONLY, "cache-v1", true));

        assertEquals(List.of(paths.get(1), paths.get(0)), codec.read(codec.write(paths)));
        assertEquals(List.of(), codec.read(codec.write(List.of())));
    }

    @Test
    void rejectsUnsupportedTruncatedTrailingAndNonCanonicalDocuments() throws Exception {
        byte[] valid = codec.write(
                List.of(new ComponentDataPath("data", ComponentDataPath.AccessMode.READ_WRITE, "data-v1", true)));
        byte[] unsupported = valid.clone();
        unsupported[4] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(unsupported));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, 8)));
        assertThrows(java.io.IOException.class, () -> codec.read(Arrays.copyOf(valid, valid.length + 1)));

        byte[] invalidAccess = valid.clone();
        invalidAccess[15] = 99;
        assertThrows(java.io.IOException.class, () -> codec.read(invalidAccess));
        assertThrows(java.io.IOException.class, () -> codec.read(new byte[1_048_577]));
    }

    @Test
    void rejectsDuplicateReviewedPathsBeforePersistence() {
        ComponentDataPath first = new ComponentDataPath("data", ComponentDataPath.AccessMode.READ_WRITE, "data-v1",
                true);
        ComponentDataPath second = new ComponentDataPath("data", ComponentDataPath.AccessMode.READ_ONLY, "data-v2",
                true);

        assertThrows(java.io.IOException.class, () -> codec.write(List.of(first, second)));
    }
}
