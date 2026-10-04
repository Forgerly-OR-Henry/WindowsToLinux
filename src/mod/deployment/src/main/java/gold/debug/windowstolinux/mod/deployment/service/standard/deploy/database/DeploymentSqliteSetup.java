package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.BoundedSourceChecker;

/** Prepares source-bound SQLite declarations; physical initialization remains in formal publication. / 准备源码绑定的 SQLite 声明；物理初始化留在正式发布阶段。 */
public final class DeploymentSqliteSetup {
    /** Prevents construction of a stateless preparation helper. / 禁止构造无状态准备器。 */
    private DeploymentSqliteSetup() {
    }

    /** Reviewed non-secret SQLite inputs for a single component. / 单组件经审阅的非秘密 SQLite 输入。
     * @param configuration generated runtime configuration / 生成的运行配置
     * @param bindings exact managed database resources / 精确受管数据库资源
     * @param schemaReview source-bound planned first initialization, when needed / 必要时绑定源码的首次初始化计划 */
    public record Prepared(List<DeploymentConfigEntry> configuration, List<DeploymentDatabaseBinding> bindings,
                           Optional<DatabaseSchemaReview> schemaReview) implements DeploymentPreparedDatabase {
        /** Copies every published value. / 复制全部输出值。
         * @param configuration generated runtime configuration / 生成的运行配置
         * @param bindings managed bindings / 受管绑定
         * @param schemaReview optional actual consent result / 可选实际同意结果 */
        public Prepared {
            configuration = List.copyOf(configuration);
            bindings = List.copyOf(bindings);
            schemaReview = java.util.Objects.requireNonNull(schemaReview);
        }

        /** SQLite needs no platform secret reference. / SQLite 不需要平台秘密引用。
         * @return empty immutable list / 空不可变列表 */
        @Override
        public List<DeploymentSecretRef> secrets() {
            return List.of();
        }
    }

    /** Builds exact SQLite bindings after an independent initialization decision. / 独立初始化决定后生成精确 SQLite 绑定。
     * @param root original frozen component root / 原冻结组件根
     * @param applicationId reviewed managed identity / 经审阅受管身份
     * @param assessment completed source declarations / 已补齐源码声明
     * @param initializationConfirmed explicit separate consent for first initialization / 对首次初始化的独立明确同意
     * @param task original admitted publication task / 原已准入发布任务
     * @return non-secret bindings and source-bound review / 非秘密绑定及源码绑定批准 */
    public static Prepared prepare(Path root, String applicationId, DatabaseProjectChecker.Report assessment,
                                   boolean initializationConfirmed, TaskContext task) {
        if (assessment.unknownDatabase() || assessment.databases().stream()
            .anyMatch(database -> database.engine() != DeploymentDatabaseEngineType.SQLITE))
            throw new IllegalArgumentException("SQLite preparation requires complete SQLite declarations");
        if (assessment.schemaReviewRequired() && !initializationConfirmed)
            throw new IllegalArgumentException("SQLite initialization needs independent consent");
        var configuration = new ArrayList<DeploymentConfigEntry>();
        var bindings = new ArrayList<DeploymentDatabaseBinding>();
        var initialized = new HashSet<String>();
        for (var requirement : assessment.databases()) {
            var sqlite = requirement.sqlite().orElseThrow();
            var connection = new DeploymentDatabaseConnection.Sqlite(sqlite.fileName(), sqlite.location(),
                sqlite.accessPath(), sqlite.seedFile(), requirement.initializationFiles());
            String path = connection.accessPath().isEmpty()
                ? connection.physicalPath(applicationId, requirement.id())
                : connection.accessPath();
            connection.physicalPath(applicationId, requirement.id());
            bindings.add(new DeploymentDatabaseBinding(requirement.id(), connection));
            if (!sqlite.pathEnvironment().isEmpty() || requirement.springDatasource()) {
                String key = requirement.springDatasource() ? "SPRING_DATASOURCE_URL" : sqlite.pathEnvironment();
                configuration.add(text(key, (requirement.springDatasource() ? "jdbc:sqlite:" : "") + path));
            }
            initialized.addAll(requirement.initializationFiles());
        }
        boolean spring = assessment.databases().stream().filter(value -> !value.initializationFiles().isEmpty())
            .allMatch(value -> value.springDatasource());
        if (assessment.schemaReviewRequired() && spring) {
            configuration.add(text("SPRING_SQL_INIT_MODE", "never"));
            configuration.add(flag("SPRING_FLYWAY_ENABLED", false));
            configuration.add(flag("SPRING_LIQUIBASE_ENABLED", false));
            configuration.add(text("SPRING_JPA_HIBERNATE_DDL_AUTO", "none"));
            configuration.add(flag("SPRING_JPA_GENERATE_DDL", false));
        }
        Optional<DatabaseSchemaReview> review = Optional.empty();
        if (assessment.schemaReviewRequired()) {
            var rejections = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason>();
            var source = new BoundedSourceChecker().inspect(root, rejections, task.cancelToken(), applicationId);
            if (task.cancelToken().requested())
                throw new CancellationException("Original SQLite source inspection was cancelled");
            if (!rejections.isEmpty())
                throw new IllegalArgumentException("Frozen SQLite source could not be verified");
            review = Optional.of(new DatabaseSchemaReview(
                SourceMutationPolicy.inspectionDigest(source), bindings.stream()
                .map(DeploymentDatabaseBinding::databaseId).collect(java.util.stream.Collectors.toSet()),
                Set.copyOf(initialized), false, false, spring, true));
        }
        return new Prepared(configuration, bindings, review);
    }

    /** Creates one bounded plain-text runtime entry. / 创建一项有界文本运行配置。
     * @param key runtime key / 运行键
     * @param value non-secret content / 非秘密内容
     * @return typed entry / 类型化条目 */
    private static DeploymentConfigEntry text(String key, String value) {
        return new DeploymentConfigEntry(key, DeploymentConfigScopeType.RUNTIME,
            new DeploymentConfigValue.Text(value));
    }

    /** Creates one runtime safety flag. / 创建一项运行安全标志。
     * @param key runtime key / 运行键
     * @param value Boolean value / 布尔值
     * @return typed entry / 类型化条目 */
    private static DeploymentConfigEntry flag(String key, boolean value) {
        return new DeploymentConfigEntry(key, DeploymentConfigScopeType.RUNTIME,
            new DeploymentConfigValue.Flag(value));
    }
}
