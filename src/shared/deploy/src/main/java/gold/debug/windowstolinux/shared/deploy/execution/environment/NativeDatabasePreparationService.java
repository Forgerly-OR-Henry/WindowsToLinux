package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDatabasePreparation;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;


import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.shared.config.contract.definition.*;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.*;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Prepares application-scoped native databases and immutable credentials before the publishing transaction. / 在发布事务前准备应用范围的原生数据库和不可变凭据。 */
public final class NativeDatabasePreparationService {
    private final gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort credentials;
    public NativeDatabasePreparationService(gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort credentials) { this.credentials = credentials; }

    /** Completes and validates source-side DB inputs before any connection or environment change. / 在任何连接或环境变更前，补齐并验证源码侧数据库输入。 */
    public DatabaseProjectInspector.Assessment completeInputs(Path root, String applicationId,
            DatabaseProjectInspector.Assessment assessment, AutomaticDeploymentInteraction interaction) throws Exception {
        {
            List<DatabaseRequirement> requirements = completeRequirements(assessment, applicationId, interaction);
            for (var requirement : requirements) {
                byte[] sql = initializationSql(root, requirement.initializationFiles());
                Arrays.fill(sql, (byte) 0);
                if (requirement.sqlite().isPresent()) {
                    var file = requirement.sqlite().orElseThrow();
                    file.location().resolve(applicationId,gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.DATABASE,requirement.id());
                    if (!file.seedFile().isEmpty()) {
                        Path seed = root.resolve(file.seedFile()).normalize();
                        if (!seed.startsWith(root) || !Files.isRegularFile(seed,LinkOption.NOFOLLOW_LINKS) || !seed.toRealPath().startsWith(root.toRealPath()))
                            throw new IllegalArgumentException("SQLite seed must be a regular reviewed source file");
                    }
                }
            }
            return new DatabaseProjectInspector.Assessment(requirements, assessment.sqlCandidates(),
                    assessment.schemaReviewRequired(), false, assessment.endpointConfirmationRequired());
        }
    }

