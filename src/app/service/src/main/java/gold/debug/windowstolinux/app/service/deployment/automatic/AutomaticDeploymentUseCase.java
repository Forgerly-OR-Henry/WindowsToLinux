package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.definition.DatabaseReviewMode;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.source.*;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationParser;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.shared.analyze.component.*;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.health.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Executes one click-time operation, pausing only for missing inputs and concrete risk decisions. / 执行一次点击触发的操作，仅因缺失输入或具体风险决策暂停。 */
public final class AutomaticDeploymentUseCase {
    private final AutomaticDeploymentApplicationFacade service;
    private final SourcePreparationUseCase source;
    private final ServerOperationLockRegistry locks;
    private final AutomaticRuntimeResolver runtime = new AutomaticRuntimeResolver();

    /** Binds the existing reviewed operations and shared server lock. / 绑定既有审阅操作和共享服务器锁。 */
    public AutomaticDeploymentUseCase(AutomaticDeploymentApplicationFacade service, SourcePreparationUseCase source,
                                      ServerOperationLockRegistry locks) {
        this.service = service; this.source = source; this.locks = locks;
    }

    /** Freezes the source, resolves its runtime, and executes a single reviewed transaction. / 冻结源码、解析运行时并执行一次已审阅事务。 */
    public AutomaticDeploymentOutcome deploy(AutomaticDeploymentRequest request, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception {
        var lock = locks.forServer(request.server().id());
        if (!lock.tryLock()) { Arrays.fill(master, '\0'); throw new IllegalStateException("server already has an active operation"); }
        try {
            progress.accept(LocalizedMessage.of("auto.progress.snapshot"));
            if (request.git().isPresent()) {
                GitSnapshot git = source.snapshotGit(request.git().orElseThrow());
                try (SourceDirectorySnapshot snapshot = source.snapshot(git)) {
                    return execute(request, snapshot.directory(), Optional.of(git), master, interaction, fingerprint, progress);
                }
            }
            try (SourceDirectorySnapshot snapshot = source.snapshot(request.directory().orElseThrow())) {
                return execute(request, snapshot.directory(), Optional.empty(), master, interaction, fingerprint, progress);
            }
        } finally { Arrays.fill(master, '\0'); lock.unlock(); }
    }

    private AutomaticDeploymentOutcome execute(AutomaticDeploymentRequest request, Path root, Optional<GitSnapshot> git,
            char[] master, AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress) throws Exception {
        var resolved = resolveInputs(request, root, master, interaction, progress);
        var discovered = resolved.discovered(); var preparations = resolved.preparations();
        var databaseAssessments = resolved.databases(); var inputs = resolved.inputs();
        progress.accept(LocalizedMessage.of("auto.progress.server"));
        service.verifyServer(request.server(), request.server().credentialMode(), master.clone(), fingerprint);
        var server = service.findTrustedServer(request.server().id()).orElseThrow();
        return discovered.size() == 1 ? deploySingle(request, root, git, master, interaction, fingerprint, progress, resolved, server)
                : deployMultiple(request, root, git, master, interaction, fingerprint, progress, resolved, server);
    }

    private record ResolvedInputs(List<DiscoveredProjectComponent> discovered,
            Map<String, ReviewedSourcePreparation> preparations,
            Map<String, gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databases,
            Map<String, Map<String,String>> inputs, String healthOwner) { }

    private ResolvedInputs resolveInputs(AutomaticDeploymentRequest request, Path root, char[] master,
            AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        progress.accept(LocalizedMessage.of("auto.progress.discovery"));
        List<DiscoveredProjectComponent> discovered = new ProjectComponentDiscovery().discover(root);
        Map<String, String> selection = new LinkedHashMap<>();
        List<DeploymentInputField> questions = new ArrayList<>();
        for (var component : discovered) {
            List<String> types = component.types().stream().map(Enum::name).toList();
            String override = request.overrides().getOrDefault(component.id() + "/type",
                    discovered.size() == 1 ? request.overrides().getOrDefault("type", "") : "");
            if (!override.isBlank()) selection.put(component.id() + "/type", override);
            else if (types.size() == 1) selection.put(component.id() + "/type", types.getFirst());
            else questions.add(AutomaticRuntimeResolver.field(component.id(), "type", "", types));
        }
        answerWithAi(questions, selection, interaction, master);
        Map<String, ReviewedSourcePreparation> preparations = new LinkedHashMap<>();
        Map<String, gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databaseAssessments = new LinkedHashMap<>();
        Map<String, Map<String, String>> inputs = new LinkedHashMap<>();
        questions.clear();
        for (var component : discovered) {
            DeploymentProjectType type = DeploymentProjectType.valueOf(selection.get(component.id() + "/type"));
            ReviewedSourcePreparation prepared = source.prepareAutomatic(root.resolve(component.relativeRoot()), type);
            if (prepared.assessment().facts().isEmpty() || prepared.assessment().facts().orElseThrow().missingInformation().stream()
                    .anyMatch(message -> !message.key().equals("analysis.db.reviewRequired"))
                    || !prepared.assessment().facts().orElseThrow().conflicts().isEmpty()) throw new IllegalArgumentException("source cannot be deployed: "
                    + prepared.assessment().rejections().stream().map(value -> value.code()).toList());
            preparations.put(component.id(), prepared);
            databaseAssessments.put(component.id(), new gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector()
                    .inspect(root.resolve(component.relativeRoot())));
            Map<String, String> values = runtime.defaults(type, prepared.assessment().runtimeSuggestion().orElse(null),
                    root.resolve(component.relativeRoot()));
            request.overrides().forEach((key, value) -> {
                if (!value.isBlank() && !key.contains("/")) values.put(key, value);
                if (key.startsWith(component.id() + "/") && !value.isBlank()) values.put(key.substring(component.id().length() + 1), value);
            });
            values.put("type", type.name());
            inputs.put(component.id(), values);
            questions.addAll(runtime.missing(component.id(), type, values));
            if (discovered.size() > 1 && !values.containsKey("dependencies"))
                questions.add(AutomaticRuntimeResolver.field(component.id(), "dependencies", "", List.of()));
        }
        Map<String, String> answers = new LinkedHashMap<>();
        answerWithAi(questions, answers, interaction, master);
        answers.forEach((key, value) -> { String[] parts = key.split("/", 2); inputs.get(parts[0]).put(parts[1], value); });
        validateInputs(inputs, preparations, databaseAssessments, request.server().host(), interaction, progress);
        String healthOwner = discovered.size() == 1 ? discovered.getFirst().id()
                : healthOwner(discovered.stream().map(DiscoveredProjectComponent::id).toList(), inputs, interaction);
        for (var component : discovered) {
            var assessment = databaseAssessments.get(component.id());
            var values = inputs.get(component.id());
            if ((!assessment.databases().isEmpty() || assessment.unknownDatabase()) && !values.containsKey("databaseMode")) {
                if (assessment.endpointConfirmationRequired() && !interaction.confirm("db.nativeEndpoint", Map.of())) throw new CancellationException();
                databaseAssessments.put(component.id(), service.completeAutomaticDatabaseInputs(root.resolve(component.relativeRoot()),
                        preparations.get(component.id()).assessment().facts().orElseThrow().applicationId(), assessment, master.clone(), interaction));
            }
        }
        return new ResolvedInputs(discovered, preparations, databaseAssessments, inputs, healthOwner);
    }

    private void validateInputs(Map<String, Map<String,String>> inputs, Map<String, ReviewedSourcePreparation> preparations,
            Map<String, gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databases,
            String host, AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) {
        for (var entry : inputs.entrySet()) {
            Map<String, String> values = entry.getValue();
            while (true) {
                try {
                    if (values.getOrDefault("databaseMode", "").equals("UNREVIEWED")) values.remove("databaseMode");
                    var assessment = databases.get(entry.getKey());
                    String mode = values.get("databaseMode");
                    if (mode != null && (!assessment.databases().isEmpty() || assessment.unknownDatabase())
                            && (mode.equals("NONE") || assessment.databases().size() > 1 || assessment.databases().stream()
                            .anyMatch(database -> !database.engine().name().equals(mode))))
                        throw new IllegalArgumentException("explicit database scope conflicts with source declarations");
                    runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values);
                    runtime.access(values, host);
                    configuration(preparations.get(entry.getKey()).assessment().facts().orElseThrow().applicationId(), values);
                    secrets(values, AutomaticDatabasePreparation.empty());
                    bindings(values, AutomaticDatabasePreparation.empty());
                    limits(values);
                    break;
                }
                catch (IllegalArgumentException invalid) {
                    progress.accept(LocalizedMessage.of("auto.progress.invalid", "component", entry.getKey()));
                    Map<String, String> corrected = new LinkedHashMap<>();
                    answer(runtime.corrections(entry.getKey(), values), corrected, interaction);
                    corrected.forEach((key, value) -> values.put(key.split("/", 2)[1], value));
                    for (String key : List.of("healthEndpoint", "accessUrl", "configuration", "secrets", "databaseDetails",
                            "jvmArguments", "arguments", "volumes")) {
                        if (values.getOrDefault(key, "").isBlank()) values.remove(key);
                    }
                }
            }
            if (preparations.get(entry.getKey()).assessment().facts().orElseThrow().support().level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER
                    && !Boolean.parseBoolean(values.getOrDefault("experimentalAdapterRisk", "false"))
                    && !interaction.confirm("auto.risk.experimental", Map.of("component", entry.getKey()))) throw new CancellationException();
            if (values.get("type").equals("DOCKERFILE_CONTAINER") && values.getOrDefault("containerEngine", "PODMAN").equals("DOCKER")
                    && !interaction.confirm("auto.risk.docker", Map.of("component", entry.getKey()))) throw new CancellationException();
        }
    }

