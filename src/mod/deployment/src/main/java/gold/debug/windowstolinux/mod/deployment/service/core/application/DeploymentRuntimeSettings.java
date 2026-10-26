package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.release.*;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentCommandText;

/** Literal settings of an already sealed native release; source discovery is deliberately absent. */
public final class DeploymentRuntimeSettings {
    private DeploymentRuntimeSettings() { }

    public record Values(String component, String port, String accessUrl, String entrypoint, String arguments) { }
    public record Form(gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot selected,
                       List<Values> components) { }
    public record ConfigurationChange(String key, String beforeValue, String afterValue, String sourceComponent) { }
    public record ComponentReview(String component, String unitId, String beforeCommand, String afterCommand,
                                  String recipeSha256, String port, List<ConfigurationChange> configurationChanges) {
        public ComponentReview { configurationChanges = List.copyOf(configurationChanges); }
    }
    public record Review(Form form, Values edited, DeploymentPublishProposal source, DeploymentPublishProposal target,
                         List<ComponentReview> components, String fingerprint, String targetIdentity, UUID operationId) {
        public Review { components = List.copyOf(components); }
    }

    public static Values values(DeploymentApplicationGraph.Part part, String host) {
        var workload = part.reviewedRuntime().workload();
        var endpoint = workload.endpoints().stream().filter(e -> e.exposure() == DeploymentEndpoint.ExposureType.EXTERNAL)
            .findFirst().or(() -> workload.endpoints().stream().findFirst());
        String port = endpoint.map(e -> Integer.toString(e.hostPort())).orElse("");
        String url = endpoint.filter(e -> e.exposure() == DeploymentEndpoint.ExposureType.EXTERNAL
            && (e.protocol() == DeploymentEndpoint.ProtocolType.HTTP || e.protocol() == DeploymentEndpoint.ProtocolType.HTTPS))
            .map(e -> e.accessUrl().isBlank() ? e.protocol().name().toLowerCase(Locale.ROOT) + "://"
                + (host.contains(":") ? "[" + host + "]" : host) + ":" + e.hostPort() : e.accessUrl()).orElse("");
        return new Values(part.componentId(), port, url, workload.command().entrypoint(),
            DeploymentCommandText.format(workload.command().arguments()));
    }

