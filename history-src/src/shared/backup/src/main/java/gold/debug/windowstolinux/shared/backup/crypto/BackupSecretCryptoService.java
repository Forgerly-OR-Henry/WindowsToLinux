package gold.debug.windowstolinux.shared.backup.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import gold.debug.windowstolinux.shared.backup.format.BackupSecretEnvelope;
import gold.debug.windowstolinux.shared.backup.format.BackupSecretEnvelopeCodec;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Stateless backup-password encryption that never retains passwords or derived keys. / 不保留密码或派生密钥的无状态备份密码加密。
 */
public final class BackupSecretCryptoService {
    /**
     * Maximum accepted encoded {@code secrets.enc} bytes. / 允许的 {@code secrets.enc} 编码字节上限。
     */
    public static final long MAXIMUM_ENVELOPE_BYTES = 64L * 1024 * 1024;

    /**
     * MEMORY KIB.
     * <p>内存KIB。
     */
    private static final int MEMORY_KIB = 64 * 1024;

    /**
     * Number of Argon2id derivation passes.
     * <p>Argon2id 派生轮数。
     */
    private static final int ITERATIONS = 3;

    /**
     * Number of Argon2id parallel lanes.
     * <p>Argon2id 并行通道数。
     */
    private static final int PARALLELISM = 1;

    /**
     * KEY BYTES.
     * <p>键字节。
     */
    private static final int KEY_BYTES = 32;

    /**
     * SALT BYTES.
     * <p>盐字节。
     */
    private static final int SALT_BYTES = 16;

    /**
     * NONCE BYTES.
     * <p>随机数字节。
     */
    private static final int NONCE_BYTES = 12;

    /**
     * ASSOCIATED CONTENT.
     * <p>关联内容。
     */
    private static final byte[] ASSOCIATED_CONTENT = "windowstolinux/secrets.enc/1".getBytes(StandardCharsets.UTF_8);

    /**
     * Random.
     * <p>随机。
     */
    private final SecureRandom random;

    /**
     * Bound backup secret envelope codec collaborator for codec.
     * <p>处理编解码器的备份秘密信封编解码器协作对象。
     */
    private final BackupSecretEnvelopeCodec codec;

    /**
     * Bound backup secret document codec collaborator for document codec.
     * <p>处理文档编解码器的备份秘密文档编解码器协作对象。
     */
    private final BackupSecretDocumentCodec documentCodec;

    /**
     * Creates a service backed by the platform secure random source. / 创建由平台安全随机源支持的服务。
     */
    public BackupSecretCryptoService() {
        this(new SecureRandom(), new BackupSecretEnvelopeCodec(), new BackupSecretDocumentCodec());
    }

    /**
     * Initializes backup secret crypto service through its shared constructor contract.
     * <p>通过共享构造契约初始化备份秘密加密服务。
     *
     * @param random random / 随机
     * @param codec codec / 编解码器
     */
    BackupSecretCryptoService(SecureRandom random, BackupSecretEnvelopeCodec codec) {
        this(random, codec, new BackupSecretDocumentCodec());
    }

