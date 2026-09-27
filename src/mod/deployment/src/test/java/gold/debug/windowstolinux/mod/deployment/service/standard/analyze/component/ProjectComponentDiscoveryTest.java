package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectComponentDiscoveryTest {
    @TempDir
    Path root;

    @Test
    void distinguishesKotlinApplicationsFromBootAndPrebuiltJavaJars() throws Exception {
        Path script = root.resolve("build.gradle.kts");
        Files.writeString(script, "plugins { application; kotlin(\"jvm\") version \"2.0.21\" }");
        assertType(DeploymentProjectKind.KOTLIN_SERVICE);
        Files.writeString(script,
                "plugins { kotlin(\"jvm\") version \"2.0.21\"; id(\"org.springframework.boot\") version \"3.4.0\" }");
        assertType(DeploymentProjectKind.SPRING_BOOT);
        Files.writeString(script, "plugins { application; java }");
        assertType(DeploymentProjectKind.JAVA_JAR);
    }

    private void assertType(DeploymentProjectKind type) throws Exception {
        var components = new ProjectComponentDiscovery().discover(root);
        assertEquals(1, components.size());
        assertEquals(List.of(type), components.getFirst().types());
    }
}