    public AutomaticDatabasePreparation prepare(Path root, String applicationId, String serverId, NativeDatabasePort port,
            DatabaseProjectInspector.Assessment assessment, AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        if (assessment.unknownDatabase()) throw new IllegalArgumentException("complete database inputs before server preparation");
        List<DatabaseRequirement> requirements = validatedRequirements(assessment.databases(), Map.of(), assessment);
        if (requirements.isEmpty()) return AutomaticDatabasePreparation.empty();
        List<ConfigurationEntry> entries = new ArrayList<>(); List<SecretReference> references = new ArrayList<>();
        List<ManagedDatabaseBinding> bindings = new ArrayList<>(); Set<String> initialized = new LinkedHashSet<>();
        boolean newInitialized = false, existingApproved = false, sqliteInitializationPlanned = false;
        for (DatabaseRequirement requirement : requirements) {
            if (requirement.engine() == DatabaseEngineType.SQLITE) {
                var connection = sqliteConnection(applicationId, requirement, entries);
                if (assessment.schemaReviewRequired()) {
                    confirmSqliteInitialization(requirement, interaction);
                    initialized.addAll(requirement.initializationFiles()); sqliteInitializationPlanned = true;
                }
                bindings.add(new ManagedDatabaseBinding(requirement.id(),connection));
                continue;
            }
            byte[] sql = initializationSql(root, requirement.initializationFiles());
            char[] admin = new char[0], password = new char[0];
            try {
                Instance instance = DatabaseInstanceResolver.resolve(port, serverId, requirement, interaction, progress);
                TargetAccess access = inspectTarget(port, instance, applicationId, requirement, interaction);
                Target target = access.target(); admin = access.administratorPassword();
                boolean createdNow = !target.exists();
                String environment = requirement.passwordEnvironment();
                String identifier = "db-" + digest(serverId+"/"+applicationId+"/"+requirement.id()).substring(0,12)
                        + ".env." + environment.toLowerCase(Locale.ROOT).replace('_','-');
                Credential selectedCredential = credential(identifier, createdNow, interaction);
                SecretReference reference = selectedCredential.reference();
                boolean stored = selectedCredential.stored();
                password = selectedCredential.password();
                progress.accept(LocalizedMessage.of(createdNow ? "db.creating" : "db.connecting", "database", requirement.id()));
                while (true) {
                    try {
                        target = port.prepareDatabaseTarget(instance, applicationId, requirement.database(), requirement.username(),
                                reference.identifier(), reference.revision(), password, admin);
                        break;
                    } catch (NativeDatabaseException failure) {
                        if (failure.reason() != NativeDatabaseFailureType.AUTH_REQUIRED || createdNow) throw failure;
                        Arrays.fill(password,'\0'); password = interaction.requestSecret("db.applicationPassword");
                        if (stored) { reference = new SecretReference(identifier,reference.revision()+1); stored = false; }
                    }
                }
                if (!createdNow && !stored) credentials.save(reference,password.clone());
                if (sql.length > 0) {
                    progress.accept(LocalizedMessage.of("db.initializing", "database", requirement.id()));
                    try { target = port.initializeDatabase(target, sourceDigest(root), sql, password, false); }
                    catch (NativeDatabaseException failure) {
                        if (failure.reason() != NativeDatabaseFailureType.STATE_CHANGED || createdNow) throw failure;
                        if (!interaction.confirm("db.existingSchema", Map.of("database", requirement.database(),
                                "files", String.join(", ", requirement.initializationFiles())))) throw new CancellationException();
                        target = port.initializeDatabase(target, sourceDigest(root), sql, password, true);
                        existingApproved = true;
                    }
                    if (target.initialization() != InitializationState.COMPLETE) throw new NativeDatabaseException(NativeDatabaseFailureType.INITIALIZATION_FAILED);
                    newInitialized |= createdNow && target.owned();
                    existingApproved |= !createdNow;
                    initialized.addAll(requirement.initializationFiles());
                }
                references.add(reference);
                bindings.add(new ManagedDatabaseBinding(requirement.id(), new ManagedDatabaseConnection.Server(
                        ManagedDatabaseEngineType.valueOf(requirement.engine().name()), "127.0.0.1",instance.port(),
                        requirement.database(),requirement.username(),reference,false)));
                entries.addAll(configuration(requirement,instance,reference));
            } finally { Arrays.fill(password,'\0'); Arrays.fill(admin,'\0'); Arrays.fill(sql,(byte)0); }
        }
        boolean spring = requirements.stream().filter(requirement -> !requirement.initializationFiles().isEmpty()).allMatch(DatabaseRequirement::springDatasource);
        if (assessment.schemaReviewRequired() && spring) entries.addAll(disabledSpringInitialization());
        Optional<DatabaseSchemaReview> review = assessment.schemaReviewRequired() ? Optional.of(new DatabaseSchemaReview(sourceDigest(root),
                bindings.stream().map(ManagedDatabaseBinding::databaseId).collect(java.util.stream.Collectors.toSet()),
                initialized,newInitialized,existingApproved,spring,sqliteInitializationPlanned)) : Optional.empty();
        return new AutomaticDatabasePreparation(entries,references,bindings,review);
    }

    private static ManagedDatabaseConnection.Sqlite sqliteConnection(String applicationId, DatabaseRequirement requirement,
            List<ConfigurationEntry> entries) {
        var file = requirement.sqlite().orElseThrow();
        var connection = new ManagedDatabaseConnection.Sqlite(file.fileName(), file.location(), file.accessPath(), file.seedFile(), requirement.initializationFiles());
        String path = connection.accessPath().isEmpty() ? connection.physicalPath(applicationId,requirement.id()) : connection.accessPath();
        connection.physicalPath(applicationId,requirement.id());
        if (!file.pathEnvironment().isEmpty() || requirement.springDatasource()) {
            String key = requirement.springDatasource() ? "SPRING_DATASOURCE_URL" : file.pathEnvironment();
            entries.add(entry(key, (requirement.springDatasource() ? "jdbc:sqlite:" : "") + path));
        }
        return connection;
    }

    private static void confirmSqliteInitialization(DatabaseRequirement requirement, AutomaticDeploymentInteraction interaction) throws Exception {
        if (!interaction.confirm("db.sqliteInitialization", Map.of("database",requirement.id(),
                "files",String.join(", ",requirement.initializationFiles())))) throw new CancellationException();
    }