    public static DeploymentPublishProposal patch(DeploymentPublishProposal source, Values edits, Map<String, Long> nextRevisions,
                                                  UUID operationId) throws Exception {
        Objects.requireNonNull(operationId);
        var selected = source.graph().components().stream().filter(p -> p.componentId().equals(edits.component())).findFirst().orElseThrow();
        var original = selected.reviewedRuntime();
        if (original instanceof DeploymentRuntimeDefinition.Container || !original.workload().reviewed())
            throw new IllegalArgumentException("Only reviewed native runtime settings are supported");
        var workload = original.workload();
        if (workload.endpoints().size() > 1)
            throw new IllegalArgumentException("This form requires at most one endpoint per component");
        int previous = workload.endpoints().isEmpty() ? 0 : workload.endpoints().getFirst().hostPort();
        int port = edits.port().isBlank() ? previous : Integer.parseInt(edits.port());
        if (previous != 0 && (port < 1 || port > 65535) || previous == 0 && port != 0)
            throw new IllegalArgumentException("Port must match an existing endpoint and be in 1..65535");
        var endpoints = new ArrayList<DeploymentEndpoint>();
        for (var e : workload.endpoints()) {
            String url = edits.accessUrl().trim();
            if (!url.isEmpty()) {
                if (e.exposure() != DeploymentEndpoint.ExposureType.EXTERNAL
                    || e.protocol() != DeploymentEndpoint.ProtocolType.HTTP && e.protocol() != DeploymentEndpoint.ProtocolType.HTTPS)
                    throw new IllegalArgumentException("Only an external HTTP or HTTPS endpoint can have a public access URL");
                var uri = DeploymentEndpoint.requireAccessUrl(URI.create(url));
                int urlPort = uri.getPort() == -1 ? uri.getScheme().equalsIgnoreCase("https") ? 443 : 80 : uri.getPort();
                if (urlPort != port || !uri.getScheme().equalsIgnoreCase(e.protocol().name()))
                    throw new IllegalArgumentException("Access URL must match the declared protocol and port");
            }
            endpoints.add(new DeploymentEndpoint(e.id(), e.protocol(), e.bindAddress(), port,
                e.targetPort() == previous ? port : e.targetPort(), e.exposure(), url));
        }
        if (endpoints.isEmpty() && !edits.accessUrl().isBlank()) throw new IllegalArgumentException("CLI applications have no access URL");
        var command = new DeploymentCommand(edits.entrypoint().trim(), DeploymentCommandText.parse(edits.arguments()));
        var health = health(original.healthCheck(), previous, port);
        var nextWorkload = new DeploymentWorkload(workload.mode(), true, command, workload.workingDirectory(), endpoints,
            workload.verification(), workload.expectedOutput(), workload.client(), workload.inputs(), workload.companions(),
            workload.buildDirectory(), workload.workers());
        var codec = new DeploymentRuntimeCodec();
        var runtime = codec.read(codec.write(original.withWorkload(nextWorkload)), health);
        if (runtime instanceof DeploymentRuntimeDefinition.PhpService p && previous != port)
            runtime = new DeploymentRuntimeDefinition.PhpService(p.version(), p.artifactName(), p.entrypoint(), port, health, p.identityPolicy(), nextWorkload);
        if (runtime instanceof DeploymentRuntimeDefinition.RubyService r && previous != port)
            runtime = new DeploymentRuntimeDefinition.RubyService(r.version(), r.artifactName(), r.entrypoint(), port, health, r.identityPolicy(), nextWorkload);
        var oldCommit = source.units().stream().filter(c -> c.unit().equals(selected.unit())).findFirst().orElseThrow();
        var configuration = oldCommit.configuration();
        if (previous != port) {
            var entries = configuration.entries().stream().map(e -> e.scope() == DeploymentConfigScopeType.RUNTIME
                && Set.of("PORT", "SERVER_PORT").contains(e.key())
                ? new DeploymentConfigEntry(e.key(), e.scope(), new DeploymentConfigValue.Number(port)) : e).toList();
            if (!entries.equals(configuration.entries())) configuration = DeploymentConfigSnapshot.create(configuration.applicationId(),
                nextRevisions.get(selected.unit().id()), configuration.schemaVersion(), Instant.now(), entries);
        }
        var unitRuntime = new DeploymentUnitRuntimeConfig(health,
            edits.accessUrl().isBlank() ? Optional.empty() : Optional.of(new DeploymentAccessUrl(URI.create(edits.accessUrl().trim()))),
            runtime.identityPolicy(), nextWorkload);
        var changed = new DeploymentApplicationGraph.Part(selected.componentId(), selected.unit(), unitRuntime,
            selected.dependencies(), runtime, selected.dataPaths(), selected.resources());
        var parts = source.graph().components().stream().map(p -> p.componentId().equals(edits.component()) ? changed : p).toList();
        var graph = new DeploymentApplicationGraph(source.graph().applicationId(), source.graph().healthComponentId(),
            source.graph().healthComponentId().equals(edits.component()) ? health(source.graph().healthCheck(), previous, port) : source.graph().healthCheck(), parts);
        String identity = revisionIdentity(operationId, oldCommit, changed, configuration);
        var commit = new DeploymentUnitCommit(selected.unit(), unitRuntime,
            new DeploymentCurrentRelease(selected.unit().id(), identity, Instant.now()), configuration, oldCommit.secrets());
        var oldOrigin = origin(workload.endpoints().isEmpty() ? "" : workload.endpoints().getFirst().accessUrl());
        if (oldOrigin.isEmpty()) oldOrigin = selected.runtime().userAccessUrl().flatMap(value -> origin(value.url().toString()));
        var newOrigin = origin(edits.accessUrl().trim());
        var commits = new ArrayList<DeploymentUnitCommit>();
        for (var originalCommit : source.units()) {
            if (originalCommit.unit().equals(selected.unit())) { commits.add(commit); continue; }
            var originalConfiguration = originalCommit.configuration();
            var linked = originalConfiguration;
            if (oldOrigin.isPresent() && newOrigin.isPresent() && !oldOrigin.equals(newOrigin)) {
                String before = oldOrigin.orElseThrow(), after = newOrigin.orElseThrow();
                var entries = originalConfiguration.entries().stream().map(e -> e.scope() == DeploymentConfigScopeType.RUNTIME
                    && e.key().equals("WEB_ORIGIN") && e.value() instanceof DeploymentConfigValue.Text text
                    && text.value().equals(before)
                    ? new DeploymentConfigEntry(e.key(), e.scope(), new DeploymentConfigValue.Text(after)) : e).toList();
                if (!entries.equals(originalConfiguration.entries()))
                    linked = DeploymentConfigSnapshot.create(originalConfiguration.applicationId(), nextRevisions.get(originalCommit.unit().id()),
                        originalConfiguration.schemaVersion(), Instant.now(), entries);
            }
            if (linked.equals(originalConfiguration)) { commits.add(originalCommit); continue; }
            var component = graph.components().stream().filter(p -> p.unit().equals(originalCommit.unit())).findFirst().orElseThrow();
            commits.add(new DeploymentUnitCommit(originalCommit.unit(), originalCommit.runtime(),
                new DeploymentCurrentRelease(originalCommit.unit().id(), revisionIdentity(operationId, originalCommit, component, linked), Instant.now()),
                linked, originalCommit.secrets()));
        }
        return new DeploymentPublishProposal(graph, commits);
    }

