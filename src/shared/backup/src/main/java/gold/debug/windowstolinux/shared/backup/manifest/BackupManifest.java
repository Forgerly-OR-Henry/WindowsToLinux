package gold.debug.windowstolinux.shared.backup.manifest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned root manifest for one portable backup archive. / 单个可移植备份归档的版本化根清单。
 *
 * @param format format / 格式
 * @param schemaVersion the configuration schema version / 配置模式版本
 * @param createdAtUtc created at utc / 已创建时刻UTC
 * @param applicationId managed application identifier / 受管应用标识
 * @param inventory inventory / 清单
 * @param members members / 成员集合
 * @param provenance provenance / 来源证据
 */
public record BackupManifest(
        String format,
        String schemaVersion,
        String createdAtUtc,
        String applicationId,
        BackupInventory inventory,
        List<BackupMember> members,
        BackupProvenance provenance
) {
    /**
     * Current stable archive format identifier. / 当前稳定归档格式标识。
     */
    public static final String CURRENT_FORMAT = "windowstolinux-backup";
    /**
     * Current manifest schema version. / 当前清单模式版本。
     */
    public static final String CURRENT_SCHEMA_VERSION = "6";

    /**
     * Validates schema compatibility and complete member uniqueness. / 校验模式兼容性与完整成员唯一性。
     *
     * @param format format / 格式
     * @param schemaVersion the configuration schema version / 配置模式版本
     * @param createdAtUtc created at utc / 已创建时刻UTC
     * @param applicationId managed application identifier / 受管应用标识
     * @param inventory inventory / 清单
     * @param members members / 成员集合
     * @param provenance provenance / 来源证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupManifest {
        format = BackupManifestRules.requiredText(format, "format", 64);
        schemaVersion = BackupManifestRules.requiredText(schemaVersion, "schemaVersion", 16);
        createdAtUtc = BackupManifestRules.requiredText(createdAtUtc, "createdAtUtc", 64);
        applicationId = BackupManifestRules.identifier(applicationId, "applicationId");
        inventory = Objects.requireNonNull(inventory, "inventory");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        provenance = Objects.requireNonNull(provenance, "provenance");
        if (!CURRENT_FORMAT.equals(format)
                || !CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported backup format or schema version");
        }
        try {
            Instant parsed = Instant.parse(createdAtUtc);
            if (!parsed.toString().equals(createdAtUtc)) throw new IllegalArgumentException("createdAtUtc must be canonical UTC");
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("createdAtUtc must be a canonical UTC instant", exception);
        }
        if (!applicationId.equals(inventory.identity().applicationId())) {
            throw new IllegalArgumentException("manifest and inventory application identities differ");
        }
        boolean exactActivation = inventory.identity().releaseSetSha256().isPresent();
        if (CURRENT_SCHEMA_VERSION.equals(schemaVersion) != exactActivation) {
            throw new IllegalArgumentException("manifest schema version differs from its release and secret bindings");
        }
        if (members.isEmpty()) throw new IllegalArgumentException("backup manifest must contain members");
        Set<String> paths = new HashSet<>();
        for (BackupMember member : members) {
            if (!paths.add(member.path().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("backup member paths must be case-insensitively unique");
            }
        }
        validateInventoryMembers(inventory, members);
    }

    /**
     * Creates an unsigned current-format manifest. / 创建当前格式的未签名清单。
     *
     * @param createdAt instant at which this record was created / 当前记录创建时刻
     * @param applicationId managed application identifier / 受管应用标识
     * @param inventory inventory / 清单
     * @param members members / 成员集合
     * @return an unsigned current-format manifest / 当前格式的未签名清单
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupManifest create(
            Instant createdAt, String applicationId, BackupInventory inventory, List<BackupMember> members) {
        return new BackupManifest(CURRENT_FORMAT, CURRENT_SCHEMA_VERSION,
                Objects.requireNonNull(createdAt, "createdAt").toString(), applicationId,
                inventory, members, BackupProvenance.unsigned());
    }

    /**
     * Returns an equivalent manifest with new provenance. / 返回带新来源信息的等价清单。
     *
     * @param newProvenance new provenance / 新来源证据
     * @return an equivalent manifest with new provenance / 带新来源信息的等价清单
     */
    public BackupManifest withProvenance(BackupProvenance newProvenance) {
        return new BackupManifest(format, schemaVersion, createdAtUtc, applicationId, inventory, members, newProvenance);
    }

    /**
     * Returns whether this manifest contains all identities required for automatic activation. / 返回清单是否包含自动激活所需的全部身份。
     *
     * @return true when returns whether this manifest contains all identities required for automatic activation, false otherwise / 返回清单是否包含自动激活所需的全部身份时为 true，否则为 false
     */
    public boolean supportsAutomaticActivation() {
        return CURRENT_SCHEMA_VERSION.equals(schemaVersion)
                && (inventory.secretReferences().isEmpty() || includesEncryptedSecrets());
    }

    /**
     * Returns whether the archive declares its one fixed encrypted-secret member. / 返回归档是否声明唯一固定加密秘密成员。
     *
     * @return true when returns whether the archive declares its one fixed encrypted-secret member, false otherwise / 返回归档是否声明唯一固定加密秘密成员时为 true，否则为 false
     */
    public boolean includesEncryptedSecrets() {
        return members.stream().anyMatch(member -> member.kind() == BackupMemberKind.ENCRYPTED_SECRETS);
    }

    /**
     * Validates inventory members and rejects inputs outside the declared constraints.
     * <p>校验清单成员集合并拒绝超出已声明约束的输入。
     *
     * @param inventory inventory / 清单
     * @param members members / 成员集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateInventoryMembers(BackupInventory inventory, List<BackupMember> members) {
        Map<String, BackupMemberKind> indexed = new HashMap<>();
        members.forEach(member -> indexed.put(member.path(), member.kind()));
        requireMembers(indexed, inventory.releaseManifests(), BackupMemberKind.RELEASE);
        requireMembers(indexed, inventory.configurationSnapshots(), BackupMemberKind.CONFIGURATION);
        requireMembers(indexed, inventory.serviceDefinitions(), BackupMemberKind.RUNTIME);
        List<BackupMember> encryptedSecrets = members.stream()
                .filter(member -> member.kind() == BackupMemberKind.ENCRYPTED_SECRETS).toList();
        if (encryptedSecrets.size() > 1
                || !encryptedSecrets.isEmpty() && !"secrets.enc".equals(encryptedSecrets.getFirst().path())) {
            throw new IllegalArgumentException("encrypted secret content must use the single fixed secrets.enc member");
        }
        boolean declaresSecrets = inventory.identity().releaseSetSha256().isPresent()
                ? !inventory.secretReferences().isEmpty()
                : !inventory.legacySecretReferences().isEmpty();
        if (!declaresSecrets && !encryptedSecrets.isEmpty()) {
            throw new IllegalArgumentException("encrypted secret content has no declared manifest references");
        }
    }

    /**
     * Requires members and rejects inputs outside the declared constraints.
     * <p>要求成员集合并拒绝超出已声明约束的输入。
     *
     * @param indexed indexed / 已索引
     * @param requiredPaths required paths / 必需路径集合
     * @param requiredKind required kind / 必需种类
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requireMembers(
            Map<String, BackupMemberKind> indexed, List<String> requiredPaths, BackupMemberKind requiredKind) {
        for (String path : requiredPaths) {
            if (indexed.get(path) != requiredKind) {
                throw new IllegalArgumentException("inventory definition is missing or has the wrong member kind: " + path);
            }
        }
    }
}
