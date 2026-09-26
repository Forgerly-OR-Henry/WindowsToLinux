package gold.debug.windowstolinux.app.mod.development;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.mod.installation.ModPackageTool;
import gold.debug.windowstolinux.app.mod.loader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDevelopmentInputsTest {
    @TempDir
    Path temporary;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void realReactorGeneratesFromCompiledSourcesResolvedCompanionAndPrivateLibrary() throws Exception {
        Path root = reactor();
        assertEquals(0, build(root), () -> log(root));
        Path module = root.resolve("src/mod/main");
        var descriptor = DevelopmentDescriptor.read(root, module.resolve("target/wtlmod-development.json"));
        assertEquals(List.of(root.resolve("src/mod/helper/target/classes")), descriptor.authorOutputs());
        assertEquals(1, descriptor.libraries().size());
        assertEquals("commons-lang3-3.17.0.jar", descriptor.libraries().getFirst().getFileName().toString());
        assertTrue(descriptor.buildInputs().contains(root.resolve(".mvn/wtlmod-author-parent/pom.xml")));
        assertTrue(descriptor.buildInputs().contains(root.resolve("src/mod/helper/pom.xml")));
        assertTrue(descriptor.buildInputs().contains(module.resolve("src/main/filters/build.properties")));
        assertEquals("processed resource", Files.readString(descriptor.mainOutput().resolve("example.txt")));
        assertFalse(Files.exists(module.resolve("target/main-1.0.0.jar")));
        assertFalse(Files.exists(root.resolve("author-initialized")));
        var urls = new ArrayList<java.net.URL>();
        for (var path : descriptor.sources())
            urls.add(path.toUri().toURL());
        try (var loader = new ModClassLoader(descriptor.manifest(), urls, Map.of())) {
            var entry = ModEntryFactory.create(loader);
            assertEquals("REAL MAVEN", entry.getClass().getMethod("value").invoke(entry));
        }
        assertTrue(Files.exists(root.resolve("author-initialized")));
        var envelope = json.readTree(module.resolve("target/wtlmod-build-input.json").toFile());
        assertEquals(1, envelope.get("authorJars").size());
        assertFalse(Files.exists(Path.of(envelope.get("authorJars").get(0).asText())));
    }

    @Test
    void failedRecompilationInvalidatesOriginalDescriptionBeforeCompilerRuns() throws Exception {
        Path root = reactor();
        assertEquals(0, build(root), () -> log(root));
        Path module = root.resolve("src/mod/main");
        Files.writeString(module.resolve("src/main/java/fixture/main/Mod.java"), "not valid Java");
        assertNotEquals(0, build(root));
        assertTrue(log(root).contains("COMPILATION ERROR"));
        assertFalse(Files.exists(module.resolve("target/wtlmod-development.json")));
        assertFalse(Files.exists(module.resolve("target/classes/META-INF/wtlmod-development-input.sha256")));
        assertTrue(DevelopmentModDiscovery.scan(root).candidates().isEmpty());
        assertFalse(Files.exists(root.resolve("author-initialized")));
    }

    @Test
    void buildRejectsBundledApiAndUnresolvedOrUndeclaredCompanions() throws Exception {
        Path root = reactor();
        Path pom = root.resolve("src/mod/main/pom.xml");
        String original = Files.readString(pom);
        for (String invalid : List.of(original.replace("<scope>provided</scope>", "<scope>compile</scope>"),
                original.replace("windowstolinux-mod-helper:jar:1.0.0", "missing-helper:jar:1.0.0"),
                original.replace("gold.debug.windowstolinux:windowstolinux-mod-helper:jar:1.0.0", ""))) {
            Files.writeString(pom, invalid);
            assertNotEquals(0, build(root));
            assertTrue(log(root).contains("CLASS build inputs could not be prepared"), () -> log(root));
            assertFalse(Files.exists(root.resolve("src/mod/main/target/wtlmod-development.json")));
            assertFalse(Files.exists(root.resolve("author-initialized")));
        }
        Files.writeString(pom, original);
        Files.writeString(root.resolve("src/mod/helper/mod.json"), "{}");
        assertNotEquals(0, build(root));
        assertTrue(log(root).contains("Other reactor Mods must have provided scope"), () -> log(root));
    }

    @Test
    void commandRejectsUnknownDuplicateOrStaleInputsWithOneStructuredResult() throws Exception {
        var fixture = new DevelopmentModDiscoveryTest();
        fixture.temporary = temporary;
        Path root = fixture.workspace();
        Path module = fixture.module(root, "main", "example.discovery");
        var envelope = json.createObjectNode().put("formatVersion", 1).put("workspace", root.toString())
                .put("manifest", module.resolve("mod.json").toString())
                .put("mainJar", module.resolve("target/unbuilt.jar").toString())
                .put("readme", module.resolve("README.md").toString())
                .put("license", module.resolve("LICENSE").toString())
                .put("mainOutput", module.resolve("target/classes").toString());
        envelope.putArray("authorJars");
        envelope.putArray("authorOutputs");
        envelope.putArray("libraries");
        var inputs = envelope.putArray("buildInputs");
        for (Path pom : List.of(root.resolve("pom.xml"), root.resolve("src/mod/pom.xml"), module.resolve("pom.xml")))
            inputs.add(pom.toString());
        Path input = module.resolve("target/input.json");
        Files.write(input, json.writeValueAsBytes(envelope));
        System.clearProperty("wtl.discovery.initialized");
        assertEquals(0, command(input));
        assertNull(System.getProperty("wtl.discovery.initialized"));
        envelope.put("unused", "not accepted");
        Files.write(input, json.writeValueAsBytes(envelope));
        assertEquals(2, command(input));
        envelope.remove("unused");
        Files.writeString(input, json.writeValueAsString(envelope).replace("\"formatVersion\":1",
                "\"formatVersion\":1,\"formatVersion\":1"));
        assertEquals(2, command(input));
        Files.write(input, json.writeValueAsBytes(envelope));
        Files.delete(module.resolve("target/classes/example/discovery/Mod.class"));
        assertEquals(3, command(input));
    }

    int command(Path input) throws Exception {
        var result = new ByteArrayOutputStream();
        var diagnostics = new ByteArrayOutputStream();
        int status;
        try (var out = new PrintStream(result, true, StandardCharsets.UTF_8);
                var err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            status = ModPackageTool.execute(new String[]{"describe", "--input", input.toString()}, out, err);
        }
        var value = json.readTree(result.toByteArray());
        assertEquals(status == 0, value.get("success").asBoolean());
        assertEquals("describe", value.get("command").asText());
        assertFalse(result.toString(StandardCharsets.UTF_8).contains("Exception"));
        assertEquals(status == 0, diagnostics.size() == 0);
        return status;
    }

    Path reactor() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(repository.resolve("docs/File.md")))
            repository = repository.getParent();
        Path root = temporary.toRealPath().resolve("Maven 中文 & spaces");
        write(root.resolve("pom.xml"),
                """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <groupId>gold.debug.windowstolinux</groupId><artifactId>build-fixture</artifactId><version>1.0.0</version><packaging>pom</packaging>
                        <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                        <modules><module>%s</module><module>%s</module><module>src/mod/helper</module><module>src/mod/main</module></modules>
                        <build><pluginManagement><plugins>
                        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                        </plugins></pluginManagement></build></project>
                        """
                        .formatted(xml(root.relativize(repository.resolve("src/appapi"))),
                                xml(root.relativize(repository.resolve("src/app/mod")))));
        write(root.resolve("src/app/pom.xml"), "<project/>\n");
        write(root.resolve("src/mod/pom.xml"), "<project/>\n");
        write(root.resolve(".mvn/wtlmod-author-parent/pom.xml"),
                Files.readString(repository.resolve(".mvn/wtlmod-author-parent/pom.xml")));
        write(root.resolve("src/mod/helper/pom.xml"),
                """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <parent><groupId>gold.debug.windowstolinux</groupId><artifactId>build-fixture</artifactId><version>1.0.0</version><relativePath>../../../pom.xml</relativePath></parent>
                        <artifactId>windowstolinux-mod-helper</artifactId></project>
                        """);
        write(root.resolve("src/mod/helper/src/main/java/fixture/helper/Helper.java"),
                "package fixture.helper; public final class Helper { public static String value(){ return \"real maven\"; } }");
        write(root.resolve("src/mod/main/pom.xml"),
                """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <parent><groupId>gold.debug.windowstolinux</groupId><artifactId>wtlmod-author-parent</artifactId><version>0.1.0-SNAPSHOT</version><relativePath>../../../.mvn/wtlmod-author-parent/pom.xml</relativePath></parent>
                        <artifactId>main</artifactId><version>1.0.0</version>
                        <properties><wtl.authorArtifacts>gold.debug.windowstolinux:windowstolinux-mod-helper:jar:1.0.0</wtl.authorArtifacts></properties>
                        <dependencies>
                        <dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-appapi</artifactId><scope>provided</scope></dependency>
                        <dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-mod-helper</artifactId><version>1.0.0</version></dependency>
                        <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version></dependency>
                        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><version>2.5.250</version><scope>test</scope></dependency>
                        </dependencies>
                        <build><filters><filter>src/main/filters/build.properties</filter></filters>
                        <resources><resource><directory>src/main/resources</directory><filtering>true</filtering></resource></resources></build>
                        </project>
                        """);
        write(root.resolve("src/mod/main/mod.json"),
                """
                        {"formatVersion":1,"id":"fixture.main","name":"Maven build","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/main.jar","entryClass":"fixture.main.Mod"}
                        """);
        write(root.resolve("src/mod/main/src/main/filters/build.properties"), "token=processed resource\n");
        write(root.resolve("src/mod/main/src/main/resources/example.txt"), "${token}");
        write(root.resolve("src/mod/main/src/main/java/fixture/main/Mod.java"),
                """
                        package fixture.main;
                        public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint {
                            public Mod() throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(%s), "constructed"); }
                            public void start(gold.debug.windowstolinux.appapi.mod.ModContext c) {}
                            public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c) {}
                            public String value() { return org.apache.commons.lang3.StringUtils.upperCase(fixture.helper.Helper.value()); }
                        }
                        """
                        .formatted(json.writeValueAsString(root.resolve("author-initialized").toString())));
        return root;
    }

    int build(Path root) throws Exception {
        Path maven = Path.of(Objects.requireNonNull(System.getProperty("wtl.test.mavenHome")));
        String repository = Objects.requireNonNull(System.getProperty("wtl.test.repository"));
        Path bootstrap;
        try (var files = Files.list(maven.resolve("boot"))) {
            bootstrap = files.filter(path -> path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dmaven.home=" + maven, "-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"),
                "-Dmaven.multiModuleProjectDirectory=" + root, "-classpath", bootstrap.toString(),
                "org.codehaus.plexus.classworlds.launcher.Launcher", "-B", "-o", "-f",
                root.resolve("pom.xml").toString(), "-Dmaven.repo.local=" + repository, "-DskipTests",
                "process-classes");
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("build.log").toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), () -> log(root));
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroy();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    static String log(Path root) {
        try {
            return Files.readString(root.resolve("build.log"));
        } catch (IOException failure) {
            return failure.getClass().getSimpleName();
        }
    }

    static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    static String xml(Path path) {
        return path.toString().replace('\\', '/').replace("&", "&amp;");
    }
}
