package gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.framework.springboot;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootDeploymentInspectorTest {
    @TempDir
    Path temporaryDirectory;

    private final DeploymentAnalysisCoordinator analyzer = new DeploymentAnalysisCoordinator();

    @Test
    void selectsMavenWrapperOnlyWhenTheRootWrapperIsComplete() throws Exception {
        Path project = mavenProject("maven-wrapper", bootPom());
        Files.writeString(project.resolve("mvnw"), "wrapper placeholder");
        Files.createDirectories(project.resolve(".mvn/wrapper"));
        Files.writeString(project.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://example.test/apache-maven.zip");

        var assessment = analyze(project);

        assertEquals(DeploymentAdmission.READY_FOR_PLANNING, assessment.admission());
        assertEquals(DeploymentBuildTool.MAVEN_WRAPPER, assessment.facts().orElseThrow().buildTool());
    }

    @Test
    void deterministicallyUsesTargetMavenForWindowsOnlyAndIncompleteWrappers() throws Exception {
        Path windowsOnly = mavenProject("windows-wrapper", bootPom());
        Files.writeString(windowsOnly.resolve("mvnw.cmd"), "windows wrapper placeholder");
        Path incomplete = mavenProject("incomplete-wrapper", bootPom());
        Files.writeString(incomplete.resolve("mvnw"), "incomplete wrapper placeholder");

        assertEquals(DeploymentBuildTool.MAVEN, analyze(windowsOnly).facts().orElseThrow().buildTool());
        assertEquals(DeploymentBuildTool.MAVEN, analyze(incomplete).facts().orElseThrow().buildTool());
    }

    @Test
    void acceptsACompleteGradleWrapper() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle-wrapper"));
        Files.writeString(project.resolve("build.gradle.kts"),
                "plugins { id(\"org.springframework.boot\") version \"3.5.0\" }");
        Files.writeString(project.resolve("gradlew"), "wrapper placeholder");
        Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https://example.test/gradle.zip");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(project.resolve("gradle/wrapper/gradle-wrapper.jar")))) {
            output.putNextEntry(new JarEntry("org/gradle/wrapper/GradleWrapperMain.class"));
            output.write(0);
            output.closeEntry();
        }

        var assessment = analyze(project);

        assertEquals(DeploymentAdmission.READY_FOR_PLANNING, assessment.admission());
        assertEquals(DeploymentBuildTool.GRADLE_WRAPPER, assessment.facts().orElseThrow().buildTool());
    }

    @Test
    void rejectsConflictingBuildSystemsMissingPluginAndWarPackaging() throws Exception {
        Path conflict = mavenProject("conflict", bootPom());
        Files.writeString(conflict.resolve("build.gradle"), "plugins { id 'org.springframework.boot' }");
        assertRejected(conflict, "SPRING_BOOT_BUILD_AMBIGUOUS");

        assertRejected(mavenProject("plugin-missing", "<project><artifactId>plugin-missing</artifactId></project>"),
                "SPRING_BOOT_PLUGIN_MISSING");
        assertRejected(mavenProject("war", "<project><artifactId>war</artifactId><packaging>war</packaging>"
                + "<build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId>"
                + "</plugin></plugins></build></project>"), "UNSUPPORTED_WAR");
    }

    @Test
    void appliesMigrationSchemaExternalConfigurationAndSecretPoliciesToMaven() throws Exception {
        Path migration = mavenProject("migration", bootPom().replace("</project>",
                "<dependencies><dependency><artifactId>flyway-core</artifactId></dependency></dependencies></project>"));
        assertRejected(migration, "DATABASE_MIGRATION_DETECTED");

        Path schema = mavenProject("schema", bootPom());
        Files.createDirectories(schema.resolve("src/main/resources"));
        Files.writeString(schema.resolve("src/main/resources/schema.sql"), "create table demo (id bigint);");
        assertRejected(schema, "AUTOMATIC_SCHEMA_MUTATION_DETECTED");

        Path configuration = mavenProject("configuration", bootPom());
        Files.createDirectories(configuration.resolve("src/main/resources"));
        Files.writeString(configuration.resolve("src/main/resources/application.yml"),
                "spring.config.import: optional:file:./external.yml\napi-key: secret");
        var assessment = analyze(configuration);
        assertEquals(DeploymentAdmission.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream().anyMatch(reason ->
                reason.code().equals("EXTERNAL_CONFIGURATION_DETECTED")));
        assertTrue(assessment.rejections().stream().anyMatch(reason ->
                reason.code().equals("APPLICATION_SECRET_DETECTED")));
    }

    @Test
    void rejectsDisabledExecutableJarForEitherBuildSystem() throws Exception {
        Path maven = mavenProject("maven-disabled", bootPom().replace("</plugin>", "<skip>true</skip></plugin>"));
        assertRejected(maven, "SPRING_BOOT_EXECUTABLE_JAR_DISABLED");

        Path gradle = Files.createDirectories(temporaryDirectory.resolve("gradle-disabled"));
        Files.writeString(gradle.resolve("build.gradle"),
                "plugins { id 'org.springframework.boot' version '3.5.0' }\nbootJar { enabled = false }");
        assertRejected(gradle, "SPRING_BOOT_EXECUTABLE_JAR_DISABLED");
    }

    private gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment analyze(Path project) {
        return analyzer.analyze(project, DeploymentProjectType.SPRING_BOOT);
    }

    private void assertRejected(Path project, String code) {
        var assessment = analyze(project);
        assertEquals(DeploymentAdmission.REJECTED, assessment.admission());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals(code)),
                () -> "Expected rejection " + code + " but found " + assessment.rejections());
    }

    private Path mavenProject(String name, String pom) throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(project.resolve("pom.xml"), pom);
        return project;
    }

    private static String bootPom() {
        return "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId>"
                + "</plugin></plugins></build></project>";
    }
}
