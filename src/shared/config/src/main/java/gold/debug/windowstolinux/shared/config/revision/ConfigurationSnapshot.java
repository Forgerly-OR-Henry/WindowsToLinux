package gold.debug.windowstolinux.shared.config.revision;

import gold.debug.windowstolinux.shared.config.ConfigurationException;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, digest-addressed normal configuration for one released application version.
 *
 *  <p>一个已发布应用版本的不可变、以摘要寻址的普通配置。
 *
 * @param applicationId the managed application identifier / 受管应用标识
 * @param revision the positive immutable revision / 正的不可变修订号
 * @param schemaVersion the configuration schema version / 配置模式版本
 * @param createdAt the creation time / 创建时间
 * @param entries the type-checked entries / 经类型检查的条目
 * @param sha256 the canonical content digest / 规范内容摘要
 */
public record ConfigurationSnapshot(
        String applicationId,
        long revision,
        String schemaVersion,
        Instant createdAt,
        List<ConfigurationEntry> entries,
        String sha256
) {
    /**
     * Validates and binds the inputs required by configuration snapshot.
     * <p>校验并绑定配置快照所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param schemaVersion the schema version / 模式版本
     * @param createdAt instant at which this record was created / 当前记录创建时刻
     * @param entries the entries / 条目
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ConfigurationSnapshot {
        applicationId = requireIdentifier(applicationId, "applicationId");
        if (revision < 1) {
            throw ConfigurationException.create(ConfigurationFailureType.REVISION_INVALID, "A configuration revision must be positive");
        }
        schemaVersion = requireIdentifier(schemaVersion, "schemaVersion");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.stream().map(ConfigurationEntry::key).distinct().count() != entries.size()) {
            throw ConfigurationException.create(ConfigurationFailureType.DUPLICATE_KEY, "Configuration snapshot keys must be unique");
        }
        sha256 = requireSha256(sha256);
        if (!sha256.equals(computeSha256(applicationId, revision, schemaVersion, entries))) {
            throw ConfigurationException.create(ConfigurationFailureType.SNAPSHOT_INTEGRITY_FAILED, "The supplied hash does not match canonical configuration content");
        }
    }

    /**
     * Creates an immutable snapshot with its canonical digest.
     *
     *  <p>创建带有规范摘要的不可变快照。
     *
     * @param applicationId the managed application identifier / 受管应用标识
     * @param revision the next positive revision / 下一个正修订号
     * @param schemaVersion the schema version / 模式版本
     * @param createdAt the creation time / 创建时间
     * @param entries the entries / 条目
     * @return the immutable snapshot / 不可变快照
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static ConfigurationSnapshot create(
            String applicationId, long revision, String schemaVersion, Instant createdAt, List<ConfigurationEntry> entries
    ) {
        String normalizedApplicationId = requireIdentifier(applicationId, "applicationId");
        String normalizedSchemaVersion = requireIdentifier(schemaVersion, "schemaVersion");
        List<ConfigurationEntry> copiedEntries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        return new ConfigurationSnapshot(normalizedApplicationId, revision, normalizedSchemaVersion, createdAt, copiedEntries,
                computeSha256(normalizedApplicationId, revision, normalizedSchemaVersion, copiedEntries));
    }

    /**
     * Finds a value only within its declared scope.
     *
     *  <p>只在其声明范围内查找值。
     *
     * @param key the declared key / 声明的键
     * @param scope the required scope / 所需范围
     * @return the matching value / 匹配的值
     */
    public ConfigurationValue requireValue(String key, ConfigurationScope scope) {
        return entries.stream()
                .filter(entry -> entry.key().equals(key) && entry.scope() == scope)
                .findFirst()
                .map(ConfigurationEntry::value)
                .orElseThrow(() -> new IllegalArgumentException("no value exists for the requested key and scope"));
    }

    /**
     * Computes the deterministic digest for snapshot content.
     *
     *  <p>计算快照内容的确定性摘要。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param schemaVersion the schema version / 模式版本
     * @param entries the entries / 条目
     * @return the deterministic digest for snapshot content / 快照内容的确定性摘要
     */
    public static String computeSha256(String applicationId, long revision, String schemaVersion, List<ConfigurationEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, requireIdentifier(applicationId, "applicationId"));
            update(digest, Long.toString(revision));
            update(digest, requireIdentifier(schemaVersion, "schemaVersion"));
            List<ConfigurationEntry> sorted = List.copyOf(entries).stream()
                    .sorted(Comparator.comparing(ConfigurationEntry::key).thenComparing(entry -> entry.scope().name()))
                    .toList();
            for (ConfigurationEntry entry : sorted) {
                update(digest, entry.key());
                update(digest, entry.scope().name());
                update(digest, entry.value().getClass().getSimpleName());
                update(digest, entry.value().canonicalValue());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw ConfigurationException.create(ConfigurationFailureType.HASH_ALGORITHM_UNAVAILABLE, "The required SHA-256 implementation is unavailable", exception);
        }
    }

    /**
     * Updates configuration snapshot.
     * <p>更新配置快照。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /**
     * Validates and returns the stable secret identifier.
     * <p>校验并返回稳定的秘密标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require identifier text / 要求标识文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw ConfigurationException.create(ConfigurationFailureType.IDENTIFIER_INVALID,
                    "A configuration snapshot identifier must be bounded and lowercase");
        }
        return value;
    }

    /**
     * Validates and returns lower-case hexadecimal SHA-256 digest.
     * <p>校验并返回小写十六进制 SHA-256 摘要。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require sha 256 text / 要求SHA256文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireSha256(String value) {
        value = Objects.requireNonNull(value, "sha256");
        if (!value.matches("[0-9a-f]{64}")) {
            throw ConfigurationException.create(ConfigurationFailureType.HASH_INVALID, "A SHA-256 value must use the canonical lowercase form");
        }
        return value;
    }
}
