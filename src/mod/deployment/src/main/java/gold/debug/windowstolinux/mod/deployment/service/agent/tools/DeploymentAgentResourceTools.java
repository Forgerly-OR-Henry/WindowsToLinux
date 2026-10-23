package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;

/** Source-bound DB and secret capabilities; no plaintext is returned to the model. / 来源绑定 DB 和秘密能力；不向模型返回明文。 */
final class DeploymentAgentResourceTools {
    /** Settled original database preparation / 已结清原数据库准备。
     * @param source current source identity / 当前来源身份
     * @param root Component source root / 组件源码根
     * @param declarationRoot Explicit declaration directory / 明确声明目录
     * @param databaseIds Selected actual database identities / 选中的实际数据库身份
     * @param effectIdentity Database declarations and SQL identity / 数据库声明及SQL身份
     * @param configuration Nonsecret configuration / 非秘密配置
     * @param bindings Verified target bindings / 已核实目标绑定
     * @param secrets Exact credential references / 精确凭据引用
     */
    private record Prepared(String source, String root, String declarationRoot, List<String> databaseIds,
                            String effectIdentity,
                            List<DeploymentConfigEntry> configuration,
                            List<DeploymentDatabaseBinding> bindings, List<DeploymentSecretRef> secrets) {
    }

    /** Current source declarations and actual targets / 当前来源声明及实际目标。
     * @param source current source identity / 当前来源身份
     * @param root Component source root / 组件源码根
     * @param declarationRoot Explicit declaration directory / 明确声明目录
     * @param databaseIds Selected actual database identities / 选中的实际数据库身份
     * @param assessment Current declaration assessment / 当前声明评估
     * @param instances Observed instance identities / 已观察实例身份
     * @param remoteObserved Whether native instances were actually observed remotely / 是否实际远端观察原生实例
     */
    private record Inventory(String source, String root, String declarationRoot, List<String> databaseIds,
                             DatabaseProjectChecker.Report assessment,
                             Map<String, Set<String>> instances, boolean remoteObserved) {
    }

    /** Original platform capabilities / 原平台能力。
     */
    private final DeploymentRemoteLayout layout;

    private final ModContext context;

    /** Original admitted task / 原已准入任务。
     */
    private final TaskContext task;

    /** Exact original server / 精确原服务器。
     */
    private final ServerId server;

    /** Current task-owned immutable sources / 当前任务所属不可变来源。
     */
    private final DeploymentAgentSources sources;

    /** Original durable task journal / 原持久任务日志。
     */
    private final DeploymentAgentActionLog journal;

    /** Original borrowed database channel / 原借用数据库通道。
     */
    private final DeploymentNativeDatabaseChannel channel;

    /** Settled source-bound database results / 已结清来源绑定数据库结果。
     */
    private final Map<String, Prepared> prepared = new HashMap<>();

    /** Current database inventories / 当前数据库库存。
     */
    private final Map<String, Inventory> inventories = new HashMap<>();

    /** Exact selected reference purposes / 精确选定引用用途。
     */
    private final Map<DeploymentSecretRef, String> selected = new HashMap<>();

    /** Binds original task capabilities / 绑定原任务能力。
     * @param context Original platform capabilities / 原平台能力
     * @param task Original admitted task / 原已准入任务
     * @param server Exact original server / 精确原服务器
     * @param session Original authenticated command channel / 原已认证命令通道
     * @param sources Current task-owned immutable sources / 当前任务所属不可变来源
     * @param journal Original durable task journal / 原持久任务日志
     */
    DeploymentAgentResourceTools(ModContext context, TaskContext task, ServerId server, RemoteSession session,
                                 DeploymentAgentSources sources, DeploymentAgentActionLog journal) {
        this(context, task, server, session, sources, journal, DeploymentRemoteLayout.original());
    }

    DeploymentAgentResourceTools(ModContext context, TaskContext task, ServerId server, RemoteSession session,
                                 DeploymentAgentSources sources, DeploymentAgentActionLog journal, DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.context = context;
        this.task = task;
        this.server = server;
        this.sources = sources;
        this.journal = journal;
        channel = new DeploymentNativeDatabaseChannel(session, context.interaction(), server, task, layout);
    }

