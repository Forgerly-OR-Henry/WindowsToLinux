package gold.debug.windowstolinux.shared.backup.manifest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/** Versioned root manifest for one portable backup archive. / 单个可移植备份归档的版本化根清单。 */
public record BackupManifest(
        String format,
        String schemaVersion,
        String createdAtUtc,
        String applicationId,
        BackupInventory inventory,
        List<BackupMember> members,
        BackupProvenance provenance
) {
    /** Current stable archive format identifier. / 当前稳定归档格式标识。 */
    public static final String CURRENT_FORMAT = "windowstolinux-backup";
    /** Current manifest schema version. / 当前清单模式版本。 */
    public static final String CURRENT_SCHEMA_VERSION = "3";

    /** Validates schema compatibility and complete member uniqueness. / 校验模式兼容性与完整成员唯一性。 */
    public BackupManifest {
        format = BackupManifestRules.requiredText(format, "format", 64);
        schemaVersion = BackupManifestRules.requiredText(schemaVersion, "schemaVersion", 16);
        createdAtUtc = BackupManifestRules.requiredText(createdAtUtc, "createdAtUtc", 64);
        applicationId = BackupManifestRules.identifier(applicationId, "applicationId");
        inventory = Objects.requireNonNull(inventory, "inventory");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        provenance = Objects.requireNonNull(provenance, "provenance");
        if (!CURRENT_FORMAT.equals(format) || !CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
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
        if (members.isEmpty()) throw new IllegalArgumentException("backup manifest must contain members");
        Set<String> paths = new HashSet<>();
        for (BackupMember member : members) {
            if (!paths.add(member.path().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("backup member paths must be case-insensitively unique");
            }
        }
        validateInventoryMembers(inventory, members);
    }

    /** Creates an unsigned current-format manifest. / 创建当前格式的未签名清单。 */
    public static BackupManifest create(
            Instant createdAt, String applicationId, BackupInventory inventory, List<BackupMember> members) {
        return new BackupManifest(CURRENT_FORMAT, CURRENT_SCHEMA_VERSION,
                Objects.requireNonNull(createdAt, "createdAt").toString(), applicationId,
                inventory, members, BackupProvenance.unsigned());
    }

    /** Returns an equivalent manifest with new provenance. / 返回带新来源信息的等价清单。 */
    public BackupManifest withProvenance(BackupProvenance newProvenance) {
        return new BackupManifest(format, schemaVersion, createdAtUtc, applicationId, inventory, members, newProvenance);
    }

    private static void validateInventoryMembers(BackupInventory inventory, List<BackupMember> members) {
        Map<String, BackupMemberKind> indexed = new HashMap<>();
        members.forEach(member -> indexed.put(member.path(), member.kind()));
        requireMembers(indexed, inventory.releaseManifests(), BackupMemberKind.RELEASE);
        requireMembers(indexed, inventory.configurationSnapshots(), BackupMemberKind.CONFIGURATION);
        requireMembers(indexed, inventory.serviceDefinitions(), BackupMemberKind.RUNTIME);
    }

    private static void requireMembers(
            Map<String, BackupMemberKind> indexed, List<String> requiredPaths, BackupMemberKind requiredKind) {
        for (String path : requiredPaths) {
            if (indexed.get(path) != requiredKind) {
                throw new IllegalArgumentException("inventory definition is missing or has the wrong member kind: " + path);
            }
        }
    }
}
