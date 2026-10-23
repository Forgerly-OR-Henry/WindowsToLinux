package gold.debug.windowstolinux.mod.deployment.ai.skill;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies bounded packaged instructions without model or remote access. / 不访问模型或远端，验证有界随包说明。 */
class DeploymentSkillResourcesTest {
    @TempDir
    Path directory;

    @Test
    void exactUtf8TextIsCachedPerOwningClassloader() throws Exception {
        Class<?> first = owner(Map.of("fixture.md", bytes(" 说明\n")));
        assertEquals(" 说明\n", DeploymentSkillResources.text(first, "fixture.md"));
        assertSame(DeploymentSkillResources.text(first, "fixture.md"), DeploymentSkillResources.text(first, "fixture.md"));
        Class<?> second = owner(Map.of("fixture.md", bytes("other loader")));
        assertEquals("other loader", DeploymentSkillResources.text(second, "fixture.md"));
    }

    @Test
    void missingInvalidEmptyAndOversizedResourcesAreBlocked() throws Exception {
        for (byte[] content : new byte[][]{bytes(" \n"), {(byte) 0xc3, 0x28}, bytes("\uFEFFtext"), bytes("text\0"), new byte[65_537]}) {
            Class<?> fixture = owner(Map.of("fixture.md", content));
            assertThrows(IllegalStateException.class, () -> DeploymentSkillResources.require(fixture, "fixture.md"));
        }
        Class<?> missing = owner(Map.of());
        assertThrows(IllegalStateException.class, () -> DeploymentSkillResources.text(missing, "missing.md"));
        Class<?> exact = owner(Map.of("fixture.md", bytes("x".repeat(65_536))));
        assertEquals(65_536, DeploymentSkillResources.text(exact, "fixture.md").length());
    }

    @Test
    void catalogsRejectDuplicatesMalformedEscapesAndMissingEntries() throws Exception {
        for (String value : new String[]{"read=first\nread=second", "read= ", "read=\\uNOT0", "# empty"}) {
            Class<?> fixture = owner(Map.of("tools.properties", bytes(value)));
            assertThrows(IllegalStateException.class, () -> DeploymentSkillResources.require(fixture, "tools.properties"));
        }
        Class<?> valid = owner(Map.of("tools.properties", bytes("read=读取 %s; {path,offset,limit}\n")));
        assertEquals("读取 main; {path,offset,limit}", DeploymentSkillResources.description(valid, "tools.properties", "read").formatted("main"));
        assertThrows(IllegalStateException.class, () -> DeploymentSkillResources.description(valid, "tools.properties", "write"));
        assertThrows(IllegalStateException.class, () -> DeploymentSkillResources.requireDescriptions(valid, "tools.properties", "read", "write"));
    }

    @Test
    void externalPathsAndForeignOwnersCannotLoadInstructions() throws Exception {
        Class<?> fixture = owner(Map.of("fixture.md", bytes("instruction")));
        for (String name : new String[]{"../fixture.md", "/fixture.md", "C:/fixture.md", "https://fixture.md", "nested/fixture.md", "fixture.txt"})
            assertThrows(IllegalArgumentException.class, () -> DeploymentSkillResources.text(fixture, name));
        assertThrows(IllegalArgumentException.class, () -> DeploymentSkillResources.text(String.class, "fixture.md"));
    }

    @Test
    void isolatedJarLoaderUsesOnlyJdkAndPreservesBundledInstruction() throws Exception {
        String expected = DeploymentSkillResources.text(DeploymentAgentProtocol.class, "autonomous-deployment.md");
        Path jar = directory.resolve("instructions.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> type : new Class<?>[]{DeploymentSkillResources.class, FixtureOwner.class})
                entry(output, type.getName().replace('.', '/') + ".class", classBytes(type));
            String loader = DeploymentSkillResources.class.getName().replace('.', '/');
            for (int index = 1; index <= 3; index++) {
                String name = loader + "$" + index + ".class";
                try (var input = DeploymentSkillResources.class.getResourceAsStream("/" + name)) {
                    assertNotNull(input);
                    entry(output, name, input.readAllBytes());
                }
            }
            entry(output, FixtureOwner.class.getPackageName().replace('.', '/') + "/fixture.md", bytes(expected));
        }
        try (var isolated = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            Class<?> loader = Class.forName(DeploymentSkillResources.class.getName(), true, isolated);
            Class<?> fixture = Class.forName(FixtureOwner.class.getName(), false, isolated);
            assertEquals(expected, loader.getMethod("text", Class.class, String.class).invoke(null, fixture, "fixture.md"));
        }
    }

    @Test
    void distributionContainsResourcesAndOnlyCurrentProtocolClasses() throws Exception {
        String configured = System.getProperty("skill.packagedJar", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(configured.isEmpty(), "Run after packaging with the explicit distribution path");
        try (var jar = new JarFile(configured)) {
            String root = "gold/debug/windowstolinux/mod/deployment/";
            assertNotNull(jar.getEntry(root + "ai/agent/DeploymentModelToolLoop.class"));
            assertNull(jar.getEntry(root + "ai/DeploymentModelToolLoop.class"));
            assertNull(jar.getEntry(root + "ai/agent/DeploymentAgentProtocol.class"));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith(root + "ai/agent/DeploymentAgent")),
                "No obsolete autonomous protocol classes may ship in the shared package");
            Path source = Path.of("src/main/resources", root);
            try (var files = Files.walk(source)) {
                var resources = files.filter(Files::isRegularFile).filter(path -> {
                    String name = source.relativize(path).toString().replace('\\', '/');
                    return (name.startsWith("ai/") || name.startsWith("service/agent/") || name.startsWith("service/standard/assisted/")
                        || name.equals("linux/command/contract/host-command.md"))
                        && (name.endsWith(".md") || name.endsWith(".properties"));
                }).toList();
                assertFalse(resources.isEmpty(), "The maintained resource inventory must not be empty");
                for (Path path : resources) {
                    String name = source.relativize(path).toString().replace('\\', '/');
                    assertTrue(name.matches("service/agent/skill/[a-z][a-z0-9-]*/SKILL\\.md")
                        || path.getFileName().toString().matches("[a-z][a-z0-9-]*\\.(md|properties)"), name);
                    var resource = jar.getJarEntry(root + name);
                    assertNotNull(resource, name);
                    try (var input = jar.getInputStream(resource)) {
                        assertArrayEquals(Files.readAllBytes(path), input.readAllBytes(), name);
                    }
                }
            }
        }
    }

    private static Class<?> owner(Map<String, byte[]> resources) throws Exception {
        byte[] definition = classBytes(FixtureOwner.class);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] value = resources.get(name.substring(name.lastIndexOf('/') + 1));
                return value == null ? null : new ByteArrayInputStream(value);
            }

            Class<?> defineOwner() {
                return defineClass(FixtureOwner.class.getName(), definition, 0, definition.length);
            }
        }.defineOwner();
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static void entry(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FixtureOwner {
    }
}