    /** Runs only the selected source-bound capability / 只运行选定的来源绑定能力。
     * @param request Explicitly selected typed action / 明确选定类型化动作
     * @param part current component plan / 当前组件计划
     * @param candidate Original protected candidate / 原受保护候选
     * @param binding Original effect identity / 原效果身份
     * @param applicationId Explicit current managed application identity, without claiming remote preparation / 明确的当前受管应用身份，不代表远端已准备
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    String run(String applicationId, DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part, DeploymentAgentDeliveryPort.Prepared candidate, String binding) throws Exception {
        if (request.name().equals("secrets") || request.name().startsWith("secret_"))
            return secret(request, part, binding);
        String source = source(part.id());
        Path root = sources.material(part.id()).snapshot().directory();
        if (Set.of("database_inspect", "database_select_source").contains(request.name())) {
            var scope = request.name().equals("database_select_source") ? DeploymentAgentDatabaseScope.SOURCE
                : DeploymentAgentDatabaseScope.parse(request.arguments());
            var browser = sources.browser(part.id());
            if (scope == DeploymentAgentDatabaseScope.REMOTE
                && (candidate == null || !candidate.workspace().applicationId().equals(applicationId)))
                throw new IllegalArgumentException("REMOTE inspection requires its actual protected candidate");
            var previous = inventories.get(part.id());
            String declarationRoot = part.sourceRoot();
            List<String> ids = List.of();
            if (request.name().equals("database_select_source")) {
                var selection = DeploymentAgentDatabaseSourceSelection.parse(request.arguments().get("sourceSelection"));
                DeploymentAgentSourceRevision.safe(root, selection.declarationPath());
                declarationRoot = selection.declarationRoot();
                ids = selection.databaseIds();
            } else if (previous != null && previous.source().equals(source) && previous.root().equals(part.sourceRoot())) {
                declarationRoot = previous.declarationRoot();
                ids = previous.databaseIds();
            }
            var assessment = new DatabaseProjectChecker().inspect(root.resolve(declarationRoot).normalize(), part.id());
            if (!ids.isEmpty()) {
                var declared = assessment.databases().stream().map(DatabaseRequirement::id).collect(java.util.stream.Collectors.toSet());
                if (!declared.containsAll(ids))
                    throw new IllegalArgumentException("Select only database IDs actually declared by this source manifest");
                var chosen = ids;
                assessment = new DatabaseProjectChecker.Report(assessment.databases().stream().filter(v -> chosen.contains(v.id())).toList(),
                    assessment.sqlCandidates(), assessment.schemaReviewRequired(), assessment.unknownDatabase(), assessment.endpointConfirmationRequired());
            } else ids = assessment.databases().stream().map(DatabaseRequirement::id).sorted().toList();
            browser.index("", 0, 1);
            if (!browser.revision().equals(source(part.id())))
                throw new IllegalArgumentException("Database source changed during inspection");
            return inspect(part, applicationId, scope, source, root, declarationRoot, ids, assessment);
        }
        return prepare(request, part, candidate, binding, source, root);
    }

    /** Prepares only current inspected targets, never replays settled initialization. / 只准备当前已检查目标，不重放已结清初始化。
     * @param request selected action / 选定动作
     * @param part current component plan / 当前组件计划
     * @param candidate original candidate / 原候选
     * @param binding effect identity / 效果身份
     * @param source current source identity / 当前来源身份
     * @param root component root / 组件根
     * @return actual resource facts / 实际资源事实
     * @throws Exception denied or unknown effect / 被拒或未知效果 */
    private String prepare(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part,
                           DeploymentAgentDeliveryPort.Prepared candidate, String binding, String source, Path root) throws Exception {
        var args = request.arguments();
        validatePreparation(part);
        var inventory = inventories.get(part.id());
        requireExclusiveSelection(part.id(), inventory);
        var assessment = inventory.assessment();
        if (gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker()
            .flatMap(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.Worker::predecessor).isPresent()
            && (assessment.databases().stream().anyMatch(value -> value.engine() != DeploymentDatabaseEngineType.SQLITE)
            || assessment.schemaReviewRequired()))
            throw new IllegalArgumentException("Online successor supports source-declared SQLite without external seed, initialization or secret effects");
        String effectIdentity = effectIdentity(root, inventory);
        var original = prepared.get(part.id());
        if (original != null) {
            releaseKnown();
            if (!original.root().equals(part.sourceRoot()) || !original.declarationRoot().equals(inventory.declarationRoot())
                || !original.databaseIds().equals(inventory.databaseIds()) || !original.effectIdentity().equals(effectIdentity)) {
                require(DeploymentAgentDatabaseReview.confirm(context, server, "db.changed-source", part.id(), source,
                    "源码数据库声明或SQL已变化。已有数据库效果只核对原回执，本工具不会重复初始化或应用变化。", task));
                throw new IllegalArgumentException("Original database effects are settled but declarations changed; obtain a separately authorized migration plan, never replay initialization");
            }
            prepared.put(part.id(), new Prepared(source, original.root(), original.declarationRoot(), original.databaseIds(), effectIdentity, original.configuration(), original.bindings(), original.secrets()));
            return "ORIGINAL_DATABASE_RECEIPTS_RECONFIRMED source=" + source + " bindings=" + original.bindings() + "; no database effect replayed";
        }
        var choices = new HashMap<String, String>();
        var array = args.get("selections");
        if (array == null || !array.isArray() || array.size() > 32)
            throw new IllegalArgumentException("Database selections required");
        for (var item : array) {
            DeploymentJsonFields.exact(item, Set.of("databaseId", "instanceId"));
            String id = DeploymentJsonFields.text(item, "databaseId", 32);
            var selectedId = item.get("instanceId");
            if (selectedId == null || !selectedId.isTextual() || selectedId.textValue().length() > 256
                || choices.put(id, selectedId.textValue()) != null || !inventory.instances().containsKey(id)
                || !selectedId.textValue().isEmpty() && !inventory.instances().get(id).contains(selectedId.textValue()))
                throw new IllegalArgumentException("Invalid observed database selection");
        }
        if (!choices.keySet().equals(inventory.instances().keySet()))
            throw new IllegalArgumentException("All native database choices required");
        var sqliteAssessment = new DatabaseProjectChecker.Report(assessment.databases().stream().filter(v -> v.engine() == DeploymentDatabaseEngineType.SQLITE).toList(),
            assessment.sqlCandidates(), assessment.schemaReviewRequired(), false, false);
        if (!sqliteAssessment.databases().isEmpty() && sqliteAssessment.schemaReviewRequired())
            require(DeploymentAgentDatabaseReview.confirm(context, server, "db.sqlite-initialization", part.id(), source,
                "Initialize source-bound SQLite data: " + sqliteAssessment.databases(), task));
        var sqlite = DeploymentSqliteSetup.prepare(root, candidate.workspace().applicationId(), sqliteAssessment, true, task, layout);
        var configuration = new ArrayList<>(sqlite.configuration());
        var bindings = new ArrayList<>(sqlite.bindings());
        var secrets = new ArrayList<DeploymentSecretRef>();
        if (!inventory.instances().isEmpty()) {
            var local = DeploymentNativeDatabaseSetup.local(part.id(), candidate.workspace().applicationId(), source, root, assessment, task).scoped(source);
            var entries = local.nativeDatabases().stream().map(v -> new DeploymentDatabasePlanActionLog.Entry(part.id(), v.id(), source)).toList();
            String key = DeploymentDatabasePlanActionLog.KEY + "." + new DeploymentDatabasePlanActionLog.Entry(part.id(), "plan", source).key();
            journal.event(task, "AGENT_DATABASE_INDEX", key, source + ":" + binding);
            var log = DeploymentDatabasePlanActionLog.begin(context.tasks(), task, server, source, entries, key);
            var nativeOutput = require(new DeploymentNativeDatabaseSetup(context).prepare(server, log, local,
                new DeploymentAgentDatabaseReview(context, source, choices), task, channel));
            configuration.addAll(nativeOutput.configuration());
            bindings.addAll(nativeOutput.bindings());
            secrets.addAll(nativeOutput.secrets());
        }
        for (var secret : secrets) selected.put(secret, "deployment-database");
        prepared.put(part.id(), new Prepared(source, part.sourceRoot(), inventory.declarationRoot(), inventory.databaseIds(), effectIdentity, configuration, bindings, secrets));
        return "DATABASES_PREPARED source=" + source + " targetIds=" + bindings.stream().map(DeploymentDatabaseBinding::databaseId).toList()
            + "\nbindings=" + bindings + "\nconfiguration=" + configuration + "\nreferences=" + secrets;
    }

