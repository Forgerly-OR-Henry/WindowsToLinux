package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.execution.migration.OfflineMigrationResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Product migration result plus the retained stopped-write archive and local cleanup warnings. / 产品迁移结果及保留的停写归档与本地清理警告。 */
public record ManagedOfflineMigrationOutcome(
        OfflineMigrationResult migration,
        Optional<Path> retainedFinalArchive,
        List<String> warnings
) {
    /** Preserves a final archive only when one was completely published and verified. / 仅在最终归档完整发布并验证后保留其路径。 */
    public ManagedOfflineMigrationOutcome {
        migration = Objects.requireNonNull(migration, "migration");
        retainedFinalArchive = Objects.requireNonNull(retainedFinalArchive, "retainedFinalArchive")
                .map(value -> value.toAbsolutePath().normalize());
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (warnings.size() > 16 || warnings.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("migration warnings are invalid");
        }
    }
}
