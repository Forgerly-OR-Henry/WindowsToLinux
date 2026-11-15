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
        var make = copy.getOrDefault(DownloadPoint.GNU_MAKE_SOURCE, List.of());
        if (make.size() > 1 || make.stream().anyMatch(endpoint -> !fixedGnuMakeEndpoint(endpoint)))
            throw new IllegalArgumentException("Frozen GNU Make source differs from the official provider");
        points = Collections.unmodifiableMap(copy);
    }

    private static boolean fixedGnuMakeEndpoint(Endpoint endpoint) {
        String origin = DownloadPoint.GNU_MAKE_ORIGIN;
        return endpoint.official() && !endpoint.custom() && endpoint.protocol().equals("HTTP")
            && endpoint.providerId().equals(DownloadPoint.GNU_MAKE_PROVIDER)
            && endpoint.baseUrl().equals(origin) && endpoint.sourceOrigins().equals(List.of(origin))
            && endpoint.allowedHosts().equals(List.of(MirrorSettings.urlHost(origin)))
            && endpoint.routes().equals(List.of(new Route(origin, origin)))
            && endpoint.secretRef().isEmpty() && endpoint.repoTemplates().isEmpty() && endpoint.redirectRules().isEmpty();
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
    /** Cross-host distribution is permitted only on an explicit redirect edge, never an initial URL. */
    public record RedirectRule(String fromHost, String toHost, String pathPrefix) {
        public RedirectRule {
            fromHost = redirectHost(fromHost);
            toHost = redirectHost(toHost);
            if (pathPrefix == null || pathPrefix.length() > 1024 || pathPrefix.equals("/")
                || !pathPrefix.startsWith("/") || !pathPrefix.endsWith("/")
                || !pathPrefix.matches("[A-Za-z0-9._~!$&'()*+,;=:@/-]+") || pathPrefix.contains("//")
                || Arrays.stream(pathPrefix.split("/")).anyMatch(part -> part.equals(".") || part.equals("..")))
                throw new IllegalArgumentException("Invalid redirect path prefix");
        }
        private static String redirectHost(String value) {
            if (value == null || value.length() > 253
                || !value.matches("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"))
                throw new IllegalArgumentException("Invalid redirect host");
            return value.toLowerCase(Locale.ROOT);
        }
    }
    public static List<RedirectRule> redirectRules(List<RedirectRule> rules, List<String> allowedHosts) {
        var result = rules == null ? List.<RedirectRule>of() : List.copyOf(rules);
        if (result.size() > 16 || result.stream().anyMatch(rule -> !allowedHosts.contains(rule.fromHost())))
            throw new IllegalArgumentException("Redirect rules escape selected source boundary");
        return result;
    }
    public record Endpoint(String providerId, String protocol, String baseUrl, List<String> allowedHosts,
                           boolean official, boolean custom, List<String> sourceOrigins, List<Route> routes,
                           Map<String, String> repoTemplates, String secretRef, boolean explicit,
                           List<RedirectRule> redirectRules) {
        public Endpoint(String providerId, String protocol, String baseUrl, List<String> allowedHosts,
                        boolean official, boolean custom, List<String> sourceOrigins, List<Route> routes,
                        Map<String, String> repoTemplates, String secretRef, boolean explicit) {
            this(providerId, protocol, baseUrl, allowedHosts, official, custom, sourceOrigins, routes,
                repoTemplates, secretRef, explicit, List.of());
        }
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
            redirectRules = ResolvedMirrorPolicy.redirectRules(redirectRules, allowedHosts);
            if (custom && !redirectRules.isEmpty())
                throw new IllegalArgumentException("Custom mirror cannot carry distribution redirect rules");
            if (!secretRef.isEmpty() && !secretRef.matches("[A-Za-z0-9_.:-]{1,256}"))
                throw new IllegalArgumentException("Invalid mirror secret reference");
        }
        public Endpoint selection(String reference) {
            return new Endpoint(providerId, protocol, baseUrl, allowedHosts, official, custom, sourceOrigins, routes,
                repoTemplates, reference, true, redirectRules);
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
                || value.catalogVersion() == null || !completePointSet(value.points().keySet()))
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

    private static boolean completePointSet(Set<DownloadPoint> points) {
        var required = EnumSet.allOf(DownloadPoint.class);
        if (!points.contains(DownloadPoint.GNU_MAKE_SOURCE)) required.remove(DownloadPoint.GNU_MAKE_SOURCE);
        return points.equals(required);
    }
}