    void validatePreparation(DeploymentAgentPlan.Part part) {
        var inventory = inventories.get(part.id());
        if (inventory == null || !inventory.source().equals(source(part.id())) || !inventory.root().equals(part.sourceRoot()))
            throw new IllegalArgumentException("Inspect current database declarations before preparation");
        if (inventory.assessment().databases().isEmpty())
            throw new IllegalArgumentException("Source inspection does not establish business database absence; assess actual source READs and explicitly replan databases:[] when none is needed");
        if (!inventory.remoteObserved() && inventory.assessment().databases().stream().anyMatch(v -> v.engine() != DeploymentDatabaseEngineType.SQLITE))
            throw new IllegalArgumentException("Native database preparation requires current database_inspect scope REMOTE; SOURCE has serverInventory NOT_OBSERVED");
    }

    /** Selects metadata or creates a single explicitly named revision through secure host input. / 选择元数据或经安全宿主输入创建一份明确修订。
     * @param request typed secret action / 类型化秘密动作
     * @param part current component plan / 当前组件计划
     * @param binding original effect identity / 原效果身份
     * @return metadata only / 仅元数据
     * @throws Exception refused or unknown creation / 创建被拒绝或未知 */
    private String secret(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part, String binding) throws Exception {
        if (gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker()
            .flatMap(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.Worker::predecessor).isPresent())
            throw new IllegalArgumentException("Online successor cannot inherit or create secret bindings");
        var args = request.arguments();
        var store = new DeploymentSecretStore(context.db());
        if (request.name().equals("secrets")) {
            String purpose = purpose(DeploymentJsonFields.text(args, "purpose", 128));
            return metadata(store.listReferences(task, 256).stream().filter(v -> v.purpose().equals(purpose))
                .map(v -> Map.<String, Object>of("identifier", v.reference().identifier(), "revision", v.reference().revision(), "purpose", purpose)).toList());
        }
        if (request.name().startsWith("secret_")) {
            String purpose = purpose(DeploymentJsonFields.text(args, "purpose", 128));
            String identifier = DeploymentJsonFields.text(args, "identifier", 128);
            if (request.name().equals("secret_select")) {
                var ref = new DeploymentSecretRef(identifier, DeploymentJsonFields.number(args, "revision", 1, Integer.MAX_VALUE));
                var metadata = store.findRevision(task, ref).orElseThrow(() -> new IllegalArgumentException("Unknown secret revision"));
                if (!metadata.purpose().equals(Optional.of(purpose)))
                    throw new IllegalArgumentException("Secret purpose changed");
                selected.put(ref, purpose);
                return metadata(List.of(Map.of("identifier", ref.identifier(), "revision", ref.revision(), "purpose", purpose)));
            }
            var previous = store.latestRevision(task, identifier, purpose);
            long revision = DeploymentJsonFields.number(args, "revision", 1, Integer.MAX_VALUE);
            if (revision != previous.map(v -> v.reference().revision() + 1).orElse(1L))
                throw new IllegalArgumentException("Secret creation requires the exact next revision; never replay a created revision");
            var ref = new DeploymentSecretRef(identifier, revision);
            String key = "deployment-secret-revision." + gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(part.id() + ":" + ref + ":" + purpose).substring(0, 32);
            journal.event(task, "AGENT_SECRET_INDEX", key, binding + ":" + purpose);
            var result = new DeploymentSecretProvisionFlow(context).createWithinTask(task, server, key, ref, purpose, Instant.ofEpochMilli(System.currentTimeMillis()), accepted -> {
                var input = context.interaction().requestText("Deployment credential", identifier + " / " + purpose, true, accepted);
                return require(input).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            });
            var actual = require(result).reference();
            selected.put(actual, purpose);
            return metadata(List.of(Map.of("identifier", actual.identifier(), "revision", actual.revision(), "purpose", purpose)));
        }
        throw new IllegalArgumentException("Unknown secret capability");
    }

