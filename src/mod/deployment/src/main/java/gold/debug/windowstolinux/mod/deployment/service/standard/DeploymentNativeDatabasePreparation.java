package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretRepository;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseResults;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseWire;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.*;

/** Prepares actual native DB targets under the original publication task and exact helper receipts. / 在原发布任务及精确 helper 回执下准备真实原生数据库目标。 */
public final class DeploymentNativeDatabasePreparation {
    /** Original Mod capabilities. / 原 Mod 能力。 */
    private final ModContext context;

    /** Immutable business secret rows. / 不可变业务秘密行。 */
    private final DeploymentSecretRepository revisions;

    /** Locally validated component inputs before any native side effect. / 原生副作用前已在本地验证的组件输入。
     * @param componentId exact discovered component / 精确发现组件
     * @param applicationId final managed unit / 最终受管单元
     * @param manifestSha256 frozen whole-source manifest / 冻结整份源码清单
     * @param root actual frozen component root / 实际冻结组件根
     * @param assessment completed source declarations / 已补齐源码声明
     * @param sourceDigest actual component inspection digest / 实际组件检查摘要
     * @param sql validated per-database SQL identities / 已校验的每库 SQL 身份 */
    public record Local(String componentId, String applicationId, String manifestSha256, Path root,
            DatabaseProjectInspector.Assessment assessment, String sourceDigest,
            Map<String, DeploymentNativeSqlSource.Plan> sql) {
        /** Copies all non-secret local evidence. / 复制全部非秘密本机依据。
         * @param componentId discovered component / 发现组件
         * @param applicationId final managed unit / 最终受管单元
         * @param manifestSha256 frozen manifest / 冻结清单
         * @param root frozen root / 冻结根
         * @param assessment completed declarations / 已补齐声明
         * @param sourceDigest component digest / 组件摘要
         * @param sql SQL identity map / SQL 身份映射 */
        public Local {
            Objects.requireNonNull(componentId);
            Objects.requireNonNull(applicationId);
            if (!manifestSha256.matches("[0-9a-f]{64}") || !sourceDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Native database source identity is incomplete");
            root = root.toAbsolutePath().normalize();
            Objects.requireNonNull(assessment);
            sql = Map.copyOf(sql);
        }

        /** Lists only actual native requirements. / 仅列出实际原生数据库要求。
         * @return immutable native declarations / 不可变原生声明 */
        public List<DatabaseRequirement> nativeDatabases() {
            return assessment.databases().stream()
                    .filter(value -> value.engine() != DeploymentDatabaseEngineType.SQLITE).toList();
        }
    }

    /** Verified native database output to merge into an ordinary reviewed publication. / 要合入普通经审阅发布的已核实原生数据库输出。
     * @param configuration generated non-secret runtime configuration / 生成的非秘密运行配置
     * @param bindings exact managed targets / 精确受管目标
     * @param secrets exact immutable password references / 精确不可变密码引用
     * @param schemaReview independent source-bound approval / 独立源码绑定批准 */
    public record Prepared(List<DeploymentConfigurationEntry> configuration, List<DeploymentDatabaseBinding> bindings,
            List<DeploymentSecretReference> secrets,
            Optional<DatabaseSchemaReview> schemaReview) implements DeploymentPreparedDatabase {
        /** Copies each published fact. / 复制每项发布事实。
         * @param configuration generated configuration / 生成配置
         * @param bindings managed bindings / 受管绑定
         * @param secrets immutable revisions / 不可变修订
         * @param schemaReview independent approval / 独立批准 */
        public Prepared {
            configuration = List.copyOf(configuration);
            bindings = List.copyOf(bindings);
            secrets = List.copyOf(secrets);
            schemaReview = Objects.requireNonNull(schemaReview);
        }
    }

    /** One actual verified target and source review effect. / 一个真实已核实目标及源码审阅效果。
     * @param configuration generated configuration / 生成配置
     * @param binding exact target binding / 精确目标绑定
     * @param reference immutable revision / 不可变修订
     * @param initialized whether reviewed SQL completed / 经审阅 SQL 是否完成
     * @param newInitialized whether a new owned target was initialized / 新归属目标是否完成初始化
     * @param existingApproved whether existing schema was independently approved / 已有结构是否独立批准 */
    private record One(List<DeploymentConfigurationEntry> configuration, DeploymentDatabaseBinding binding,
            DeploymentSecretReference reference, boolean initialized, boolean newInitialized,
            boolean existingApproved) {
    }

    /** Preserves original review classification without fabricating a remote result. / 保留原审阅分类，不虚构远端结果。 */
    private static final class NativeDatabasePreparationException extends ApiException {
        /** Original classified result. / 原分类结果。 */
        private final OperationResult<?> result;

        /** Carries one exact classified refusal. / 携带一项精确分类拒绝。
         * @param result original refusal / 原拒绝 */
        private NativeDatabasePreparationException(OperationResult<?> result) {
            super(result.problem().orElseGet(() -> new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_NATIVE_DB_CANCELLED",
                    "Native database review was cancelled", Map.of(), Optional.empty())));
            this.result = result;
        }
    }

