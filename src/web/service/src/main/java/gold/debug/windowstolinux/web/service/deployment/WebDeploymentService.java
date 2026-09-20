package gold.debug.windowstolinux.web.service.deployment;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.analyze.component.*;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.backup.format.*;
import gold.debug.windowstolinux.shared.config.contract.definition.*;
import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.*;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.execution.environment.EnvironmentSetupService;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.*;
import gold.debug.windowstolinux.shared.deploy.input.*;
import gold.debug.windowstolinux.shared.deploy.plan.*;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.managed.*;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/** Web composition for deterministic analysis and the existing shared publication transactions. */
public final class WebDeploymentService {
    private final WebServerService servers;
    private final WebSourceService sources;
    private final WebApplicationInventory applications;
    private final WebApplicationSecrets secrets;
    private final gold.debug.windowstolinux.web.service.ai.WebAiService ai;
    private final AutomaticRuntimeResolver runtime = new AutomaticRuntimeResolver();
    public WebDeploymentService(WebServerService servers, WebSourceService sources, WebApplicationInventory applications, WebApplicationSecrets secrets,gold.debug.windowstolinux.web.service.ai.WebAiService ai) {
        this.servers = servers; this.sources = sources; this.applications = applications; this.secrets = secrets;this.ai=ai;
    }
    public PreparedWebOperation prepare(WebRequestContext context, JsonNode input) throws Exception {
        WebJson.fields(input, "serverId", "sourceId", "overrides");
        String serverId = WebJson.text(input,"serverId",63), sourceId = WebJson.text(input,"sourceId",63);
        var server = servers.require(context, serverId); sources.requireReady(context, sourceId);
        var overrides = WebDeploymentInputs.overrides(input.path("overrides"));
        for (var entry : overrides.entrySet()) {
            if (entry.getKey().equals("configuration") || entry.getKey().endsWith("/configuration")) DeploymentConfigurationParser.parse(entry.getValue());
            if (entry.getKey().equals("secrets") || entry.getKey().endsWith("/secrets")) DeploymentConfigurationParser.secrets(entry.getValue());
        }
        return new PreparedWebOperation("DEPLOY", input, List.of(serverId), List.of(WebServerService.lockKey(server)), true, sourceId,null,null,
                interaction -> execute(context, serverId, sourceId, overrides, interaction));
    }
    private JsonNode execute(WebRequestContext context, String serverId, String sourceId, Map<String,String> overrides, TaskInteraction interaction) throws Exception {
        interaction.progress("SOURCE_ANALYZING", WebJson.object());
        var resolvedSecrets = new ArrayList<ResolvedSecretRevision>();
        try (var snapshot = sources.snapshot(context, sourceId)) {
            Path root = snapshot.directory();
            var analysis=analyze(context,sourceId,root,serverId,overrides,interaction);
            var discovered=analysis.components(); var inputs=analysis.inputs(); var facts=analysis.facts();
            String applicationId=analysis.applicationId(); var plan=analysis.plan(); var requests=analysis.requests();
            String healthOwner = healthOwner(discovered,inputs,interaction);
            approveRuntimeChoices(analysis, interaction);
            var databaseService = new WebDatabaseService(secrets,servers,ai);
            var databaseInputs = new LinkedHashMap<String,gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment>();
            for (var component:discovered) databaseInputs.put(component.id(),databaseService.inputs(context,root.resolve(component.relativeRoot()),facts.get(component.id()).applicationId(),inputs.get(component.id()),interaction));
            // Authenticate and pin the endpoint before deriving ownership identities.
            servers.withSession(context,serverId,interaction,session -> session.collectCapabilities());
            var identity = servers.identity(context,serverId);
            var old = applications.managed(context,serverId,applicationId);
            var known = old.map(applications::graph).map(ManagedWebGraph::components).orElse(List.of());
            if (!known.isEmpty() && !known.stream().map(ManagedWebComponent::id).collect(java.util.stream.Collectors.toSet()).equals(facts.keySet()))
                throw new IllegalStateException("Component topology changed; review the existing application before redeploying");
            validatePublicationInputs(context,analysis,identity);
            prepareEnvironment(context,serverId,applicationId,identity,interaction);
            var databases=prepareDatabases(context,serverId,root,analysis,databaseService,databaseInputs,interaction);
            var publication=review(context,sourceId,root,discovered,facts,inputs,databases,identity,known,plan,resolvedSecrets);
            var reviewed=publication.reviewed(); var durable=publication.durable();
            for (var component : reviewed) for (var item : gold.debug.windowstolinux.shared.deploy.input.ManagedStoragePreparation.preview(component.request())) {
                var preview = WebJson.object(); item.arguments().forEach(preview::put);
                interaction.progress(item.key().equals("storage.preflight")?"STORAGE_PREFLIGHT":"APPLICATION_PREFLIGHT",preview);
            }
            var graph = new ManagedWebGraph(applicationId,healthOwner,durable);
            var record = applications.stage(context,serverId,applicationId,graph);
            var progress = (java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent>) event ->
                    WebTaskPrompts.progress(interaction,event.step().code(),WebJson.object().put("succeeded",event.succeeded()).put("messageKey",event.message().key()));
            try {
            interaction.progress("DEPLOYMENT_STARTED",WebJson.object().put("applicationId",record.id()));
            DeploymentStatus status; Map<String,String> releases;
            if (reviewed.size() == 1) {
                var component = reviewed.getFirst();
                var result = servers.withCredential(context,serverId,interaction,(endpoint,credential,verifier) -> new ReviewedDeploymentService().deploy(
                        component.request(),component.application(),servers.gateway(),endpoint,credential,verifier,component.resolvedSecrets(),progress));
                status = result.status(); releases = result.publishedReleaseSha256().map(value -> Map.of(component.componentId(),value)).orElse(Map.of());
            } else {
                var result = servers.withCredential(context,serverId,interaction,(endpoint,credential,verifier) -> new ReviewedMultiComponentDeploymentService().deploy(
                        plan,reviewed,new ApplicationHealthGate(healthOwner,runtime.health(inputs.get(healthOwner))),servers.gateway(),endpoint,credential,verifier,progress));
                status = result.status(); releases = result.componentReleaseIdentities();
            }
            boolean success = status == DeploymentStatus.SUCCEEDED;
            if (!success) interaction.completion(status == DeploymentStatus.MANUAL_RECOVERY_REQUIRED ? OperationCompletionState.REVALIDATION_REQUIRED : OperationCompletionState.FAILED);
            var published = success ? new ManagedWebGraph(applicationId,healthOwner,durable.stream().map(value -> value.published(releases.get(value.id()))).toList()) : null;
            var finalObservation = success ? WebJson.object().put("state", reviewed.stream().allMatch(component ->
                    component.request().runtime().workload().mode() == gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND)
                    ? "INSTALLED" : "RUNNING").put("observedAt", Instant.now().toString()) : null;
            applications.finish(context,record.id(),status.name(),published,finalObservation);
            var result = WebJson.object().put("applicationId",record.id()).put("status",status.name());
            if (success) runtime.access(inputs.get(healthOwner),identity.host()).ifPresent(url -> result.put("accessUrl",url.url().toString()));
            return result;
            } catch (Exception failure) {
                interaction.completion(OperationCompletionState.REVALIDATION_REQUIRED);
                applications.finish(context,record.id(),"REVALIDATION_REQUIRED",null,null); throw failure;
            }
        } finally { resolvedSecrets.forEach(ResolvedSecretRevision::close); }
    }
    private static void approveRuntimeChoices(InitialAnalysis analysis, TaskInteraction interaction) throws Exception {
        var facts = analysis.facts(); var inputs = analysis.inputs();
        for (var entry : facts.entrySet()) {
            var values = inputs.get(entry.getKey());
            if (entry.getValue().support().level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER)
                WebTaskPrompts.approve(interaction,"EXPERIMENTAL_ADAPTER",WebJson.object().put("component",entry.getKey()).put("type",values.get("type")));
            if (values.get("type").equals("DOCKERFILE_CONTAINER") && values.getOrDefault("containerEngine","PODMAN").equals("DOCKER"))
                WebTaskPrompts.approve(interaction,"DOCKER_DAEMON",WebJson.object().put("component",entry.getKey()));
        }
    }
    private void prepareEnvironment(WebRequestContext context,String serverId,String applicationId,gold.debug.windowstolinux.shared.model.server.ServerIdentity identity,TaskInteraction interaction) throws Exception {
            WebTaskPrompts.approve(interaction,"ENVIRONMENT_PREPARATION",WebJson.object().put("serverId",serverId).put("host",identity.host()).put("applicationId",applicationId));
            interaction.progress("ENVIRONMENT_PREPARING",WebJson.object());
            servers.withCredential(context,serverId,interaction,(endpoint,credential,verifier) -> new EnvironmentSetupService().prepare(
                    new EnvironmentSetupApproval(serverId,true,Instant.now()),servers.gateway(),endpoint,credential,verifier,
                    system -> WebTaskPrompts.confirm(interaction,"SYSTEM_PREPARATION",WebJson.object().put("security",system.securityState().name()).put("state",system.state().name()))));
    }
    private void validatePublicationInputs(WebRequestContext context,InitialAnalysis analysis,gold.debug.windowstolinux.shared.model.server.ServerIdentity identity) throws Exception {
        var discovered=analysis.components();var inputs=analysis.inputs();var facts=analysis.facts();
            for (var component : discovered) {
                var values=inputs.get(component.id());
                var storageFacts = facts.get(component.id());
                gold.debug.windowstolinux.shared.deploy.input.ManagedStoragePreparation.prepare(storageFacts.sourceRoot(),storageFacts.applicationId(),
                        configuration(storageFacts.applicationId(),values),runtime.runtime(storageFacts.projectType(),values),List.of());
                runtime.runtime(facts.get(component.id()).projectType(),values); runtime.access(values,identity.host());
                var references=DeploymentConfigurationParser.secrets(values.getOrDefault("secrets",""));
                var checkedSecrets=secrets.resolve(context,references); checkedSecrets.forEach(ResolvedSecretRevision::close);
                DeploymentRuntimeParser.databaseBindings(DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode","NONE")),values.getOrDefault("databaseDetails",""));
            }
    }
    private Map<String,AutomaticDatabasePreparation> prepareDatabases(WebRequestContext context,String serverId,Path root,InitialAnalysis analysis,
            WebDatabaseService databaseService,Map<String,gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment> databaseInputs,TaskInteraction interaction) throws Exception {
        var discovered=analysis.components();var facts=analysis.facts();var inputs=analysis.inputs();var requests=analysis.requests();String applicationId=analysis.applicationId();
            var databases = new LinkedHashMap<String,AutomaticDatabasePreparation>();
            var databaseReviews = new LinkedHashMap<String,gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview>();
            for (var component:discovered) {
                var db = databaseService.prepare(context,serverId,root.resolve(component.relativeRoot()),facts.get(component.id()).applicationId(),databaseInputs.get(component.id()),inputs.get(component.id()),interaction);
                databases.put(component.id(),db); db.schemaReview().ifPresent(review -> databaseReviews.put(component.id(),review));
            }
            if (discovered.size()==1) {
                var component=discovered.getFirst(); var type=DeploymentProjectType.valueOf(inputs.get(component.id()).get("type"));
                var assessment=databaseReviews.containsKey(component.id()) ? new DeploymentAnalysisCoordinator().analyze(root.resolve(component.relativeRoot()),type,databaseReviews.get(component.id()))
                        : new DeploymentAnalysisCoordinator().analyze(root.resolve(component.relativeRoot()),type);
                var fact=assessment.facts().orElseThrow(); if(!fact.readyForPlanning()) throw new IllegalArgumentException("Source database review is incomplete"); facts.put(component.id(),fact);
            } else {
                var assessment=new MixedProjectInspector().analyze(root,applicationId,requests,databaseReviews);
                new MultiComponentDeploymentPlanner().plan(assessment);
                assessment.components().forEach(component -> facts.put(component.componentId(),component.facts()));
            }
        return databases;
    }
    private record InitialAnalysis(List<DiscoveredProjectComponent> components, Map<String,Map<String,String>> inputs,
            LinkedHashMap<String,DeploymentProjectFacts> facts, String applicationId, MultiComponentDeploymentPlan plan, List<ComponentAnalysisRequest> requests) {}
    private InitialAnalysis analyze(WebRequestContext context,String sourceId,Path root,String serverId,Map<String,String> overrides,TaskInteraction interaction) throws Exception {
            var discovered = new ProjectComponentDiscovery().discover(root);
            var inputs = new WebDeploymentInputs(ai,context).resolve(root, discovered, overrides, WebServerService.endpoint(servers.require(context,serverId)).host(), interaction);
            var facts = new LinkedHashMap<String,DeploymentProjectFacts>();
            String applicationId;
            MultiComponentDeploymentPlan plan;
            var requests = new ArrayList<ComponentAnalysisRequest>();
            if (discovered.size() == 1) {
                var component = discovered.getFirst(); var values = inputs.get(component.id());
                var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(root.resolve(component.relativeRoot()), DeploymentProjectType.valueOf(values.get("type")));
                var fact = assessment.facts().orElseThrow();
                applicationId = fact.applicationId(); facts.put(component.id(), fact);
                plan = new MultiComponentDeploymentPlanner().restore(applicationId, Map.of(component.id(), applicationId), Map.of(component.id(),List.of()));
            } else {
                applicationId = sources.requireReady(context,sourceId).name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]","-");
                for (var component : discovered) {
                    var values = inputs.get(component.id()); var specification = runtime.runtime(DeploymentProjectType.valueOf(values.get("type")), values);
                    var fact = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(root.resolve(component.relativeRoot()), specification.projectType()).facts().orElseThrow();
                    String path = component.relativeRoot().toString().replace('\\','/');
                    requests.add(new ComponentAnalysisRequest(component.id(), path.isBlank()? ".":path, specification.projectType(), Optional.of(specification),
                            runtime.artifacts(component.relativeRoot(), fact, values), specification.workload().endpoints().stream().map(endpoint -> endpoint.hostPort()).collect(java.util.stream.Collectors.toSet()), List.of(),List.of(),List.of(),
                            dependencies(values), true, ComponentIsolationSpecification.managed()));
                }
                var assessment = new MixedProjectInspector().analyzeAutomatic(root,applicationId,requests);
                if (assessment.issues().stream().anyMatch(issue -> !issue.code().equals("COMPONENT_REQUIRES_INPUT"))) throw new IllegalArgumentException("Invalid component graph");
                var namespaces = new LinkedHashMap<String,String>(); var dependencies = new LinkedHashMap<String,List<String>>();
                assessment.components().forEach(component -> { namespaces.put(component.componentId(),component.facts().applicationId()); dependencies.put(component.componentId(),component.dependencies().stream().sorted().toList()); });
                plan = new MultiComponentDeploymentPlanner().restore(applicationId,namespaces,dependencies);
                assessment.components().forEach(component -> facts.put(component.componentId(),component.facts()));
            }
        return new InitialAnalysis(discovered,inputs,facts,applicationId,plan,requests);
    }
    private record Publication(List<ReviewedComponentDeployment> reviewed,List<ManagedWebComponent> durable) {}
    private Publication review(WebRequestContext context,String sourceId,Path root,List<DiscoveredProjectComponent> discovered,
            Map<String,DeploymentProjectFacts> facts,Map<String,Map<String,String>> inputs,Map<String,AutomaticDatabasePreparation> databases,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity identity,List<ManagedWebComponent> known,MultiComponentDeploymentPlan plan,
            List<ResolvedSecretRevision> resolvedSecrets) throws Exception {
            var reviewed = new ArrayList<ReviewedComponentDeployment>(); var durable = new ArrayList<ManagedWebComponent>();
            for (var component : discovered) {
                var fact = facts.get(component.id()); var values = inputs.get(component.id());
                var archive = new SafeSourceArchivePreparer().archive(root.resolve(component.relativeRoot()), root.getParent().resolve(component.id()+".tar.gz"));
                var descriptor = new SourceArchiveDescriptor(archive.archivePath(),archive.contentSha256(),archive.byteCount(),archive.uncompressedByteCount());
                var provenance = WebJson.read(sources.requireReady(context,sourceId).document());
                var revision = new SourceRevision(archive.contentSha256(), Optional.ofNullable(provenance.has("commit")?provenance.path("commit").asText():null),
                        Optional.ofNullable(provenance.has("remote")?java.net.URI.create(provenance.path("remote").asText()):null),Map.of());
                var db=databases.get(component.id());
                var configuration = withDatabases(configuration(fact.applicationId(),values),db);
                var references = new ArrayList<>(DeploymentConfigurationParser.secrets(values.getOrDefault("secrets",""))); references.addAll(db.secrets());
                var boundSecrets=secrets.resolve(context,references); resolvedSecrets.addAll(boundSecrets);
                var bindings = db.bindings().isEmpty() ? DeploymentRuntimeParser.databaseBindings(DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode","NONE")),values.getOrDefault("databaseDetails","")) : Optional.of(db.bindings());
                var storage = gold.debug.windowstolinux.shared.deploy.input.ManagedStoragePreparation.prepare(fact.sourceRoot(),fact.applicationId(),configuration,
                        runtime.runtime(fact.projectType(),values),List.of());
                configuration = storage.configuration();
                var request = new ReviewedDeploymentRequest(identity,fact,revision,descriptor,configuration,references,bindings,storage.files(),
                        runtime.runtime(fact.projectType(),values),runtime.access(values,identity.host()),BuildLimitConfiguration.defaultNonRoot(),
                        new DeploymentApproval(fact.applicationId(),archive.contentSha256(),identity.id(),false,Instant.now()),true,true);
                var application = known.stream().filter(value -> value.id().equals(component.id())).findFirst().map(ManagedWebComponent::application)
                        .orElseGet(() -> ManagedApplication.forManaged(fact.applicationId(),identity,ownership()));
                if (!application.server().equals(identity)) throw new IllegalStateException("Server identity changed");
                var resources = new ManagedComponentResourceBindings(storage.files(),bindings);
                var activation = new BackupConfigurationDocument(configuration,resources,new ManagedApplicationRuntimeConfiguration(request.runtime().healthCheck(),request.userAccessUrl(),request.runtime().identityPolicy(), request.runtime().workload()));
                durable.add(new ManagedWebComponent(component.id(),application,values,Base64.getEncoder().encodeToString(new BackupConfigurationCodec().writeActivation(activation)),
                        plan.dependencies().get(component.id()),"",references,Base64.getEncoder().encodeToString(new gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec().write(request.runtime()))));
                reviewed.add(new ReviewedComponentDeployment(component.id(),request,application,boundSecrets,resources));
            }
        return new Publication(reviewed,durable);
    }
    private static ConfigurationSnapshot withDatabases(ConfigurationSnapshot configuration, AutomaticDatabasePreparation db) {
        var entries=new LinkedHashMap<String,ConfigurationEntry>(); configuration.entries().forEach(entry -> entries.put(entry.key(),entry));
        db.configuration().forEach(entry -> { if(entries.containsKey(entry.key()) && !entries.get(entry.key()).equals(entry)) throw new IllegalArgumentException("Configuration conflicts with verified database"); entries.put(entry.key(),entry); });
        return ConfigurationSnapshot.create(configuration.applicationId(),configuration.revision(),configuration.schemaVersion(),configuration.createdAt(),List.copyOf(entries.values()));
    }
    private static Set<String> dependencies(Map<String,String> values) {
        var result = new LinkedHashSet<String>(); for (String value : values.getOrDefault("dependencies","").split("[,;\\s]+")) if (!value.isBlank()) result.add(value); return result;
    }
    private static String healthOwner(List<DiscoveredProjectComponent> components, Map<String,Map<String,String>> inputs, TaskInteraction interaction) throws Exception {
        if (components.size() == 1) return components.getFirst().id();
        var owners = components.stream().filter(value -> inputs.get(value.id()).get("healthMode").equals("HTTP")).toList();
        if (owners.size() == 1) return owners.getFirst().id();
        return WebTaskPrompts.inputs(interaction,List.of(AutomaticRuntimeResolver.field("application","healthOwner","",components.stream().map(DiscoveredProjectComponent::id).toList()))).get("application/healthOwner");
    }
    public static ConfigurationSnapshot configuration(String id, Map<String,String> values) {
        values = gold.debug.windowstolinux.shared.deploy.input.ApplicationDeclaration.completed(values);
        var entries = new ArrayList<>(DeploymentConfigurationParser.parse(values.getOrDefault("configuration","")));
        String key = values.get("type").equals("SPRING_BOOT") ? "SERVER_PORT" : "PORT";
        if (values.containsKey("port") && entries.stream().noneMatch(entry -> entry.key().equals(key))) entries.add(new ConfigurationEntry(key,ConfigurationScope.RUNTIME,new ConfigurationValue.Number(Long.parseLong(values.get("port")))));
        return ConfigurationSnapshot.create(id,Instant.now().toEpochMilli(),"runtime-v1",Instant.now(),entries);
    }
    private static String ownership() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
}
