package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.database;

import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentSqliteSetup;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseSetup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigInput;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DatabaseSchemaReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabasePlanActionLog;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog;

/** Separates whole-source database validation from original-task native effects. / 将整份源码数据库校验与原任务原生效果分开。 */
public final class DeploymentDatabaseStages {
    /** Original Mod capabilities. / 原 Mod 能力。 */
    private final ModContext context;

    /** Binds the existing original task capabilities. / 绑定既有原任务能力。
     * @param context original Mod context / 原 Mod 上下文 */
    public DeploymentDatabaseStages(ModContext context) {
        this.context = context;
    }

    /** All local facts for one component. / 一组件的全部本机事实。
     * @param sqlite verified local SQLite output / 已验证本机 SQLite 输出
     * @param nativeSource optional fully checked native input / 可选已完整校验原生输入 */
    public record Local(DeploymentSqliteSetup.Prepared sqlite,
                        Optional<DeploymentNativeDatabaseSetup.Local> nativeSource) {
        /** Lists actual native child addresses before any effect. / 任何效果前列出真实原生子项地址。
         * @return exact child addresses / 精确子项地址 */
        List<DeploymentDatabasePlanActionLog.Entry> entries() {
            return nativeSource.stream()
                .flatMap(source -> source.nativeDatabases().stream().map(
                    database -> new DeploymentDatabasePlanActionLog.Entry(source.componentId(), database.id())))
                .toList();
        }

        /** Whether generated DB bindings will replace manual runtime DB fields. / 生成绑定是否替代手填运行数据库字段。
         * @return true for an actual database declaration / 存在实际数据库声明时为真 */
        public boolean hasBindings() {
            return !sqlite.bindings().isEmpty() || nativeSource.isPresent();
        }
    }

    /** Immutable merged publication facts. / 不可变合并发布事实。
     * @param configuration exact non-secret runtime entries / 精确非秘密运行条目
     * @param bindings managed DB targets / 受管数据库目标
     * @param secrets immutable secret revisions / 不可变秘密修订
     * @param schemaReview one actual component source-bound review / 同组件真实源码绑定审阅 */
    public record Prepared(List<DeploymentConfigEntry> configuration, List<DeploymentDatabaseBinding> bindings,
                           List<DeploymentSecretRef> secrets,
                           Optional<DatabaseSchemaReview> schemaReview) implements DeploymentPreparedDatabase {
        /** Copies exact immutable output. / 复制精确不可变输出。
         * @param configuration runtime entries / 运行条目
         * @param bindings DB targets / 数据库目标
         * @param secrets secret revisions / 秘密修订
         * @param schemaReview source-bound review / 来源绑定审阅 */
        public Prepared {
            configuration = List.copyOf(configuration);
            bindings = List.copyOf(bindings);
            secrets = List.copyOf(secrets);
            schemaReview = java.util.Objects.requireNonNull(schemaReview);
        }
    }

