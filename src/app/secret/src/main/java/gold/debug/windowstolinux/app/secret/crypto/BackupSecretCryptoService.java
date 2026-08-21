package gold.debug.windowstolinux.app.secret.crypto;

import gold.debug.windowstolinux.shared.backup.format.BackupSecretEnvelope;
import gold.debug.windowstolinux.shared.backup.format.BackupSecretEnvelopeCodec;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Stateless backup-password encryption that never retains passwords or derived keys. / 不保留密码或派生密钥的无状态备份密码加密。 */
public final class BackupSecretCryptoService {
    /** Maximum accepted encoded {@code secrets.enc} bytes. / 允许的 {@code secrets.enc} 编码字节上限。 */
    public static final long MAXIMUM_ENVELOPE_BYTES = 64L * 1024 * 1024;
    private static final int MEMORY_KIB = 64 * 1024;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int KEY_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final byte[] ASSOCIATED_CONTENT = "windowstolinux/secrets.enc/1".getBytes(StandardCharsets.UTF_8);
    private final SecureRandom random;
    private final BackupSecretEnvelopeCodec codec;
    private final BackupSecretDocumentCodec documentCodec;

    /** Creates a service backed by the platform secure random source. / 创建由平台安全随机源支持的服务。 */
    public BackupSecretCryptoService() {
        this(new SecureRandom(), new BackupSecretEnvelopeCodec(), new BackupSecretDocumentCodec());
    }

    BackupSecretCryptoService(SecureRandom random, BackupSecretEnvelopeCodec codec) {
        this(random, codec, new BackupSecretDocumentCodec());
    }

    BackupSecretCryptoService(SecureRandom random, BackupSecretEnvelopeCodec codec,
                              BackupSecretDocumentCodec documentCodec) {
        this.random = Objects.requireNonNull(random, "random");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
    }

    /** Encrypts an exact revision set without materializing secret strings. / 加密精确修订集且不产生秘密字符串。 */
    public byte[] encryptRevisions(
            char[] backupPassword,
            List<ResolvedSecretRevision> revisions
    ) throws BackupSecretException {
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

    /** Authenticates and decodes every revision before returning one clearable document. / 认证并解码全部修订后返回一个可清零文档。 */
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

    /** Encrypts one complete secret document using an independent backup password. / 使用独立备份密码加密一个完整秘密文档。 */
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
            BackupSecretEnvelope envelope = new BackupSecretEnvelope(
                    BackupSecretEnvelope.CURRENT_FORMAT, BackupSecretEnvelope.CURRENT_KEY_DERIVATION,
                    MEMORY_KIB, ITERATIONS, PARALLELISM, salt,
                    BackupSecretEnvelope.CURRENT_CIPHER, nonce, cipher.doFinal(plaintext));
            return codec.write(envelope);
        } catch (GeneralSecurityException | IOException | RuntimeException exception) {
            throw BackupSecretException.create(BackupSecretFailureType.ENCRYPT_FAILED,
                    "backup secret encryption failed without persisting plaintext", exception);
        } finally {
            clear(password, plaintext, salt, nonce, key);
        }
    }

    /** Authenticates the complete envelope before returning any plaintext bytes. / 在返回任何明文字节前认证完整信封。 */
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

    private static char[] copyPassword(char[] password) throws BackupSecretException {
        if (password == null || password.length < 12 || password.length > 1024) {
            throw BackupSecretException.create(BackupSecretFailureType.PASSWORD_INVALID,
                    "backup password must contain between 12 and 1024 characters");
        }
        return password.clone();
    }

    private static void requireSupportedParameters(BackupSecretEnvelope envelope) {
        if (envelope.memoryKiB() != MEMORY_KIB || envelope.iterations() != ITERATIONS
                || envelope.parallelism() != PARALLELISM) {
            throw new IllegalArgumentException("unsupported backup key derivation parameters");
        }
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withMemoryAsKB(memoryKiB).withIterations(iterations)
                .withParallelism(parallelism).build();
        byte[] key = new byte[KEY_BYTES];
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        generator.generateBytes(password, key);
        return key;
    }

    private byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        random.nextBytes(value);
        return value;
    }

    private static void clear(Object... values) {
        for (Object value : values) {
            if (value instanceof byte[] bytes) Arrays.fill(bytes, (byte) 0);
            if (value instanceof char[] characters) Arrays.fill(characters, '\0');
        }
    }
}
