package gold.debug.windowstolinux.mod.deployment.db.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.db.DeploymentCodecMatch;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import org.junit.jupiter.api.Test;

class DeploymentStoragePathCodecTest {
    private final DeploymentStoragePathCodec codec = new DeploymentStoragePathCodec();

    @Test
    void roundTripsCanonicalReviewedPathsAndAnExplicitEmptyInventory() throws Exception {
        List<DeploymentStoragePath> paths = List.of(
            new DeploymentStoragePath("uploads", DeploymentStoragePath.AccessMode.READ_WRITE, "uploads-v2", false),
            new DeploymentStoragePath("cache/index", DeploymentStoragePath.AccessMode.READ_ONLY, "cache-v1", true));

        assertEquals(List.of(paths.get(1), paths.get(0)), codec.read(DeploymentCodecMatch.write(paths)));
        assertEquals(List.of(), codec.read(DeploymentCodecMatch.write(List.of())));
    }

    @Test
    void rejectsUnsupportedTruncatedTrailingAndNonCanonicalDocuments() throws Exception {
        byte[] valid = DeploymentCodecMatch.write(List
            .of(new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE, "data-v1", true)));
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
        DeploymentStoragePath first = new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE,
            "data-v1", true);
        DeploymentStoragePath second = new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_ONLY,
            "data-v2", true);

        assertThrows(java.io.IOException.class, () -> DeploymentCodecMatch.write(List.of(first, second)));
    }
}
