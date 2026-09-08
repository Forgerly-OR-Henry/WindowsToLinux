package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationUseCase;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.server.*;
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

/** Prepares application-scoped native databases and immutable credentials before the publishing transaction. */
public final class AutomaticDatabaseUseCase {
    private final ServerUseCaseFacade servers;
    private final DeploymentLinuxGateway gateway;
    private final ApplicationSecretRepository secretMetadata;
    private final DesktopSecretStoreService stores;
    private final DeploymentConfigurationUseCase configurations;
    private final AutomaticInputCompletion completion;

    public AutomaticDatabaseUseCase(ServerUseCaseFacade servers, DeploymentLinuxGateway gateway,
            ApplicationSecretRepository secretMetadata, DesktopSecretStoreService stores,
            DeploymentConfigurationUseCase configurations, AiApplicationFacade ai) {
        this.servers = servers; this.gateway = gateway; this.secretMetadata = secretMetadata; this.stores = stores;
        this.configurations = configurations; this.completion = new AutomaticInputCompletion(ai);
    }

    /** Completes and validates source-side DB inputs before any connection or environment change. */
    public DatabaseProjectInspector.Assessment completeInputs(Path root, String applicationId,
            DatabaseProjectInspector.Assessment assessment, char[] master, AutomaticDeploymentInteraction interaction) throws Exception {
        try {
            List<DatabaseRequirement> requirements = completeRequirements(assessment, applicationId, master, interaction);
            for (var requirement : requirements) {
                byte[] sql = initializationSql(root, requirement.initializationFiles());
                Arrays.fill(sql, (byte) 0);
            }
            return new DatabaseProjectInspector.Assessment(requirements, assessment.sqlCandidates(),
                    assessment.schemaReviewRequired(), false, assessment.endpointConfirmationRequired());
        } finally { Arrays.fill(master, '\0'); }
    }

