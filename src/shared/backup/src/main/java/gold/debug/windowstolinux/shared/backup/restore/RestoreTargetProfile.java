package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Verified target facts required before any restore mutation. / 任何恢复修改前所需的已验证目标事实。 */
public record RestoreTargetProfile(
        String serverId,
        String distroId,
        String distroVersion,
        String architecture,
        String runtimeKind,
        String runtimeVersion,
        BackupDatabaseType databaseType,
        String databaseEngineVersion,
        long availableBytes,
        boolean managedRootWritable,
        boolean requiredPortsAvailable,
        boolean foreignApplicationConflict,
        boolean sourceRebuildSupported,
        boolean databaseCompatibilityVerified,
        boolean binaryExperimentApproved,
        List<String> evidence
) {
    /** Validates bounded non-secret target evidence. / 校验有界无秘密目标证据。 */
    public RestoreTargetProfile {
        serverId = identifier(serverId, "serverId");
        distroId = identifier(distroId, "distroId");
        distroVersion = text(distroVersion, "distroVersion", 128);
        architecture = identifier(architecture, "architecture");
        runtimeKind = identifier(runtimeKind, "runtimeKind");
        runtimeVersion = text(runtimeVersion, "runtimeVersion", 128);
        databaseType = Objects.requireNonNull(databaseType, "databaseType");
        databaseEngineVersion = text(databaseEngineVersion, "databaseEngineVersion", 128);
        if (availableBytes < 0) throw new IllegalArgumentException("availableBytes must not be negative");
        evidence = evidence(evidence);
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String text(String value, String field, int maximumLength) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain bounded printable text");
        }
        return value;
    }

    private static List<String> evidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("target evidence is incomplete");
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String item = text(value, "evidence", 512);
            if (!unique.add(item)) throw new IllegalArgumentException("target evidence contains duplicates");
            result.add(item);
        }
        return List.copyOf(result);
    }
}
