package gold.debug.windowstolinux.web.secret;

import gold.debug.windowstolinux.web.db.WebPersistence;
import gold.debug.windowstolinux.web.db.WebDatabaseTestContext;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.repository.WebSecretRepository;
import gold.debug.windowstolinux.web.secret.masterkey.WebMasterKey;
import gold.debug.windowstolinux.web.secret.crypto.WebSecretCipher;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class WebSecretTest {
    @TempDir Path root;
    @Test void ciphertextIsNotPlaintextAndCallerBuffersAreCleared() throws Exception {
        try (var database = new WebDatabaseTestContext(root); var key = key(1)) {
            var store = new WebCredentialStore(database.secrets(), new WebSecretCipher(key));
            char[] value = "synthetic-secret-123".toCharArray();
            String id = store.save(WebPersistence.INTERNAL_SCOPE, "ssh", value);
            assertEquals("\0".repeat(value.length), new String(value));
            assertEquals("synthetic-secret-123", store.use(WebPersistence.INTERNAL_SCOPE, id, "ssh", String::new));
            assertFalse(new String(Files.readAllBytes(root.resolve("windowstolinuxweb.db")), java.nio.charset.StandardCharsets.ISO_8859_1).contains("synthetic-secret-123"));
            assertThrows(Exception.class, () -> store.use(WebPersistence.INTERNAL_SCOPE, id, "git", String::new));
            assertThrows(Exception.class, () -> store.use(new ResourceScope("other", "internal"), id, "ssh", String::new));
        }
    }
    @Test void wrongKeyTamperingAndWrongOwnershipCannotDecrypt() throws Exception {
        try (var first = key(1); var second = key(2)) {
            var cipher = new WebSecretCipher(first);
            byte[] encrypted = cipher.encrypt(new byte[]{10,20,30}, "one:revision");
            assertArrayEquals(new byte[]{10,20,30}, cipher.decrypt(encrypted, "one:revision"));
            assertThrows(Exception.class, () -> new WebSecretCipher(second).decrypt(encrypted, "one:revision"));
            assertThrows(Exception.class, () -> cipher.decrypt(encrypted, "another:revision"));
            encrypted[encrypted.length-1] ^= 1;
            assertThrows(Exception.class, () -> cipher.decrypt(encrypted, "one:revision"));
        }
    }
    @Test void keysHaveNoFallbackAndClosedKeyCannotBeUsed() {
        assertThrows(IllegalArgumentException.class, () -> new WebMasterKey(null));
        assertThrows(IllegalArgumentException.class, () -> new WebMasterKey(new byte[3]));
        byte[] supplied = new byte[32]; Arrays.fill(supplied, (byte) 7);
        try (var owned = new WebMasterKey(supplied)) {
            assertArrayEquals(new byte[32], supplied); assertEquals(7, owned.copy()[0]);
        }
        var key = key(1); key.close(); assertThrows(IllegalStateException.class, key::copy);
    }
    private static WebMasterKey key(int fill) {
        byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte) fill); return new WebMasterKey(bytes);
    }
}
