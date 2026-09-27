package gold.debug.windowstolinux.app.service.secret;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.repository.CredentialReferenceRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.task.*;

/** Provides owner-bound protected bytes and generation-owned references through accepted tasks. / 经已接纳任务提供所有者绑定的受保护字节及代次所属引用。 */
public final class PlatformSecretService implements SecretService, AutoCloseable {
    /** Maximum plaintext bytes accepted for one secret. / 单个秘密接受的最大明文字节数。 */
    private static final int MAX_BYTES = 1024 * 1024;

    /** Task and credential authorization authority. / 任务及凭据授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Existing protected credential store access. / 已有受保护凭据存储访问。 */
    private final DesktopSecurityService security;

    /** Persistent association transactions. / 持久关联事务。 */
    private final CredentialReferenceRepository references;

    /** Exact Mod owner. / 精确 Mod 所有者。 */
    private final String owner;

    /** Actual loaded release. / 实际已加载版本。 */
    private final String version;

    /** Selected data generation, if present. / 可选的选定数据代次。 */
    private final Optional<SchemaIdentity> schema;

    /** Shared physical and durable operation ownership. / 共享的物理和持久操作归属。 */
    private final TaskCapabilityScope scope;

    /**
     * Binds an activation to existing platform security without copying master keys. / 将一次启用绑定到已有平台安全，不复制主密钥。
     * @param database current platform engine / 当前平台引擎
     * @param tasks task identity authority / 任务身份授权方
     * @param security protected credential access / 受保护凭据访问
     * @param references persistent reference repository / 持久引用仓库
     * @param owner exact Mod owner / 精确 Mod 所有者
     * @param version loaded release / 已加载版本
     * @param schema selected optional data generation / 可选的选定数据代次
     */
    public PlatformSecretService(PlatformDatabase database, PlatformTaskRuntime tasks, DesktopSecurityService security,
            CredentialReferenceRepository references, String owner, String version, Optional<SchemaIdentity> schema) {
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.references = Objects.requireNonNull(references);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.schema = Objects.requireNonNull(schema);
        scope = new TaskCapabilityScope(database, tasks, owner, version, schema, "secret-access-");
    }

    /**
     * Copies and protects an immutable secret without clearing caller-owned input. / 复制并保护不可变秘密，不清除调用方输入。
     * @param purpose exact declared purpose / 精确声明用途
     * @param value at most one MiB of arbitrary bytes / 最多一 MiB 的任意字节
     * @param task authentic executing task / 真实执行任务
     * @return newly owned opaque secret / 新的所属不透明秘密
     * @throws ApiException if input, authorization or persistence fails / 输入、授权或持久化失败时
     */
    @Override
    public SecretRef create(String purpose, byte[] value, TaskContext task) throws ApiException {
        requirePurpose(purpose);
        if (value == null || value.length > MAX_BYTES)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_SIZE", "A secret must contain at most one MiB");
        byte[] copy = value.clone();
        try {
            return access(task, false, true, () -> credentials(task, store -> {
                var secret = CredentialReferenceRepository.newSecret(owner, purpose);
                char[] encoded = encode(copy);
                try {
                    store.save(secret.id(), encoded);
                } finally {
                    Arrays.fill(encoded, '\0');
                }
                return secret;
            }));
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    /**
     * Reserves an association before the author's business transaction. / 在作者业务事务前预留关联。
     * @param secret owned secret with its exact purpose / 带精确用途的所属秘密
     * @param businessKey stable association key / 稳定关联键
     * @param task authentic executing task / 真实执行任务
     * @return idempotent original reservation / 幂等原预留
     * @throws ApiException if ownership, schema or persistence fails / 归属、数据结构或持久化失败时
     */
    @Override
    public SecretReference reserve(SecretRef secret, String businessKey, TaskContext task) throws ApiException {
        requireSecret(secret);
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 512)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_REFERENCE_KEY", "Invalid secret business key");
        var identity = requireSchema();
        return access(task, false, true, () -> references.reserve(identity, secret, businessKey));
    }

    /**
     * Reads an exact owned association during execution or original-task recovery without replaying reserve. / 执行或原任务恢复时读取精确所属关联，不重放预留。
     * @param secret original resource identity / 原资源身份
     * @param businessKey exact original business key / 精确原业务键
     * @param task authentic executing or reconciling task / 真实执行或核对任务
     * @return current reference or absence, without proving a business outcome / 当前引用或空值，不证明业务结果
     * @throws ApiException if input, authorization or reading fails / 输入、授权或读取失败时
     */
    @Override
    public Optional<SecretReference> findReference(SecretRef secret, String businessKey, TaskContext task)
            throws ApiException {
        requireSecret(secret);
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 512)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_REFERENCE_KEY", "Invalid secret business key");
        var identity = requireSchema();
        return access(task, true, false, () -> references.find(identity, secret, businessKey));
    }