    private static List<ConfigurationEntry> disabledSpringInitialization() {
        return List.of(entry("SPRING_SQL_INIT_MODE", "never"), entry("SPRING_FLYWAY_ENABLED", false),
                entry("SPRING_LIQUIBASE_ENABLED", false), entry("SPRING_JPA_HIBERNATE_DDL_AUTO", "none"),
                entry("SPRING_JPA_GENERATE_DDL", false));
    }

    private record TargetAccess(Target target, char[] administratorPassword) { }
    private TargetAccess inspectTarget(NativeDatabasePort port, Instance instance, String applicationId,
            DatabaseRequirement requirement, AutomaticDeploymentInteraction interaction) throws Exception {
        char[] password = new char[0];
        try {
            while (true) {
                try { return new TargetAccess(port.inspectDatabaseTarget(instance, applicationId, requirement.database(), requirement.username(), password), password); }
                catch (NativeDatabaseException failure) {
                    if (failure.reason() != NativeDatabaseFailureType.AUTH_REQUIRED) throw failure;
                    Arrays.fill(password,'\0'); password = interaction.requestSecret("db.adminPassword");
                }
            }
        } catch (Exception failure) { Arrays.fill(password,'\0'); throw failure; }
    }

    private record Credential(SecretReference reference, boolean stored, char[] password) { }
    private Credential credential(String identifier, boolean createdNow, AutomaticDeploymentInteraction interaction) throws Exception {
        var stored = credentials.latest(identifier);
        SecretReference reference = stored.orElseGet(() -> new SecretReference(identifier,1));
        char[] password = stored.isPresent() ? credentials.load(reference) : createdNow ? randomPassword() : interaction.requestSecret("db.applicationPassword");
        if (stored.isEmpty() && createdNow) credentials.save(reference,password.clone());
        return new Credential(reference,stored.isPresent() || createdNow,password);
    }

    private List<DatabaseRequirement> completeRequirements(DatabaseProjectInspector.Assessment assessment, String app,
            AutomaticDeploymentInteraction interaction) {
        List<DatabaseRequirement> requirements = new ArrayList<>(assessment.databases());
        if (assessment.unknownDatabase()) {
            var field = field("main","engine","",Arrays.stream(DatabaseEngineType.values()).map(Enum::name).toList());
            var answer = ask(List.of(field),interaction);
            requirements.add(new DatabaseRequirement("main",DatabaseEngineType.valueOf(answer.get(field.id())),"","","","","",List.of(),false,"user-selected database type"));
        }
        List<DeploymentInputField> fields = new ArrayList<>();
        boolean declaredInitialization = requirements.stream().anyMatch(requirement -> !requirement.initializationFiles().isEmpty());
        for (var requirement : requirements) {
            String id = requirement.id();
            if (requirement.engine() == DatabaseEngineType.SQLITE) {
                var file = requirement.sqlite().orElseThrow();
                if (file.location().type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED)
                    fields.add(field(id,"path","",List.of()));
                if (file.pathEnvironment().isEmpty() && !requirement.springDatasource() && file.accessPath().isEmpty())
                    fields.add(field(id,"pathEnvironment","",List.of()));
                if (assessment.schemaReviewRequired() && !declaredInitialization)
                    fields.add(field(id,"initialize","",initializationChoices(assessment, requirements.size()>1)));
                continue;
            }
            if (requirement.database().isEmpty()) fields.add(field(id,"database",generatedName(app,id),List.of()));
            if (requirement.username().isEmpty()) fields.add(field(id,"username",generatedName(app,id),List.of()));
            if (requirement.environmentPrefix().isEmpty()) fields.add(field(id,"environmentPrefix","",List.of()));
            if (requirement.passwordEnvironment().isEmpty()) fields.add(field(id,"passwordEnvironment","",List.of()));
            if (assessment.schemaReviewRequired() && !declaredInitialization && requirement.engine() != DatabaseEngineType.REDIS)
                fields.add(field(id,"initialize","",initializationChoices(assessment, requirements.size() > 1)));
        }
        Map<String,String> answers = ask(fields,interaction);
        while (true) {
            try { return validatedRequirements(requirements, answers, assessment); }
            catch (IllegalArgumentException invalid) {
                List<DeploymentInputField> correction = new ArrayList<>();
                Map<String,String> previousAnswers = answers;
                for (var requirement : requirements) {
                    if (requirement.engine() == DatabaseEngineType.SQLITE) {
                        var file = requirement.sqlite().orElseThrow();
                        correction.add(field(requirement.id(),"path",previousAnswers.getOrDefault("db/"+requirement.id()+"/path",file.accessPath().isEmpty() ? "DEFAULT" : file.accessPath()),List.of()));
                        correction.add(field(requirement.id(),"pathEnvironment",previousAnswers.getOrDefault("db/"+requirement.id()+"/pathEnvironment",file.pathEnvironment()),List.of()));
                        if (assessment.schemaReviewRequired()) correction.add(field(requirement.id(),"initialize",previousAnswers.getOrDefault("db/"+requirement.id()+"/initialize",String.join(",",requirement.initializationFiles())),initializationChoices(assessment,requirements.size()>1)));
                        continue;
                    }
                    Map<String,String> current = Map.of("database",requirement.database(),"username",requirement.username(),
                            "environmentPrefix",requirement.environmentPrefix(),"passwordEnvironment",requirement.passwordEnvironment());
                    current.forEach((key,value) -> correction.add(field(requirement.id(),key,
                            previousAnswers.getOrDefault("db/"+requirement.id()+"/"+key,value),List.of())));
                    if (assessment.schemaReviewRequired() && requirement.engine() != DatabaseEngineType.REDIS)
                        correction.add(field(requirement.id(),"initialize",answers.getOrDefault("db/"+requirement.id()+"/initialize",
                                String.join(",",requirement.initializationFiles())),initializationChoices(assessment, requirements.size() > 1)));
                }
                answers = ask(correction,interaction);
            }
        }
    }

