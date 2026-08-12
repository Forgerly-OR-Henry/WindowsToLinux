package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.model.analysis.ProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.SupportDecision;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticProjectAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    private final StaticProjectAnalyzer analyzer = new StaticProjectAnalyzer();

    @Test
    void acceptsAPlainMavenSpringBootJarWithoutExternalRequirements() throws Exception {
        Path project = project("accepted", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId>"
                + "</plugin></plugins></build></project>");
        Files.writeString(project.resolve("mvnw"), "wrapper placeholder");
        Files.createDirectories(project.resolve(".mvn/wrapper"));
        Files.writeString(project.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://example.test/apache-maven.zip");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/Application.java"), "class Application {}");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.SUPPORTED, assessment.decision());
        assertTrue(assessment.facts().orElseThrow().usesMavenWrapper());
        assertEquals("demo", assessment.facts().orElseThrow().applicationName());
        assertTrue(assessment.facts().orElseThrow().evidence().stream().anyMatch(evidence ->
                evidence.subject().key().equals("analysis.evidence.mavenEntry")
                        && evidence.source().equals("pom.xml")
                        && evidence.confidence() == EvidenceConfidence.HIGH
        ));
        assertTrue(assessment.facts().orElseThrow().conflicts().isEmpty());
        assertTrue(assessment.facts().orElseThrow().missingInformation().isEmpty());
    }

    @Test
    void rejectsSymbolicLinksBeforeTheTarGzipArchiverCanCrossTheSourceBoundary() throws Exception {
        Path project = project("symbolic-link", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId>"
                + "</plugin></plugins></build></project>");
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.java"), "class Outside {}");
        try {
            Files.createSymbolicLink(project.resolve("linked.java"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertTrue(assessment.rejections().stream().anyMatch(reason ->
                reason.code().equals("SYMBOLIC_LINK_DETECTED") || reason.code().equals("UNSAFE_SOURCE_ENTRY")
        ));
    }

    @Test
    void rejectsDatabaseMigrationsAndDoesNotRunAnything() throws Exception {
        Path project = project("migration", ""
                + "<project><artifactId>demo</artifactId><dependencies><dependency>"
                + "<artifactId>flyway-core</artifactId></dependency></dependencies><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("DATABASE_MIGRATION_DETECTED")));
    }

    @Test
    void rejectsExternalConfigAndApplicationSecrets() throws Exception {
        Path project = project("config", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/application.yml"), "spring.config.import: optional:file:./external.yml\napi-key: secret");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("EXTERNAL_CONFIGURATION_DETECTED")));
        assertTrue(assessment.rejections().stream().anyMatch(reason -> reason.code().equals("APPLICATION_SECRET_DETECTED")));
    }

    @Test
    void rejectsHibernateSchemaMutation() throws Exception {
        Path project = project("schema-change", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "spring.jpa.hibernate.ddl-auto=update");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertTrue(assessment.rejections().stream()
                .anyMatch(reason -> reason.code().equals("AUTOMATIC_SCHEMA_MUTATION_DETECTED")));
    }

    @Test
    void rejectsAutomaticSchemaScriptEvenWhenItIsNotReferencedByText() throws Exception {
        Path project = project("schema-script", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/schema.sql"), "create table demo (id bigint primary key);");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertTrue(assessment.rejections().stream()
                .anyMatch(reason -> reason.code().equals("AUTOMATIC_SCHEMA_MUTATION_DETECTED")));
    }

    @Test
    void requiresTargetMavenWhenOnlyWindowsWrapperIsPresent() throws Exception {
        Path project = project("windows-wrapper", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Files.writeString(project.resolve("mvnw.cmd"), "windows wrapper placeholder");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.SUPPORTED, assessment.decision());
        assertFalse(assessment.facts().orElseThrow().usesMavenWrapper());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(item -> item.key().equals("analysis.missing.windowsWrapperOnly")));
    }

    @Test
    void requiresTargetMavenForAnIncompleteOrNestedWrapper() throws Exception {
        Path project = project("incomplete-wrapper", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Files.writeString(project.resolve("mvnw"), "incomplete wrapper placeholder");
        Files.createDirectories(project.resolve("nested"));
        Files.writeString(project.resolve("nested/mvnw"), "nested wrapper placeholder");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.SUPPORTED, assessment.decision());
        assertFalse(assessment.facts().orElseThrow().usesMavenWrapper());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(item -> item.key().equals("analysis.missing.wrapperProperties")));
    }

    @Test
    void rejectsOversizedTextInsteadOfSkippingAnUnboundedStaticRead() throws Exception {
        Path project = project("oversized-source", ""
                + "<project><artifactId>demo</artifactId><build><plugins><plugin>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.write(project.resolve("src/main/java/Oversized.java"), new byte[2 * 1024 * 1024 + 1]);

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertTrue(assessment.rejections().stream()
                .anyMatch(reason -> reason.code().equals("SOURCE_TEXT_ENTRY_TOO_LARGE")));
    }

    @Test
    void rejectsGradleWithAnExplicitNextAction() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle"));
        Files.writeString(project.resolve("build.gradle"), "plugins {}");

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.REJECTED, assessment.decision());
        assertEquals("UNSUPPORTED_BUILD", assessment.rejections().getFirst().code());
        assertEquals("deployment", assessment.rejections().getFirst().nextAction());
    }

    @Test
    void usesTheRootArtifactInsteadOfTheParentArtifact() throws Exception {
        Path project = project("parent", """
                <project><parent><artifactId>parent-artifact</artifactId></parent>
                <artifactId>child-app</artifactId><build><plugins><plugin>
                <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>
                """);

        ProjectAssessment assessment = analyzer.analyze(project);

        assertEquals(SupportDecision.SUPPORTED, assessment.decision());
        assertEquals("child-app", assessment.facts().orElseThrow().applicationName());
        assertTrue(assessment.facts().orElseThrow().missingInformation().stream()
                .anyMatch(item -> item.key().equals("analysis.missing.mavenRequired")));
    }

    private Path project(String name, String pom) throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(project.resolve("pom.xml"), pom);
        return project;
    }
}
