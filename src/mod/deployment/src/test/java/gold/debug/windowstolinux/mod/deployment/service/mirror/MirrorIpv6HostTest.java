package gold.debug.windowstolinux.mod.deployment.service.mirror;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy.*;
import org.junit.jupiter.api.Test;

class MirrorIpv6HostTest {
    @Test void normalizesIpv6HostIdentityAcrossCatalogSnapshotAndPythonTransport() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        if (root == null) throw new IOException("Project workspace unavailable");
        Path directory = Files.createDirectories(root.resolve(".ai-workspace/tmp/deployment-mirrors-20261104/ipv6/junit-" + UUID.randomUUID()));
        Process process = null;
        try {
            for (var resource : Map.of("runtime.py", "/gold/debug/windowstolinux/mod/deployment/linux/mirror/10-transfer.py",
                "ipv6_test.py", "/gold/debug/windowstolinux/mod/deployment/service/mirror/ipv6_test.py").entrySet()) {
                try (var input = getClass().getResourceAsStream(resource.getValue())) {
                    if (input == null) throw new IOException("IPv6 test resource missing");
                    Files.copy(input, directory.resolve(resource.getKey()));
                }
            }
            var command = new ArrayList<String>();
            if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of(directory.resolve("ipv6_test.py").toString(), directory.toString()));
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().put("TEMP", directory.toString());
            builder.environment().put("TMP", directory.toString());
            builder.environment().put("TMPDIR", directory.toString());
            process = builder.start();
            Path connection = directory.resolve("connection.json");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(connection) && process.isAlive() && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(Files.isRegularFile(connection), "IPv6 capability process did not return");
            var json = new ObjectMapper();
            var capability = json.readTree(Files.readAllBytes(connection));
            String loopback = "http://[::1]:" + (capability.path("available").asBoolean()
                ? capability.path("port").asInt() : 8080) + "/repo/";
            var settings = new MirrorSettings(1, Map.of(
                DownloadPoint.MAVEN_REPOSITORY, List.of(new MirrorSettings.Row("", loopback, false, false, "")),
                DownloadPoint.DNF_FEDORA, List.of(new MirrorSettings.Row("", "https://[2001:DB8::1]:443/fedora/", false, false, ""))), Map.of());
            var catalog = MirrorCatalog.load();
            var policy = MirrorSelector.resolve(catalog, settings, "server", "CN").withTask("task");
            var dnf = policy.points().get(DownloadPoint.DNF_FEDORA).getFirst();
            assertEquals("https://[2001:db8::1]/fedora/", dnf.baseUrl());
            assertEquals(List.of("2001:db8::1"), dnf.allowedHosts());
            assertEquals("[2001:db8::1]", java.net.URI.create(dnf.baseUrl()).getRawAuthority());
            assertEquals("example.com", MirrorSettings.urlHost("https://EXAMPLE.COM/path"));
            assertEquals("fe80::abcd%25Eth0", MirrorSettings.urlHost("https://[fe80::ABCD%25Eth0]/repo/"));

            String origin = catalog.point(DownloadPoint.MAVEN_DISTRIBUTION).sourceOrigins().getFirst();
            var route = new Route(origin, "https://[2001:DB8::1]/objects/",
                "^" + origin.replace(".", "\\.") + "([^/]+)$", "https://[2001:DB8::1]/objects/\\g<1>");
            assertEquals("2001:db8::1", route.templateTargetHost());
            var source = new MirrorCatalog.Source("https://[2001:DB8::1]/objects/",
                List.of("[2001:DB8::1]", "2001:db8::1"), List.of(route), true, "fixture",
                Map.of("repo", "https://[2001:DB8::1]/repo/$releasever/$basearch/"));
            assertEquals(List.of("2001:db8::1"), source.allowedHosts());
            var providers = new ArrayList<>(catalog.providers());
            providers.add(new MirrorCatalog.Provider("ipv6-fixture", "fixture", "CN", "CN", false,
                List.of("fixture"), Map.of(DownloadPoint.MAVEN_DISTRIBUTION, source)));
            var fixture = new MirrorCatalog(catalog.formatVersion(), catalog.catalogVersion(), catalog.timeouts(), catalog.regions(),
                catalog.countries(), catalog.points(), providers, catalog.geoIp(), catalog.ipProbeUrls());
            var parsed = MirrorCatalog.load(new ByteArrayInputStream(json.writeValueAsBytes(fixture)));
            assertEquals(source, parsed.providersFor(DownloadPoint.MAVEN_DISTRIBUTION).stream()
                .filter(provider -> provider.id().equals("ipv6-fixture")).findFirst().orElseThrow()
                .sources().get(DownloadPoint.MAVEN_DISTRIBUTION));

            var points = new EnumMap<DownloadPoint, List<Endpoint>>(DownloadPoint.class);
            points.putAll(policy.points());
            points.put(DownloadPoint.MAVEN_DISTRIBUTION, List.of(new Endpoint("ipv6-fixture", "HTTP", source.baseUrl(),
                List.of("[2001:DB8::1]"), false, true, List.of(origin), source.routes(), source.repoTemplates())));
            policy = new ResolvedMirrorPolicy(1, "task", "server", policy.catalogVersion(), policy.country(), policy.region(), policy.timeouts(), points);
            assertEquals(policy, ResolvedMirrorPolicy.fromJson(policy.toJson(), "task", "server"));
            var bracketed = json.readTree(policy.toJson());
            var allowed = (com.fasterxml.jackson.databind.node.ArrayNode) bracketed.path("points")
                .path("MAVEN_DISTRIBUTION").get(0).path("allowedHosts");
            allowed.removeAll().add("[2001:DB8::1]");
            assertEquals(policy, ResolvedMirrorPolicy.fromJson(bracketed.toString(), "task", "server"));
            var altered = json.readTree(policy.toJson());
            ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("points").path("MAVEN_DISTRIBUTION").get(0)
                .path("repoTemplates")).put("repo", "https://[2001:db8::2]/repo/");
            assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(altered.toString(), "task", "server"));
            var badRoute = json.readTree(policy.toJson());
            ((com.fasterxml.jackson.databind.node.ObjectNode) badRoute.path("points").path("MAVEN_DISTRIBUTION").get(0)
                .path("routes").get(0)).put("replacement", "https://[2001:db8::2]/objects/\\g<1>");
            assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(badRoute.toString(), "task", "server"));
            Path pending = directory.resolve("policy.next");
            Files.writeString(pending, policy.toJson());
            Files.move(pending, directory.resolve("policy.json"), StandardCopyOption.ATOMIC_MOVE);
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "IPv6 parser/transport process exceeded deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PARSER_VECTORS_OK"), output);
            if (capability.path("available").asBoolean()) assertTrue(output.contains("IPV6_LOOPBACK_OK"), output);
            System.out.println(output);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor();
            }
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
