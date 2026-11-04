package gold.debug.windowstolinux.mod.deployment.service.mirror;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;

/** Offline literal-IP lookup. Hostnames are never resolved and server IPs never leave the process. */
public final class LocalCountryDatabase {
    private static final Map<String, LocalCountryDatabase> BUNDLED = new HashMap<>();
    private record Range(BigInteger start, BigInteger end, String country) { }
    private final List<Range> ipv4;
    private final List<Range> ipv6;
    private LocalCountryDatabase(List<Range> ipv4, List<Range> ipv6) {
        this.ipv4 = List.copyOf(ipv4); this.ipv6 = List.copyOf(ipv6);
    }
    public static synchronized LocalCountryDatabase load(MirrorCatalog catalog) throws IOException {
        String key = catalog.geoIp().resource() + "\n" + catalog.geoIp().version();
        var cached = BUNDLED.get(key);
        if (cached != null) return cached;
        try (var input = LocalCountryDatabase.class.getResourceAsStream(catalog.geoIp().resource())) {
            if (input == null) throw new IOException("Country database resource missing");
            var result = load(input);
            BUNDLED.put(key, result);
            return result;
        }
    }
    /** Reads DB-IP Country Lite CSV.gz. Caller owns the compressed input. */
    public static LocalCountryDatabase load(InputStream compressed) throws IOException {
        var ipv4 = new ArrayList<Range>(); var ipv6 = new ArrayList<Range>();
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(compressed), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (++count > 2_000_000 || line.length() > 256) throw new IOException("Country database exceeds limit");
                var fields = line.split(",", -1);
                if (fields.length != 3) throw new IOException("Invalid Country Lite CSV row");
                for (int i = 0; i < fields.length; i++) fields[i] = fields[i].replace("\"", "");
                var first = literal(fields[0]); var last = literal(fields[1]);
                if (first == null || last == null || first.length != last.length || !fields[2].matches("[A-Z]{2}"))
                    throw new IOException("Invalid Country Lite address or country");
                var range = new Range(new BigInteger(1, first), new BigInteger(1, last), fields[2]);
                if (range.start().compareTo(range.end()) > 0) throw new IOException("Reversed Country Lite range");
                var target = first.length == 4 ? ipv4 : ipv6;
                if (!target.isEmpty() && target.getLast().end().compareTo(range.start()) >= 0)
                    throw new IOException("Unsorted or overlapping Country Lite ranges");
                target.add(range);
            }
        }
        return new LocalCountryDatabase(ipv4, ipv6);
    }
    public Optional<String> country(String ipLiteral) {
        var address = literal(ipLiteral);
        if (address == null) return Optional.empty();
        var value = new BigInteger(1, address); var ranges = address.length == 4 ? ipv4 : ipv6;
        int low = 0, high = ranges.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1; var range = ranges.get(mid);
            if (value.compareTo(range.start()) < 0) high = mid - 1;
            else if (value.compareTo(range.end()) > 0) low = mid + 1;
            else return range.country().equals("ZZ") ? Optional.empty() : Optional.of(range.country());
        }
        return Optional.empty();
    }
    private static byte[] literal(String input) {
        if (input == null || input.length() > 45 || input.isEmpty()) return null;
        if (input.indexOf(':') < 0) {
            var fields = input.split("\\.", -1);
            if (fields.length != 4) return null;
            var address = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!fields[i].matches("0|[1-9][0-9]{0,2}")) return null;
                int part = Integer.parseInt(fields[i]); if (part > 255) return null; address[i] = (byte) part;
            }
            return address;
        }
        if (!input.matches("[0-9A-Fa-f:.]+")) return null;
        try { return InetAddress.getByName(input).getAddress(); }
        catch (UnknownHostException e) { return null; }
    }
}