    private static List<String> initializationChoices(DatabaseProjectInspector.Assessment assessment, boolean optional) {
        if (!optional || assessment.sqlCandidates().isEmpty()) return assessment.sqlCandidates();
        if (assessment.sqlCandidates().size() == 64) return List.of();
        var choices = new ArrayList<String>(); choices.add(""); choices.addAll(assessment.sqlCandidates());
        return List.copyOf(choices);
    }

    private static List<DatabaseRequirement> validatedRequirements(List<DatabaseRequirement> requirements, Map<String,String> answers,
            DatabaseProjectInspector.Assessment assessment) {
        List<DatabaseRequirement> completed = new ArrayList<>();
        for (var requirement : requirements) {
            String prefix = "db/"+requirement.id()+"/";
            String initialization = answers.getOrDefault(prefix+"initialize", "");
            Optional<SqliteFileRequirement> sqlite = requirement.sqlite().map(file -> {
                if (!answers.containsKey(prefix+"path") && !answers.containsKey(prefix+"pathEnvironment")) return file;
                var corrected = SqliteFileRequirement.fromPath(answers.getOrDefault(prefix+"path", file.location().type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.DEFAULT ? "DEFAULT" : file.accessPath()),
                        answers.getOrDefault(prefix+"pathEnvironment",file.pathEnvironment()),file.seedFile());
                if (file.hostLocationExplicit()) return new SqliteFileRequirement(file.location(),corrected.fileName(),corrected.accessPath(),corrected.pathEnvironment(),corrected.seedFile(),true);
                return corrected;
            });
            completed.add(new DatabaseRequirement(requirement.id(),requirement.engine(),requirement.version(),
                    answers.getOrDefault(prefix+"database",requirement.database()), answers.getOrDefault(prefix+"username",requirement.username()),
                    answers.getOrDefault(prefix+"environmentPrefix",requirement.environmentPrefix()),
                    answers.getOrDefault(prefix+"passwordEnvironment",requirement.passwordEnvironment()),
                    initialization.isEmpty() ? requirement.initializationFiles() : Arrays.stream(initialization.split(",")).map(String::trim).toList(),
                    requirement.springDatasource(),requirement.evidence(),sqlite));
        }
        if (completed.stream().anyMatch(requirement -> requirement.engine() == DatabaseEngineType.SQLITE
                ? requirement.sqlite().orElseThrow().location().type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED
                    || requirement.sqlite().orElseThrow().accessPath().isBlank() && requirement.sqlite().orElseThrow().pathEnvironment().isBlank() && !requirement.springDatasource()
                : requirement.database().isBlank() || requirement.username().isBlank()
                || requirement.environmentPrefix().isBlank() || requirement.passwordEnvironment().isBlank()))
            throw new IllegalArgumentException("database inputs must be complete before server mutation");
        var servers = completed.stream().filter(requirement -> requirement.engine() != DatabaseEngineType.SQLITE).toList();
        if (servers.stream().map(DatabaseRequirement::environmentPrefix).distinct().count() != servers.size()
                || servers.stream().map(DatabaseRequirement::passwordEnvironment).distinct().count() != servers.size())
            throw new IllegalArgumentException("database environment variables must be unique");
        if (assessment.schemaReviewRequired() && completed.stream().allMatch(requirement -> requirement.initializationFiles().isEmpty()))
            throw new IllegalArgumentException("database schema declarations require an explicit supported initialization file");
        return List.copyOf(completed);
    }

