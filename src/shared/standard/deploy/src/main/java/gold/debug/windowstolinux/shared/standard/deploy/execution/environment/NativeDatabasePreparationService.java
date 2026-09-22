package gold.debug.windowstolinux.shared.standard.deploy.execution.environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import gold.debug.windowstolinux.shared.config.contract.definition.*;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.*;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.standard.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.standard.deploy.contract.AutomaticDatabasePreparation;

/**
 * Prepares application-scoped native databases and immutable credentials before the publishing transaction. / 在发布事务前准备应用范围的原生数据库和不可变凭据。
 */
public final class NativeDatabasePreparationService {
    /**
     * Credentials.
     * <p>凭据。
     */
    private final gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort credentials;
    /**
     * Binds the supplied dependencies and state for native database preparation service.
     * <p>为原生数据库准备服务绑定传入的依赖及状态。
     *
     * @param credentials credentials / 凭据
     */
    public NativeDatabasePreparationService(
            gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort credentials) {
        this.credentials = credentials;
    }

    /**
     * Completes and validates source-side DB inputs before any connection or environment change. / 在任何连接或环境变更前，补齐并验证源码侧数据库输入。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param assessment the typed static assessment / 类型化静态评估
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved assessment / 构造或解析得到的评估
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DatabaseProjectInspector.Assessment completeInputs(Path root, String applicationId,
            DatabaseProjectInspector.Assessment assessment, AutomaticDeploymentInteraction interaction)
            throws Exception {
        {
            List<DatabaseRequirement> requirements = completeRequirements(assessment, applicationId, interaction);
            for (var requirement : requirements) {
                byte[] sql = initializationSql(root, requirement.initializationFiles());
                Arrays.fill(sql, (byte) 0);
                if (requirement.sqlite().isPresent()) {
                    var file = requirement.sqlite().orElseThrow();
                    file.location().resolve(applicationId,
                            gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.DATABASE,
                            requirement.id());
                    if (!file.seedFile().isEmpty()) {
                        Path seed = root.resolve(file.seedFile()).normalize();
                        if (!seed.startsWith(root) || !Files.isRegularFile(seed, LinkOption.NOFOLLOW_LINKS)
                                || !seed.toRealPath().startsWith(root.toRealPath()))
                            throw new IllegalArgumentException("SQLite seed must be a regular reviewed source file");
                    }
                }
            }
            return new DatabaseProjectInspector.Assessment(requirements, assessment.sqlCandidates(),
                    assessment.schemaReviewRequired(), false, assessment.endpointConfirmationRequired());
        }
    }

    /**
     * Resolves approved database instances and credentials, prepares targets and confirmed initialization, and returns exact resource bindings while clearing transient secrets.
     * <p>解析已批准数据库实例及凭据，准备目标及已确认初始化，返回精确资源绑定，并清空临时秘密。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param assessment the typed static assessment / 类型化静态评估
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param progress progress / 进度
     * @return approved database instances and credentials, prepares targets and confirmed initialization, and returns exact resource bindings while clearing transient secrets / 已批准数据库实例及凭据，准备目标及已确认初始化，返回精确资源绑定，并清空临时秘密
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public AutomaticDatabasePreparation prepare(Path root, String applicationId, String serverId,
            NativeDatabasePort port, DatabaseProjectInspector.Assessment assessment,
            AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        if (assessment.unknownDatabase())
            throw new IllegalArgumentException("complete database inputs before server preparation");
        List<DatabaseRequirement> requirements = validatedRequirements(assessment.databases(), Map.of(), assessment);
        if (requirements.isEmpty())
            return AutomaticDatabasePreparation.empty();
        List<ConfigurationEntry> entries = new ArrayList<>();
        List<SecretReference> references = new ArrayList<>();
        List<ManagedDatabaseBinding> bindings = new ArrayList<>();
        Set<String> initialized = new LinkedHashSet<>();
        boolean newInitialized = false, existingApproved = false, sqliteInitializationPlanned = false;
        for (DatabaseRequirement requirement : requirements) {
            if (requirement.engine() == DatabaseEngineType.SQLITE) {
                var connection = sqliteConnection(applicationId, requirement, entries);
                if (assessment.schemaReviewRequired()) {
                    confirmSqliteInitialization(requirement, interaction);
                    initialized.addAll(requirement.initializationFiles());
                    sqliteInitializationPlanned = true;
                }
                bindings.add(new ManagedDatabaseBinding(requirement.id(), connection));
                continue;
            }
            byte[] sql = initializationSql(root, requirement.initializationFiles());
            char[] admin = new char[0], password = new char[0];
            try {
                Instance instance = DatabaseInstanceResolver.resolve(port, serverId, requirement, interaction,
                        progress);
                TargetAccess access = inspectTarget(port, instance, applicationId, requirement, interaction);
                Target target = access.target();
                admin = access.administratorPassword();
                boolean createdNow = !target.exists();
                String environment = requirement.passwordEnvironment();
                String identifier = "db-"
                        + digest(serverId + "/" + applicationId + "/" + requirement.id()).substring(0, 12) + ".env."
                        + environment.toLowerCase(Locale.ROOT).replace('_', '-');
                Credential selectedCredential = credential(identifier, createdNow, interaction);
                SecretReference reference = selectedCredential.reference();
                boolean stored = selectedCredential.stored();
                password = selectedCredential.password();
                progress.accept(LocalizedMessage.of(createdNow ? "db.creating" : "db.connecting", "database",
                        requirement.id()));
                while (true) {
                    try {
                        target = port.prepareDatabaseTarget(instance, applicationId, requirement.database(),
                                requirement.username(), reference.identifier(), reference.revision(), password, admin);
                        break;
                    } catch (NativeDatabaseException failure) {
                        if (failure.reason() != NativeDatabaseFailureType.AUTH_REQUIRED || createdNow)
                            throw failure;
                        Arrays.fill(password, '\0');
                        password = interaction.requestSecret("db.applicationPassword");
                        if (stored) {
                            reference = new SecretReference(identifier, reference.revision() + 1);
                            stored = false;
                        }
                    }
                }
                if (!createdNow && !stored)
                    credentials.save(reference, password.clone());
                if (sql.length > 0) {
                    progress.accept(LocalizedMessage.of("db.initializing", "database", requirement.id()));
                    try {
                        target = port.initializeDatabase(target, sourceDigest(root), sql, password, false);
                    } catch (NativeDatabaseException failure) {
                        if (failure.reason() != NativeDatabaseFailureType.STATE_CHANGED || createdNow)
                            throw failure;
                        if (!interaction.confirm("db.existingSchema", Map.of("database", requirement.database(),
                                "files", String.join(", ", requirement.initializationFiles()))))
                            throw new CancellationException();
                        target = port.initializeDatabase(target, sourceDigest(root), sql, password, true);
                        existingApproved = true;
                    }
                    if (target.initialization() != InitializationState.COMPLETE)
                        throw new NativeDatabaseException(NativeDatabaseFailureType.INITIALIZATION_FAILED);
                    newInitialized |= createdNow && target.owned();
                    existingApproved |= !createdNow;
                    initialized.addAll(requirement.initializationFiles());
                }
                references.add(reference);
                bindings.add(new ManagedDatabaseBinding(requirement.id(),
                        new ManagedDatabaseConnection.Server(
                                ManagedDatabaseEngineType.valueOf(requirement.engine().name()), "127.0.0.1",
                                instance.port(), requirement.database(), requirement.username(), reference, false)));
                entries.addAll(configuration(requirement, instance, reference));
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(admin, '\0');
                Arrays.fill(sql, (byte) 0);
            }
        }
        boolean spring = requirements.stream().filter(requirement -> !requirement.initializationFiles().isEmpty())
                .allMatch(DatabaseRequirement::springDatasource);
        if (assessment.schemaReviewRequired() && spring)
            entries.addAll(disabledSpringInitialization());
        Optional<DatabaseSchemaReview> review = assessment.schemaReviewRequired()
                ? Optional.of(new DatabaseSchemaReview(sourceDigest(root),
                        bindings.stream().map(ManagedDatabaseBinding::databaseId)
                                .collect(java.util.stream.Collectors.toSet()),
                        initialized, newInitialized, existingApproved, spring, sqliteInitializationPlanned))
                : Optional.empty();
        return new AutomaticDatabasePreparation(entries, references, bindings, review);
    }

    /**
     * Builds the reviewed SQLite connection and adds its environment configuration entries.
     * <p>构建已审阅 SQLite 连接，并添加其环境配置项。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param requirement requirement / 要求
     * @param entries the type-checked entries / 经类型检查的条目
     * @return the reviewed SQLite connection and adds its environment configuration entries / 已审阅 SQLite 连接，并添加其环境配置项
     */
    private static ManagedDatabaseConnection.Sqlite sqliteConnection(String applicationId,
            DatabaseRequirement requirement, List<ConfigurationEntry> entries) {
        var file = requirement.sqlite().orElseThrow();
        var connection = new ManagedDatabaseConnection.Sqlite(file.fileName(), file.location(), file.accessPath(),
                file.seedFile(), requirement.initializationFiles());
        String path = connection.accessPath().isEmpty()
                ? connection.physicalPath(applicationId, requirement.id())
                : connection.accessPath();
        connection.physicalPath(applicationId, requirement.id());
        if (!file.pathEnvironment().isEmpty() || requirement.springDatasource()) {
            String key = requirement.springDatasource() ? "SPRING_DATASOURCE_URL" : file.pathEnvironment();
            entries.add(entry(key, (requirement.springDatasource() ? "jdbc:sqlite:" : "") + path));
        }
        return connection;
    }