    /** Completes nonsecret source facts and observes instances only in the selected remote scope. / 补齐非秘密源码事实，仅在选定远端范围观察实例。
     * @param part current component plan / 当前组件计划
     * @param applicationId current component application identity / 当前组件应用身份
     * @param scope explicitly selected observation scope / 明确选定观测范围
     * @param source current source identity / 当前来源身份
     * @param root component source / 组件来源
     * @param assessment actual source facts / 实际源码事实
     * @return observed source and inventory / 已观察来源及库存
     * @throws Exception missing or refused business inputs / 业务输入缺失或被拒绝 */
    private String inspect(DeploymentAgentPlan.Part part, String applicationId, DeploymentAgentDatabaseScope scope,
                           String source, Path root, String declarationRoot, List<String> ids,
                           DatabaseProjectChecker.Report assessment) throws Exception {
        inventories.remove(part.id());
        var questions = DeploymentDatabaseDeclarations.questions(assessment, applicationId, Map.of());
        if (assessment.unknownDatabase() || !questions.isEmpty()) {
            inventories.remove(part.id());
            return databaseMetadata(part, source, declarationRoot, scope, assessment.unknownDatabase() ? "NEEDS_SOURCE_SELECTION" : "NEEDS_DECLARATION",
                assessment, List.of(), questions);
        }
        assessment = normalize(root, declarationRoot, assessment);
        assessment = DeploymentDatabaseDeclarations.complete(root, applicationId, assessment, Map.of(), layout);
        if (scope == DeploymentAgentDatabaseScope.REMOTE && assessment.endpointConfirmationRequired())
            require(DeploymentAgentDatabaseReview.confirm(context, server, "db.endpoint", part.id(), source,
                "将源码数据库连接绑定到本任务的实际服务器及所选实例: " + assessment.databases(), task));
        var inventory = new TreeMap<String, Set<String>>();
        var metadata = new ArrayList<Map<String, Object>>();
        var instances = new TreeMap<String, List<Map<String, Object>>>();
        for (var database : assessment.databases()) {
            if (scope == DeploymentAgentDatabaseScope.SOURCE || database.engine() == DeploymentDatabaseEngineType.SQLITE)
                continue;
            var observed = require(channel.inspect(database.engine()));
            inventory.put(database.id(), observed.instances().stream().map(v -> v.id()).collect(java.util.stream.Collectors.toSet()));
            instances.put(database.id(), observed.instances().stream().sorted(java.util.Comparator.comparing(v -> v.id()))
                .map(v -> Map.<String, Object>of("id", v.id(), "version", v.version(), "port", v.port(), "running", v.running(), "fingerprint", v.fingerprint())).toList());
        }
        for (var database : assessment.databases()) {
            var fields = new LinkedHashMap<String, Object>(Map.ofEntries(
                Map.entry("databaseId", database.id()), Map.entry("engine", database.engine().name()), Map.entry("requestedVersion", database.version()),
                Map.entry("initializationFiles", database.initializationFiles()), Map.entry("environmentPrefix", database.environmentPrefix()),
                Map.entry("credentialEnvironmentName", database.passwordEnvironment()),
                Map.entry("sqlitePath", database.sqlite().map(DeploymentSqliteRequirement::accessPath).orElse("")),
                Map.entry("sqlitePathEnvironment", database.sqlite().map(DeploymentSqliteRequirement::pathEnvironment).orElse("")),
                Map.entry("sqliteSeedFile", database.sqlite().map(DeploymentSqliteRequirement::seedFile).orElse(""))));
            if (scope == DeploymentAgentDatabaseScope.REMOTE) {
                fields.put("instanceIds", inventory.getOrDefault(database.id(), Set.of()).stream().sorted().toList());
                fields.put("instances", instances.getOrDefault(database.id(), List.of()));
            }
            metadata.add(Map.copyOf(fields));
        }
        String status = scope == DeploymentAgentDatabaseScope.SOURCE ? "SOURCE_INSPECTED" : "READY";
        String output = databaseMetadata(part, source, declarationRoot, scope, status, assessment, metadata, List.of());
        inventories.put(part.id(), new Inventory(source, part.sourceRoot(), declarationRoot, List.copyOf(ids), assessment, Map.copyOf(inventory),
            scope == DeploymentAgentDatabaseScope.REMOTE && assessment.databases().stream().anyMatch(v -> v.engine() != DeploymentDatabaseEngineType.SQLITE)));
        return output;
    }

