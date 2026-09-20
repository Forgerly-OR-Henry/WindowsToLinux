package gold.debug.windowstolinux.web.secret.credential;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.repository.WebSecretRepository;
import gold.debug.windowstolinux.web.secret.crypto.WebSecretCipher;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.NoSuchElementException;
import java.util.Arrays;
import java.util.UUID;

/** Write-only browser credentials; callers receive immutable references and scoped use callbacks. */
public final class WebCredentialStore {
    private final WebSecretRepository repository;
    private final WebSecretCipher cipher;
    public WebCredentialStore(WebSecretRepository repository, WebSecretCipher cipher) { this.repository = repository; this.cipher = cipher; }

    public void verifyMasterKey(ResourceScope scope, boolean newDatabase) throws GeneralSecurityException {
        String id = "master-key-check", purpose = "master-key-check";
        byte[] encrypted;
        try { encrypted = repository.read(scope, id, 1, purpose); }
        catch (NoSuchElementException missing) {
            if (!newDatabase) throw new GeneralSecurityException("Existing Web database has no master key verification record");
            repository.insert(scope, id, 1, purpose, cipher.encrypt(new byte[]{87,50,76}, associated(scope, id, purpose)));
            return;
        }
        byte[] plain = cipher.decrypt(encrypted, associated(scope, id, purpose));
        try { if (!Arrays.equals(plain, new byte[]{87,50,76})) throw new GeneralSecurityException("Web master key verification failed"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public String save(ResourceScope scope, String purpose, char[] value) throws GeneralSecurityException {
        String id = UUID.randomUUID().toString();
        saveRevision(scope, id, 1, purpose, value);
        return id;
    }
    public int latestVersion(ResourceScope scope, String id, String purpose) { return repository.latestVersion(scope,id,purpose); }
    public void saveRevision(ResourceScope scope, String id, int version, String purpose, char[] value) throws GeneralSecurityException {
        ResourceScope.identifier(id);
        if (version < 1 || value.length == 0 || value.length > 65536) { Arrays.fill(value,'\0'); throw new IllegalArgumentException("Invalid credential length or revision"); }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        try { repository.insert(scope, id, version, purpose, cipher.encrypt(bytes, associated(scope, id, version, purpose))); }
        finally {
            Arrays.fill(value, '\0'); Arrays.fill(bytes, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    public <T> T use(ResourceScope scope, String id, String purpose, CredentialAction<T> action) throws Exception {
        return useRevision(scope,id,1,purpose,action);
    }
    public <T> T useRevision(ResourceScope scope, String id, int version, String purpose, CredentialAction<T> action) throws Exception {
        byte[] raw = cipher.decrypt(repository.read(scope, id, version, purpose), associated(scope, id, version, purpose));
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(raw));
        char[] value = new char[decoded.remaining()]; decoded.get(value);
        try { return action.execute(value); }
        finally {
            Arrays.fill(raw, (byte) 0); Arrays.fill(value, '\0');
            if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        }
    }
    private static String associated(ResourceScope scope, String id, String purpose) {
        return associated(scope,id,1,purpose);
    }
    private static String associated(ResourceScope scope, String id, int version, String purpose) {
        return scope.workspaceId() + "\n" + ResourceScope.identifier(id) + "\n" + version + "\n" + ResourceScope.identifier(purpose);
    }
    @FunctionalInterface public interface CredentialAction<T> { T execute(char[] value) throws Exception; }
}