    /**
     * Confirms sqlite initialization.
     * <p>确认sqlite初始化。
     *
     * @param requirement requirement / 要求
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private static void confirmSqliteInitialization(DatabaseRequirement requirement,
            AutomaticDeploymentInteraction interaction) throws Exception {
        if (!interaction.confirm("db.sqliteInitialization",
                Map.of("database", requirement.id(), "files", String.join(", ", requirement.initializationFiles()))))
            throw new CancellationException();
    }

    /**
     * Returns disabled spring initialization.
     * <p>返回已禁用Spring初始化。
     *
     * @return disabled spring initialization / 已禁用Spring初始化
     */
    private static List<ConfigurationEntry> disabledSpringInitialization() {
        return List.of(entry("SPRING_SQL_INIT_MODE", "never"), entry("SPRING_FLYWAY_ENABLED", false),
                entry("SPRING_LIQUIBASE_ENABLED", false), entry("SPRING_JPA_HIBERNATE_DDL_AUTO", "none"),
                entry("SPRING_JPA_GENERATE_DDL", false));
    }

    /**
     * Pairs an admitted native database target with its credential-access contract.
     * <p>将已准入原生数据库目标与其凭据访问契约配对。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param administratorPassword administrator password / 管理员密码
     */
    private record TargetAccess(Target target, char[] administratorPassword) {
    }
    /**
     * Inspects exact destination or managed target of the operation.
     * <p>检查操作的精确目的地或受管目标。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param instance instance / 实例
     * @param applicationId managed application identifier / 受管应用标识
     * @param requirement requirement / 要求
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved target access / 构造或解析得到的目标访问
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private TargetAccess inspectTarget(NativeDatabasePort port, Instance instance, String applicationId,
            DatabaseRequirement requirement, AutomaticDeploymentInteraction interaction) throws Exception {
        char[] password = new char[0];
        try {
            while (true) {
                try {
                    return new TargetAccess(port.inspectDatabaseTarget(instance, applicationId, requirement.database(),
                            requirement.username(), password), password);
                } catch (NativeDatabaseException failure) {
                    if (failure.reason() != NativeDatabaseFailureType.AUTH_REQUIRED)
                        throw failure;
                    Arrays.fill(password, '\0');
                    password = interaction.requestSecret("db.adminPassword");
                }
            }
        } catch (Exception failure) {
            Arrays.fill(password, '\0');
            throw failure;
        }
    }

    /**
     * Owns temporary database credential material for a scoped preparation action.
     * <p>持有限定准备操作所用的临时数据库凭据素材。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @param stored stored / 已存储
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     */
    private record Credential(SecretReference reference, boolean stored, char[] password) {
    }
    /**
     * Builds credential from the supplied credential inputs.
     * <p>根据所提供凭据输入构建凭据。
     *
     * @param identifier the stable secret identifier / 稳定的秘密标识
     * @param createdNow created now / 已创建Now
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return credential from the supplied credential inputs / 根据所提供凭据输入构建凭据
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private Credential credential(String identifier, boolean createdNow, AutomaticDeploymentInteraction interaction)
            throws Exception {
        var stored = credentials.latest(identifier);
        SecretReference reference = stored.orElseGet(() -> new SecretReference(identifier, 1));
        char[] password = stored.isPresent()
                ? credentials.load(reference)
                : createdNow ? randomPassword() : interaction.requestSecret("db.applicationPassword");
        if (stored.isEmpty() && createdNow)
            credentials.save(reference, password.clone());
        return new Credential(reference, stored.isPresent() || createdNow, password);
    }

    /**
     * Completes unknown database requirements through explicit deployment input interaction.
     * <p>通过显式部署输入交互补全未知数据库要求。
     *
     * @param assessment the typed static assessment / 类型化静态评估
     * @param app app / 应用
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private List<DatabaseRequirement> completeRequirements(DatabaseProjectInspector.Assessment assessment, String app,
            AutomaticDeploymentInteraction interaction) {
        List<DatabaseRequirement> requirements = new ArrayList<>(assessment.databases());
        if (assessment.unknownDatabase()) {
            var field = field("main", "engine", "",
                    Arrays.stream(DatabaseEngineType.values()).map(Enum::name).toList());
            var answer = ask(List.of(field), interaction);
            requirements.add(new DatabaseRequirement("main", DatabaseEngineType.valueOf(answer.get(field.id())), "", "",
                    "", "", "", List.of(), false, "user-selected database type"));
        }
        List<DeploymentInputField> fields = new ArrayList<>();
        boolean declaredInitialization = requirements.stream()
                .anyMatch(requirement -> !requirement.initializationFiles().isEmpty());
        for (var requirement : requirements) {
            String id = requirement.id();
            if (requirement.engine() == DatabaseEngineType.SQLITE) {
                var file = requirement.sqlite().orElseThrow();
                if (file.location()
                        .type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED)
                    fields.add(field(id, "path", "", List.of()));
                if (file.pathEnvironment().isEmpty() && !requirement.springDatasource() && file.accessPath().isEmpty())
                    fields.add(field(id, "pathEnvironment", "", List.of()));
                if (assessment.schemaReviewRequired() && !declaredInitialization)
                    fields.add(field(id, "initialize", "", initializationChoices(assessment, requirements.size() > 1)));
                continue;
            }
            if (requirement.database().isEmpty())
                fields.add(field(id, "database", generatedName(app, id), List.of()));
            if (requirement.username().isEmpty())
                fields.add(field(id, "username", generatedName(app, id), List.of()));
            if (requirement.environmentPrefix().isEmpty())
                fields.add(field(id, "environmentPrefix", "", List.of()));
            if (requirement.passwordEnvironment().isEmpty())
                fields.add(field(id, "passwordEnvironment", "", List.of()));
            if (assessment.schemaReviewRequired() && !declaredInitialization
                    && requirement.engine() != DatabaseEngineType.REDIS)
                fields.add(field(id, "initialize", "", initializationChoices(assessment, requirements.size() > 1)));
        }
        Map<String, String> answers = ask(fields, interaction);
        while (true) {
            try {
                return validatedRequirements(requirements, answers, assessment);
            } catch (IllegalArgumentException invalid) {
                List<DeploymentInputField> correction = new ArrayList<>();
                Map<String, String> previousAnswers = answers;
                for (var requirement : requirements) {
                    if (requirement.engine() == DatabaseEngineType.SQLITE) {
                        var file = requirement.sqlite().orElseThrow();
                        correction
                                .add(field(requirement.id(), "path",
                                        previousAnswers.getOrDefault("db/" + requirement.id() + "/path",
                                                file.accessPath().isEmpty() ? "DEFAULT" : file.accessPath()),
                                        List.of()));
                        correction.add(field(requirement.id(), "pathEnvironment", previousAnswers.getOrDefault(
                                "db/" + requirement.id() + "/pathEnvironment", file.pathEnvironment()), List.of()));
                        if (assessment.schemaReviewRequired())
                            correction.add(field(requirement.id(), "initialize",
                                    previousAnswers.getOrDefault("db/" + requirement.id() + "/initialize",
                                            String.join(",", requirement.initializationFiles())),
                                    initializationChoices(assessment, requirements.size() > 1)));
                        continue;
                    }
                    Map<String, String> current = Map.of("database", requirement.database(), "username",
                            requirement.username(), "environmentPrefix", requirement.environmentPrefix(),
                            "passwordEnvironment", requirement.passwordEnvironment());
                    current.forEach((key, value) -> correction.add(field(requirement.id(), key,
                            previousAnswers.getOrDefault("db/" + requirement.id() + "/" + key, value), List.of())));
                    if (assessment.schemaReviewRequired() && requirement.engine() != DatabaseEngineType.REDIS)
                        correction.add(field(requirement.id(), "initialize",
                                answers.getOrDefault("db/" + requirement.id() + "/initialize",
                                        String.join(",", requirement.initializationFiles())),
                                initializationChoices(assessment, requirements.size() > 1)));
                }
                answers = ask(correction, interaction);
            }
        }
    }

    /**
     * Builds bounded SQL initialization choices, adding the optional no-initialization choice when permitted.
     * <p>构建有界 SQL 初始化选项，并在允许时添加不初始化选项。
     *
     * @param assessment the typed static assessment / 类型化静态评估
     * @param optional optional / 可选
     * @return bounded SQL initialization choices, adding the optional no-initialization choice when permitted / 有界 SQL 初始化选项，并在允许时添加不初始化选项
     */
    private static List<String> initializationChoices(DatabaseProjectInspector.Assessment assessment,
            boolean optional) {
        if (!optional || assessment.sqlCandidates().isEmpty())
            return assessment.sqlCandidates();
        if (assessment.sqlCandidates().size() == 64)
            return List.of();
        var choices = new ArrayList<String>();
        choices.add("");
        choices.addAll(assessment.sqlCandidates());
        return List.copyOf(choices);
    }