    private String databaseMetadata(DeploymentAgentPlan.Part part, String source, String declarationRoot, DeploymentAgentDatabaseScope scope, String status,
                                    DatabaseProjectChecker.Report assessment, List<Map<String, Object>> databases,
                                    List<DeploymentDatabaseDeclarations.Input> questions) throws Exception {
        var manifests = new ArrayList<Map<String, Object>>();
        if (!status.equals("READY"))
            for (String path : sources.material(part.id()).includedFiles().stream().sorted()
                .filter(p -> p.equals("windowstolinux-db.properties") || p.endsWith("/windowstolinux-db.properties")).toList()) {
                if (manifests.size() >= 16)
                    throw new IllegalArgumentException("Too many database source declarations for bounded selection");
                String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
                var report = new DatabaseProjectChecker().inspect(sources.material(part.id()).snapshot().directory().resolve(directory), part.id());
                manifests.add(Map.of("path", path, "declarationRoot", directory,
                    "databaseIds", report.databases().stream().map(DatabaseRequirement::id).sorted().toList()));
            }
        String output = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.ofEntries(
            Map.entry("sourceRevision", source), Map.entry("component", part.id()), Map.entry("declarationRoot", declarationRoot),
            Map.entry("scope", scope.name()), Map.entry("sourceCoverage", "EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY"),
            Map.entry("serverInventory", scope == DeploymentAgentDatabaseScope.SOURCE || !status.equals("READY") ? "NOT_OBSERVED"
                : assessment.databases().stream().anyMatch(v -> v.engine() != DeploymentDatabaseEngineType.SQLITE) ? "OBSERVED" : "NOT_APPLICABLE"),
            Map.entry("status", status), Map.entry("databases", databases), Map.entry("candidateManifests", manifests),
            Map.entry("questions", questions.stream().map(q -> Map.of("key", q.key(), "value", q.value(), "choices", q.choices())).toList()),
            Map.entry("schemaReviewRequired", assessment.schemaReviewRequired()), Map.entry("endpointConfirmationRequired", assessment.endpointConfirmationRequired())));
        if (output.length() > 11000)
            throw new IllegalArgumentException("Database declaration metadata exceeds bounded Agent feedback");
        return output;
    }

    private static DatabaseProjectChecker.Report normalize(Path root, String declarationRoot, DatabaseProjectChecker.Report report) throws Exception {
        var databases = new ArrayList<DatabaseRequirement>();
        for (var database : report.databases()) {
            var paths = new ArrayList<String>();
            for (String path : database.initializationFiles()) paths.add(sourceMember(root, declarationRoot, path));
            var sqlite = database.sqlite();
            if (sqlite.isPresent()) {
                var file = sqlite.orElseThrow();
                String seed = file.seedFile().isEmpty() ? "" : sourceMember(root, declarationRoot, file.seedFile());
                sqlite = Optional.of(new DeploymentSqliteRequirement(file.location(), file.fileName(), file.accessPath(), file.pathEnvironment(), seed, file.hostLocationExplicit()));
            }
            databases.add(new DatabaseRequirement(database.id(), database.engine(), database.version(), database.database(), database.username(),
                database.environmentPrefix(), database.passwordEnvironment(), paths, database.springDatasource(), database.evidence(), sqlite));
        }
        return new DatabaseProjectChecker.Report(databases, report.sqlCandidates().stream()
            .map(p -> declarationRoot.equals(".") ? p : declarationRoot + "/" + p).toList(), report.schemaReviewRequired(), report.unknownDatabase(), report.endpointConfirmationRequired());
    }

    private static String sourceMember(Path root, String declarationRoot, String path) throws Exception {
        String member = declarationRoot.equals(".") ? path : declarationRoot + "/" + path;
        DeploymentAgentSourceRevision.safe(root, member);
        return member;
    }

    private void requireExclusiveSelection(String component, Inventory selectedInventory) {
        String manifest = selectedInventory.declarationRoot().equals(".") ? "windowstolinux-db.properties" : selectedInventory.declarationRoot() + "/windowstolinux-db.properties";
        if (!Files.isRegularFile(sources.material(component).snapshot().directory().resolve(manifest), LinkOption.NOFOLLOW_LINKS))
            return;
        for (var entry : inventories.entrySet())
            if (!entry.getKey().equals(component) && entry.getValue().declarationRoot().equals(selectedInventory.declarationRoot())
                && !Collections.disjoint(entry.getValue().databaseIds(), selectedInventory.databaseIds()))
                throw new IllegalArgumentException("The same source manifest database cannot be copied into multiple component preparations");
        for (var entry : prepared.entrySet())
            if (!entry.getKey().equals(component) && entry.getValue().declarationRoot().equals(selectedInventory.declarationRoot())
                && !Collections.disjoint(entry.getValue().databaseIds(), selectedInventory.databaseIds()))
                throw new IllegalArgumentException("This source manifest database already belongs to another prepared component");
    }

    /** Binds a plan to all current prepared resources / 将计划绑定所有当前已准备资源。
     * @param part current component plan / 当前组件计划
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    DeploymentAgentPlan.Part resolve(DeploymentAgentPlan.Part part) throws Exception {
        var found = prepared.get(part.id());
        var inventory = inventories.get(part.id());
        if (found != null && inventory != null && (!found.declarationRoot().equals(inventory.declarationRoot())
            || !found.databaseIds().equals(inventory.databaseIds())))
            throw new IllegalArgumentException("Database source selection changed; original prepared bindings cannot be relabeled");
        var bindings = part.databaseIds().map(ids -> {
            if (ids.isEmpty()) {
                if (found != null && !found.bindings().isEmpty())
                    throw new IllegalArgumentException("Prepared database targets cannot be omitted from the plan");
                return List.<DeploymentDatabaseBinding>of();
            }
            if (found == null || !found.source().equals(source(part.id())) || !found.root().equals(part.sourceRoot()))
                throw new IllegalArgumentException("Stale or unverified database preparation");
            if (!new HashSet<>(ids).equals(found.bindings().stream().map(DeploymentDatabaseBinding::databaseId).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("Database plan must include every prepared target");
            return found.bindings();
        });
        var refs = new LinkedHashSet<>(part.secretReferences());
        var config = new ArrayList<>(part.configuration());
        if (bindings.isPresent() && !bindings.orElseThrow().isEmpty()) {
            refs.addAll(found.secrets());
            var keys = new HashSet<String>();
            config.forEach(v -> keys.add(v.key()));
            for (var entry : found.configuration()) {
                if (!keys.add(entry.key()))
                    throw new IllegalArgumentException("Agent configuration conflicts with DB-owned key " + entry.key());
                config.add(entry);
            }
        }
        for (var ref : refs)
            if (!selected.containsKey(ref))
                throw new IllegalArgumentException("Secret reference must be selected through secure capability");
        return new DeploymentAgentPlan.Part(part.id(), part.sourceRoot(), part.dependencies(), part.evidence(), part.runtime(),
            new DeploymentResourceBindings(part.resources().fileBindings(), bindings), config, part.databaseIds(), List.copyOf(refs), part.workspaceRequest(), part.workspaceSelection());
    }

    /** Resolves exact references to opaque host credential identities / 将精确引用解析为不透明宿主凭据身份。
     * @param part current component plan / 当前组件计划
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    Map<DeploymentSecretRef, SecretId> credentials(DeploymentAgentPlan.Part part) throws Exception {
        var values = new HashMap<DeploymentSecretRef, SecretId>();
        for (var ref : part.secretReferences()) {
            var metadata = new DeploymentSecretStore(context.db()).findRevision(task, ref).orElseThrow();
            if (!metadata.purpose().equals(Optional.ofNullable(selected.get(ref))))
                throw new IllegalArgumentException("Selected secret purpose changed");
            values.put(ref, new SecretId(metadata.credentialId(), metadata.purpose().orElseThrow()));
        }
        return Map.copyOf(values);
    }

    /** Releases only known original indexed children. / 只释放确知的原索引子项。
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    void releaseKnown() throws Exception {
        if (!gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentResourceRecovery.settle(
            context, task, server, journal.events(task), channel))
            throw new IllegalStateException("Original database or secret work remains unknown");
    }

    /** Serializes only identifiers, revisions and purposes / 只序列化标识、修订及用途。
     * @param values values contract input / values合同输入
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private static String metadata(List<Map<String, Object>> values) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("references", values));
    }

    /** Returns the current immutable snapshot identity / 返回当前不可变快照身份。
     * @param component Selected component identity / 选定组件身份
     * @return checked current result / 已校验当前结果
     */
    private String source(String component) {
        return sources.material(component).snapshot().sha256();
    }

    /** Binds declarations, SQL and SQLite seed bytes independently of unrelated code changes. / 将声明、SQL及SQLite种子字节独立于其他代码变化绑定。
     * @param root full frozen source / 完整冻结来源
     * @param inventory source-bound selected declarations / 来源绑定的选定声明
     * @return stable effect identity / 稳定效果身份
     * @throws Exception unavailable source / 来源不可用 */
    private static String effectIdentity(Path root, Inventory inventory) throws Exception {
        var report = inventory.assessment();
        var value = new StringBuilder(inventory.declarationRoot()).append('\n').append(inventory.databaseIds()).append('\n').append(report);
        var paths = new TreeSet<String>();
        String declaration = inventory.declarationRoot().equals(".") ? "windowstolinux-db.properties" : inventory.declarationRoot() + "/windowstolinux-db.properties";
        if (Files.isRegularFile(root.resolve(declaration), LinkOption.NOFOLLOW_LINKS)) paths.add(declaration);
        report.databases().forEach(v -> {
            paths.addAll(v.initializationFiles());
            v.sqlite().filter(s -> !s.seedFile().isEmpty()).ifPresent(s -> paths.add(s.seedFile()));
        });
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        for (String path : paths) {
            Path file = DeploymentAgentSourceRevision.safe(root, path);
            value.append('\n').append(path).append(':').append(java.util.HexFormat.of().formatHex(digestFile(hash, file)));
        }
        return gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(value.toString());
    }

    /** Streams bounded memory over validated source bytes / 以有界内存流式读取已校验来源字节。
     * @param hash SHA-256 digest accumulator / SHA-256摘要累积器
     * @param file Validated source member / 已校验来源成员
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private static byte[] digestFile(java.security.MessageDigest hash, Path file) throws Exception {
        hash.reset();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0; ) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    /** Accepts only current deployment credential purposes / 只接纳当前部署凭据用途。
     * @param value Current typed input / 当前类型化输入
     * @return checked current result / 已校验当前结果
     */
    private static String purpose(String value) {
        if (!Set.of("deployment-runtime", "deployment-database").contains(value))
            throw new IllegalArgumentException("Unsupported deployment secret purpose");
        return value;
    }

    /** Preserves failure and uncertainty of the original capability / 保留原能力失败及未知。
     * @param <T> typed payload / 类型化载荷
     * @param result Original capability result / 原能力结果
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private static <T> T require(OperationResult<T> result) throws Exception {
        if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty())
            throw new DeploymentAiJson.CapabilityOutcomeException(result.outcome(), result.problem());
        return result.value().orElseThrow();
    }
}