    public AutomaticDatabasePreparation prepare(Path root, String applicationId, ServerProfile profile,
            DatabaseProjectInspector.Assessment assessment, char[] master, AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception {
        try {
            if (assessment.unknownDatabase()) throw new IllegalArgumentException("complete database inputs before server preparation");
            List<DatabaseRequirement> requirements = validatedRequirements(assessment.databases(), Map.of(), assessment);
            if (requirements.isEmpty()) return AutomaticDatabasePreparation.empty();
            List<ConfigurationEntry> entries = new ArrayList<>(); List<SecretReference> references = new ArrayList<>();
            List<ManagedDatabaseBinding> bindings = new ArrayList<>(); Set<String> initialized = new LinkedHashSet<>();
            boolean newInitialized = false, existingApproved = false;
            try (var store = stores.open(profile.credentialMode(), master);
                 var session = gateway.connect(profile.endpoint(), servers.loadPassword(profile, store), servers.hostKeyVerifier(profile, fingerprint))) {
                NativeDatabasePort port = session.nativeDatabases();
                for (DatabaseRequirement requirement : requirements) {
                    byte[] sql = initializationSql(root, requirement.initializationFiles());
                    char[] admin = new char[0], password = new char[0];
                    try {
                        Instance instance = DatabaseInstanceResolver.resolve(port, profile.id(), requirement, interaction, progress);
                        TargetAccess access = inspectTarget(port, instance, applicationId, requirement, interaction);
                        Target target = access.target(); admin = access.administratorPassword();
                        boolean createdNow = !target.exists();
                        String environment = requirement.passwordEnvironment();
                        String identifier = "db-" + digest(profile.id()+"/"+applicationId+"/"+requirement.id()).substring(0,12)
                                + ".env." + environment.toLowerCase(Locale.ROOT).replace('_','-');
                        Credential selectedCredential = credential(identifier, createdNow, profile, master, interaction);
                        SecretReference reference = selectedCredential.reference();
                        Optional<StoredApplicationSecretRevision> stored = selectedCredential.stored();
                        password = selectedCredential.password();
                        progress.accept(LocalizedMessage.of(createdNow ? "db.creating" : "db.connecting", "database", requirement.id()));
                        while (true) {
                            try {
                                target = port.prepareDatabaseTarget(instance, applicationId, requirement.database(), requirement.username(),
                                        reference.identifier(), reference.revision(), password, admin);
                                break;
                            } catch (DatabaseFailure failure) {
                                if (failure.reason() != FailureType.AUTH_REQUIRED || createdNow) throw failure;
                                Arrays.fill(password,'\0'); password = interaction.requestSecret("db.applicationPassword");
                                if (stored.isPresent()) { reference = new SecretReference(identifier,reference.revision()+1); stored = Optional.empty(); }
                            }
                        }
                        if (!createdNow && stored.isEmpty()) saveCredential(reference,profile,master,password);
                        if (sql.length > 0) {
                            progress.accept(LocalizedMessage.of("db.initializing", "database", requirement.id()));
                            try { target = port.initializeDatabase(target, sourceDigest(root), sql, password, false); }
                            catch (DatabaseFailure failure) {
                                if (failure.reason() != FailureType.STATE_CHANGED || createdNow) throw failure;
                                if (!interaction.confirm("db.existingSchema", Map.of("database", requirement.database(),
                                        "files", String.join(", ", requirement.initializationFiles())))) throw new CancellationException();
                                target = port.initializeDatabase(target, sourceDigest(root), sql, password, true);
                                existingApproved = true;
                            }
                            if (target.initialization() != InitializationState.COMPLETE) throw new DatabaseFailure(FailureType.INITIALIZATION_FAILED);
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
            }
            boolean spring = requirements.stream().filter(requirement -> !requirement.initializationFiles().isEmpty()).allMatch(DatabaseRequirement::springDatasource);
            if (assessment.schemaReviewRequired() && spring) entries.addAll(disabledSpringInitialization());
            Optional<DatabaseSchemaReview> review = assessment.schemaReviewRequired() ? Optional.of(new DatabaseSchemaReview(sourceDigest(root),
                    bindings.stream().map(ManagedDatabaseBinding::databaseId).collect(java.util.stream.Collectors.toSet()),
                    initialized,newInitialized,existingApproved,spring)) : Optional.empty();
            return new AutomaticDatabasePreparation(entries,references,bindings,review);
        } finally { Arrays.fill(master,'\0'); }
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
                catch (DatabaseFailure failure) {
                    if (failure.reason() != FailureType.AUTH_REQUIRED) throw failure;
                    Arrays.fill(password,'\0'); password = interaction.requestSecret("db.adminPassword");
                }
            }
        } catch (Exception failure) { Arrays.fill(password,'\0'); throw failure; }
    }

    private record Credential(SecretReference reference, Optional<StoredApplicationSecretRevision> stored, char[] password) { }
    private Credential credential(String identifier, boolean createdNow, ServerProfile profile, char[] master,
            AutomaticDeploymentInteraction interaction) throws Exception {
        char[] password;
        SecretReference reference = new SecretReference(identifier,1);
        Optional<StoredApplicationSecretRevision> stored = secretMetadata.findRevision(reference);
        while (stored.isPresent() && secretMetadata.findRevision(new SecretReference(identifier, reference.revision()+1)).isPresent()) {
            reference = new SecretReference(identifier, reference.revision()+1);
            stored = secretMetadata.findRevision(reference);
        }
        if (stored.isPresent()) {
            try (var secretStore = stores.open(stored.orElseThrow().credentialMode(), master)) {
                password = secretStore.read(stored.orElseThrow().credentialKey()).orElseThrow(
                        () -> new IllegalStateException("saved DB credential is missing; restore the credential before retrying"));
            }
        } else {
            password = createdNow ? randomPassword() : interaction.requestSecret("db.applicationPassword");
            if (createdNow) saveCredential(reference,profile,master,password);
        }
        return new Credential(reference, stored, password);
    }

    private List<DatabaseRequirement> completeRequirements(DatabaseProjectInspector.Assessment assessment, String app,
            char[] master, AutomaticDeploymentInteraction interaction) {
        List<DatabaseRequirement> requirements = new ArrayList<>(assessment.databases());
        if (assessment.unknownDatabase()) {
            var field = field("main","engine","",Arrays.stream(DatabaseEngineType.values()).map(Enum::name).toList());
            var answer = completion.resolve(List.of(field),master,interaction);
            requirements.add(new DatabaseRequirement("main",DatabaseEngineType.valueOf(answer.get(field.id())),"","","","","",List.of(),false,"user-selected database type"));
        }
        List<DeploymentInputField> fields = new ArrayList<>();
        boolean declaredInitialization = requirements.stream().anyMatch(requirement -> !requirement.initializationFiles().isEmpty());
        for (var requirement : requirements) {
            String id = requirement.id();
            if (requirement.database().isEmpty()) fields.add(field(id,"database",generatedName(app,id),List.of()));
            if (requirement.username().isEmpty()) fields.add(field(id,"username",generatedName(app,id),List.of()));
            if (requirement.environmentPrefix().isEmpty()) fields.add(field(id,"environmentPrefix","",List.of()));
            if (requirement.passwordEnvironment().isEmpty()) fields.add(field(id,"passwordEnvironment","",List.of()));
            if (assessment.schemaReviewRequired() && !declaredInitialization && requirement.engine() != DatabaseEngineType.REDIS)
                fields.add(field(id,"initialize","",initializationChoices(assessment, requirements.size() > 1)));
        }
        Map<String,String> answers = completion.resolve(fields,master,interaction);
        while (true) {
            try { return validatedRequirements(requirements, answers, assessment); }
            catch (IllegalArgumentException invalid) {
                List<DeploymentInputField> correction = new ArrayList<>();
                Map<String,String> previousAnswers = answers;
                for (var requirement : requirements) {
                    Map<String,String> current = Map.of("database",requirement.database(),"username",requirement.username(),
                            "environmentPrefix",requirement.environmentPrefix(),"passwordEnvironment",requirement.passwordEnvironment());
                    current.forEach((key,value) -> correction.add(field(requirement.id(),key,
                            previousAnswers.getOrDefault("db/"+requirement.id()+"/"+key,value),List.of())));
                    if (assessment.schemaReviewRequired() && requirement.engine() != DatabaseEngineType.REDIS)
                        correction.add(field(requirement.id(),"initialize",answers.getOrDefault("db/"+requirement.id()+"/initialize",
                                String.join(",",requirement.initializationFiles())),initializationChoices(assessment, requirements.size() > 1)));
                }
                answers = AutomaticInputCompletion.ask(correction,interaction);
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
            completed.add(new DatabaseRequirement(requirement.id(),requirement.engine(),requirement.version(),
                    answers.getOrDefault(prefix+"database",requirement.database()), answers.getOrDefault(prefix+"username",requirement.username()),
                    answers.getOrDefault(prefix+"environmentPrefix",requirement.environmentPrefix()),
                    answers.getOrDefault(prefix+"passwordEnvironment",requirement.passwordEnvironment()),
                    initialization.isEmpty() ? requirement.initializationFiles() : Arrays.stream(initialization.split(",")).map(String::trim).toList(),
                    requirement.springDatasource(),requirement.evidence()));
        }
        if (completed.stream().anyMatch(requirement -> requirement.database().isBlank() || requirement.username().isBlank()
                || requirement.environmentPrefix().isBlank() || requirement.passwordEnvironment().isBlank()))
            throw new IllegalArgumentException("database inputs must be complete before server mutation");
        if (completed.stream().map(DatabaseRequirement::environmentPrefix).distinct().count() != completed.size()
                || completed.stream().map(DatabaseRequirement::passwordEnvironment).distinct().count() != completed.size())
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
    private void saveCredential(SecretReference reference, ServerProfile profile, char[] master, char[] password) throws Exception {
        configurations.saveSecretRevision(new StoredApplicationSecretRevision(reference,
                "application-secret/"+reference.identifier()+"/"+reference.revision(),profile.credentialMode(),Instant.now()),
                profile.credentialMode(),master.clone(),password.clone());
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
