package gold.debug.windowstolinux.shared.analyze.contract.policy;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rejects source declarations that could mutate a managed database automatically.
 *
 *  <p>拒绝可能自动修改受管数据库的源码声明。
 */
public final class SourceMutationPolicy {
    /**
     * Pattern recognizing DATABASE MIGRATION.
     * <p>用于识别数据库迁移的匹配模式。
     */
    private static final Pattern DATABASE_MIGRATION = Pattern.compile(
            "\\b(flyway|liquibase|alembic|prisma(?:\\s+migrate)?|knex)\\b"
                    + "|(?:ddl-auto|hibernate\\.hbm2ddl\\.auto)\\s*[:=]\\s*(?:create(?:-drop)?|update)\\b"
                    + "|(?:spring\\.sql\\.init\\.mode|initialization-mode|sql:\\s+init:\\s+mode)\\s*[:=]\\s*always\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Adds deterministic policy rejections without mutating the inspected source. / 添加确定性策略拒绝，不修改被检查源码。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     */
    public void validate(SourceInspectionFacts source, List<RejectionReason> rejections) {
        validate(source, rejections, java.util.Optional.empty());
    }

    /**
     * Admits schema declarations only with matching database evidence and disabled duplicate framework entrypoints. / 仅在数据库证据匹配且重复框架入口被禁用时接纳模式声明。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @param review review / 审阅
     */
    public void validate(SourceInspectionFacts source, List<RejectionReason> rejections,
                         java.util.Optional<gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> review) {
        if (review.filter(value -> value.inspectionSha256().equals(inspectionDigest(source)))
                .filter(value -> !DATABASE_MIGRATION.matcher(source.scannedText()).find() || value.frameworkMigrationsDisabled())
                .filter(value -> value.existingSchemaChangeApproved() || source.relativeFiles().stream()
                        .filter(SourceMutationPolicy::databaseChangePath)
                        .allMatch(path -> value.initializedSqlPaths().contains(path.toString().replace('\\', '/')))).isPresent()) return;
        if (source.relativeFiles().stream().anyMatch(SourceMutationPolicy::databaseChangePath)) {
            rejections.add(rejection("AUTOMATIC_SCHEMA_MUTATION_DETECTED", "analysis.rejection.schemaMutationDetected"));
        }
        if (DATABASE_MIGRATION.matcher(source.scannedText()).find()) {
            rejections.add(rejection("DATABASE_MIGRATION_DETECTED", "analysis.rejection.migrationDetected"));
        }
    }

    /**
     * Detects review needs during static discovery without authorizing a deployment. / 在静态发现中检测审阅需求，不授权部署。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return true when detects review needs during static discovery without authorizing a deployment, false otherwise / 在静态发现中检测审阅需求，不授权部署时为 true，否则为 false
     */
    public boolean requiresReview(SourceInspectionFacts source) {
        return source.relativeFiles().stream().anyMatch(SourceMutationPolicy::databaseChangePath)
                || DATABASE_MIGRATION.matcher(source.scannedText()).find();
    }

    /**
     * Binds approval to the bounded file list and exact inspected contents. / 将批准绑定有界文件列表及精确检查内容。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return inspection digest text / 检查摘要文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static String inspectionDigest(SourceInspectionFacts source) {
        try {
            String content = source.relativeFiles().stream().map(path -> path.toString().replace('\\', '/')).sorted()
                    .collect(java.util.stream.Collectors.joining("\n")) + "\0" + source.scannedText();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /**
     * Tests the database change path predicate against the supplied evidence.
     * <p>根据所提供证据检查数据库变更路径条件。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return true when database change path predicate against the supplied evidence, false otherwise / 根据所提供证据检查数据库变更路径条件时为 true，否则为 false
     */
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

    /**
     * Builds the admission rejection associated with the supplied reason.
     * <p>构建与所提供原因关联的准入拒绝。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the admission rejection associated with the supplied reason / 与所提供原因关联的准入拒绝
     */
    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
