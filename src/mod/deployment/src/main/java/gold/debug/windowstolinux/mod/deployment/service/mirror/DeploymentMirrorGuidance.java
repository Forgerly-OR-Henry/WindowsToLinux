package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Public model guidance projected only from the original task's frozen routing snapshot. */
public final class DeploymentMirrorGuidance {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Shared publication feedback retains at most 6000 characters including its observation prefix.
    private static final int PAGE_BYTES = 5 * 1024;
    private static final Set<DownloadPoint> DEPENDENCIES = EnumSet.of(DownloadPoint.MAVEN_REPOSITORY,
        DownloadPoint.GRADLE_PLUGIN, DownloadPoint.COMPOSER_REPOSITORY, DownloadPoint.PYTHON_PACKAGE,
        DownloadPoint.NODE_PACKAGE, DownloadPoint.GO_MODULE, DownloadPoint.RUST_CRATE,
        DownloadPoint.NUGET, DownloadPoint.RUBYGEMS);

    private DeploymentMirrorGuidance() { }

    public static boolean present(TaskContext task) {
        return !DeploymentMirrorTasks.policy(Objects.requireNonNull(task)).isEmpty();
    }

    public static Map<String, Object> summary(TaskContext task) {
        String original = DeploymentMirrorTasks.policy(Objects.requireNonNull(task));
        if (original.isEmpty()) return Map.of("present", false);
        var policy = read(original, task);
        var points = new ArrayList<Map<String, Object>>();
        for (var point : DownloadPoint.values()) points.add(Map.of("point", point.name(),
            "candidateCount", policy.path("points").path(point.name()).size(), "helper", helper(point)));
        var result = new LinkedHashMap<String, Object>();
        result.put("present", true);
        result.put("policySha256", digest(original));
        result.put("task", task.id().toString());
        for (String field : List.of("catalogVersion", "country", "region")) result.put(field, policy.path(field).asText());
        result.put("points", List.copyOf(points));
        result.put("details", "mirror_sources(point, offset=0, limit=5), limit 1..10; public DATA, no execution authority");
        result.put("selection", "Effective frozen candidate chains only; original automatic/fixed UI rows are not reconstructed.");
        requireBound(result);
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, Object> sources(TaskContext task, String point) {
        return sources(task, point, 0, 5);
    }

    public static Map<String, Object> sources(TaskContext task, String point, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 10) throw new IllegalArgumentException("Invalid mirror source page: offset >= 0, limit 1..10 required");
        String original = DeploymentMirrorTasks.policy(Objects.requireNonNull(task));
        if (original.isEmpty()) throw new IllegalArgumentException("Original task has no frozen mirror policy");
        var policy = read(original, task);
        DownloadPoint selected;
        try { selected = DownloadPoint.valueOf(Objects.requireNonNullElse(point, "")); }
        catch (IllegalArgumentException unknown) {
            return Map.of("status", "UNSUPPORTED", "reason", "UNKNOWN_DOWNLOAD_POINT", "returned", 0,
                "nextOffset", offset, "hasMore", false, "entries", List.of());
        }
        var chain = policy.path("points").path(selected.name());
        var entries = new ArrayList<Map<String, Object>>();
        int next = Math.min(offset, chain.size());
        var result = page(task, original, selected, offset, limit, chain.size(), entries, next);
        if (chain.isEmpty()) {
            result.put("status", "UNSUPPORTED");
            result.put("reason", "NO_FROZEN_ENDPOINTS");
        }
        while (next < chain.size() && entries.size() < limit) {
            var entry = endpoint(chain.get(next), next);
            entries.add(entry);
            updatePage(result, entries, next + 1, chain.size());
            if (bytes(result) > PAGE_BYTES) {
                entries.removeLast();
                if (!entries.isEmpty()) break;
                entries.add(Map.of("index", next, "status", "UNSUPPORTED",
                    "reason", "PUBLIC_CANDIDATE_EXCEEDS_PAGE_BYTES", "publicCandidateBytes", bytes(entry)));
                updatePage(result, entries, next + 1, chain.size());
            }
            next++;
        }
        updatePage(result, entries, next, chain.size());
        requireBound(result);
        return Collections.unmodifiableMap(result);
    }

