package gold.debug.windowstolinux.web.service.execution.lifecycle;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.*;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Scoped application cards and shared typed lifecycle, with freshly rechecked external identities. */
public final class WebApplicationInventory {
    private final WebResourceRepository repository;
    private final WebTaskRepository tasks;
    private final WebServerService servers;
    private final Duration scanValidity;
    public WebApplicationInventory(WebResourceRepository repository, WebTaskRepository tasks, WebServerService servers, Duration scanValidity) {
        this.repository = repository; this.tasks = tasks; this.servers = servers; this.scanValidity = scanValidity;
    }

    public JsonNode list(WebRequestContext context) throws Exception {
        return WebJson.tree(repository.list(scope(context), ResourceType.APPLICATION).stream().map(WebApplicationInventory::view).toList());
    }
    public StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.APPLICATION, id).orElseThrow(() -> new NoSuchElementException("Application not found"));
    }
    public Optional<StoredResource> managed(WebRequestContext context, String serverId, String applicationId) throws Exception {
        return repository.list(scope(context), ResourceType.APPLICATION).stream().filter(row -> row.attributes().get("server_id").equals(serverId)
                && row.attributes().get("kind").equals("MANAGED") && row.attributes().get("remote_identity").equals(applicationId)).findFirst();
    }
    public ManagedWebGraph graph(StoredResource row) {
        if (!row.attributes().get("kind").equals("MANAGED")) throw new IllegalArgumentException("External applications have no managed backup graph");
        return WebJson.convert(WebJson.read(row.document()).path("graph"), ManagedWebGraph.class);
    }
    public StoredResource stage(WebRequestContext context, String serverId, String name, ManagedWebGraph graph) throws Exception {
        var old = managed(context, serverId, graph.applicationId());
        ObjectNode document = old.isPresent() ? (ObjectNode) WebJson.read(old.orElseThrow().document()) : WebJson.object();
        document.set("pendingGraph", WebJson.tree(graph)); document.put("deploymentState", "RUNNING");
        if (!document.has("graph")) document.set("graph", WebJson.tree(graph));
        return repository.save(scope(context), ResourceType.APPLICATION, old.map(StoredResource::id).orElseGet(() -> UUID.randomUUID().toString()),
                old.map(StoredResource::name).orElse(name), Map.of("server_id", serverId, "kind", "MANAGED", "remote_identity", graph.applicationId()),
                WebJson.write(document), old.map(StoredResource::version).orElse(0L));
    }
    public JsonNode finish(WebRequestContext context, String id, String status, ManagedWebGraph published, JsonNode observation) throws Exception {
        var row = require(context, id); var document = (ObjectNode) WebJson.read(row.document());
        document.put("deploymentState", status);
        if (published != null) {
            document.set("graph", WebJson.tree(published)); document.remove("pendingGraph"); document.put("deployedAt", Instant.now().toString());
            if(!document.has("accessUrl")) {
                var owner=published.components().stream().filter(component -> component.id().equals(published.healthOwner())).findFirst().orElseThrow();
                owner.configuration().runtimeConfiguration().userAccessUrl().ifPresent(url -> document.put("accessUrl",url.url().toString()));
            }
        }
        if (observation != null) document.set("observation", observation);
        return view(repository.save(scope(context), ResourceType.APPLICATION, id, row.name(), row.attributes(), WebJson.write(document), row.version()));
    }
    public JsonNode presentation(WebRequestContext context, String id, JsonNode input) throws Exception {
        WebJson.fields(input, "name", "category", "accessUrl", "version");
        var row = require(context, id);
        if (row.version() != input.path("version").asLong()) throw new IllegalStateException("Application changed");
        String name = WebJson.text(input, "name", 200), category = input.path("category").asText("");
        if (!Set.of("", "WEBSITE", "APP", "UNKNOWN").contains(category)) throw new IllegalArgumentException("Invalid application category");
        if (row.attributes().get("kind").equals("MANAGED") && !category.equals(view(row).path("category").asText()))
            throw new IllegalArgumentException("Managed category comes from reviewed service declarations");
        String access = input.path("accessUrl").asText("");
        if (!access.isBlank()) new UserAccessUrl(URI.create(access));
        var document = (ObjectNode) WebJson.read(row.document()); document.put("category", category).put("accessUrl", access);
        return view(repository.save(scope(context), ResourceType.APPLICATION, id, name, row.attributes(), WebJson.write(document), row.version()));
    }
    public PreparedWebOperation lifecycle(WebRequestContext context, String id, String requestedAction) throws Exception {
        var row = require(context, id); String serverId = (String) row.attributes().get("server_id");
        LifecycleAction action = LifecycleAction.valueOf(requestedAction);
        boolean external = row.attributes().get("kind").equals("EXTERNAL");
        if (external && !Set.of(LifecycleAction.REFRESH_STATUS, LifecycleAction.START, LifecycleAction.STOP, LifecycleAction.RESTART).contains(action))
            throw new IllegalArgumentException("External lifecycle action is unsupported");
        if (!external && action != LifecycleAction.REFRESH_STATUS && !view(row).path("canLifecycle").asBoolean())
            throw new IllegalArgumentException("On-demand tools use a command; obsolete contracts require reanalysis");
        return new PreparedWebOperation("LIFECYCLE", WebJson.object().put("applicationId", id).put("action", action.name()), List.of(serverId),
                List.of(WebServerService.lockKey(servers.require(context, serverId))), action != LifecycleAction.REFRESH_STATUS, null, id, null, interaction -> {
                    var current = require(context, id); var document = (ObjectNode) WebJson.read(current.document());
                    if(!external && action!=LifecycleAction.REFRESH_STATUS && Set.of("RUNNING","REVALIDATION_REQUIRED","MANUAL_RECOVERY_REQUIRED").contains(document.path("deploymentState").asText()))
                        throw new IllegalStateException("Application publication requires revalidation");
                    JsonNode observation;
                    if (external) {
                        var target = WebJson.convert(document.path("target"), ExternalApplicationTarget.class);
                        var result = servers.withSession(context, serverId, interaction, session -> session.externalApplications().execute(target, action));
                        if (result.managed() || !result.target().equals(target)) throw new IllegalStateException("External application identity changed");
                        if(action==LifecycleAction.STOP && result.state()!=RuntimeState.STOPPED
                                || (action==LifecycleAction.START || action==LifecycleAction.RESTART) && result.state()!=RuntimeState.RUNNING)
                            interaction.completion(OperationCompletionState.FAILED);
                        observation = WebJson.object().put("state", result.state().name()).put("observedAt", Instant.now().toString());
                    } else {
                        var graph = graph(current);
                        if (!servers.identity(context, serverId).equals(graph.components().getFirst().application().server())) throw new IllegalStateException("Server identity changed");
                        var components = graph.components().stream().map(component -> new ManagedComponentLifecycle(component.id(), component.application(), component.runtime().healthCheck())).toList();
                        var result = servers.withCredential(context, serverId, interaction, (endpoint, credential, verifier) ->
                                new MultiComponentLifecycleService().execute(graph.plan(), components, graph.components().stream().filter(component -> action == LifecycleAction.REFRESH_STATUS || component.runtime().workload().supportsLifecycle()).map(ManagedWebComponent::id).collect(java.util.stream.Collectors.toSet()), action, servers.gateway(), endpoint, credential, verifier));
                        observation = WebJson.tree(result);
                        if (!result.accepted()) interaction.completion(OperationCompletionState.FAILED);
                    }
                    // Only the narrow status projection is exposed; raw remote evidence is never returned.
                    var safe = statusProjection(observation);
                    var latest = require(context, id);
                    document = (ObjectNode) WebJson.read(latest.document());
                    document.set("observation", safe);
                    repository.save(scope(context), ResourceType.APPLICATION, id, latest.name(), latest.attributes(), WebJson.write(document), latest.version());
                    return safe;
                });
    }
    public PreparedWebOperation scan(WebRequestContext context, String serverId) throws Exception {
        servers.require(context, serverId);
        return new PreparedWebOperation("APPLICATION_SCAN", WebJson.object().put("serverId", serverId), List.of(serverId), List.of(), false,
                null, null, null, interaction -> {
                    var scan = servers.withSession(context, serverId, interaction, session -> session.externalApplications().scan());
                    return WebJson.object().put("serverId", serverId).put("observedAt", Instant.now().toString())
                            .set("scan", WebJson.tree(scan));
                });
    }
    public PreparedWebOperation adopt(WebRequestContext context, String taskId, String key) throws Exception {
        var scanTask = tasks.find(scope(context), taskId).orElseThrow(() -> new NoSuchElementException("Scan not found"));
        if (!scanTask.kind().equals("APPLICATION_SCAN") || !scanTask.state().equals("SUCCEEDED")
                || Duration.between(Instant.parse(scanTask.updatedAt()), Instant.now()).compareTo(scanValidity) > 0) throw new IllegalStateException("Rescan the server first");
        var result = WebJson.read(scanTask.resultJson()); String serverId = result.path("serverId").asText();
        var scan = WebJson.convert(result.path("scan"), ExternalApplicationScan.class);
        var candidate = scan.applications().stream().filter(item -> item.target().key().equals(key) && !item.managed()).findFirst().orElseThrow();
        return new PreparedWebOperation("APPLICATION_ADOPT", WebJson.object().put("scanTaskId", taskId).put("candidateKey", key), List.of(serverId),
                List.of(WebServerService.lockKey(servers.require(context, serverId))), true, null, null, null, interaction -> {
                    var checked = servers.withSession(context, serverId, interaction, session -> session.externalApplications().execute(candidate.target(), LifecycleAction.REFRESH_STATUS));
                    if (checked.managed() || !checked.target().equals(candidate.target())) throw new IllegalStateException("Application identity changed");
                    var document = WebJson.object().put("canStart", checked.canStart()).put("canStop", checked.canStop());
                    document.set("target", WebJson.tree(checked.target()));
                    document.set("observation", WebJson.object().put("state", checked.state().name()).put("observedAt", Instant.now().toString()));
                    var created = repository.save(scope(context), ResourceType.APPLICATION, UUID.randomUUID().toString(), checked.name(),
                            Map.of("server_id", serverId, "kind", "EXTERNAL", "remote_identity", checked.target().key()), WebJson.write(document), 0);
                    return view(created);
                });
    }

    private static JsonNode statusProjection(JsonNode raw) {
        if (raw.has("state")) return WebJson.object().put("state", raw.path("state").asText()).put("observedAt", raw.path("observedAt").asText());
        var result = WebJson.object().put("observedAt", Instant.now().toString()).put("state", raw.path("runtimeState").asText("UNKNOWN"));
        var items = new ArrayList<JsonNode>();
        for (var component : raw.path("componentResults")) {
            var observation = component.path("observation");
            items.add(WebJson.object().put("id", component.path("componentId").asText()).put("state", observation.path("runtimeState").asText("UNKNOWN"))
                    .put("autostart", observation.path("autostartState").asText("UNKNOWN")).put("observedAt", observation.path("observedAt").asText()));
        }
        if (!items.isEmpty() && items.stream().allMatch(item -> item.path("state").asText().equals("RUNNING"))) result.put("state", "RUNNING");
        else if (!items.isEmpty() && items.stream().allMatch(item -> item.path("state").asText().equals("INSTALLED"))) result.put("state", "INSTALLED");
        else if (!items.isEmpty() && items.stream().allMatch(item -> item.path("state").asText().equals("STOPPED"))) result.put("state", "STOPPED");
        result.set("components", WebJson.tree(items)); return result;
    }
    private static JsonNode view(StoredResource row) {
        var data = WebJson.read(row.document());
        var view = WebJson.object().put("id", row.id()).put("name", row.name()).put("serverId", row.attributes().get("server_id").toString())
                .put("kind", row.attributes().get("kind").toString()).put("version", row.version()).put("category", data.path("category").asText("SERVICE"))
                .put("accessUrl", data.path("accessUrl").asText("")).put("deployedAt", data.path("deployedAt").asText(row.createdAt()))
                .put("deploymentState", data.path("deploymentState").asText("EXTERNAL"));
        view.set("observation", data.path("observation"));
        view.put("canLifecycle", !row.attributes().get("kind").equals("MANAGED"));
        view.put("reanalysisRequired", false);
        if (row.attributes().get("kind").equals("MANAGED")) {
            try {
                var graph = WebJson.convert(data.path("graph"), ManagedWebGraph.class);
                var usage = graph.components().stream().map(component -> gold.debug.windowstolinux.shared.model.managed.ApplicationUsage.from(
                        component.application(), component.runtime().workload())).toList();
                boolean reviewed = usage.stream().allMatch(item -> item.reviewed());
                view.put("category", !reviewed ? "UNKNOWN" : usage.stream().anyMatch(item -> item.category().equals("WEBSITE")) ? "WEBSITE" : "APP");
                view.put("canLifecycle", reviewed && usage.stream().anyMatch(item -> item.lifecycle()));
                view.put("reanalysisRequired", !reviewed);
                view.set("usage", WebJson.tree(usage));
                if (!view.path("category").asText().equals("WEBSITE")) view.put("accessUrl", "");
            } catch (IllegalArgumentException | IllegalStateException obsolete) {
                view.put("reanalysisRequired", true).put("category", "UNKNOWN");
            }
        }
        if (data.has("graph")) view.set("types", WebJson.tree(graphTypes(data.path("graph"))));
        return view;
    }
    private static List<String> graphTypes(JsonNode graph) {
        var result = new LinkedHashSet<String>(); for (var component : graph.path("components")) result.add(component.path("inputs").path("type").asText()); return List.copyOf(result);
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
}