    private static List<ConfigurationEntry> configuration(DatabaseRequirement requirement, Instance instance, SecretReference secret) {
        List<ConfigurationEntry> result = new ArrayList<>(); String prefix = requirement.environmentPrefix();
        if (requirement.springDatasource()) result.add(entry(prefix+"_URL", "jdbc:"+requirement.engine().name().toLowerCase(Locale.ROOT)
                +"://127.0.0.1:"+instance.port()+"/"+requirement.database()));
        else {
            result.add(entry(prefix+"_HOST","127.0.0.1")); result.add(entry(prefix+"_PORT",instance.port()));
            result.add(entry(prefix+"_DATABASE",requirement.engine() == DatabaseEngineType.REDIS ? 0 : requirement.database()));
        }
        result.add(entry(prefix+"_USERNAME",requirement.username()));
        if (requirement.engine() == DatabaseEngineType.REDIS) result.add(entry(prefix+"_KEY_PREFIX",requirement.database()+":"));
        return List.copyOf(result);
    }

    private static byte[] initializationSql(Path root, List<String> paths) throws Exception {
        StringBuilder sql = new StringBuilder();
        for (String path : paths) {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 2 * 1024 * 1024)
                throw new IllegalArgumentException("initialization file must belong to the frozen source");
            sql.append(Files.readString(file)).append('\n');
        }
        byte[] result = sql.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > 2 * 1024 * 1024) throw new IllegalArgumentException("initialization exceeds size limit");
        return result;
    }
    static Map<String,String> ask(List<DeploymentInputField> fields, AutomaticDeploymentInteraction interaction) {
        if (fields.isEmpty()) return Map.of();
        Map<String,String> supplied = interaction.requestInputs(fields).orElseThrow(CancellationException::new);
        var result = new LinkedHashMap<String,String>();
        for (var field : fields) {
            String value = supplied.get(field.id());
            if (value == null || value.length() > 4096 || (!field.choices().isEmpty() && !field.choices().contains(value))) throw new IllegalArgumentException("Invalid database input");
            result.put(field.id(),value.trim());
        }
        return Map.copyOf(result);
    }
    private static String sourceDigest(Path root) {
        List<RejectionReason> rejected = new ArrayList<>();
        var facts = new BoundedSourceInspector().inspect(root,rejected);
        if (!rejected.isEmpty()) throw new IllegalArgumentException("source changed during database preparation");
        return SourceMutationPolicy.inspectionDigest(facts);
    }
    private static DeploymentInputField field(String id, String key, String value, List<String> choices) {
        return new DeploymentInputField("db/"+id+"/"+key,"db.field."+key,"db.help."+key,value,choices);
    }
    private static ConfigurationEntry entry(String key, Object value) {
        return new ConfigurationEntry(key,ConfigurationScope.RUNTIME,value instanceof Number number ? new ConfigurationValue.Number(number.longValue())
                : value instanceof Boolean flag ? new ConfigurationValue.Flag(flag) : new ConfigurationValue.Text(value.toString()));
    }
    private static String generatedName(String app, String id) {
        String stem = (app+"_"+id).replace('-','_');
        if (!Character.isLetter(stem.charAt(0))) stem = "app_"+stem;
        return stem.substring(0,Math.min(20,stem.length()))+"_"+digest(app+"/"+id).substring(0,8);
    }
    private static char[] randomPassword() {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray(); }
        finally { Arrays.fill(random,(byte)0); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