    private static LinkedHashMap<String, Object> page(TaskContext task, String original, DownloadPoint point,
                                                       int offset, int limit, int total,
                                                       List<Map<String, Object>> entries, int next) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "SUPPORTED");
        result.put("trust", "DATA");
        result.put("policySha256", digest(original));
        result.put("task", task.id().toString());
        result.put("point", point.name());
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("candidateCount", total);
        result.put("helper", helper(point));
        result.put("usage", usage(point));
        result.put("order", "Frozen candidate order; runtime selects and fails over. Do not reorder or add sources.");
        updatePage(result, entries, next, total);
        return result;
    }

    private static void updatePage(Map<String, Object> result, List<Map<String, Object>> entries, int next, int total) {
        result.put("entries", List.copyOf(entries));
        result.put("returned", entries.size());
        result.put("nextOffset", next);
        result.put("hasMore", next < total);
    }

    private static Map<String, Object> endpoint(JsonNode value, int index) {
        var result = new LinkedHashMap<String, Object>();
        result.put("index", index);
        for (String field : List.of("providerId", "protocol")) result.put(field, value.path(field).asText());
        result.put("baseUrl", publicUrl(value.path("baseUrl").asText()));
        result.put("allowedHosts", strings(value.path("allowedHosts"), false));
        result.put("sourceOrigins", strings(value.path("sourceOrigins"), true));
        result.put("official", value.path("official").asBoolean());
        result.put("custom", value.path("custom").asBoolean());
        result.put("credentialManaged", !value.path("secretRef").asText().isEmpty());
        var routes = new ArrayList<Map<String, String>>();
        for (var route : value.path("routes")) routes.add(Map.of(
            "origin", publicUrl(route.path("origin").asText()), "prefix", publicUrl(route.path("prefix").asText())));
        result.put("routes", List.copyOf(routes));
        var redirects = new ArrayList<Map<String, String>>();
        for (var valueRule : value.path("redirectRules")) {
            var rule = new ResolvedMirrorPolicy.RedirectRule(valueRule.path("fromHost").asText(),
                valueRule.path("toHost").asText(), valueRule.path("pathPrefix").asText());
            redirects.add(Map.of("fromHost", rule.fromHost(), "toHost", rule.toHost(), "pathPrefix", rule.pathPrefix()));
        }
        result.put("redirectRules", List.copyOf(redirects));
        var repositories = new TreeMap<String, String>();
        value.path("repoTemplates").fields().forEachRemaining(entry -> repositories.put(entry.getKey(), publicUrl(entry.getValue().asText())));
        result.put("repoTemplates", Collections.unmodifiableMap(repositories));
        result.put("urlProjection", "Public addresses omit query, user information and fragment; invoke helpers with source-backed original inputs.");
        return Collections.unmodifiableMap(result);
    }

    private static List<String> strings(JsonNode values, boolean urls) {
        var result = new ArrayList<String>();
        for (var value : values) result.add(urls ? publicUrl(value.asText()) : value.asText());
        return List.copyOf(result);
    }

    private static String publicUrl(String value) {
        try {
            var uri = URI.create(value);
            if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())) return "[UNSUPPORTED_PUBLIC_URL]";
            String authority = uri.getRawAuthority();
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            return uri.getScheme() + "://" + authority + Objects.requireNonNullElse(uri.getRawPath(), "");
        } catch (IllegalArgumentException invalid) { return "[UNSUPPORTED_PUBLIC_URL]"; }
    }

    private static String helper(DownloadPoint point) {
        if (point.name().startsWith("APT_")) return "wtl_apt";
        if (point.name().startsWith("DNF_")) return "wtl_dnf";
        if (point == DownloadPoint.OCI_REGISTRY) return "wtl_oci";
        if (point == DownloadPoint.MAVEN_DISTRIBUTION || point == DownloadPoint.GRADLE_DISTRIBUTION) return "wtl_wrapper / wtl_download";
        return DEPENDENCIES.contains(point) ? "wtl_dependency" : "wtl_download";
    }

    private static String usage(DownloadPoint point) {
        if (point.name().startsWith("APT_")) return "wtl_apt <apt-get arguments...>";
        if (point.name().startsWith("DNF_")) return "wtl_dnf <dnf arguments...>";
        if (point == DownloadPoint.OCI_REGISTRY) return "wtl_oci <Dockerfile> <output-Dockerfile-path> <engine-command...>";
        if (point == DownloadPoint.MAVEN_DISTRIBUTION || point == DownloadPoint.GRADLE_DISTRIBUTION)
            return "wtl_wrapper " + point + " <wrapper-properties> <directory>; or wtl_download " + point + " <original-url> <target>";
        return DEPENDENCIES.contains(point) ? "wtl_dependency " + point + " <verified-command> <arguments...>"
            : "wtl_download " + point + " <original-url> <target> [--algorithm <algorithm> --expected <digest>]";
    }

    private static JsonNode read(String original, TaskContext task) {
        try {
            var value = JSON.readTree(original);
            if (!value.isObject() || value.path("formatVersion").asInt() != 1 || !value.path("task").asText().equals(task.id().toString())
                || !value.path("points").isObject()) throw new IllegalArgumentException("Original frozen mirror policy binding invalid");
            return value;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Original frozen mirror policy invalid"); }
    }

    private static int bytes(Object value) {
        try { return JSON.writeValueAsBytes(value).length; }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Public mirror projection cannot be encoded"); }
    }

    private static void requireBound(Object value) {
        if (bytes(value) > PAGE_BYTES) throw new IllegalArgumentException("Public mirror guidance exceeds bounded context");
    }

    private static String digest(String original) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