    static List<ConfigurationChange> configurationChanges(DeploymentUnitCommit source, DeploymentUnitCommit target, String sourceComponent) {
        var result = new ArrayList<ConfigurationChange>();
        for (var before : source.configuration().entries()) {
            var after = target.configuration().entries().stream()
                .filter(e -> e.key().equals(before.key()) && e.scope() == before.scope()).findFirst().orElseThrow();
            if (before.equals(after)) continue;
            if (before.scope() != DeploymentConfigScopeType.RUNTIME || after.scope() != before.scope()
                || !Set.of("PORT", "SERVER_PORT", "WEB_ORIGIN").contains(before.key()))
                throw new IllegalArgumentException("Unexpected linked configuration change");
            result.add(new ConfigurationChange(before.key(), before.value().canonicalValue(), after.value().canonicalValue(), sourceComponent));
        }
        if (source.configuration().entries().size() != target.configuration().entries().size())
            throw new IllegalArgumentException("Settings revision cannot add configuration entries");
        return List.copyOf(result);
    }

    private static String revisionIdentity(UUID operationId, DeploymentUnitCommit source, DeploymentApplicationGraph.Part target,
                                          DeploymentConfigSnapshot configuration) throws Exception {
        return hash("runtime-settings-v3\n" + operationId + "\n" + source.unit().id() + "\n" + source.release().sha256() + "\n"
            + Base64.getEncoder().encodeToString(new DeploymentRuntimeCodec().write(target.reviewedRuntime())) + "\n"
            + Base64.getEncoder().encodeToString(new gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentHealthCodec().write(target.runtime().healthCheck()))
            + "\n" + configuration.sha256() + "\n" + target.runtime().userAccessUrl().map(value -> value.url().toString()).orElse(""));
    }

    private static Optional<String> origin(String value) {
        if (value.isBlank()) return Optional.empty();
        var uri = URI.create(value);
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null) return Optional.empty();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        int port = uri.getPort();
        boolean defaultPort = port == -1 || port == (scheme.equals("https") ? 443 : 80);
        return Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port));
    }

    private static DeploymentHealthCheck health(DeploymentHealthCheck health, int previous, int port) throws Exception {
        if (previous == 0 || previous == port) return health;
        return switch (health) {
            case DeploymentHealthCheck.Http h -> {
                URI u = h.endpoint();
                int effectivePort = u.getPort() == -1 ? u.getScheme().equalsIgnoreCase("https") ? 443 : 80 : u.getPort();
                yield effectivePort == previous ? new DeploymentHealthCheck.Http(new URI(u.getScheme(), u.getUserInfo(), u.getHost(), port,
                    u.getPath(), u.getQuery(), u.getFragment()), h.expectedStatus(), h.timeoutSeconds()) : h;
            }
            case DeploymentHealthCheck.Tcp h -> h.port() == previous ? new DeploymentHealthCheck.Tcp(port, h.timeoutSeconds(), h.stabilitySeconds()) : h;
            case DeploymentHealthCheck.Udp h -> h.port() == previous ? new DeploymentHealthCheck.Udp(port, h.requestHex(), h.responseHex(), h.probe(), h.timeoutSeconds()) : h;
            default -> health;
        };
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