    /** Binds actual task-owned capabilities. / 绑定真实任务所属能力。
     * @param context active Mod context / 活动 Mod 上下文 */
    public DeploymentNativeDatabasePreparation(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.revisions = new DeploymentSecretRepository(context.db());
    }

    /** Validates every native SQL file and actual component digest locally before any native effect. / 任何原生效果前在本地校验全部 SQL 文件及实际组件摘要。
     * @param componentId discovered component / 发现组件
     * @param applicationId final managed unit / 最终受管单元
     * @param manifestSha256 frozen manifest / 冻结清单
     * @param root actual frozen root / 实际冻结根
     * @param assessment completed declarations / 已补齐声明
     * @param task original task / 原任务
     * @return source-backed local plan / 源码绑定本机计划
     * @throws java.io.IOException if a SQL source is unsafe / SQL 来源不安全时 */
    public static Local local(String componentId, String applicationId, String manifestSha256, Path root,
            DatabaseProjectInspector.Assessment assessment, TaskContext task) throws java.io.IOException {
        var sql = new HashMap<String, DeploymentNativeSqlSource.Plan>();
        for (var database : assessment.databases())
            if (database.engine() != DeploymentDatabaseEngineType.SQLITE)
                sql.put(database.id(), DeploymentNativeSqlSource.plan(root, database.initializationFiles()));
        var rejected = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason>();
        var inspected = new BoundedSourceInspector().inspect(root, rejected, task.cancellation());
        if (task.cancellation().requested())
            throw new CancellationException("Native database source inspection was cancelled");
        if (!rejected.isEmpty())
            throw new IllegalArgumentException("Native database source inspection was rejected");
        return new Local(componentId, applicationId, manifestSha256, root, assessment,
                SourceMutationPolicy.inspectionDigest(inspected), sql);
    }

