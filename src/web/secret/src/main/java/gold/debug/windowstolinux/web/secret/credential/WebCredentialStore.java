package gold.debug.windowstolinux.web.secret.credential;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.UUID;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.repository.WebSecretRepository;
import gold.debug.windowstolinux.web.secret.crypto.WebSecretCipher;

/**
 * Accepts browser credentials for write-only storage and exposes immutable references and scoped-use callbacks.
 * <p>接受浏览器凭据用于只写存储，并公开不可变引用及限定作用域使用回调。
 */
public final class WebCredentialStore {
    /**
     * Bound web secret repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web秘密仓库协作对象。
     */
    private final WebSecretRepository repository;

    /**
     * Cipher.
     * <p>密码器。
     */
    private final WebSecretCipher cipher;
    /**
     * Binds the supplied dependencies and state for web credential store.
     * <p>为Web凭据存储绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param cipher cipher / 密码器
     */
    public WebCredentialStore(WebSecretRepository repository, WebSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /**
     * Verifies master key.
     * <p>验证主密钥。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param newDatabase new database / 新数据库
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     */
    public void verifyMasterKey(ResourceScope scope, boolean newDatabase) throws GeneralSecurityException {
        String id = "master-key-check", purpose = "master-key-check";
        byte[] encrypted;
        try {
            encrypted = repository.read(scope, id, 1, purpose);
        } catch (NoSuchElementException missing) {
            if (!newDatabase)
                throw new GeneralSecurityException("Existing Web database has no master key verification record");
            repository.insert(scope, id, 1, purpose,
                    cipher.encrypt(new byte[]{87, 50, 76}, associated(scope, id, purpose)));
            return;
        }
        byte[] plain = cipher.decrypt(encrypted, associated(scope, id, purpose));
        try {
            if (!Arrays.equals(plain, new byte[]{87, 50, 76}))
                throw new GeneralSecurityException("Web master key verification failed");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * Persists web credential store.
     * <p>持久化Web凭据存储。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param purpose purpose / 用途
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return save text / 保存文本
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     */
    public String save(ResourceScope scope, String purpose, char[] value) throws GeneralSecurityException {
        String id = UUID.randomUUID().toString();
        saveRevision(scope, id, 1, purpose, value);
        return id;
    }

    /**
     * Returns the scoped repository's latest secret revision for the requested purpose.
     * <p>返回作用域仓库中请求用途对应的最新秘密修订。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param purpose purpose / 用途
     * @return latest stored revision, or zero if absent / 最新持久化修订；不存在时为零
     */
    public int latestVersion(ResourceScope scope, String id, String purpose) {
        return repository.latestVersion(scope, id, purpose);
    }

    /**
     * Persists immutable configuration or secret revision number.
     * <p>持久化不可变配置或秘密修订号。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param purpose purpose / 用途
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void saveRevision(ResourceScope scope, String id, int version, String purpose, char[] value)
            throws GeneralSecurityException {
        ResourceScope.identifier(id);
        if (version < 1 || value.length == 0 || value.length > 65536) {
            Arrays.fill(value, '\0');
            throw new IllegalArgumentException("Invalid credential length or revision");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        try {
            repository.insert(scope, id, version, purpose,
                    cipher.encrypt(bytes, associated(scope, id, version, purpose)));
        } finally {
            Arrays.fill(value, '\0');
            Arrays.fill(bytes, (byte) 0);
            if (encoded.hasArray())
                Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    /**
     * Opens revision one of the scoped secret for the callback and clears transient plaintext after use.
     * <p>为回调打开作用域内秘密的修订一，并在使用后清空临时明文。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param purpose purpose / 用途
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public <T> T use(ResourceScope scope, String id, String purpose, CredentialAction<T> action) throws Exception {
        return useRevision(scope, id, 1, purpose, action);
    }

    /**
     * Decrypts the exact scoped revision for the callback and clears owned plaintext buffers when the callback finishes.
     * <p>为回调解密作用域内的精确修订，并在回调结束时清空持有的明文缓冲区。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param purpose purpose / 用途
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public <T> T useRevision(ResourceScope scope, String id, int version, String purpose, CredentialAction<T> action)
            throws Exception {
        byte[] raw = cipher.decrypt(repository.read(scope, id, version, purpose),
                associated(scope, id, version, purpose));
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(raw));
        char[] value = new char[decoded.remaining()];
        decoded.get(value);
        try {
            return action.execute(value);
        } finally {
            Arrays.fill(raw, (byte) 0);
            Arrays.fill(value, '\0');
            if (decoded.hasArray())
                Arrays.fill(decoded.array(), '\0');
        }
    }

    /**
     * Binds encrypted secret authentication to workspace, identifier, revision and purpose; the legacy overload uses revision one.
     * <p>将加密秘密认证绑定到工作区、标识、修订及用途；历史重载使用修订一。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param purpose purpose / 用途
     * @return associated text / 关联文本
     */
    private static String associated(ResourceScope scope, String id, String purpose) {
        return associated(scope, id, 1, purpose);
    }

    /**
     * Binds encrypted secret authentication to workspace, identifier, revision and purpose; the legacy overload uses revision one.
     * <p>将加密秘密认证绑定到工作区、标识、修订及用途；历史重载使用修订一。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param purpose purpose / 用途
     * @return associated text / 关联文本
     */
    private static String associated(ResourceScope scope, String id, int version, String purpose) {
        return scope.workspaceId() + "\n" + ResourceScope.identifier(id) + "\n" + version + "\n"
                + ResourceScope.identifier(purpose);
    }
    /**
     * Uses a temporary decrypted credential inside the owning store's cleanup boundary.
     * <p>在凭据存储的清理边界内使用临时解密凭据。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     */
    @FunctionalInterface
    public interface CredentialAction<T> {
        /**
         * Executes T.
         * <p>执行T。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         * @return constructed or resolved T / 构造或解析得到的T
         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
         */
        T execute(char[] value) throws Exception;
    }
}
