package gold.debug.windowstolinux.shared.analyze.policy;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rejects source declarations that could mutate a managed database automatically.
 *
 * <p>拒绝可能自动修改受管数据库的源码声明。
 */
public final class SourceMutationPolicy {
    private static final Pattern DATABASE_MIGRATION = Pattern.compile(
            "\\b(flyway|liquibase|alembic|prisma(?:\\s+migrate)?|knex)\\b", Pattern.CASE_INSENSITIVE);

    /** Adds deterministic policy rejections without mutating the inspected source. / 添加确定性策略拒绝，不修改被检查源码。 */
    public void validate(SourceInspection source, List<RejectionReason> rejections) {
        if (source.relativeFiles().stream().anyMatch(SourceMutationPolicy::databaseChangePath)) {
            rejections.add(rejection("AUTOMATIC_SCHEMA_MUTATION_DETECTED", "analysis.rejection.schemaMutationDetected"));
        }
        if (DATABASE_MIGRATION.matcher(source.scannedText()).find()) {
            rejections.add(rejection("DATABASE_MIGRATION_DETECTED", "analysis.rejection.migrationDetected"));
        }
    }

    private static boolean databaseChangePath(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("schema.sql") || (name.startsWith("schema-") && name.endsWith(".sql"))) {
            return true;
        }
        if (!name.endsWith(".sql")) {
            return false;
        }
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
            Path segment = parent.getFileName();
            if (segment != null && (segment.toString().equalsIgnoreCase("migration")
                    || segment.toString().equalsIgnoreCase("migrations"))) {
                return true;
            }
        }
        return false;
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
