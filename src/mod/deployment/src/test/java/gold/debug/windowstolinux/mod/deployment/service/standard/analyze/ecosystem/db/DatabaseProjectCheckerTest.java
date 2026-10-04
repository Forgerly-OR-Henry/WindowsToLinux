package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseVersionRequirement;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.BoundedSourceChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseProjectCheckerTest {
    @TempDir
    Path root;

    @Test
    void sqliteManifestKeepsFileInitializationWithoutServerCredentials() throws Exception {
        Files.writeString(root.resolve("windowstolinux-db.properties"),
            "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.initialize=initial.sql\ndb.main.seed=seed.db\n");
        var database = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).databases().getFirst();
        assertEquals(DeploymentDatabaseEngineType.SQLITE, database.engine());
        assertEquals("", database.username());
        assertEquals("", database.passwordEnvironment());
        assertEquals("state/store.db", database.sqlite().orElseThrow().accessPath());
        assertEquals("seed.db", database.sqlite().orElseThrow().seedFile());
        assertEquals(List.of("initial.sql"), database.initializationFiles());
    }

    @Test
    void sqliteSpringRetainsLiteralPathAndDoesNotDefaultUnresolvedVariables() throws Exception {
        Files.writeString(root.resolve("application.properties"), "spring.datasource.url=jdbc:sqlite:storage/app.db\n");
        var database = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).databases().getFirst();
        assertEquals("storage/app.db", database.sqlite().orElseThrow().accessPath());
        assertEquals("SPRING_DATASOURCE_URL", database.sqlite().orElseThrow().pathEnvironment());
        Files.writeString(root.resolve("application.properties"), "spring.datasource.url=jdbc:sqlite:${DB_FILE}\n");
        database = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).databases().getFirst();
        assertEquals(
            gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentStorageLocation.StorageLocationType.UNRESOLVED,
            database.sqlite().orElseThrow().location().type());
    }

    @Test
    void sqliteMemoryIsNotOfferedAsPersistentDatabase() throws Exception {
        Files.writeString(root.resolve("application.properties"), "spring.datasource.url=jdbc:sqlite::memory:\n");
        var result = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertTrue(result.databases().isEmpty());
        assertFalse(result.unknownDatabase());
    }

    @Test
    void keepsExternalEngineAndRequiresExplicitEndpointDecisionWithoutLeakingPassword() throws Exception {
        Files.writeString(root.resolve("application.properties"),
            "spring.datasource.url=jdbc:mysql://db.example.test:3306/demo\nspring.datasource.password=top-secret\n");
        var assessment = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertEquals(DeploymentDatabaseEngineType.MYSQL, assessment.databases().getFirst().engine());
        assertTrue(assessment.endpointConfirmationRequired());
        assertFalse(assessment.toString().contains("top-secret"));
    }

    @Test
    void ignoresTestFixtureDatabases() throws Exception {
        Files.createDirectory(root.resolve("tests"));
        Files.writeString(root.resolve("tests/fixture.properties"),
            "spring.datasource.url=jdbc:postgresql://localhost/test");
        assertTrue(new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).databases().isEmpty());
    }

    @Test
    void explicitManifestPreservesTypesVersionsAndSelectedInitialization() throws Exception {
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE example(id integer);");
        Files.writeString(root.resolve("windowstolinux-db.properties"),
            "db=main,cache\ndb.main.engine=POSTGRESQL\ndb.main.version=>= 16\ndb.main.initialize=schema.sql\ndb.cache.engine=REDIS\n");
        var result = new DatabaseProjectChecker().inspect(root, (root).getFileName().toString());
        assertEquals(2, result.databases().size());
        assertEquals(List.of("schema.sql"), result.databases().getFirst().initializationFiles());
        assertTrue(new DatabaseVersionRequirement(">= 16").accepts("17.2"));
        assertFalse(new DatabaseVersionRequirement("16").accepts("17.2"));
    }

    @Test
    void schemaGuardRequiresBoundEvidenceAndDoesNotSimplyRemoveLegacyRejection() throws Exception {
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        var inspection = new BoundedSourceChecker().inspect(root, new ArrayList<>(), (root).getFileName().toString());
        var policy = new SourceMutationPolicy();
        List<RejectionReason> rejected = new ArrayList<>();
        policy.validate(inspection, rejected);
        assertFalse(rejected.isEmpty());
        rejected.clear();
        var proof = new DatabaseSchemaReview(SourceMutationPolicy.inspectionDigest(inspection), Set.of("main"),
            Set.of("schema.sql"), true, false, false);
        policy.validate(inspection, rejected, Optional.of(proof));
        assertTrue(rejected.isEmpty());
        Files.writeString(root.resolve("new.properties"), "flyway=true");
        policy.validate(new BoundedSourceChecker().inspect(root, new ArrayList<>(), (root).getFileName().toString()), rejected, Optional.of(proof));
        assertFalse(rejected.isEmpty());
    }

    @Test
    void declaredSpringInitializationOrdersSchemaBeforeDataAndRejectsAmbiguousFiles() throws Exception {
        Files.writeString(root.resolve("application.properties"),
            "spring.datasource.url=jdbc:postgresql://localhost/demo\nspring.sql.init.mode=always\n");
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        Files.writeString(root.resolve("data.sql"), "INSERT INTO sample VALUES (1);");
        assertEquals(List.of("schema.sql", "data.sql"),
            new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).databases().getFirst().initializationFiles());
        Files.createDirectory(root.resolve("alternate"));
        Files.writeString(root.resolve("alternate/schema.sql"), "CREATE TABLE different(id integer);");
        assertTrue(new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).databases().getFirst().initializationFiles().isEmpty());
    }

    @Test
    void jpaSchemaChangesRequireReviewAndSqlContentChangesInvalidateProof() throws Exception {
        Files.writeString(root.resolve("application.properties"), "spring.jpa.hibernate.ddl-auto=update\n");
        assertTrue(new DatabaseProjectChecker().inspect(root, (root).getFileName().toString()).schemaReviewRequired());
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id integer);");
        var inspector = new BoundedSourceChecker();
        String before = SourceMutationPolicy.inspectionDigest(inspector.inspect(root, new ArrayList<>(), (root).getFileName().toString()));
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample(id bigint);");
        assertNotEquals(before, SourceMutationPolicy.inspectionDigest(inspector.inspect(root, new ArrayList<>(), (root).getFileName().toString())));
    }
}
