package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Exact task snapshot consumed by the remote runtime. */
public record ResolvedMirrorPolicy(int formatVersion, String task, String server, String catalogVersion,
                                   String country, String region, Map<String, Integer> timeouts,
                                   Map<DownloadPoint, List<Endpoint>> points) {
    public ResolvedMirrorPolicy {
        timeouts = Map.copyOf(timeouts);
        var copy = new EnumMap<DownloadPoint, List<Endpoint>>(DownloadPoint.class);
        points.forEach((point, endpoints) -> copy.put(point, List.copyOf(endpoints)));
        points = Collections.unmodifiableMap(copy);
    }
    public record Route(String origin, String prefix, String pattern, String replacement) {
        public Route(String origin, String prefix) { this(origin, prefix, "", ""); }
        public Route {
            pattern = pattern == null ? "" : pattern;
            replacement = replacement == null ? "" : replacement;
            if (pattern.isEmpty() != replacement.isEmpty()) throw new IllegalArgumentException("Incomplete route template");
            if (!pattern.isEmpty()) {
                if (pattern.length() > 2048 || !pattern.startsWith("^") || !pattern.endsWith("$")
                    || pattern.contains("(?") || pattern.contains(".*") || pattern.contains(".+")
                    || pattern.contains(")+") || pattern.contains(")*"))
                    throw new IllegalArgumentException("Unbounded route pattern");
                java.util.regex.Pattern.compile(pattern);
                MirrorSettings.validateRouteUrl(templateUrl(replacement));
            }
        }
        public String templateTargetHost() {
            return pattern.isEmpty() ? "" : MirrorSettings.urlHost(templateUrl(replacement));
        }
        private static String templateUrl(String value) {
            return value.replaceAll("\\\\g<[0-9]+>", "capture");
        }
    }
    public record Endpoint(String providerId, String protocol, String baseUrl, List<String> allowedHosts,
                           boolean official, boolean custom, List<String> sourceOrigins, List<Route> routes,
                           Map<String, String> repoTemplates, String secretRef, boolean explicit) {
        public Endpoint(String providerId, String protocol, String baseUrl, List<String> allowedHosts,
                        boolean official, boolean custom, List<String> sourceOrigins, List<Route> routes,
                        Map<String, String> repoTemplates) {
            this(providerId, protocol, baseUrl, allowedHosts, official, custom, sourceOrigins, routes, repoTemplates, "", false);
        }
        public Endpoint(String providerId, String protocol, String baseUrl, List<String> allowedHosts,
                        boolean official, boolean custom, List<String> sourceOrigins, List<Route> routes) {
            this(providerId, protocol, baseUrl, allowedHosts, official, custom, sourceOrigins, routes, Map.of(), "", false);
        }
        public Endpoint {
            allowedHosts = allowedHosts.stream().map(MirrorSettings::normalizedHost).distinct().toList();
            sourceOrigins = List.copyOf(sourceOrigins);
            routes = List.copyOf(routes);
            repoTemplates = repoTemplates == null ? Map.of() : Map.copyOf(repoTemplates);
            secretRef = secretRef == null ? "" : secretRef;
            if (!secretRef.isEmpty() && !secretRef.matches("[A-Za-z0-9_.:-]{1,256}"))
                throw new IllegalArgumentException("Invalid mirror secret reference");
        }
        public Endpoint selection(String reference) {
            return new Endpoint(providerId, protocol, baseUrl, allowedHosts, official, custom, sourceOrigins, routes, repoTemplates, reference, true);
        }
    }
    public ResolvedMirrorPolicy withTask(String id) {
        return new ResolvedMirrorPolicy(formatVersion, Objects.requireNonNull(id), server, catalogVersion,
            country, region, timeouts, points);
    }
    public String toJson() {
        try { return new ObjectMapper().writeValueAsString(this); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode mirror policy", e); }
    }
    public static ResolvedMirrorPolicy fromJson(String json, String task, String server) {
        if (json == null || json.length() > 1_000_000) throw new IllegalArgumentException("Mirror snapshot size invalid");
        try {
            var value = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(json, ResolvedMirrorPolicy.class);
            if (value.formatVersion() != 1 || !Objects.equals(value.task(), task) || !Objects.equals(value.server(), server)
                || value.catalogVersion() == null || value.points().size() != DownloadPoint.values().length)
                throw new IllegalArgumentException("Mirror snapshot binding invalid");
            if (value.timeouts().isEmpty() || value.timeouts().size() > 20
                || value.timeouts().values().stream().anyMatch(n -> n == null || n < 1 || n > 16_777_216))
                throw new IllegalArgumentException("Mirror snapshot timeout bounds invalid");
            for (var endpoints : value.points().values()) {
                if (endpoints.size() > 256) throw new IllegalArgumentException("Mirror snapshot chain too long");
                for (var endpoint : endpoints) {
                    MirrorSettings.validateUrl(endpoint.baseUrl());
                    if (endpoint.repoTemplates().size() > 64) throw new IllegalArgumentException("Too many repository templates");
                    for (var template : endpoint.repoTemplates().values()) {
                        MirrorSettings.validateRouteUrl(template);
                        if (!endpoint.allowedHosts().contains(MirrorSettings.urlHost(template)))
                            throw new IllegalArgumentException("Repository template host invalid");
                    }
                    if (endpoint.routes().size() > 100 || endpoint.allowedHosts().size() > 100
                        || !endpoint.allowedHosts().contains(MirrorSettings.urlHost(endpoint.baseUrl())))
                        throw new IllegalArgumentException("Mirror snapshot host boundary invalid");
                    for (var route : endpoint.routes()) {
                        MirrorSettings.validateRouteUrl(route.origin()); MirrorSettings.validateRouteUrl(route.prefix());
                        if (!endpoint.sourceOrigins().contains(route.origin())
                            || !endpoint.allowedHosts().contains(MirrorSettings.urlHost(route.prefix()))
                            || (!route.pattern().isEmpty() && !endpoint.allowedHosts().contains(route.templateTargetHost())))
                            throw new IllegalArgumentException("Mirror snapshot route invalid");
                    }
                }
            }
            return value;
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid mirror snapshot", e); }
    }
}
