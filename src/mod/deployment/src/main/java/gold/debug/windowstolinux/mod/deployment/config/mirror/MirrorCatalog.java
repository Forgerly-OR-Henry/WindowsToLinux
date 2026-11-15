package gold.debug.windowstolinux.mod.deployment.config.mirror;

import java.io.*;
import java.util.*;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSettings;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy.*;

/** Mod-owned catalog; providers, geography and upstream routing are exclusively resource data. */
public record MirrorCatalog(int formatVersion, String catalogVersion, Map<String, Integer> timeouts,
                            Map<String, Region> regions, Map<String, String> countries,
                            Map<DownloadPoint, Point> points, List<Provider> providers, GeoIp geoIp,
                            List<String> ipProbeUrls) {
    private static final String RESOURCE = "/gold/debug/windowstolinux/mod/deployment/config/mirrors.yaml";
    public record Region(String label, List<String> neighbors) {
        public Region { neighbors = List.copyOf(neighbors); }
    }
    public record Point(String label, String protocol, List<String> sourceOrigins, String coverageNote,
                        Map<String, String> repoTemplates) {
        public Point {
            sourceOrigins = List.copyOf(sourceOrigins);
            repoTemplates = repoTemplates == null ? Map.of() : Map.copyOf(repoTemplates);
        }
    }
    public record Source(String baseUrl, List<String> allowedHosts, List<Route> routes,
                         boolean complete, String coverageNote, Map<String, String> repoTemplates,
                         List<RedirectRule> redirectRules) {
        public Source(String baseUrl, List<String> allowedHosts, List<Route> routes,
                      boolean complete, String coverageNote, Map<String, String> repoTemplates) {
            this(baseUrl, allowedHosts, routes, complete, coverageNote, repoTemplates, List.of());
        }
        public Source {
            allowedHosts = allowedHosts.stream().map(MirrorSettings::normalizedHost).distinct().toList();
            routes = List.copyOf(routes);
            repoTemplates = repoTemplates == null ? Map.of() : Map.copyOf(repoTemplates);
            redirectRules = ResolvedMirrorPolicy.redirectRules(redirectRules, allowedHosts);
        }
    }
    public record Provider(String id, String label, String country, String region, boolean official,
                           List<String> evidence, Map<DownloadPoint, Source> sources, List<String> excludedCountries) {
        public Provider(String id, String label, String country, String region, boolean official,
                        List<String> evidence, Map<DownloadPoint, Source> sources) {
            this(id, label, country, region, official, evidence, sources, List.of());
        }
        public Provider {
            evidence = List.copyOf(evidence); sources = Map.copyOf(sources);
            excludedCountries = excludedCountries == null ? List.of() : List.copyOf(excludedCountries);
        }
    }
    public record GeoIp(String resource, String version, String attribution, String license, String source) { }

    public MirrorCatalog {
        if (formatVersion != 1 || catalogVersion == null || catalogVersion.isBlank())
            throw new IllegalArgumentException("Invalid mirror catalog version");
        timeouts = Map.copyOf(timeouts); regions = Map.copyOf(regions); countries = Map.copyOf(countries);
        points = Map.copyOf(points); providers = List.copyOf(providers);
        ipProbeUrls = List.copyOf(ipProbeUrls);
        ipProbeUrls.forEach(MirrorSettings::validateUrl);
        if (!points.keySet().containsAll(Set.of(DownloadPoint.values())))
            throw new IllegalArgumentException("Missing download points");
        var ids = new HashSet<String>();
        for (var region : regions.values())
            if (!regions.keySet().containsAll(region.neighbors())) throw new IllegalArgumentException("Unknown neighbor region");
        if (!regions.keySet().containsAll(countries.values())) throw new IllegalArgumentException("Unknown country region");
        for (var provider : providers) {
            if (!ids.add(provider.id()) || !regions.containsKey(provider.region()) || provider.evidence().isEmpty())
                throw new IllegalArgumentException("Invalid provider identity, region or evidence");
            if (!countries.keySet().containsAll(provider.excludedCountries()))
                throw new IllegalArgumentException("Unknown excluded provider country");
            for (var entry : provider.sources().entrySet()) {
                var source = entry.getValue(); MirrorSettings.validateUrl(source.baseUrl());
                if (points.get(entry.getKey()).protocol().equals("DNF") && source.complete() && source.repoTemplates().isEmpty())
                    throw new IllegalArgumentException("DNF endpoint missing explicit repository templates");
                for (var template : source.repoTemplates().values()) {
                    MirrorSettings.validateRouteUrl(template);
                    if (!source.allowedHosts().contains(MirrorSettings.urlHost(template)))
                        throw new IllegalArgumentException("DNF template escapes selected host boundary");
                }
                if (!source.allowedHosts().contains(MirrorSettings.urlHost(source.baseUrl())) || source.routes().isEmpty())
                    throw new IllegalArgumentException("Endpoint missing explicit host or origin routes");
                for (var route : source.routes()) {
                    MirrorSettings.validateRouteUrl(route.origin()); MirrorSettings.validateRouteUrl(route.prefix());
                    if (!points.get(entry.getKey()).sourceOrigins().contains(route.origin())
                        || !source.allowedHosts().contains(MirrorSettings.urlHost(route.prefix()))
                        || (!route.pattern().isEmpty() && (!source.allowedHosts().contains(route.templateTargetHost())
                            || Set.of("APT", "DNF").contains(points.get(entry.getKey()).protocol()))))
                        throw new IllegalArgumentException("Endpoint route escapes configured trust boundary");
                }
            }
        }
        for (var point : DownloadPoint.values()) {
            if (providers.stream().noneMatch(p -> p.official() && p.sources().containsKey(point)))
                throw new IllegalArgumentException("Missing official source for " + point);
        }
        validateGnuMakeSource(points.get(DownloadPoint.GNU_MAKE_SOURCE), providers);
    }

    private static void validateGnuMakeSource(Point point, List<Provider> providers) {
        var make = providers.stream().filter(provider -> provider.sources().containsKey(DownloadPoint.GNU_MAKE_SOURCE)).toList();
        if (make.size() != 1 || !make.getFirst().official() || !make.getFirst().id().equals(DownloadPoint.GNU_MAKE_PROVIDER)
            || !make.getFirst().excludedCountries().isEmpty())
            throw new IllegalArgumentException("GNU Make requires the fixed official provider");
        var source = make.getFirst().sources().get(DownloadPoint.GNU_MAKE_SOURCE);
        String origin = DownloadPoint.GNU_MAKE_ORIGIN;
        if (!source.complete() || !point.protocol().equals("HTTP") || !point.sourceOrigins().equals(List.of(origin))
            || !source.baseUrl().equals(origin) || !source.allowedHosts().equals(List.of(MirrorSettings.urlHost(origin)))
            || !source.routes().equals(List.of(new Route(origin, origin))) || !source.repoTemplates().isEmpty()
            || !point.repoTemplates().isEmpty() || !source.redirectRules().isEmpty())
            throw new IllegalArgumentException("GNU Make catalog route differs from the fixed source");
    }
    public Point point(DownloadPoint point) { return points.get(point); }
    public List<Provider> providersFor(DownloadPoint point) {
        return providers.stream().filter(p -> p.sources().containsKey(point) && p.sources().get(point).complete()).toList();
    }
    public List<Provider> allProvidersFor(DownloadPoint point) {
        return providers.stream().filter(p -> p.sources().containsKey(point)).toList();
    }
    private static final class Bundled {
        private static MirrorCatalog value;
    }
    public static synchronized MirrorCatalog load() {
        if (Bundled.value == null) {
            try (var stream = MirrorCatalog.class.getResourceAsStream(RESOURCE)) { Bundled.value = load(stream); }
            catch (IOException e) { throw new IllegalStateException("Mirror catalog unavailable", e); }
        }
        return Bundled.value;
    }
    public static MirrorCatalog load(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Mirror catalog resource missing");
        var bytes = stream.readNBytes(2_000_001);
        if (bytes.length > 2_000_000) throw new IOException("Mirror catalog exceeds size limit");
        var mapper = new ObjectMapper(YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper.readValue(bytes, MirrorCatalog.class);
    }
}
