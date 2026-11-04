package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.net.URI;
import java.util.*;

/** Non-secret, versioned user preferences. Inactive rows remain stored. */
public record MirrorSettings(int formatVersion, Map<DownloadPoint, List<Row>> points,
                             Map<String, String> serverRegions) {
    public MirrorSettings {
        if (formatVersion != 1) throw new IllegalArgumentException("Unsupported mirror settings version");
        var copy = new EnumMap<DownloadPoint, List<Row>>(DownloadPoint.class);
        Objects.requireNonNull(points).forEach((point, rows) -> {
            if (rows == null || rows.isEmpty() || rows.size() > 3)
                throw new IllegalArgumentException("A download point requires 1-3 rows");
            copy.put(Objects.requireNonNull(point), List.copyOf(rows));
        });
        points = Collections.unmodifiableMap(copy);
        serverRegions = Map.copyOf(Objects.requireNonNull(serverRegions));
        if (serverRegions.size() > 1024 || serverRegions.entrySet().stream().anyMatch(e ->
            e.getKey().isBlank() || e.getKey().length() > 256 || !e.getValue().matches("[A-Z]{2}")))
            throw new IllegalArgumentException("Server region correction requires server ID and ISO country code");
    }

    public record Row(String providerId, String customUrl, boolean automatic, boolean privacyConsent,
                      String secretRef) {
        public Row {
            providerId = blank(providerId); customUrl = blank(customUrl); secretRef = blank(secretRef);
            if (!providerId.isEmpty() && !customUrl.isEmpty())
                throw new IllegalArgumentException("A row cannot contain both provider and custom URL");
            if (!automatic && (providerId.isEmpty() == customUrl.isEmpty()))
                throw new IllegalArgumentException("Fixed row requires exactly one provider or custom URL");
            if (!customUrl.isEmpty()) validateUrl(customUrl);
            if (!secretRef.isEmpty() && !secretRef.matches("[A-Za-z0-9_.:-]{1,256}"))
                throw new IllegalArgumentException("Invalid mirror secret reference");
        }
        public static Row auto(boolean consent) { return new Row("", "", true, consent, ""); }
        private static String blank(String s) { return s == null ? "" : s.trim(); }
    }

    public static void validateUrl(String url) {
        validateRouteUrl(url);
        var uri = URI.create(url);
        if (uri.getRawQuery() != null)
            throw new IllegalArgumentException("Mirror URL requires HTTP(S), host and no credentials/query/fragment");
    }

    public static void validateRouteUrl(String url) {
        var uri = URI.create(url);
        if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Route URL requires HTTP(S), host and no credentials/fragment");
    }

    /** Canonical host identity shared with Python urlsplit.hostname, without DNS resolution. */
    public static String normalizedHost(String host) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Mirror host is absent");
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.isBlank()) throw new IllegalArgumentException("Mirror host is absent");
        int zone = value.indexOf('%');
        // An IPv6 scope identifier is case-sensitive; Python preserves it as well.
        return zone < 0 ? value.toLowerCase(Locale.ROOT)
            : value.substring(0, zone).toLowerCase(Locale.ROOT) + value.substring(zone);
    }

    public static String urlHost(String url) {
        return normalizedHost(URI.create(url).getHost());
    }

    public static MirrorSettings defaults() {
        var points = new EnumMap<DownloadPoint, List<Row>>(DownloadPoint.class);
        for (var point : DownloadPoint.values()) points.put(point, List.of(Row.auto(false)));
        return new MirrorSettings(1, points, Map.of());
    }

    public List<Row> rows(DownloadPoint point) {
        return points.getOrDefault(point, List.of(Row.auto(false)));
    }
}