    private AutomaticDeploymentOutcome deploySingle(AutomaticDeploymentRequest request, Path root, Optional<GitSnapshot> git,
            char[] master, AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint, Consumer<LocalizedMessage> progress,
            ResolvedInputs resolved, gold.debug.windowstolinux.shared.model.server.ServerIdentity server) throws Exception {
        var discovered = resolved.discovered(); var preparations = resolved.preparations();
        var databaseAssessments = resolved.databases(); var inputs = resolved.inputs();
            String component = discovered.getFirst().id();
            var prepared = preparations.get(component);
            Map<String, String> values = inputs.get(component);
            String id = prepared.assessment().facts().orElseThrow().applicationId();
            var config = configuration(id, values);
            // Validate local inputs before preparing any database or environment. / 在准备任何数据库或环境前验证本地输入。
            runtime.access(values, server.host()); limits(values);
            var db = prepareDatabases(root.resolve(discovered.getFirst().relativeRoot()), id, databaseAssessments.get(component),
                    request, values, master, interaction, fingerprint, progress);
            if (db.schemaReview().isPresent()) prepared = source.prepareWithDatabaseReview(root.resolve(discovered.getFirst().relativeRoot()),
                    DeploymentProjectType.valueOf(values.get("type")), db.schemaReview().orElseThrow());
            prepared = withGit(prepared, git);
            config = withDatabases(config, db);
            var reviewed = service.createReviewedDeploymentRequest(prepared, server, config,
                    secrets(values, db), bindings(values, db),
                    runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values), runtime.access(values, server.host()),
                    limits(values), false, true, true);
            service.planDeployment(reviewed);
            service.saveDeploymentConfigurationSnapshot(config);
            progress.accept(LocalizedMessage.of("auto.progress.environment"));
            if (db.bindings().isEmpty()) prepareEnvironment(request, master, fingerprint, interaction);
            progress.accept(LocalizedMessage.of("auto.progress.deploy"));
            var outcome = service.deployAutomaticallyReviewed(reviewed, request.server(), master.clone(), fingerprint, progress);
            return new AutomaticDeploymentOutcome(id, outcome.status(), outcome.handoff().map(value -> Map.of(component, value)).orElse(Map.of()));
    }

    private AutomaticDeploymentOutcome deployMultiple(AutomaticDeploymentRequest request, Path root, Optional<GitSnapshot> git,
            char[] master, AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint, Consumer<LocalizedMessage> progress,
            ResolvedInputs resolved, gold.debug.windowstolinux.shared.model.server.ServerIdentity server) throws Exception {
        var discovered = resolved.discovered(); var preparations = resolved.preparations();
        var databaseAssessments = resolved.databases(); var inputs = resolved.inputs();
        String applicationId = request.directory().map(path -> path.getFileName().toString()).orElseGet(() -> {
            String path = request.git().orElseThrow().remote().location().getPath();
            return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        }).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("application name must be a bounded identifier");
        List<ComponentAnalysisRequest> components = new ArrayList<>();
        for (var component : discovered) {
            var values = inputs.get(component.id());
            DeploymentRuntimeSpecification specification = runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values);
            String path = component.relativeRoot().toString().replace('\\', '/');
            Set<String> dependencies = new LinkedHashSet<>();
            for (String dependency : values.getOrDefault("dependencies", "").split("[,;\\s]+")) if (!dependency.isBlank()) dependencies.add(dependency);
            components.add(new ComponentAnalysisRequest(component.id(), path.isBlank() ? "." : path,
                    specification.projectType(), Optional.of(specification),
                    runtime.artifacts(component.relativeRoot(), preparations.get(component.id()).assessment().facts().orElseThrow(), values),
                    Set.of(Integer.parseInt(values.get("port"))),
                    List.of(), List.of(), List.of(), dependencies, true, ComponentIsolationSpecification.managed()));
        }
        var graph = new MixedProjectInspector().analyzeAutomatic(root, applicationId, components);
        if (graph.issues().stream().anyMatch(issue -> !issue.code().equals("COMPONENT_REQUIRES_INPUT")))
            throw new IllegalArgumentException("component graph requires correction: " + graph.issues().stream().map(issue -> issue.code()).toList());
        String healthOwner = resolved.healthOwner();
        Map<String, AutomaticDatabasePreparation> databases = new LinkedHashMap<>();
        Map<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> databaseReviews = new LinkedHashMap<>();
        for (var component : graph.components()) {
            var db = prepareDatabases(component.sourceRoot(), component.facts().applicationId(), databaseAssessments.get(component.componentId()),
                    request, inputs.get(component.componentId()), master, interaction, fingerprint, progress);
            databases.put(component.componentId(), db);
            db.schemaReview().ifPresent(review -> databaseReviews.put(component.componentId(), review));
        }
        var prepared = source.prepareMultiComponent(root, applicationId, components, databaseReviews);
        if (prepared.components().isEmpty()) throw new IllegalArgumentException("component graph requires correction");
        if (git.isPresent()) {
            Map<String, PreparedComponentSource> pinned = new LinkedHashMap<>();
            prepared.components().forEach((id, value) -> pinned.put(id, new PreparedComponentSource(id, value.facts(), value.archive(),
                    new SourceRevision(value.archive().contentSha256(), Optional.of(git.orElseThrow().commit()),
                            Optional.of(git.orElseThrow().remote().location()), Map.of()), value.excludedEntries())));
            prepared = new PreparedMultiComponentSource(prepared.assessment(), pinned);
        }
        List<MultiComponentReviewInput> reviews = new ArrayList<>();
        for (var component : prepared.assessment().components()) {
            var values = inputs.get(component.componentId());
            var db = databases.get(component.componentId());
            var config = withDatabases(configuration(component.facts().applicationId(), values), db);
            reviews.add(new MultiComponentReviewInput(component.componentId(), config,
                    secrets(values, db), bindings(values, db), runtime.access(values, server.host()), limits(values), true, true));
        }
        var reviewed = service.createReviewedMultiComponentApplication(prepared, server, reviews,
                new ApplicationHealthGate(healthOwner, runtime.health(inputs.get(healthOwner))));
        for (var input : reviews) service.saveDeploymentConfigurationSnapshot(input.configuration());
        progress.accept(LocalizedMessage.of("auto.progress.environment"));
        prepareEnvironment(request, master, fingerprint, interaction);
        progress.accept(LocalizedMessage.of("auto.progress.deploy"));
        var result = service.deployAutomaticallyReviewed(reviewed, request.server(), master.clone(), fingerprint, progress);
        Map<String, DeploymentHandoff> handoffs = new LinkedHashMap<>();
        if (result.status() == DeploymentStatus.SUCCEEDED) for (var component : reviewed.components()) {
            var access = component.request().userAccessUrl();
            handoffs.put(component.componentId(), access.isPresent() ? new DeploymentHandoff.HttpAccessUrl(access.orElseThrow().url())
                    : new DeploymentHandoff.SystemdStartCommand(component.application().id(), component.application().systemdUnit(), component.application().ownershipManifestSha256()));
        }
        return new AutomaticDeploymentOutcome(applicationId, result.status(), handoffs);
    }


    private static void answer(List<DeploymentInputField> fields, Map<String, String> values, AutomaticDeploymentInteraction interaction) {
        values.putAll(AutomaticInputCompletion.ask(fields, interaction));
    }

    private static String healthOwner(List<String> components, Map<String, Map<String, String>> inputs,
                                      AutomaticDeploymentInteraction interaction) {
        List<String> owners = components.stream().filter(id -> inputs.get(id).get("healthMode").equals("HTTP")).toList();
        if (owners.size() == 1) return owners.getFirst();
        var field = AutomaticRuntimeResolver.field("application", "healthOwner", "",
                components);
        return AutomaticInputCompletion.ask(List.of(field), interaction).get(field.id());
    }

    private AutomaticDatabasePreparation prepareDatabases(Path root, String id,
            gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment, AutomaticDeploymentRequest request,
            Map<String,String> values, char[] master, AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint,
            Consumer<LocalizedMessage> progress) throws Exception {
        if (assessment.databases().isEmpty() && !assessment.unknownDatabase()) return AutomaticDatabasePreparation.empty();
        if (!values.getOrDefault("databaseMode", "NONE").equals("NONE")) {
            if (assessment.schemaReviewRequired()) throw new IllegalArgumentException("schema initialization requires an automatic DB declaration or a separately reviewed schema change");
            return AutomaticDatabasePreparation.empty();
        }
        if (values.get("type").equals("DOCKERFILE_CONTAINER")) throw new IllegalArgumentException(
                "native database access from isolated containers requires an explicit reachable database binding; configure the DB binding in advanced options");
        progress.accept(LocalizedMessage.of("auto.progress.environment"));
        prepareEnvironment(request, master, fingerprint, interaction);
        return service.prepareAutomaticDatabases(root, id, request.server(), assessment, master.clone(), interaction, fingerprint, progress);
    }

    private void prepareEnvironment(AutomaticDeploymentRequest request, char[] master,
            Predicate<String> fingerprint, AutomaticDeploymentInteraction interaction) throws Exception {
        if (!interaction.confirm("environment.confirm", Map.of("serverId", request.server().id(),
                "host", request.server().host(), "port", request.server().sshPort(),
                "username", request.server().username()))) throw new CancellationException();
        service.prepareEnvironmentWithStoredPassword(request.server(), request.server().credentialMode(),
                master.clone(), fingerprint, true, plan -> interaction.confirm("environment.system.confirm", Map.of(
                        "serverId", request.server().id(), "host", request.server().host(),
                        "security", plan.securityState().name(), "reboot",
                        plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.UNPREPARED
                                || plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.REBOOT_PENDING)));
    }
    private static ConfigurationSnapshot withDatabases(ConfigurationSnapshot config, AutomaticDatabasePreparation db) {
        var entries = new LinkedHashMap<String, gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry>();
        config.entries().forEach(entry -> entries.put(entry.key(), entry));
        db.configuration().forEach(entry -> {
            if (entries.containsKey(entry.key()) && !entries.get(entry.key()).equals(entry))
                throw new IllegalArgumentException("advanced configuration conflicts with verified DB input: " + entry.key());
            entries.put(entry.key(), entry);
        });
        return ConfigurationSnapshot.create(config.applicationId(), config.revision(), config.schemaVersion(), config.createdAt(), List.copyOf(entries.values()));
    }

    private static List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> secrets(Map<String,String> values, AutomaticDatabasePreparation db) {
        var result = new ArrayList<>(gold.debug.windowstolinux.app.service.config.DeploymentConfigurationParser.secrets(values.getOrDefault("secrets", ""))); result.addAll(db.secrets());
        return List.copyOf(result);
    }

    private static Optional<List<gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding>> bindings(Map<String,String> values, AutomaticDatabasePreparation db) {
        return db.bindings().isEmpty() ? DeploymentRuntimeParser.databaseBindings(DatabaseReviewMode.valueOf(
                values.getOrDefault("databaseMode", "NONE")), values.getOrDefault("databaseDetails", "")) : Optional.of(db.bindings());
    }

    private void answerWithAi(List<DeploymentInputField> fields, Map<String, String> values,
                              AutomaticDeploymentInteraction interaction, char[] master) {
        values.putAll(new AutomaticInputCompletion(service).resolve(fields, master, interaction));
    }

    private static ReviewedSourcePreparation withGit(ReviewedSourcePreparation prepared, Optional<GitSnapshot> git) {
        if (git.isEmpty()) return prepared;
        return new ReviewedSourcePreparation(prepared.assessment(), prepared.archive(), Optional.of(new SourceRevision(
                prepared.archive().orElseThrow().contentSha256(), Optional.of(git.orElseThrow().commit()),
                Optional.of(git.orElseThrow().remote().location()), Map.of())), prepared.excludedEntries());
    }

    private static ConfigurationSnapshot configuration(String id, Map<String, String> values) {
        var entries = new ArrayList<>(DeploymentConfigurationParser.parse(values.getOrDefault("configuration", "")));
        String portKey = values.get("type").equals("SPRING_BOOT") ? "SERVER_PORT" : "PORT";
        if (entries.stream().noneMatch(entry -> entry.key().equals(portKey))) entries.add(
                new gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry(portKey,
                        gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope.RUNTIME,
                        new gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue.Number(Long.parseLong(values.get("port")))));
        return ConfigurationSnapshot.create(id, Instant.now().toEpochMilli(), "runtime-v1", Instant.now(), entries);
    }

    private static BuildLimitConfiguration limits(Map<String, String> values) {
        if (Boolean.parseBoolean(values.getOrDefault("rootBuild", "false"))) {
            throw new IllegalArgumentException("Root builds are not supported");
        }
        return BuildLimitConfiguration.defaultNonRoot();
    }
}
