package gold.debug.windowstolinux.web.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;

import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import gold.debug.windowstolinux.web.secret.masterkey.WebMasterKeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebMasterKeyStoreTest {
    @TempDir
    Path root;
    @Test
    void firstRunCreatesPrivateKeyAndRelocationReusesIt() throws Exception {
        Path data = Files.createDirectory(root.resolve("data")), keys = root.resolve("private/keys");
        byte[] first = WebMasterKeyStore.loadOrCreate(data, keys);
        assertEquals(32, first.length);
        assertArrayEquals(first, WebMasterKeyStore.loadOrCreate(data, keys));
        Path saved = keys.resolve(Files.readString(data.resolve(".master-key-id")) + ".key");
        var posix = Files.getFileAttributeView(saved, PosixFileAttributeView.class);
        if (posix != null)
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
        else {
            var acl = Files.getFileAttributeView(saved, AclFileAttributeView.class);
            var owner = Files.getOwner(saved);
            assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == AclEntryType.ALLOW)
                    .allMatch(entry -> entry.principal().equals(owner)));
        }
        Files.writeString(data.resolve(WebStorageLocation.DATABASE_NAME), "existing database");
        Path relocated = Files.move(data, root.resolve("relocated"));
        assertArrayEquals(first, WebMasterKeyStore.loadOrCreate(relocated, keys));
    }

    @Test
    void missingOrDamagedExistingKeyNeverGetsReplaced() throws Exception {
        Path data = Files.createDirectory(root.resolve("data")), keys = root.resolve("keys");
        WebMasterKeyStore.loadOrCreate(data, keys);
        Path saved = keys.resolve(Files.readString(data.resolve(".master-key-id")) + ".key");
        Files.writeString(data.resolve(WebStorageLocation.DATABASE_NAME), "keep");
        Files.write(saved, new byte[]{1, 2});
        assertThrows(IOException.class, () -> WebMasterKeyStore.loadOrCreate(data, keys));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(saved));
        Files.delete(saved);
        assertThrows(IOException.class, () -> WebMasterKeyStore.loadOrCreate(data, keys));
        assertFalse(Files.exists(saved));
        assertEquals("keep", Files.readString(data.resolve(WebStorageLocation.DATABASE_NAME)));
        Files.delete(data.resolve(".master-key-id"));
        assertThrows(IOException.class, () -> WebMasterKeyStore.loadOrCreate(data, keys));
        assertFalse(Files.exists(data.resolve(".master-key-id")));
    }

    @Test
    void keysCannotBeStoredInsideTheDataRoot() throws Exception {
        Path data = Files.createDirectory(root.resolve("data"));
        assertThrows(IOException.class, () -> WebMasterKeyStore.loadOrCreate(data, data.resolve("keys")));
    }
}