    /**
     * Resolves only an existing association, including cancellation cleanup. / 仅核对已有关系，包括取消收尾。
     * @param referenceId original reservation / 原预留
     * @param result author-confirmed association result / 作者确认的关联结果
     * @param task authentic executing or reconciling task / 真实执行或核对任务
     * @throws ApiException if ownership or persistence fails / 归属或持久化失败时
     */
    @Override
    public void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException {
        if (referenceId == null || result == null)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_REFERENCE_RESULT",
                    "A reference and resolution are required");
        var identity = requireSchema();
        access(task, true, true, () -> {
            references.resolve(identity, referenceId, result);
            return null;
        });
    }

    /**
     * Offers temporary plaintext only inside the callback, then clears every platform buffer. / 仅在回调中提供临时明文，随后清除所有平台缓冲。
     * @param secret owned opaque secret / 所属不透明秘密
     * @param purpose exact authorized purpose / 精确授权用途
     * @param task authentic executing task / 真实执行任务
     * @param consumer callback that does not retain or return plaintext / 不保留或返回明文的回调
     * @param <T> non-secret callback result / 非秘密回调结果
     * @return callback result / 回调结果
     * @throws ApiException if authorization, storage or callback fails / 授权、存储或回调失败时
     */
    @Override
    public <T> T use(SecretRef secret, String purpose, TaskContext task, SecretConsumer<T> consumer)
            throws ApiException {
        requireSecret(secret);
        requirePurpose(purpose);
        if (!purpose.equals(secret.purpose()))
            throw error(ProblemCode.ACCESS_DENIED, "SECRET_PURPOSE", "The secret purpose does not match");
        if (consumer == null)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_CONSUMER", "A secret consumer is required");
        return access(task, false, false, () -> {
            CredentialReferenceRepository.requireOwned(owner, secret);
            return credentials(task, store -> {
                char[] encoded = store.read(secret.id()).orElseThrow(
                        () -> error(ProblemCode.NOT_FOUND, "SECRET_MISSING", "The requested secret is unavailable"));
                byte[] plaintext = null;
                try {
                    plaintext = decode(encoded);
                    T result;
                    try {
                        result = consumer.use(plaintext);
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                        throw error(ProblemCode.IO_FAILURE, "SECRET_CONSUMER_FAILED", "The secret consumer failed");
                    }
                    if (result == plaintext)
                        throw error(ProblemCode.CONTRACT_VIOLATION, "SECRET_RETURNED",
                                "The consumer cannot return the plaintext buffer");
                    return result;
                } finally {
                    Arrays.fill(encoded, '\0');
                    if (plaintext != null)
                        Arrays.fill(plaintext, (byte) 0);
                }
            });
        });
    }

    /**
     * Borrows the original task's credential scope and never exposes store exceptions. / 借用原任务凭据作用域，不暴露存储异常。
     * @param task authentic original task / 真实原任务
     * @param work synchronous credential operation / 同步凭据操作
     * @param <T> safe result type / 安全结果类型
     * @return copied result / 复制的结果
     * @throws ApiException if task authorization or callback fails / 任务授权或回调失败时
     * @throws SQLException if protected storage fails / 受保护存储失败时
     */
    private <T> T credentials(TaskContext task, SecretWork<T> work) throws ApiException, SQLException {
        try {
            return tasks.authorized(task, owner, version, () -> {
                try (var store = security.openCredentials()) {
                    return work.run(store);
                }
            });
        } catch (gold.debug.windowstolinux.app.secret.SecretStoreException failure) {
            // Storage failures still enter the write-uncertainty boundary; being an ApiException does not prove rollback. / 存储失败仍进入写入不确定边界，属于 ApiException 不能证明已回滚。
            throw new SQLException("Protected credential storage failed");
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("Protected credential storage failed");
        }
    }

    /**
     * Maps expected rejection and retains uncertain durable writes. / 映射预期拒绝并保留不确定持久写入。
     * @param task original task / 原任务
     * @param cleanup whether only an existing association is resolved / 是否仅核对已有关系
     * @param writes whether persistence may change / 持久化是否可能改变
     * @param work synchronous operation / 同步操作
     * @param <T> safe result type / 安全结果类型
     * @return copied result / 复制的结果
     * @throws ApiException if authorization or storage fails / 授权或存储失败时
     */
    private <T> T access(TaskContext task, boolean cleanup, boolean writes, TaskCapabilityScope.Work<T> work)
            throws ApiException {
        try {
            return scope.call(task, cleanup, () -> {
                if (writes && !cleanup && tasks.reconciling(task))
                    throw error(ProblemCode.CONTRACT_VIOLATION, "SECRET_RECOVERY_WRITE",
                            "Recovery cannot create new secrets or reserve references");
                try {
                    return work.run();
                } catch (SQLException failure) {
                    if ("42501".equals(failure.getSQLState()))
                        throw error(ProblemCode.ACCESS_DENIED, "SECRET_OWNER",
                                "The secret belongs to another owner, purpose or generation");
                    if ("55000".equals(failure.getSQLState()))
                        throw error(ProblemCode.UNAVAILABLE, "SECRET_SCHEMA", "The data generation is unavailable");
                    if ("02000".equals(failure.getSQLState()))
                        throw error(ProblemCode.NOT_FOUND, "SECRET_MISSING",
                                "The requested secret or reference is unavailable");
                    if (writes)
                        tasks.reportUncertainEffect(task);
                    throw error(writes ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE, "SECRET_STORAGE",
                            writes
                                    ? "Secret persistence requires reconciliation"
                                    : "The protected secret could not be read");
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "SECRET_ACCESS_CLAIM", "Secret capability admission could not be read");
        }
    }

    /** Encodes binary data using only clearable temporary buffers. / 仅使用可清零临时缓冲编码二进制数据。
     * @param bytes private copied bytes / 私有复制字节
     * @return versioned character representation / 带版本的字符表示 */
    private static char[] encode(byte[] bytes) {
        byte[] encoded = Base64.getEncoder().encode(bytes);
        try {
            char[] result = new char[encoded.length + 3];
            result[0] = 'B';
            result[1] = '1';
            result[2] = ':';
            for (int i = 0; i < encoded.length; i++)
                result[i + 3] = (char) encoded[i];
            return result;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    /** Decodes bounded protected data without constructing plaintext strings. / 解码有界受保护数据，不构造明文字符串。
     * @param encoded decrypted temporary characters / 解密得到的临时字符
     * @return temporary plaintext bytes / 临时明文字节
     * @throws ApiException if the stored representation is invalid / 存储表示无效时 */
    private static byte[] decode(char[] encoded) throws ApiException {
        if (encoded.length < 3 || encoded.length > 3 + 4 * ((MAX_BYTES + 2) / 3) || encoded[0] != 'B'
                || encoded[1] != '1' || encoded[2] != ':')
            throw error(ProblemCode.IO_FAILURE, "SECRET_ENCODING", "The stored secret format is invalid");
        byte[] text = new byte[encoded.length - 3];
        try {
            for (int i = 0; i < text.length; i++) {
                if (encoded[i + 3] > 127)
                    throw new IllegalArgumentException();
                text[i] = (byte) encoded[i + 3];
            }
            byte[] result = Base64.getDecoder().decode(text);
            if (result.length > MAX_BYTES) {
                Arrays.fill(result, (byte) 0);
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException invalid) {
            throw error(ProblemCode.IO_FAILURE, "SECRET_ENCODING", "The stored secret format is invalid");
        } finally {
            Arrays.fill(text, (byte) 0);
        }
    }

    /** Validates the exact purpose without normalizing it. / 校验精确用途，不规范化其值。
     * @param purpose declared purpose / 声明用途
     * @throws ApiException if the purpose is absent or too long / 用途缺失或过长时 */
    private static void requirePurpose(String purpose) throws ApiException {
        if (purpose == null || purpose.isBlank() || purpose.length() > 128)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_PURPOSE",
                    "Secret purpose must contain one to 128 characters");
    }

    /** Validates public reference size and purpose. / 校验公开引用大小和用途。
     * @param secret supplied reference / 提供的引用
     * @throws ApiException if the reference is invalid / 引用无效时 */
    private static void requireSecret(SecretRef secret) throws ApiException {
        if (secret == null || secret.id().isBlank() || secret.id().length() > 240)
            throw error(ProblemCode.INVALID_INPUT, "SECRET_ID", "Invalid secret identity");
        requirePurpose(secret.purpose());
    }

    /** Requires selected data for persistent business associations. / 持久业务关联要求选定数据结构。
     * @return selected generation / 选定代次
     * @throws ApiException if the Mod is stateless / Mod 无状态时 */
    private SchemaIdentity requireSchema() throws ApiException {
        if (schema.isEmpty())
            throw error(ProblemCode.UNAVAILABLE, "SECRET_NO_SCHEMA", "Persistent references require a business schema");
        return schema.orElseThrow();
    }

    /** Retries original-task releases after physical work has returned. / 物理操作返回后重试原任务释放。
     * @param task original task / 原任务
     * @throws ApiException if release still fails / 释放仍失败时 */
    public void retryFinishedReleases(TaskContext task) throws ApiException {
        scope.retryFinishedReleases(task);
    }

    /** Seals access and verifies completed physical release. / 封闭访问并核对物理释放完成。
     * @throws ApiException if work or releases remain / 仍有操作或释放时 */
    @Override
    public void close() throws ApiException {
        scope.close();
    }

    /** Builds a failure without secret values or nested consumer exceptions. / 构造不含秘密值或嵌套回调异常的失败。
     * @param code public category / 公开类别
     * @param event stable diagnostic ID / 稳定诊断标识
     * @param message safe explanation / 安全说明
     * @return public failure / 公开失败 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Synchronous credential work inside the retained authorization. / 保留授权中的同步凭据操作。
     * @param <T> safe result type / 安全结果类型 */
    @FunctionalInterface
    private interface SecretWork<T> {
        /** Runs while the scoped credential handle remains owned. / 限定凭据句柄仍被持有时运行。
         * @param store borrowed scoped store / 借用的限定存储
         * @return safe result / 安全结果
         * @throws Exception if storage or callback fails / 存储或回调失败时 */
        T run(SecretStore store) throws Exception;
    }
}