    /**
     * Validates completed database requirements and schema-review obligations before any remote preparation.
     * <p>在任何远端准备前校验已补全数据库要求及结构审阅义务。
     *
     * @param requirements requirements / 要求集合
     * @param answers answers / 回答集合
     * @param assessment the typed static assessment / 类型化静态评估
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static List<DatabaseRequirement> validatedRequirements(List<DatabaseRequirement> requirements,
            Map<String, String> answers, DatabaseProjectInspector.Assessment assessment) {
        List<DatabaseRequirement> completed = new ArrayList<>();
        for (var requirement : requirements) {
            String prefix = "db/" + requirement.id() + "/";
            String initialization = answers.getOrDefault(prefix + "initialize", "");
            Optional<SqliteFileRequirement> sqlite = requirement.sqlite().map(file -> {
                if (!answers.containsKey(prefix + "path") && !answers.containsKey(prefix + "pathEnvironment"))
                    return file;
                var corrected = SqliteFileRequirement.fromPath(answers.getOrDefault(prefix + "path", file.location()
                        .type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.DEFAULT
                                ? "DEFAULT"
                                : file.accessPath()),
                        answers.getOrDefault(prefix + "pathEnvironment", file.pathEnvironment()), file.seedFile());
                if (file.hostLocationExplicit())
                    return new SqliteFileRequirement(file.location(), corrected.fileName(), corrected.accessPath(),
                            corrected.pathEnvironment(), corrected.seedFile(), true);
                return corrected;
            });
            completed.add(new DatabaseRequirement(requirement.id(), requirement.engine(), requirement.version(),
                    answers.getOrDefault(prefix + "database", requirement.database()),
                    answers.getOrDefault(prefix + "username", requirement.username()),
                    answers.getOrDefault(prefix + "environmentPrefix", requirement.environmentPrefix()),
                    answers.getOrDefault(prefix + "passwordEnvironment", requirement.passwordEnvironment()),
                    initialization.isEmpty()
                            ? requirement.initializationFiles()
                            : Arrays.stream(initialization.split(",")).map(String::trim).toList(),
                    requirement.springDatasource(), requirement.evidence(), sqlite));
        }
        if (completed.stream().anyMatch(requirement -> requirement.engine() == DatabaseEngineType.SQLITE
                ? requirement.sqlite().orElseThrow().location()
                        .type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED
                        || requirement.sqlite().orElseThrow().accessPath().isBlank()
                                && requirement.sqlite().orElseThrow().pathEnvironment().isBlank()
                                && !requirement.springDatasource()
                : requirement.database().isBlank() || requirement.username().isBlank()
                        || requirement.environmentPrefix().isBlank() || requirement.passwordEnvironment().isBlank()))
            throw new IllegalArgumentException("database inputs must be complete before server mutation");
        var servers = completed.stream().filter(requirement -> requirement.engine() != DatabaseEngineType.SQLITE)
                .toList();
        if (servers.stream().map(DatabaseRequirement::environmentPrefix).distinct().count() != servers.size()
                || servers.stream().map(DatabaseRequirement::passwordEnvironment).distinct().count() != servers.size())
            throw new IllegalArgumentException("database environment variables must be unique");
        if (assessment.schemaReviewRequired()
                && completed.stream().allMatch(requirement -> requirement.initializationFiles().isEmpty()))
            throw new IllegalArgumentException(
                    "database schema declarations require an explicit supported initialization file");
        return List.copyOf(completed);
    }

    /**
     * Builds non-secret database environment entries referencing the selected instance and credential revision.
     * <p>构建引用所选实例及凭据修订的非秘密数据库环境项。
     *
     * @param requirement requirement / 要求
     * @param instance instance / 实例
     * @param secret secret / 秘密
     * @return non-secret database environment entries referencing the selected instance and credential revision / 引用所选实例及凭据修订的非秘密数据库环境项
     */
    private static List<ConfigurationEntry> configuration(DatabaseRequirement requirement, Instance instance,
            SecretReference secret) {
        List<ConfigurationEntry> result = new ArrayList<>();
        String prefix = requirement.environmentPrefix();
        if (requirement.springDatasource())
            result.add(entry(prefix + "_URL", "jdbc:" + requirement.engine().name().toLowerCase(Locale.ROOT)
                    + "://127.0.0.1:" + instance.port() + "/" + requirement.database()));
        else {
            result.add(entry(prefix + "_HOST", "127.0.0.1"));
            result.add(entry(prefix + "_PORT", instance.port()));
            result.add(entry(prefix + "_DATABASE",
                    requirement.engine() == DatabaseEngineType.REDIS ? 0 : requirement.database()));
        }
        result.add(entry(prefix + "_USERNAME", requirement.username()));
        if (requirement.engine() == DatabaseEngineType.REDIS)
            result.add(entry(prefix + "_KEY_PREFIX", requirement.database() + ":"));
        return List.copyOf(result);
    }

