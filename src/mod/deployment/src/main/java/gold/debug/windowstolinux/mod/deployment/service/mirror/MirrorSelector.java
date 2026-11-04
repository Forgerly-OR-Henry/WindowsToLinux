package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.net.URI;
import java.util.*;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog.Provider;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy.*;

/** Pure selection; never probes or contacts any provider. */
public final class MirrorSelector {
    private MirrorSelector() { }
    public static ResolvedMirrorPolicy resolve(MirrorCatalog catalog, MirrorSettings settings,
                                               String serverId, String country) {
        return resolve(catalog, settings, serverId, country, EnumSet.allOf(DownloadPoint.class));
    }
    public static ResolvedMirrorPolicy resolve(MirrorCatalog catalog, MirrorSettings settings,
                                               String serverId, String country, Set<DownloadPoint> automaticPoints) {
        automaticPoints = Set.copyOf(Objects.requireNonNull(automaticPoints));
        country = country == null ? "" : country.toUpperCase(Locale.ROOT);
        country = settings.serverRegions().getOrDefault(serverId, country).toUpperCase(Locale.ROOT);
        if (!catalog.countries().containsKey(country)) country = "";
        String region = catalog.countries().getOrDefault(country, "");
        if (!region.isEmpty() && !catalog.regions().containsKey(region))
            throw new IllegalArgumentException("Unknown selected mirror region");
        var result = new EnumMap<DownloadPoint, List<Endpoint>>(DownloadPoint.class);
        for (var point : DownloadPoint.values()) {
            var chain = new ArrayList<Endpoint>();
            var seen = new HashSet<String>();
            for (var row : settings.rows(point)) {
                if (row.automatic()) {
                    if (row.privacyConsent() && automaticPoints.contains(point)) {
                        var choices = new ArrayList<>(catalog.providersFor(point));
                        final String selectedCountry = country;
                        choices.removeIf(provider -> provider.excludedCountries().contains(selectedCountry));
                        choices.sort(Comparator.comparingInt((Provider p) -> rank(catalog, p, selectedCountry, region))
                            .thenComparing(Comparator.comparingInt((Provider p) -> p.sources().size()).reversed())
                            .thenComparing(Provider::id));
                        for (var provider : choices) {
                            add(chain, seen, endpoint(catalog, point, provider));
                        }
                    }
                    break; // Later rows are preserved preferences, never active after automatic.
                }
                if (!row.customUrl().isEmpty()) {
                    String customUrl = normalizedUrl(row.customUrl());
                    var definition = catalog.point(point);
                    var official = catalog.providersFor(point).stream().filter(Provider::official).findFirst().orElseThrow()
                        .sources().get(point);
                    var routes = definition.sourceOrigins().stream().map(origin -> {
                        var template = official.routes().stream().filter(route -> route.origin().equals(origin)
                            && !route.pattern().isEmpty() && route.replacement().startsWith(official.baseUrl()))
                            .findFirst();
                        if (template.isEmpty()) return new Route(origin,
                            origin.endsWith("/") ? customUrl.replaceAll("/+$", "") + "/" : customUrl);
                        var route = template.get();
                        return new Route(origin, customUrl, route.pattern(), customUrl.replaceAll("/+$", "")
                            + "/" + route.replacement().substring(official.baseUrl().length()).replaceAll("^/+", ""));
                    }).toList();
                    var repoTemplates = new LinkedHashMap<String, String>();
                    definition.repoTemplates().forEach((id, path) -> repoTemplates.put(id,
                        customUrl.replaceAll("/+$", "") + "/" + path));
                    add(chain, seen, new Endpoint(customId(customUrl), definition.protocol(), customUrl,
                        List.of(MirrorSettings.urlHost(customUrl)), false, true, definition.sourceOrigins(), routes, repoTemplates).selection(row.secretRef()));
                } else {
                    var provider = catalog.providersFor(point).stream().filter(p -> p.id().equals(row.providerId()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Provider does not support " + point));
                    add(chain, seen, endpoint(catalog, point, provider).selection(row.secretRef()));
                }
            }
            result.put(point, List.copyOf(chain));
        }
        return new ResolvedMirrorPolicy(1, "", serverId, catalog.catalogVersion(), country, region, catalog.timeouts(), result);
    }
    private static void add(List<Endpoint> chain, Set<String> seen, Endpoint endpoint) {
        if (seen.add(normalizedUrl(endpoint.baseUrl()))) {
            if (chain.size() >= 256) throw new IllegalArgumentException("Resolved mirror chain exceeds 256 endpoints");
            chain.add(endpoint);
        }
    }
    private static String normalizedUrl(String value) {
        var uri = URI.create(value).normalize();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = MirrorSettings.normalizedHost(uri.getHost());
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        int port = uri.getPort();
        if (port == 443 && scheme.equals("https") || port == 80 && scheme.equals("http")) port = -1;
        String path = uri.getRawPath();
        return scheme + "://" + host + (port < 0 ? "" : ":" + port) + (path == null || path.isEmpty() ? "/" : path);
    }
    private static String customId(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "custom-" + java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static int rank(MirrorCatalog catalog, Provider p, String country, String region) {
        if (p.official()) return 1000;
        if (!country.isEmpty() && p.country().equals(country)) return 0;
        if (!region.isEmpty() && p.region().equals(region)) return 1;
        var selected = catalog.regions().get(region);
        if (selected != null) {
            int neighbor = selected.neighbors().indexOf(p.region());
            if (neighbor >= 0) return 2 + neighbor;
        }
        return 100;
    }
    private static Endpoint endpoint(MirrorCatalog catalog, DownloadPoint point, Provider p) {
        var source = p.sources().get(point);
        return new Endpoint(p.id(), catalog.point(point).protocol(), source.baseUrl(), source.allowedHosts(),
            p.official(), false, catalog.point(point).sourceOrigins(), source.routes(), source.repoTemplates());
    }
}
