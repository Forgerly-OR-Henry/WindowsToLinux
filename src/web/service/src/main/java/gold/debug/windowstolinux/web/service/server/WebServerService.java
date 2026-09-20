package gold.debug.windowstolinux.web.service.server;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.shared.linux.connection.*;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import java.time.Instant;
import java.util.*;

/** Scoped server profiles, exact credential retention, first-use trust and fresh remote observations. */
public final class WebServerService {
    private final WebResourceRepository repository;
    private final WebCredentialStore secrets;
    private final DeploymentLinuxGateway gateway;
    public WebServerService(WebResourceRepository repository, WebCredentialStore secrets, DeploymentLinuxGateway gateway) {
        this.repository = repository; this.secrets = secrets; this.gateway = gateway;
    }

    public JsonNode list(WebRequestContext context) throws Exception {
        return WebJson.tree(repository.list(scope(context), ResourceType.SERVER).stream().map(WebServerService::view).toList());
    }
    public JsonNode save(WebRequestContext context, String id, JsonNode input) throws Exception {
        WebJson.fields(input, "name", "host", "port", "username", "password", "version");
        if (id == null) id = UUID.randomUUID().toString();
        String name = WebJson.text(input, "name", 200);
        var endpoint = new SshEndpoint(id, WebJson.text(input, "host", 253), input.path("port").asInt(22), WebJson.text(input, "username", 128));
        if (!endpoint.host().matches("[A-Za-z0-9.:%_-]+") || endpoint.username().chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Invalid server endpoint");
        var previous = repository.find(scope(context), ResourceType.SERVER, id);
        long version = input.path("version").asLong(0);
        if (previous.isPresent() && previous.orElseThrow().version() != version) throw new IllegalStateException("Server changed; refresh before saving");
        boolean same = previous.isPresent() && endpoint(previous.orElseThrow()).equals(endpoint);
        String password = input.path("password").asText("");
        String secret;
        if (password.isEmpty()) {
            if (!same) throw new IllegalArgumentException("A password is required for a new server endpoint");
            secret = (String) previous.orElseThrow().attributes().get("secret_id");
        } else secret = secrets.save(scope(context), "ssh", password.toCharArray());
        var fields = new LinkedHashMap<String, Object>();
        fields.put("host", endpoint.host()); fields.put("port", endpoint.port()); fields.put("username", endpoint.username());
        fields.put("fingerprint", same ? previous.orElseThrow().attributes().get("fingerprint") : null);
        fields.put("secret_id", secret); fields.put("secret_version", 1);
        String document = same ? previous.orElseThrow().document() : "{}";
        return view(repository.save(scope(context), ResourceType.SERVER, id, name, fields, document, version));
    }

    public void delete(WebRequestContext context, String id, long version) throws Exception {
        repository.delete(scope(context), ResourceType.SERVER, id, version);
    }

    public PreparedWebOperation probe(WebRequestContext context, String id) throws Exception {
        StoredResource server = require(context, id);
        return new PreparedWebOperation("SERVER_PROBE", WebJson.object().put("serverId", id), List.of(id), List.of(lockKey(server)), false,
                null, null, null, interaction -> {
                    try {
                        var facts = withSession(context, id, interaction, DeploymentRemoteSession::collectDeploymentCapabilities);
                        var result = WebJson.object().put("connected", true).put("observedAt", Instant.now().toString())
                                .put("operatingSystem", facts.distro().name() + " " + facts.version()).put("architecture", facts.architecture());
                        observation(context, id, result); return result;
                    } catch (Exception failure) {
                        observation(context, id, WebJson.object().put("connected", false).put("observedAt", Instant.now().toString()).put("operatingSystem", "UNKNOWN"));
                        throw failure;
                    }
                });
    }

    public <T> T withSession(WebRequestContext context, String id, TaskInteraction interaction, SessionAction<T> action) throws Exception {
        return withCredential(context, id, interaction, (endpoint, credential, verifier) -> {
            try (var session = gateway.connect(endpoint, credential, verifier)) { return action.execute(session); }
        });
    }

    public <T> T withCredential(WebRequestContext context, String id, TaskInteraction interaction, ConnectionAction<T> action) throws Exception {
        StoredResource server = require(context, id); SshEndpoint endpoint = endpoint(server);
        String secretId = (String) server.attributes().get("secret_id");
        var verifier = verifier(context, server, interaction);
        return secrets.use(scope(context), secretId, "ssh", value -> {
            interaction.checkCancelled();
            var credential = new SshCredential.Password(value);
            try { return action.execute(endpoint, credential, verifier); } finally { credential.clear(); }
        });
    }

    public StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.SERVER, id).orElseThrow(() -> new NoSuchElementException("Server not found"));
    }
    public ServerIdentity identity(WebRequestContext context, String id) throws Exception {
        var server = require(context, id); var endpoint = endpoint(server);
        return new ServerIdentity(id, endpoint.host(), endpoint.port(), (String) server.attributes().get("fingerprint"));
    }
    public DeploymentLinuxGateway gateway() { return gateway; }
    public static String lockKey(StoredResource server) {
        return server.attributes().get("host").toString().toLowerCase(Locale.ROOT) + ":" + server.attributes().get("port");
    }
    public static SshEndpoint endpoint(StoredResource server) {
        return new SshEndpoint(server.id(), (String) server.attributes().get("host"), ((Number) server.attributes().get("port")).intValue(), (String) server.attributes().get("username"));
    }
    private void observation(WebRequestContext context, String id, JsonNode observation) throws Exception {
        var current = require(context, id);
        repository.save(scope(context), ResourceType.SERVER, id, current.name(), current.attributes(), WebJson.write(observation), current.version());
    }

    private HostKeyEvaluator verifier(WebRequestContext context, StoredResource captured, TaskInteraction interaction) {
        return new HostKeyEvaluator() {
            private String accepted;
            @Override public HostKeyDecision verify(SshEndpoint endpoint, String fingerprint) {
                try {
                    var current = require(context, captured.id());
                    if (!endpoint(current).equals(endpoint) || !Objects.equals(current.attributes().get("secret_id"), captured.attributes().get("secret_id"))) return HostKeyDecision.REJECT;
                    String known = (String) current.attributes().get("fingerprint");
                    if (known != null) return known.equals(fingerprint) ? HostKeyDecision.ACCEPT_EXISTING : HostKeyDecision.REJECT;
                    if (!fingerprint.matches("SHA256:[A-Za-z0-9+/=]{10,100}")) return HostKeyDecision.REJECT;
                    JsonNode answer = interaction.decide("HOST_KEY", WebJson.object().put("serverId", captured.id()).put("host", endpoint.host()).put("fingerprint", fingerprint));
                    WebJson.fields(answer, "accepted");
                    if (!answer.path("accepted").asBoolean(false)) return HostKeyDecision.REJECT;
                    accepted = fingerprint; return HostKeyDecision.ACCEPT_FIRST_USE;
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    return HostKeyDecision.REJECT;
                }
            }
            @Override public boolean authenticated(SshEndpoint endpoint, HostKeyObservation observation) {
                try {
                    var current = require(context, captured.id());
                    if (!endpoint(current).equals(endpoint) || !Objects.equals(current.attributes().get("secret_id"), captured.attributes().get("secret_id"))) return false;
                    String known = (String) current.attributes().get("fingerprint");
                    if (known != null) return known.equals(observation.sshSha256());
                    if (!Objects.equals(accepted, observation.sshSha256())) return false;
                    var fields = new LinkedHashMap<>(current.attributes()); fields.put("fingerprint", accepted);
                    repository.save(scope(context), ResourceType.SERVER, current.id(), current.name(), fields, current.document(), current.version()); return true;
                } catch (Exception failure) { return false; }
            }
        };
    }
    private static JsonNode view(StoredResource server) {
        var fields = server.attributes();
        var result = WebJson.object().put("id", server.id()).put("name", server.name()).put("version", server.version())
                .put("host", (String) fields.get("host")).put("port", ((Number) fields.get("port")).intValue())
                .put("username", (String) fields.get("username")).put("fingerprint", (String) fields.get("fingerprint"))
                .put("credentialConfigured", fields.get("secret_id") != null);
        result.set("observation", WebJson.read(server.document())); return result;
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
    @FunctionalInterface public interface SessionAction<T> { T execute(DeploymentRemoteSession session) throws Exception; }
    @FunctionalInterface public interface ConnectionAction<T> { T execute(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator verifier) throws Exception; }
}