    /** Prepares one component's actual native targets after the whole graph passed local validation. / 整图本机校验通过后准备一组件的真实原生目标。
     * @param server exact admitted server / 精确已准入服务器
     * @param plan prior durable whole-database index / 先前持久全数据库索引
     * @param local source-bound local inputs / 源码绑定本机输入
     * @param review original owner-view decisions / 原所属视图决定
     * @param task original publication task / 原发布任务
     * @return fully verified native preparation or classified refusal / 完整已核实准备或分类拒绝
     * @throws Exception if original local or platform access fails / 原本机或平台访问失败时 */
    public OperationResult<Prepared> prepare(ServerRef server, DeploymentDatabasePlanJournal plan, Local local,
            DeploymentNativeDatabaseReview review, TaskContext task) throws Exception {
        if (!plan.facts().serverId().equals(server.id())
                || !plan.facts().manifestSha256().equals(local.manifestSha256()))
            throw new IllegalArgumentException("Native database plan differs from frozen source or server");
        var entries = new ArrayList<DeploymentConfigurationEntry>();
        var bindings = new ArrayList<DeploymentDatabaseBinding>();
        var secrets = new ArrayList<DeploymentSecretReference>();
        var initialized = new HashSet<String>();
        boolean newInitialized = false;
        boolean existingApproved = false;
        try (var session = context.linux().connect(server, task)) {
            var channel = new DeploymentNativeDatabaseChannel(session, context.interaction(), server, task);
            for (var database : local.nativeDatabases()) {
                var entry = new DeploymentDatabasePlanJournal.Entry(local.componentId(), database.id());
                if (!plan.facts().keys().contains(entry.key()))
                    throw new IllegalArgumentException("Native database was not in the original plan");
                One prepared = prepareOne(server, channel, plan, entry, local, database, review, task);
                entries.addAll(prepared.configuration());
                bindings.add(prepared.binding());
                secrets.add(prepared.reference());
                if (prepared.initialized())
                    initialized.addAll(database.initializationFiles());
                newInitialized |= prepared.newInitialized();
                existingApproved |= prepared.existingApproved();
            }
        } catch (NativeDatabasePreparationException refused) {
            return new OperationResult<>(refused.result.outcome(), Optional.empty(), refused.result.problem());
        }
        boolean spring = local.nativeDatabases().stream().filter(value -> !value.initializationFiles().isEmpty())
                .allMatch(DatabaseRequirement::springDatasource);
        if (local.assessment().schemaReviewRequired() && spring && !bindings.isEmpty())
            entries.addAll(disabledSpringInitialization());
        Optional<DatabaseSchemaReview> schema = local.assessment().schemaReviewRequired() && !bindings.isEmpty()
                ? Optional.of(new DatabaseSchemaReview(local.sourceDigest(),
                        bindings.stream().map(DeploymentDatabaseBinding::databaseId)
                                .collect(java.util.stream.Collectors.toSet()),
                        initialized, newInitialized, existingApproved, spring, false))
                : Optional.empty();
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new Prepared(entries, bindings, secrets, schema)),
                Optional.empty());
    }

    /** Prepares one already running, precisely versioned instance and target. / 准备一个已运行且精确版本匹配的实例及目标。
     * @param server admitted server / 已准入服务器
     * @param channel reviewed helper channel / 已审阅 helper 通道
     * @param plan durable whole-database index / 持久全数据库索引
     * @param entry exact planned child / 精确计划子项
     * @param local frozen local facts / 冻结本机事实
     * @param database completed requirement / 已补齐要求
     * @param review owner-view decisions / 所属视图决定
     * @param task original task / 原任务
     * @return verified target and runtime facts / 已核实目标与运行事实
     * @throws Exception if original access fails / 原访问失败时 */
    private One prepareOne(ServerRef server, DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, Local local,
            DatabaseRequirement database, DeploymentNativeDatabaseReview review, TaskContext task) throws Exception {
        Instance instance = resolveInstance(server, channel, plan, entry, local, database, review, task);
        instance = startInstance(server, channel, plan, entry, instance, database, review, task);
        var targetFields = targetFields(instance, local.applicationId(), database);
        byte[] admin = new byte[0];
        byte[] application = new byte[0];
        try {
            Target target;
            while (true) {
                var observed = channel.target(targetFields, admin);
                if (observed.outcome() == Outcome.SUCCEEDED) {
                    target = require(observed);
                    break;
                }
                if (!failureCode(observed).equals("AUTH_REQUIRED"))
                    throw new NativeDatabasePreparationException(observed);
                Arrays.fill(admin, (byte) 0);
                admin = require(review.administratorPassword(server, instance, task));
            }
            if (target.instance().engine() != instance.engine() || !target.instance().id().equals(instance.id())
                    || !target.applicationId().equals(local.applicationId())
                    || !target.database().equals(database.database()) || !target.username().equals(database.username()))
                throw refusal("DEPLOYMENT_NATIVE_DB_TARGET_CHANGED");
            boolean createdNow = !target.exists();
            String identifier = secretIdentifier(server, local.applicationId(), database);
            var latest = revisions.latestRevision(task, identifier, "deployment-database");
            DeploymentSecretReference reference;
            if (createdNow && latest.isEmpty()) {
                application = generatedPassword();
                reference = new DeploymentSecretReference(identifier, 1);
                require(new DeploymentSecretProvisionFlow(context).saveBorrowedWithinTask(task, server,
                        "deployment-secret-revision." + entry.key(), reference, "deployment-database",
                        Instant.ofEpochMilli(System.currentTimeMillis()), application));
                target = preparedTarget(channel, plan, entry, targetFields, reference, application, admin);
            } else if (latest.isPresent()) {
                var stored = latest.orElseThrow();
                reference = stored.reference();
                var saved = new SecretRef(stored.credentialId(), stored.purpose().orElseThrow());
                var borrowedAdmin = admin;
                var storedReference = reference;
                var observed = context.secrets().use(saved, "deployment-database", task, value -> prepareTarget(channel,
                        plan, entry, targetFields, storedReference, value, borrowedAdmin));
                if (failureCode(observed).equals("AUTH_REQUIRED") && !createdNow) {
                    reference = new DeploymentSecretReference(identifier, reference.revision() + 1);
                    while (true) {
                        Arrays.fill(application, (byte) 0);
                        application = require(review.applicationPassword(server, database, task));
                        observed = prepareTarget(channel, plan, entry, targetFields, reference, application, admin);
                        if (!failureCode(observed).equals("AUTH_REQUIRED"))
                            break;
                    }
                    target = DeploymentNativeDatabaseResults.target(require(observed), "");
                    require(new DeploymentSecretProvisionFlow(context).saveBorrowedWithinTask(task, server,
                            "deployment-secret-revision." + entry.key(), reference, "deployment-database",
                            Instant.ofEpochMilli(System.currentTimeMillis()), application));
                } else
                    target = DeploymentNativeDatabaseResults.target(require(observed), "");
            } else {
                reference = new DeploymentSecretReference(identifier, 1);
                OperationResult<Map<String, String>> observed;
                while (true) {
                    Arrays.fill(application, (byte) 0);
                    application = require(review.applicationPassword(server, database, task));
                    observed = prepareTarget(channel, plan, entry, targetFields, reference, application, admin);
                    if (!failureCode(observed).equals("AUTH_REQUIRED"))
                        break;
                }
                target = DeploymentNativeDatabaseResults.target(require(observed), "");
                require(new DeploymentSecretProvisionFlow(context).saveBorrowedWithinTask(task, server,
                        "deployment-secret-revision." + entry.key(), reference, "deployment-database",
                        Instant.ofEpochMilli(System.currentTimeMillis()), application));
            }
            if (!target.exists() || !target.instance().id().equals(instance.id())
                    || target.instance().engine() != instance.engine()
                    || !target.applicationId().equals(local.applicationId())
                    || !target.database().equals(database.database()) || !target.username().equals(database.username()))
                throw refusal("DEPLOYMENT_NATIVE_DB_TARGET_CHANGED");
            boolean initialized = false;
            boolean existingApproved = false;
            if (!database.initializationFiles().isEmpty()) {
                byte[] sql = local.sql().get(database.id()).load();
                try {
                    var result = initialize(channel, plan, entry, targetFields, target, local.sourceDigest(), sql,
                            application, reference, false, task);
                    if (failureCode(result).equals("STATE_CHANGED") && !createdNow) {
                        confirmed(review.existingSchema(server, local.componentId(), database, local.sourceDigest(),
                                task));
                        existingApproved = true;
                        result = initialize(channel, plan, entry, targetFields, target, local.sourceDigest(), sql,
                                application, reference, true, task);
                    }
                    target = DeploymentNativeDatabaseResults.target(require(result), "");
                    if (target.instance().engine() != instance.engine() || !target.instance().id().equals(instance.id())
                            || !target.applicationId().equals(local.applicationId())
                            || !target.database().equals(database.database())
                            || !target.username().equals(database.username()))
                        throw refusal("DEPLOYMENT_NATIVE_DB_TARGET_CHANGED");
                    if (!"COMPLETE".equals(target.initialization())
                            || !local.sourceDigest().equals(target.initializedSourceSha256()))
                        throw refusal("DEPLOYMENT_NATIVE_DB_INITIALIZATION_UNCONFIRMED");
                    initialized = true;
                    existingApproved |= !createdNow;
                } finally {
                    Arrays.fill(sql, (byte) 0);
                }
            }
            var binding = new DeploymentDatabaseBinding(database.id(),
                    new DeploymentDatabaseConnection.Server(database.engine(), "127.0.0.1", instance.port(),
                            database.database(), database.username(), reference, false));
            return new One(configuration(database, instance), binding, reference, initialized,
                    initialized && createdNow && !target.ownershipToken().isEmpty(), existingApproved);
        } finally {
            Arrays.fill(admin, (byte) 0);
            Arrays.fill(application, (byte) 0);
        }
    }

    /** Starts or reconciles the selected exact instance before target work. / 在目标操作前启动或核对所选精确实例。
     * @param server admitted server / 已准入服务器
     * @param channel original helper channel / 原 helper 通道
     * @param plan durable graph index / 持久整图索引
     * @param entry exact database entry / 精确数据库子项
     * @param instance observed exact instance / 已观察精确实例
     * @param database source requirement / 来源要求
     * @param review owner decisions / 所属者决定
     * @param task original task / 原任务
     * @return verified running instance / 已核实运行实例
     * @throws Exception if original start or restoration cannot complete / 原启动或恢复无法完成时 */
    private Instance startInstance(ServerRef server, DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, Instance instance,
            DatabaseRequirement database, DeploymentNativeDatabaseReview review, TaskContext task) throws Exception {
        var start = channel.perform(
                context.tasks(), plan, entry, "start", Map.of("engine", database.engine().name(), "instanceId",
                        instance.id(), "fingerprint", instance.fingerprint()),
                Map.of(), entry.componentId() + "/" + entry.databaseId());
        if (failureCode(start).equals("MANUAL_RESTORE_REQUIRED"))
            instance = awaitRestoration(server, channel, plan, entry, database, review, task);
        else {
            var started = DeploymentNativeDatabaseResults.instance(require(start), "instance.");
            if (started.engine() != instance.engine() || !started.id().equals(instance.id())
                    || !started.fingerprint().equals(instance.fingerprint()) || !started.running()
                    || !new DatabaseVersionRequirement(database.version()).accepts(started.version()))
                throw refusal("DEPLOYMENT_NATIVE_DB_INSTANCE_CHANGED");
            instance = started;
        }
        return instance;
    }

    /** Selects, installs or replaces an exact native instance before target work. / 在目标操作前选择、安装或替换精确原生实例。
     * @param server admitted server / 已准入服务器
     * @param channel original helper channel / 原 helper 通道
     * @param plan durable graph index / 持久整图索引
     * @param entry exact database entry / 精确数据库子项
     * @param local frozen component / 冻结组件
     * @param database source requirement / 来源要求
     * @param review owner decisions / 所属者决定
     * @param task original task / 原任务
     * @return selected verified instance / 已选择核实实例
     * @throws Exception if the review or original operation cannot complete / 审阅或原操作无法完成时 */
    private Instance resolveInstance(ServerRef server, DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, Local local,
            DatabaseRequirement database, DeploymentNativeDatabaseReview review, TaskContext task) throws Exception {
        Inventory inventory = require(channel.inspect(database.engine()));
        if (!inventory.conflicts().isEmpty())
            throw refusal("DEPLOYMENT_NATIVE_DB_INVENTORY_CONFLICT");
        Instance previous = inventory.instances().isEmpty()
                ? null
                : inventory.instances().size() == 1
                        ? inventory.instances().getFirst()
                        : selected(inventory,
                                require(review.choose(server, local.componentId(), database, inventory, task)));
        if (previous != null && new DatabaseVersionRequirement(database.version()).accepts(previous.version()))
            return previous;
        var candidate = inventory.candidate().orElseThrow(() -> refusal("DEPLOYMENT_NATIVE_DB_PACKAGE_UNAVAILABLE"));
        if (!new DatabaseVersionRequirement(database.version()).accepts(candidate.engineVersion()))
            throw refusal("DEPLOYMENT_NATIVE_DB_VERSION_MISMATCH");
        if (previous == null)
            confirmed(review.install(server, database, candidate, task));
        else {
            if (inventory.instances().size() != 1)
                throw refusal("DEPLOYMENT_NATIVE_DB_REPLACEMENT_AMBIGUOUS");
            confirmed(review.replacement(server, previous, candidate, task));
        }
        var fields = new HashMap<String, String>();
        fields.put("engine", database.engine().name());
        fields.put("package", candidate.name());
        fields.put("packageVersion", candidate.packageVersion());
        fields.put("engineVersion", candidate.engineVersion());
        if (previous != null) {
            fields.put("instanceId", previous.id());
            fields.put("fingerprint", previous.fingerprint());
            fields.put("replacementApproved", "true");
            fields.put("approvedServer", server.id());
        }
        var installed = DeploymentNativeDatabaseResults.inventory(require(channel.perform(context.tasks(), plan, entry,
                "install", fields, Map.of(), entry.componentId() + "/" + entry.databaseId())), "");
        if (installed.engine() != database.engine() || !installed.conflicts().isEmpty()
                || installed.instances().size() != 1)
            throw refusal("DEPLOYMENT_NATIVE_DB_INSTALL_UNCONFIRMED");
        Instance selected = installed.instances().getFirst();
        if (!new DatabaseVersionRequirement(database.version()).accepts(selected.version()))
            throw refusal("DEPLOYMENT_NATIVE_DB_VERSION_MISMATCH");
        if (previous != null)
            return awaitRestoration(server, channel, plan, entry, database, review, task);
        return selected;
    }

    /** Confirms manual restoration and retries only a known authentication refusal. / 确认人工恢复，且仅对已知认证拒绝重试。
     * @param server admitted server / 已准入服务器
     * @param channel original helper channel / 原 helper 通道
     * @param plan durable graph index / 持久整图索引
     * @param entry exact database child / 精确数据库子项
     * @param database source requirement / 来源要求
     * @param review owner decisions / 所属者决定
     * @param task original task / 原任务
     * @return exact restored instance / 精确已恢复实例
     * @throws Exception if review or original operation cannot complete / 审阅或原操作无法完成时 */
    private Instance awaitRestoration(ServerRef server, DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, DatabaseRequirement database,
            DeploymentNativeDatabaseReview review, TaskContext task) throws Exception {
        byte[] admin = new byte[0];
        try {
            var inventory = require(channel.inspect(database.engine()));
            if (!inventory.conflicts().isEmpty() || inventory.instances().isEmpty())
                throw refusal("DEPLOYMENT_NATIVE_DB_RESTORE_UNCONFIRMED");
            Instance selected = inventory.instances().size() == 1
                    ? inventory.instances().getFirst()
                    : selected(inventory,
                            require(review.choose(server, entry.componentId(), database, inventory, task)));
            if (!new DatabaseVersionRequirement(database.version()).accepts(selected.version()))
                throw refusal("DEPLOYMENT_NATIVE_DB_RESTORE_UNCONFIRMED");
            confirmed(review.restored(server, selected, task));
            var fresh = require(channel.inspect(database.engine()));
            if (!fresh.conflicts().isEmpty() || fresh.instances().size() != inventory.instances().size())
                throw refusal("DEPLOYMENT_NATIVE_DB_RESTORE_CHANGED");
            Instance restoredInstance = fresh.instances().stream()
                    .filter(value -> value.engine() == selected.engine() && value.id().equals(selected.id()))
                    .findFirst().orElseThrow(() -> refusal("DEPLOYMENT_NATIVE_DB_RESTORE_CHANGED"));
            if (!restoredInstance.fingerprint().equals(selected.fingerprint())
                    || !restoredInstance.version().equals(selected.version()) || !restoredInstance.running())
                throw refusal("DEPLOYMENT_NATIVE_DB_RESTORE_CHANGED");
            while (true) {
                var observed = channel.perform(context.tasks(), plan, entry, "resume",
                        Map.of("engine", restoredInstance.engine().name(), "instanceId", restoredInstance.id(),
                                "fingerprint", restoredInstance.fingerprint()),
                        admin.length == 0 ? Map.of() : Map.of("adminPassword", admin),
                        entry.componentId() + "/" + entry.databaseId());
                if (failureCode(observed).equals("AUTH_REQUIRED")) {
                    Arrays.fill(admin, (byte) 0);
                    admin = require(review.administratorPassword(server, restoredInstance, task));
                    continue;
                }
                var restored = DeploymentNativeDatabaseResults.instance(require(observed), "instance.");
                if (restored.engine() != database.engine() || !restored.id().equals(restoredInstance.id())
                        || !restored.fingerprint().equals(restoredInstance.fingerprint()) || !restored.running()
                        || !new DatabaseVersionRequirement(database.version()).accepts(restored.version()))
                    throw refusal("DEPLOYMENT_NATIVE_DB_RESTORE_UNCONFIRMED");
                return restored;
            }
        } finally {
            Arrays.fill(admin, (byte) 0);
        }
    }

    /** Finds only a user-selected actual instance. / 仅查找用户选中的实际实例。
     * @param inventory actual inventory / 实际库存
     * @param id selected ID / 选中 ID
     * @return exact instance / 精确实例
     * @throws NativeDatabasePreparationException if identity is absent or ambiguous / 身份不存在或有歧义时 */
    private static Instance selected(Inventory inventory, String id) throws NativeDatabasePreparationException {
        var matches = inventory.instances().stream().filter(value -> value.id().equals(id)).toList();
        if (matches.size() != 1)
            throw refusal("DEPLOYMENT_NATIVE_DB_INSTANCE_CHANGED");
        return matches.getFirst();
    }

    /** Builds exact non-secret helper target fields. / 构造精确非秘密 helper 目标字段。
     * @param instance actual selected instance / 实际选中实例
     * @param applicationId managed unit / 受管单元
     * @param database completed requirement / 已补齐要求
     * @return immutable fields / 不可变字段 */
    private static Map<String, String> targetFields(Instance instance, String applicationId,
            DatabaseRequirement database) {
        return Map.of("engine", instance.engine().name(), "instanceId", instance.id(), "fingerprint",
                instance.fingerprint(), "applicationId", applicationId, "database", database.database(), "username",
                database.username());
    }

    /** Prepares a target from caller-owned password bytes. / 用调用方所属密码字节准备目标。
     * @param channel reviewed helper channel / 已审阅 helper 通道
     * @param plan original index / 原索引
     * @param entry original child / 原子项
     * @param fields target fields / 目标字段
     * @param reference exact immutable revision / 精确不可变修订
     * @param password borrowed application bytes / 借用应用字节
     * @param administrator borrowed administrator bytes / 借用管理员字节
     * @return observed helper result / 已观察 helper 结果
     * @throws ApiException if original platform access fails / 原平台访问失败时 */
    private OperationResult<Map<String, String>> prepareTarget(DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, Map<String, String> fields,
            DeploymentSecretReference reference, byte[] password, byte[] administrator) throws ApiException {
        validateApplicationPassword(password);
        var actual = new HashMap<>(fields);
        actual.put("secretIdentifier", reference.identifier());
        actual.put("secretRevision", Long.toString(reference.revision()));
        return channel.perform(context.tasks(), plan, entry, "prepare", actual,
                Map.of("applicationPassword", password, "adminPassword", administrator),
                entry.componentId() + "/" + entry.databaseId());
    }

    /** Requires a complete prepared target. / 要求完整已准备目标。
     * @param channel reviewed helper channel / 已审阅 helper 通道
     * @param plan original plan / 原计划
     * @param entry original child / 原子项
     * @param fields target fields / 目标字段
     * @param reference immutable revision / 不可变修订
     * @param password borrowed app password / 借用应用密码
     * @param administrator borrowed admin password / 借用管理员密码
     * @return verified target / 已核实目标
     * @throws Exception if result is unavailable / 结果不可用时 */
    private Target preparedTarget(DeploymentNativeDatabaseChannel channel, DeploymentDatabasePlanJournal plan,
            DeploymentDatabasePlanJournal.Entry entry, Map<String, String> fields, DeploymentSecretReference reference,
            byte[] password, byte[] administrator) throws Exception {
        return DeploymentNativeDatabaseResults
                .target(require(prepareTarget(channel, plan, entry, fields, reference, password, administrator)), "");
    }

    /** Initializes only the reviewed frozen SQL using transient application bytes or scoped platform use. / 仅用短期应用字节或平台限定使用初始化已审阅冻结 SQL。
     * @param channel reviewed helper channel / 已审阅 helper 通道
     * @param plan original index / 原索引
     * @param entry original child / 原子项
     * @param fields target fields / 目标字段
     * @param target confirmed prepared target / 已确认准备目标
     * @param sourceDigest component inspection digest / 组件检查摘要
     * @param sql borrowed reviewed SQL bytes / 借用已审阅 SQL 字节
     * @param password borrowed transient password, empty for stored reference / 借用短期密码；已存引用时为空
     * @param reference exact immutable revision / 精确不可变修订
     * @param approved separate existing-schema approval / 单独已有结构批准
     * @param task original task / 原任务
     * @return observed helper result / 已观察 helper 结果
     * @throws Exception if original secret or remote access fails / 原秘密或远端访问失败时 */
    private OperationResult<Map<String, String>> initialize(DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, Map<String, String> fields,
            Target target, String sourceDigest, byte[] sql, byte[] password, DeploymentSecretReference reference,
            boolean approved, TaskContext task) throws Exception {
        if (password.length > 0)
            return initializeWithPassword(channel, plan, entry, fields, target, sourceDigest, sql, password, approved);
        var saved = revisions.findRevision(task, reference).orElseThrow();
        return context.secrets().use(new SecretRef(saved.credentialId(), saved.purpose().orElseThrow()),
                "deployment-database", task, value -> initializeWithPassword(channel, plan, entry, fields, target,
                        sourceDigest, sql, value, approved));
    }

    /** Sends one exact SQL attempt after its source and approval fields were fixed. / 来源及批准字段固定后发送一次精确 SQL 尝试。
     * @param channel reviewed helper channel / 已审阅 helper 通道
     * @param plan original index / 原索引
     * @param entry original child / 原子项
     * @param fields target fields / 目标字段
     * @param target confirmed prepared target / 已确认准备目标
     * @param sourceDigest component inspection digest / 组件检查摘要
     * @param sql borrowed SQL / 借用 SQL
     * @param password borrowed application password / 借用应用密码
     * @param approved separate existing-schema approval / 单独已有结构批准
     * @return observed non-secret target / 已观察非秘密目标
     * @throws ApiException if original transport fails / 原传输失败时 */
    private OperationResult<Map<String, String>> initializeWithPassword(DeploymentNativeDatabaseChannel channel,
            DeploymentDatabasePlanJournal plan, DeploymentDatabasePlanJournal.Entry entry, Map<String, String> fields,
            Target target, String sourceDigest, byte[] sql, byte[] password, boolean approved) throws ApiException {
        validateApplicationPassword(password);
        var actual = new HashMap<>(fields);
        actual.put("ownershipToken", target.ownershipToken());
        actual.put("sourceSha256", sourceDigest);
        actual.put("existingApproved", Boolean.toString(approved));
        return channel.perform(context.tasks(), plan, entry, "initialize", actual,
                Map.of("applicationPassword", password, "sql", sql), entry.componentId() + "/" + entry.databaseId());
    }

    /** Reuses the previous stable business identifier derivation without encoding a password. / 复用旧稳定业务标识派生，不编码密码。
     * @param server exact server / 精确服务器
     * @param applicationId final managed unit / 最终受管单元
     * @param database completed requirement / 已补齐要求
     * @return stable secret identifier / 稳定秘密标识 */
    private static String secretIdentifier(ServerRef server, String applicationId, DatabaseRequirement database) {
        String source = server.id() + "/" + applicationId + "/" + database.id();
        String digest = DeploymentNativeDatabaseWire.digest(source.getBytes(StandardCharsets.UTF_8));
        return "db-" + digest.substring(0, 12) + ".env."
                + database.passwordEnvironment().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Creates a strong caller-owned ASCII password for a new owned target. / 为新归属目标创建调用方持有的强 ASCII 密码。
     * @return newly owned bytes / 新所属字节 */
    private static byte[] generatedPassword() {
        byte[] random = new byte[32];
        try {
            new SecureRandom().nextBytes(random);
            return java.util.Base64.getUrlEncoder().withoutPadding().encode(random);
        } finally {
            Arrays.fill(random, (byte) 0);
        }
    }

    /** Enforces the helper's decoded character contract without retaining a password String. / 不保留密码 String 地执行 helper 解码字符契约。
     * @param borrowed original borrowed UTF-8 bytes / 原借用 UTF-8 字节 */
    private static void validateApplicationPassword(byte[] borrowed) {
        if (borrowed == null || borrowed.length == 0 || borrowed.length > 16_384)
            throw new IllegalArgumentException("Native database application password is invalid");
        var characters = CharBuffer.allocate(4_097);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var source = ByteBuffer.wrap(borrowed);
            var decoded = decoder.decode(source, characters, true);
            if (decoded.isError() || decoded.isOverflow())
                throw new IllegalArgumentException("Native database application password is invalid");
            var flushed = decoder.flush(characters);
            if (flushed.isError() || flushed.isOverflow() || source.hasRemaining() || characters.position() == 0
                    || characters.position() > 4_096)
                throw new IllegalArgumentException("Native database application password is invalid");
            for (int i = 0; i < characters.position(); i++)
                if (characters.get(i) == '\0' || characters.get(i) == '\r' || characters.get(i) == '\n')
                    throw new IllegalArgumentException("Native database application password is invalid");
        } finally {
            Arrays.fill(characters.array(), '\0');
        }
    }

    /** Extracts only a bounded helper failure code, never a diagnostic containing input bytes. / 仅提取有界 helper 失败码，不取含输入字节的诊断。
     * @param result observed helper result / 已观察 helper 结果
     * @return stable helper code or empty / 稳定 helper 码或空值 */
    private static String failureCode(OperationResult<?> result) {
        return result.problem().map(
                problem -> problem.context().getOrDefault("failureCode", problem.context().getOrDefault("reason", "")))
                .orElse("");
    }

    /** Generates exact non-secret runtime fields for a verified local endpoint. / 为已核实本机端点生成精确非秘密运行字段。
     * @param database reviewed requirement / 经审阅要求
     * @param instance selected actual instance / 选中实际实例
     * @return typed runtime entries / 类型化运行条目 */
    private static List<DeploymentConfigurationEntry> configuration(DatabaseRequirement database, Instance instance) {
        var result = new ArrayList<DeploymentConfigurationEntry>();
        String prefix = database.environmentPrefix();
        if (database.springDatasource())
            result.add(text(prefix + "_URL", "jdbc:" + database.engine().name().toLowerCase(Locale.ROOT)
                    + "://127.0.0.1:" + instance.port() + "/" + database.database()));
        else {
            result.add(text(prefix + "_HOST", "127.0.0.1"));
            result.add(number(prefix + "_PORT", instance.port()));
            result.add(database.engine() == DeploymentDatabaseEngineType.REDIS
                    ? number(prefix + "_DATABASE", 0)
                    : text(prefix + "_DATABASE", database.database()));
        }
        result.add(text(prefix + "_USERNAME", database.username()));
        if (database.engine() == DeploymentDatabaseEngineType.REDIS)
            result.add(text(prefix + "_KEY_PREFIX", database.database() + ":"));
        return List.copyOf(result);
    }

    /** Reports a known invalid native target without suppressing earlier effects. / 报告已知无效原生目标但不掩盖先前效果。
     * @param event safe stable event / 安全稳定事件
     * @return classified refusal / 已分类拒绝 */
    private static NativeDatabasePreparationException refusal(String event) {
        return new NativeDatabasePreparationException(new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Native database preparation needs review",
                        Map.of(), Optional.empty()))));
    }

    /** Requires a successful reviewed or helper value while preserving its original classification. / 要求审阅或 helper 成功值且保留原分类。
     * @param result original result / 原结果
     * @return exact observed value / 精确已观察值
     * @param <T> value type / 值类型
     * @throws NativeDatabasePreparationException if original result did not succeed / 原结果未成功时 */
    private static <T> T require(OperationResult<T> result) throws NativeDatabasePreparationException {
        if (result.outcome() == Outcome.SUCCEEDED && result.value().isEmpty())
            throw new NativeDatabasePreparationException(new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_NATIVE_DB_RESULT_MISSING",
                            "Native database result is missing", Map.of(), Optional.empty()))));
        if (result.outcome() != Outcome.SUCCEEDED)
            throw new NativeDatabasePreparationException(result);
        return result.value().orElseThrow();
    }

    /** Requires an explicit affirmative business decision. / 要求显式肯定业务决定。
     * @param result original consent / 原同意
     * @throws NativeDatabasePreparationException if declined or unavailable / 拒绝或不可用时 */
    private static void confirmed(OperationResult<Boolean> result) throws NativeDatabasePreparationException {
        if (!require(result))
            throw new NativeDatabasePreparationException(new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_NATIVE_DB_DECLINED",
                            "Native database change was declined", Map.of(), Optional.empty()))));
    }

    /** Renders one non-secret text configuration entry. / 呈现一项非秘密文本配置。
     * @param key runtime key / 运行键
     * @param value non-secret value / 非秘密值
     * @return typed entry / 类型化条目 */
    private static DeploymentConfigurationEntry text(String key, String value) {
        return new DeploymentConfigurationEntry(key, DeploymentConfigurationScopeType.RUNTIME,
                new DeploymentConfigurationValue.Text(value));
    }

    /** Renders one non-secret numeric configuration entry. / 呈现一项非秘密数字配置。
     * @param key runtime key / 运行键
     * @param value non-secret value / 非秘密值
     * @return typed entry / 类型化条目 */
    private static DeploymentConfigurationEntry number(String key, long value) {
        return new DeploymentConfigurationEntry(key, DeploymentConfigurationScopeType.RUNTIME,
                new DeploymentConfigurationValue.Number(value));
    }

    /** Disables framework auto-schema changes after reviewed native SQL initialization. / 已审阅原生 SQL 初始化后关闭框架自动结构变更。
     * @return immutable safety flags / 不可变安全标记 */
    private static List<DeploymentConfigurationEntry> disabledSpringInitialization() {
        return List.of(text("SPRING_SQL_INIT_MODE", "never"),
                new DeploymentConfigurationEntry("SPRING_FLYWAY_ENABLED", DeploymentConfigurationScopeType.RUNTIME,
                        new DeploymentConfigurationValue.Flag(false)),
                new DeploymentConfigurationEntry("SPRING_LIQUIBASE_ENABLED", DeploymentConfigurationScopeType.RUNTIME,
                        new DeploymentConfigurationValue.Flag(false)),
                text("SPRING_JPA_HIBERNATE_DDL_AUTO", "none"),
                new DeploymentConfigurationEntry("SPRING_JPA_GENERATE_DDL", DeploymentConfigurationScopeType.RUNTIME,
                        new DeploymentConfigurationValue.Flag(false)));
    }
}