    /**
     * Reads bounded reviewed SQL files beneath the source root and combines their UTF-8 content.
     * <p>读取源码根目录下有界的已审阅 SQL 文件，并组合其 UTF-8 内容。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param paths paths / 路径集合
     * @return bounded reviewed SQL files beneath the source root and combines their UTF-8 content / 源码根目录下有界的已审阅 SQL 文件，并组合其 UTF-8 内容
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static byte[] initializationSql(Path root, List<String> paths) throws Exception {
        StringBuilder sql = new StringBuilder();
        for (String path : paths) {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                    || Files.size(file) > 2 * 1024 * 1024)
                throw new IllegalArgumentException("initialization file must belong to the frozen source");
            sql.append(Files.readString(file)).append('\n');
        }
        byte[] result = sql.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > 2 * 1024 * 1024)
            throw new IllegalArgumentException("initialization exceeds size limit");
        return result;
    }

    /**
     * Checks ask syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查请求语法及边界。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static Map<String, String> ask(List<DeploymentInputField> fields, AutomaticDeploymentInteraction interaction) {
        if (fields.isEmpty())
            return Map.of();
        Map<String, String> supplied = interaction.requestInputs(fields).orElseThrow(CancellationException::new);
        var result = new LinkedHashMap<String, String>();
        for (var field : fields) {
            String value = supplied.get(field.id());
            if (value == null || value.length() > 4096
                    || (!field.choices().isEmpty() && !field.choices().contains(value)))
                throw new IllegalArgumentException("Invalid database input");
            result.put(field.id(), value.trim());
        }
        return Map.copyOf(result);
    }

    /**
     * Reinspects the bounded source tree and rejects unsafe changes before deriving its mutation-policy digest.
     * <p>重新检查有界源码树，在派生变更策略摘要前拒绝不安全变更。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return source digest text / 源码摘要文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String sourceDigest(Path root) {
        List<RejectionReason> rejected = new ArrayList<>();
        var facts = new BoundedSourceInspector().inspect(root, rejected);
        if (!rejected.isEmpty())
            throw new IllegalArgumentException("source changed during database preparation");
        return SourceMutationPolicy.inspectionDigest(facts);
    }

    /**
     * Builds deployment input field from the supplied field inputs.
     * <p>根据所提供字段输入构建部署输入字段。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param choices choices / 选项集合
     * @return deployment input field from the supplied field inputs / 根据所提供字段输入构建部署输入字段
     */
    private static DeploymentInputField field(String id, String key, String value, List<String> choices) {
        return new DeploymentInputField("db/" + id + "/" + key, "db.field." + key, "db.help." + key, value, choices);
    }