    /** Completes and validates every local declaration without a server effect. / 无服务器效果地补齐校验每项本地声明。
     * @param server selected server / 选中服务器
     * @param componentId frozen component / 冻结组件
     * @param applicationId final managed identity / 最终受管身份
     * @param manifestSha256 frozen manifest / 冻结清单
     * @param root frozen component root / 冻结组件根
     * @param review owner-view decisions / 所属视图决定
     * @param task original task / 原任务
     * @return fully checked local stage or classified refusal / 完整本机阶段或分类拒绝
     * @throws Exception if original source or review fails / 原来源或审阅失败时 */
    public OperationResult<Local> local(ServerId server, String componentId, String applicationId, String manifestSha256,
                                        Path root, DeploymentStandardInputs.Reviewer review, TaskContext task) throws Exception {
        var observed = new DatabaseProjectChecker().inspect(root, applicationId);
        Map<String, String> answers = Map.of();
        if (!observed.databases().isEmpty() || observed.unknownDatabase()) {
            var supplied = review.databaseInputs(server, componentId, manifestSha256, root, applicationId, observed,
                task);
            if (supplied.outcome() != TaskResult.SUCCEEDED)
                return new OperationResult<>(supplied.outcome(), Optional.empty(), supplied.problem());
            answers = supplied.value().orElseThrow();
        }
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        var completed = DeploymentDatabaseSourceInputs.complete(root, applicationId, observed, answers, layout);
        var sqlite = subset(completed, DeploymentDatabaseEngineType.SQLITE);
        var nativeDatabases = completed.databases().stream()
            .filter(value -> value.engine() != DeploymentDatabaseEngineType.SQLITE).toList();
        var nativeAssessment = new DatabaseProjectChecker.Report(nativeDatabases, completed.sqlCandidates(),
            completed.schemaReviewRequired()
                && nativeDatabases.stream().anyMatch(value -> !value.initializationFiles().isEmpty()),
            false, completed.endpointConfirmationRequired());
        var sqlitePaths = paths(sqlite.databases());
        var nativePaths = paths(nativeDatabases);
        sqlitePaths.addAll(nativePaths);
        if (sqlitePaths.size() > 64)
            throw new IllegalArgumentException("Database schema review exceeds 64 SQL paths for one component");
        boolean approved = false;
        if (sqlite.schemaReviewRequired()) {
            var consent = review.confirmSqliteInitialization(server, componentId, manifestSha256, sqlite, task);
            if (consent.outcome() != TaskResult.SUCCEEDED)
                return new OperationResult<>(consent.outcome(), Optional.empty(), consent.problem());
            if (!consent.value().orElse(false))
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), consent.problem());
            approved = true;
        }
        var localSqlite = DeploymentSqliteSetup.prepare(root, applicationId, sqlite, approved, task, layout);
        var reserved = new HashSet<String>();
        for (var value : localSqlite.configuration())
            if (!reserved.add(value.key()))
                throw new IllegalArgumentException("Duplicate SQLite runtime configuration");
        for (var requirement : nativeDatabases)
            for (String key : nativeKeys(requirement))
                if (!reserved.add(key))
                    throw new IllegalArgumentException("Database runtime configuration conflicts across engines");
        if (nativeAssessment.schemaReviewRequired()
            && nativeDatabases.stream().filter(value -> !value.initializationFiles().isEmpty())
            .allMatch(DatabaseRequirement::springDatasource))
            reserved.addAll(frameworkKeys());
        var localNative = nativeDatabases.isEmpty()
            ? Optional.<DeploymentNativeDatabaseSetup.Local>empty()
            : Optional.of(DeploymentNativeDatabaseSetup.local(componentId, applicationId, manifestSha256,
            root, nativeAssessment, task));
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new Local(localSqlite, localNative)),
            Optional.empty());
    }

    /** Rejects runtime entries conflicting with source-derived DB entries before remote effects. / 远端效果前拒绝与源码派生数据库条目冲突的运行配置。
     * @param local locally validated database facts / 本地已验证数据库事实
     * @param answer reviewed runtime answer / 经审阅运行答案 */
    public void validateRuntime(Local local, DeploymentStandardInputs.Answers answer) {
        var keys = new HashSet<String>();
        local.sqlite().configuration().forEach(value -> keys.add(value.key()));
        local.nativeSource()
            .ifPresent(source -> source.nativeDatabases().forEach(database -> keys.addAll(nativeKeys(database))));
        local.nativeSource()
            .filter(source -> source.assessment().schemaReviewRequired()
                && source.nativeDatabases().stream().filter(value -> !value.initializationFiles().isEmpty())
                .allMatch(DatabaseRequirement::springDatasource))
            .ifPresent(source -> keys.addAll(frameworkKeys()));
        for (var value : DeploymentConfigInput.parse(answer.values().getOrDefault("configuration", "")))
            if (keys.contains(value.key()))
                throw new IllegalArgumentException("Runtime configuration conflicts with source database");
    }

    /** Persists the complete child list before any native effect. / 任何原生效果前持久保存完整子项列表。
     * @param server exact server / 精确服务器
     * @param manifestSha256 frozen manifest / 冻结清单
     * @param local all component stages / 全部组件阶段
     * @param task original task / 原任务
     * @return optional original plan / 可选原计划
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if persistence fails / 持久化失败时 */
    public Optional<DeploymentDatabasePlanActionLog> begin(ServerId server, String manifestSha256, List<Local> local,
                                                           TaskContext task) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        var entries = local.stream().flatMap(value -> value.entries().stream()).toList();
        return entries.isEmpty()
            ? Optional.empty()
            : Optional.of(
            DeploymentDatabasePlanActionLog.begin(context.tasks(), task, server, manifestSha256, entries));
    }

    /** Releases only original children with confirmed terminal or unsent intent facts. / 只释放原子项已确认终态或未发送意图的依据。
     * @param task original task / 原任务
     * @param plan original indexed plan / 原索引计划
     * @return true only when all indexed work is known and released / 仅全部索引工作确知且释放时为真
     * @throws ApiException if original evidence is unavailable / 原依据不可得时 */
    public boolean releaseKnown(TaskContext task, DeploymentDatabasePlanActionLog plan) throws ApiException {
        for (String key : plan.facts().keys()) {
            var child = DeploymentNativeDatabaseActionLog.find(context.tasks(), task,
                DeploymentNativeDatabaseActionLog.KEY + "." + key);
            if (child.isPresent()) {
                var facts = child.orElseThrow().facts();
                if (!plan.facts().serverId().equals(facts.serverId()) || "DISPATCHED".equals(facts.phase()))
                    return false;
                child.orElseThrow().close();
            }
            var secretKey = "deployment-secret-revision." + key;
            if (context.tasks().findCheckpoint(task, secretKey).isPresent()
                && new DeploymentSecretProvisionFlow(context)
                .reconcileChild(task, secretKey, new ServerId(plan.facts().serverId()))
                .outcome() == TaskResult.UNKNOWN)
                return false;
        }
        plan.close();
        return true;
    }

    /** Summarizes only bounded original database and secret child facts for a later refusal. / 为后续拒绝仅概括有界原数据库及秘密子项事实。
     * @param task original task / 原任务
     * @param plan original durable index / 原持久索引
     * @return non-secret prior-effect context / 不含秘密的先前效果上下文
     * @throws ApiException if evidence lookup fails / 依据查找失败时 */
    public Map<String, String> effectDetails(TaskContext task, DeploymentDatabasePlanActionLog plan) throws ApiException {
        int affected = 0;
        int savedSecrets = 0;
        String firstTarget = "";
        for (String key : plan.facts().keys()) {
            var child = DeploymentNativeDatabaseActionLog.find(context.tasks(), task,
                DeploymentNativeDatabaseActionLog.KEY + "." + key);
            if (child.isPresent() && child.orElseThrow().facts().effectsObserved()) {
                affected++;
                if (firstTarget.isEmpty())
                    firstTarget = child.orElseThrow().facts().target();
            }
            if (new DeploymentSecretProvisionFlow(context)
                .savedChild(task, "deployment-secret-revision." + key, new ServerId(plan.facts().serverId()))
                .isPresent())
                savedSecrets++;
        }
        if (affected == 0 && savedSecrets == 0)
            return Map.of();
        var result = new HashMap<String, String>();
        result.put("databaseServerId", plan.facts().serverId());
        result.put("databaseAffectedCount", Integer.toString(affected));
        result.put("savedSecretRevisionCount", Integer.toString(savedSecrets));
        if (!firstTarget.isEmpty())
            result.put("firstDatabaseTarget", firstTarget);
        return Map.copyOf(result);
    }

    /** Executes native children under their original index, then merges source-bound outputs. / 在原索引下执行原生子项，再合并源码绑定输出。
     * @param server exact server / 精确服务器
     * @param plan original indexed plan / 原索引计划
     * @param local local validated component / 本机已验证组件
     * @param review original owner-view decisions / 原所属视图决定
     * @param task original task / 原任务
     * @return merged publication facts or classified refusal / 合并发布事实或分类拒绝
     * @throws Exception if original access fails / 原访问失败时 */
    public OperationResult<DeploymentPreparedDatabase> prepare(ServerId server, Optional<DeploymentDatabasePlanActionLog> plan,
                                                               Local local, DeploymentStandardInputs.Reviewer review, TaskContext task) throws Exception {
        if (local.nativeSource().isEmpty())
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(local.sqlite()), Optional.empty());
        var nativeResult = new DeploymentNativeDatabaseSetup(context).prepare(server, plan.orElseThrow(),
            local.nativeSource().orElseThrow(), review, task,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
        if (nativeResult.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(nativeResult.outcome(), Optional.empty(), nativeResult.problem());
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(merge(local.sqlite(), nativeResult.value().orElseThrow())), Optional.empty());
    }

    /** Unions exact SQLite/native facts for the same component and source digest. / 合并同组件同源码摘要的 SQLite 与原生事实。
     * @param sqlite local SQLite facts / 本机 SQLite 事实
     * @param nativeOutput verified native facts / 已核原生事实
     * @return one publishable result / 一份可发布结果 */
    private static Prepared merge(DeploymentSqliteSetup.Prepared sqlite,
                                  DeploymentNativeDatabaseSetup.Prepared nativeOutput) {
        var configuration = new HashMap<String, DeploymentConfigEntry>();
        for (var value : sqlite.configuration())
            configuration.put(value.key(), value);
        for (var value : nativeOutput.configuration()) {
            var prior = configuration.putIfAbsent(value.key(), value);
            if (prior != null && !prior.equals(value))
                throw new IllegalArgumentException("Database runtime configuration conflicts across engines");
        }
        var bindings = new ArrayList<>(sqlite.bindings());
        bindings.addAll(nativeOutput.bindings());
        if (new HashSet<>(bindings.stream().map(DeploymentDatabaseBinding::databaseId).toList()).size() != bindings
            .size())
            throw new IllegalArgumentException("Database ID is duplicated across engines");
        return new Prepared(List.copyOf(configuration.values()), bindings, nativeOutput.secrets(),
            combine(sqlite.schemaReview(), nativeOutput.schemaReview()));
    }

    /** Combines independent decisions only when both bind the same component digest. / 仅在两种独立决定绑定同一组件摘要时合并。
     * @param sqlite SQLite consent / SQLite 同意
     * @param nativeReview native verified work / 已核原生效果
     * @return one source-bound review / 一份来源绑定审阅 */
    private static Optional<DatabaseSchemaReview> combine(Optional<DatabaseSchemaReview> sqlite,
                                                          Optional<DatabaseSchemaReview> nativeReview) {
        if (sqlite.isEmpty())
            return nativeReview;
        if (nativeReview.isEmpty())
            return sqlite;
        var first = sqlite.orElseThrow();
        var second = nativeReview.orElseThrow();
        if (!first.inspectionSha256().equals(second.inspectionSha256()))
            throw new IllegalArgumentException("Database reviews refer to different component source digests");
        var ids = new HashSet<>(first.databaseIds());
        ids.addAll(second.databaseIds());
        var sql = new HashSet<>(first.initializedSqlPaths());
        sql.addAll(second.initializedSqlPaths());
        return Optional.of(new DatabaseSchemaReview(first.inspectionSha256(), ids, sql,
            first.newOwnedDatabaseInitialized() || second.newOwnedDatabaseInitialized(),
            first.existingSchemaChangeApproved() || second.existingSchemaChangeApproved(),
            first.frameworkMigrationsDisabled() || second.frameworkMigrationsDisabled(),
            first.firstDeploymentInitializationPlanned() || second.firstDeploymentInitializationPlanned()));
    }

    /** Filters one engine without changing the original component source observation. / 按引擎筛选且不改原组件来源观测。
     * @param original completed source assessment / 已补齐来源评估
     * @param engine selected engine / 选中引擎
     * @return filtered assessment / 筛选后评估 */
    private static DatabaseProjectChecker.Report subset(DatabaseProjectChecker.Report original,
                                                        DeploymentDatabaseEngineType engine) {
        var values = original.databases().stream().filter(value -> value.engine() == engine).toList();
        return new DatabaseProjectChecker.Report(values, original.sqlCandidates(),
            original.schemaReviewRequired()
                && values.stream().anyMatch(value -> !value.initializationFiles().isEmpty()),
            false, original.endpointConfirmationRequired());
    }

    /** Collects exact source SQL paths for the existing component review limit. / 为既有组件审阅上限收集精确源码 SQL 路径。
     * @param databases completed declarations / 已补齐声明
     * @return distinct relative paths / 不重复相对路径 */
    private static HashSet<String> paths(List<DatabaseRequirement> databases) {
        var result = new HashSet<String>();
        databases.forEach(value -> result.addAll(value.initializationFiles()));
        return result;
    }

    /** Computes only the keys generated for an actual native requirement. / 仅计算真实原生数据库要求会生成的键。
     * @param database completed requirement / 已补齐要求
     * @return exact generated keys / 精确生成键 */
    private static List<String> nativeKeys(DatabaseRequirement database) {
        String prefix = database.environmentPrefix();
        var result = new ArrayList<String>();
        if (database.springDatasource())
            result.add(prefix + "_URL");
        else {
            result.add(prefix + "_HOST");
            result.add(prefix + "_PORT");
            result.add(prefix + "_DATABASE");
        }
        result.add(prefix + "_USERNAME");
        if (database.engine() == DeploymentDatabaseEngineType.REDIS)
            result.add(prefix + "_KEY_PREFIX");
        return result;
    }

    /** Exact shared framework safety keys emitted after reviewed SQL initialization. / 经审阅 SQL 初始化后发出的精确框架安全键。
     * @return immutable generated keys / 不可变生成键 */
    private static List<String> frameworkKeys() {
        return List.of("SPRING_SQL_INIT_MODE", "SPRING_FLYWAY_ENABLED", "SPRING_LIQUIBASE_ENABLED",
            "SPRING_JPA_HIBERNATE_DDL_AUTO", "SPRING_JPA_GENERATE_DDL");
    }
}
