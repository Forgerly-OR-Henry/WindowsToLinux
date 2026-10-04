package gold.debug.windowstolinux.app.secret;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

import gold.debug.windowstolinux.app.db.security.StoredSecret;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Version-one password wrapping and authenticated credential encryption. / 第一版主密码包装及凭据认证加密。 */
public final class PasswordKeyCrypto {
    /** Cryptographic random source. / 密码学随机源。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Fixed supported Argon2id parameters. / 固定支持的 Argon2id 参数。
     */
    private static final String PARAMETERS = "{\"version\":19,\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1}";

    /** Versioned password verifier format. / 带版本的密码验证格式。
     */
    private static final String VERIFIER_PREFIX = "$argon2id$v=19$m=65536,t=3,p=1$";

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     */
    private PasswordKeyCrypto() {
    }

    /** Obtains fresh bytes from the cryptographic random source. / 从密码学随机源取得新随机字节。
     * @param length length supplied to this boundary / 传入当前边界的length值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** Uses independent Argon2id salts for verification and data-key wrapping. / 使用独立 Argon2id 盐进行验证和数据密钥包装。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param dataKey caller-owned 256-bit data key / 调用方持有的 256 位数据密钥
     * @param encrypted global credential encryption flag / 全局凭据加密标记
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws GeneralSecurityException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public static StoredSecurityProfile wrap(char[] password, byte[] dataKey, boolean encrypted)
        throws GeneralSecurityException {
        requirePassword(password);
        byte[] verifierSalt = random(16), wrapSalt = random(16), nonce = random(12);
        byte[] hash = derive(password, verifierSalt), wrappingKey = null;
        try {
            String verifier = VERIFIER_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(verifierSalt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(hash);
            wrappingKey = derive(password, wrapSalt);
            byte[] wrapped = crypt(Cipher.ENCRYPT_MODE, wrappingKey, nonce, wrapAad(), dataKey);
            return new StoredSecurityProfile(encrypted, verifier, wrapped, "AES256_GCM_V1", nonce, "ARGON2ID_V1",
                wrapSalt, PARAMETERS, 1);
        } finally {
            clear(hash);
            clear(wrappingKey);
        }
    }

    /** Verifies the password and opens a session without exposing the data key. / 验证密码并建立会话，不暴露数据密钥。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws GeneralSecurityException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public static byte[] unlock(StoredSecurityProfile profile, char[] password) throws GeneralSecurityException {
        validateProfile(profile);
        requirePassword(password);
        String[] parts = profile.passwordVerifier().substring(VERIFIER_PREFIX.length()).split("\\$", -1);
        if (parts.length != 2)
            throw new GeneralSecurityException("invalid password verifier");
        byte[] salt = null, expected = null, actual = null, key = null;
        try {
            salt = Base64.getDecoder().decode(parts[0]);
            expected = Base64.getDecoder().decode(parts[1]);
            if (salt.length != 16 || expected.length != 32)
                throw new GeneralSecurityException("invalid password verifier");
            actual = derive(password, salt);
            if (!MessageDigest.isEqual(expected, actual))
                throw new GeneralSecurityException("password verification failed");
            key = derive(password, profile.keyDerivationSalt());
            byte[] dataKey = crypt(Cipher.DECRYPT_MODE, key, profile.keyWrapNonce(), wrapAad(),
                profile.wrappedDataKey());
            if (dataKey.length != 32) {
                clear(dataKey);
                throw new GeneralSecurityException("invalid data key");
            }
            return dataKey;
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("invalid security format", failure);
        } finally {
            clear(expected);
            clear(actual);
            clear(key);
        }
    }

    /** Rejects unknown algorithms, parameters, versions and malformed key envelopes. / 拒绝未知算法、参数、版本及异常密钥封套。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @throws GeneralSecurityException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public static void validateProfile(StoredSecurityProfile profile) throws GeneralSecurityException {
        if (profile.formatVersion() != 1 || !profile.keyWrapAlgorithm().equals("AES256_GCM_V1")
            || !profile.keyDerivationAlgorithm().equals("ARGON2ID_V1")
            || !profile.keyDerivationParameters().equals(PARAMETERS)
            || !profile.passwordVerifier().startsWith(VERIFIER_PREFIX) || profile.passwordVerifier().length() > 256
            || profile.keyDerivationSalt().length != 16 || profile.keyWrapNonce().length != 12
            || profile.wrappedDataKey().length != 48)
            throw new GeneralSecurityException("unsupported security profile");
    }

    /** Encodes a credential under the selected authenticated storage format. / 按选定的认证存储格式编码凭据。
     * @param id stable persisted identifier / 稳定持久化标识
     * @param value value supplied to this boundary / 传入当前边界的value值
     * @param dataKey caller-owned 256-bit data key / 调用方持有的 256 位数据密钥
     * @param encrypted global credential encryption flag / 全局凭据加密标记
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws GeneralSecurityException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public static StoredSecret encode(String id, char[] value, byte[] dataKey, boolean encrypted)
        throws GeneralSecurityException {
        if (value.length == 0)
            throw new IllegalArgumentException("credential must not be empty");
        byte[] plain = utf8(value), payload = null, nonce = encrypted ? random(12) : null;
        try {
            payload = encrypted ? crypt(Cipher.ENCRYPT_MODE, dataKey, nonce, credentialAad(id), plain) : plain;
            return new StoredSecret(id, encrypted ? "AES256_GCM_V1" : "PLAIN_V1", payload, nonce);
        } finally {
            clear(plain);
            if (payload != plain)
                clear(payload);
        }
    }

    /** Authenticates a credential against its identifier and global format. / 对照凭据标识及全局格式认证凭据。
     * @param record record supplied to this boundary / 传入当前边界的record值
     * @param dataKey caller-owned 256-bit data key / 调用方持有的 256 位数据密钥
     * @param encrypted global credential encryption flag / 全局凭据加密标记
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws GeneralSecurityException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public static char[] decode(StoredSecret record, byte[] dataKey, boolean encrypted)
        throws GeneralSecurityException {
        if (!record.storageFormat().equals(encrypted ? "AES256_GCM_V1" : "PLAIN_V1")
            || (encrypted ? (record.nonce() == null || record.nonce().length != 12) : record.nonce() != null))
            throw new GeneralSecurityException("credential format differs from security profile");
        byte[] payload = record.payload(), plain = null;
        CharBuffer chars = null;
        try {
            plain = encrypted
                ? crypt(Cipher.DECRYPT_MODE, dataKey, record.nonce(), credentialAad(record.id()), payload)
                : payload;
            chars = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain));
            char[] result = new char[chars.remaining()];
            chars.get(result);
            return result;
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new GeneralSecurityException("invalid credential encoding", failure);
        } finally {
            clear(payload);
            if (plain != payload)
                clear(plain);
            if (chars != null && chars.hasArray())
                Arrays.fill(chars.array(), '\0');
        }
    }

    /** Derives a 256-bit key with the fixed Argon2id cost parameters. / 使用固定 Argon2id 成本参数派生 256 位密钥。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param salt salt supplied to this boundary / 传入当前边界的salt值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static byte[] derive(char[] password, byte[] salt) {
        var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withIterations(3).withMemoryAsKB(65536)
            .withParallelism(1).build();
        var generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] key = new byte[32];
        generator.generateBytes(password, key);
        params.clear();
        return key;
    }

    /** Applies AES-256-GCM with a 128-bit tag and explicit associated data. / 使用带 128 位认证标签及显式附加数据的 AES-256-GCM。
     * @param mode mode supplied to this boundary / 传入当前边界的mode值
     * @param key key supplied to this boundary / 传入当前边界的key值
     * @param nonce fresh GCM nonce / 新 GCM 随机数
     * @param aad authenticated associated data / 认证附加数据
     * @param value value supplied to this boundary / 传入当前边界的value值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws GeneralSecurityException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] value)
        throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(value);
    }

    /** Binds a wrapped key to the singleton security profile and format. / 将包装密钥绑定到唯一安全配置及格式。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static byte[] wrapAad() {
        return "WindowsToLinux/security_profile/1/v1".getBytes(StandardCharsets.UTF_8);
    }

    /** Binds ciphertext to the credential identifier and format version. / 将密文绑定到凭据标识及格式版本。
     * @param id stable persisted identifier / 稳定持久化标识
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static byte[] credentialAad(String id) {
        return ("WindowsToLinux/credential/v1/" + id).getBytes(StandardCharsets.UTF_8);
    }

    /** Copies characters into UTF-8 bytes and clears the temporary encoder buffer. / 复制字符为 UTF-8 字节并清除临时编码缓冲区。
     * @param chars chars supplied to this boundary / 传入当前边界的chars值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static byte[] utf8(char[] chars) {
        var buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        if (buffer.hasArray())
            clear(buffer.array());
        return result;
    }

    /** Requires at least eight characters for a master password. / 要求主密码至少八个字符。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     */
    private static void requirePassword(char[] password) {
        if (password == null || password.length < 8)
            throw new IllegalArgumentException("master password must contain at least 8 characters");
    }

    /** Overwrites the caller-owned sensitive buffer when present. / 覆盖调用方持有的非空敏感缓冲区。
     * @param value value supplied to this boundary / 传入当前边界的value值
     */
    private static void clear(byte[] value) {
        if (value != null)
            Arrays.fill(value, (byte) 0);
    }
}