    /**
     * Builds configuration entry from the supplied entry inputs.
     * <p>根据所提供条目输入构建配置条目。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return configuration entry from the supplied entry inputs / 根据所提供条目输入构建配置条目
     */
    private static ConfigurationEntry entry(String key, Object value) {
        return new ConfigurationEntry(key, ConfigurationScope.RUNTIME,
                value instanceof Number number
                        ? new ConfigurationValue.Number(number.longValue())
                        : value instanceof Boolean flag
                                ? new ConfigurationValue.Flag(flag)
                                : new ConfigurationValue.Text(value.toString()));
    }

    /**
     * Derives a bounded SQL identifier with a deterministic digest suffix to distinguish application resources.
     * <p>派生带确定摘要后缀的有界 SQL 标识，以区分应用资源。
     *
     * @param app app / 应用
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return generated name text / 已生成名称文本
     */
    private static String generatedName(String app, String id) {
        String stem = (app + "_" + id).replace('-', '_');
        if (!Character.isLetter(stem.charAt(0)))
            stem = "app_" + stem;
        return stem.substring(0, Math.min(20, stem.length())) + "_" + digest(app + "/" + id).substring(0, 8);
    }

    /**
     * Returns random password.
     * <p>返回随机密码。
     *
     * @return random password / 随机密码
     */
    private static char[] randomPassword() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
        } finally {
            Arrays.fill(random, (byte) 0);
        }
    }

    /**
     * Computes or retrieves content identity for independent evidence checks.
     * <p>计算或取得用于独立证据检查的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return or retrieves content identity for independent evidence checks / 或取得用于独立证据检查的内容身份
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