    /**
     * Validates and binds the inputs required by backup secret crypto service.
     * <p>校验并绑定备份秘密加密服务所需输入。
     *
     * @param random random / 随机
     * @param codec codec / 编解码器
     * @param documentCodec document codec / 文档编解码器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    BackupSecretCryptoService(SecureRandom random, BackupSecretEnvelopeCodec codec,
            BackupSecretDocumentCodec documentCodec) {
        this.random = Objects.requireNonNull(random, "random");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
    }

    /**
     * Encrypts an exact revision set without materializing secret strings. / 加密精确修订集且不产生秘密字符串。
     *
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param revisions revisions / 修订集合
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    public byte[] encryptRevisions(char[] backupPassword, List<ResolvedSecretRevision> revisions)
            throws BackupSecretException {
        byte[] document = null;
        try {
            document = documentCodec.write(revisions);
            return encrypt(backupPassword, document);
        } catch (BackupSecretException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw BackupSecretException.create(BackupSecretFailureType.ENCRYPT_FAILED,
                    "backup secret revision encoding failed without persisting plaintext", exception);
        } finally {
            clear(document);
        }
    }

    /**
     * Authenticates and decodes every revision before returning one clearable document. / 认证并解码全部修订后返回一个可清零文档。
     *
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param envelopeDocument envelope document / 信封文档
     * @return constructed or resolved backup secret document / 构造或解析得到的备份秘密文档
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    public BackupSecretDocument decryptRevisions(char[] backupPassword, byte[] envelopeDocument)
            throws BackupSecretException {
        byte[] document = decrypt(backupPassword, envelopeDocument);
        try {
            return documentCodec.read(document);
        } catch (IOException | RuntimeException exception) {
            throw BackupSecretException.create(BackupSecretFailureType.PAYLOAD_INVALID,
                    "authenticated backup secret revision document is invalid; no partial revisions were returned",
                    exception);
        } finally {
            clear(document);
        }
    }

    /**
     * Encrypts one complete secret document using an independent backup password. / 使用独立备份密码加密一个完整秘密文档。
     *
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param secretDocument secret document / 秘密文档
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] encrypt(char[] backupPassword, byte[] secretDocument) throws BackupSecretException {
        Objects.requireNonNull(secretDocument, "secretDocument");
        char[] password = copyPassword(backupPassword);
        byte[] plaintext = secretDocument.clone();
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] key = null;
        try {
            key = deriveKey(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ASSOCIATED_CONTENT);
            BackupSecretEnvelope envelope = new BackupSecretEnvelope(BackupSecretEnvelope.CURRENT_FORMAT,
                    BackupSecretEnvelope.CURRENT_KEY_DERIVATION, MEMORY_KIB, ITERATIONS, PARALLELISM, salt,
                    BackupSecretEnvelope.CURRENT_CIPHER, nonce, cipher.doFinal(plaintext));
            return codec.write(envelope);
        } catch (GeneralSecurityException | IOException | RuntimeException exception) {
            throw BackupSecretException.create(BackupSecretFailureType.ENCRYPT_FAILED,
                    "backup secret encryption failed without persisting plaintext", exception);
        } finally {
            clear(password, plaintext, salt, nonce, key);
        }
    }

    /**
     * Authenticates the complete envelope before returning any plaintext bytes. / 在返回任何明文字节前认证完整信封。
     *
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param envelopeDocument envelope document / 信封文档
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] decrypt(char[] backupPassword, byte[] envelopeDocument) throws BackupSecretException {
        Objects.requireNonNull(envelopeDocument, "envelopeDocument");
        char[] password = copyPassword(backupPassword);
        byte[] document = envelopeDocument.clone();
        byte[] key = null;
        byte[] salt = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            BackupSecretEnvelope envelope = codec.read(document);
            requireSupportedParameters(envelope);
            salt = envelope.salt();
            nonce = envelope.nonce();
            ciphertext = envelope.ciphertext();
            key = deriveKey(password, salt, envelope.memoryKiB(), envelope.iterations(), envelope.parallelism());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ASSOCIATED_CONTENT);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | IOException | RuntimeException exception) {
            throw BackupSecretException.create(BackupSecretFailureType.DECRYPT_FAILED,
                    "backup secret authentication failed; no plaintext was released", exception);
        } finally {
            clear(password, document, salt, nonce, ciphertext, key);
        }
    }

    /**
     * Copies temporary plaintext authentication buffer.
     * <p>复制临时明文认证缓冲区。
     *
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    private static char[] copyPassword(char[] password) throws BackupSecretException {
        if (password == null || password.length < 12 || password.length > 1024) {
            throw BackupSecretException.create(BackupSecretFailureType.PASSWORD_INVALID,
                    "backup password must contain between 12 and 1024 characters");
        }
        return password.clone();
    }

    /**
     * Requires supported parameters and rejects inputs outside the declared constraints.
     * <p>要求受支持参数集合并拒绝超出已声明约束的输入。
     *
     * @param envelope envelope / 信封
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requireSupportedParameters(BackupSecretEnvelope envelope) {
        if (envelope.memoryKiB() != MEMORY_KIB || envelope.iterations() != ITERATIONS
                || envelope.parallelism() != PARALLELISM) {
            throw new IllegalArgumentException("unsupported backup key derivation parameters");
        }
    }

    /**
     * Derives the encryption key from the supplied password and salt parameters.
     * <p>根据提供的密码及盐参数派生加密密钥。
     *
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param salt salt / 盐
     * @param memoryKiB memory ki B / 内存KiB
     * @param iterations iterations / 迭代轮数
     * @param parallelism parallelism / 并行度
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private static byte[] deriveKey(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt)
                .withMemoryAsKB(memoryKiB).withIterations(iterations).withParallelism(parallelism).build();
        byte[] key = new byte[KEY_BYTES];
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        generator.generateBytes(password, key);
        return key;
    }

    /**
     * Fills a new buffer with cryptographically secure random bytes.
     * <p>用密码学安全随机字节填充新缓冲区。
     *
     * @param size size / 大小
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        random.nextBytes(value);
        return value;
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     */
    private static void clear(Object... values) {
        for (Object value : values) {
            if (value instanceof byte[] bytes)
                Arrays.fill(bytes, (byte) 0);
            if (value instanceof char[] characters)
                Arrays.fill(characters, '\0');
        }
    }
}
