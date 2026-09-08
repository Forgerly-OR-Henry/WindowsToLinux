package gold.debug.windowstolinux.shared.analyze.ecosystem.db;

import gold.debug.windowstolinux.shared.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.ecosystem.db.*;
import gold.debug.windowstolinux.shared.model.ecosystem.db.sql.DatabaseVersionRequirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseProjectInspectorTest {
    @TempDir Path root;
    @Test void keepsExternalEngineAndRequiresExplicitEndpointDecisionWithoutLeakingPassword() throws Exception {
        Files.writeString(root.resolve("application.properties"),"spring.datasource.url=jdbc:mysql://db.example.test:3306/demo\nspring.datasource.password=top-secret\n");
        var assessment = new DatabaseProjectInspector().inspect(root);
        assertEquals(DatabaseEngineType.MYSQL,assessment.databases().getFirst().engine());
        assertTrue(assessment.endpointConfirmationRequired()); assertFalse(assessment.toString().contains("top-secret"));
    }
    @Test void ignoresTestFixtureDatabases() throws Exception {
        Files.createDirectory(root.resolve("tests"));
        Files.writeString(root.resolve("tests/fixture.properties"),"spring.datasource.url=jdbc:postgresql://localhost/test");
        assertTrue(new DatabaseProjectInspector().inspect(root).databases().isEmpty());
    }
    @Test void explicitManifestPreservesTypesVersionsAndSelectedInitialization() throws Exception {
        Files.writeString(root.resolve("schema.sql"),"CREATE TABLE example(id integer);");
        Files.writeString(root.resolve("windowstolinux-db.properties"),"db=main,cache\ndb.main.engine=POSTGRESQL\ndb.main.version=>= 16\ndb.main.initialize=schema.sql\ndb.cache.engine=REDIS\n");
        var result = new DatabaseProjectInspector().inspect(root);
        assertEquals(2,result.databases().size()); assertEquals(List.of("schema.sql"),result.databases().getFirst().initializationFiles());
        assertTrue(new DatabaseVersionRequirement(">= 16").accepts("17.2"));
        assertFalse(new DatabaseVersionRequirement("16").accepts("17.2"));
    }
    @Test void schemaGuardRequiresBoundEvidenceAndDoesNotSimplyRemoveLegacyRejection() throws Exception {
        Files.writeString(root.resolve("schema.sql"),"CREATE TABLE sample(id integer);");
        var inspection = new BoundedSourceInspector().inspect(root,new ArrayList<>());
        var policy = new SourceMutationPolicy(); List<RejectionReason> rejected = new ArrayList<>();
        policy.validate(inspection,rejected); assertFalse(rejected.isEmpty());
        rejected.clear();
        var proof = new DatabaseSchemaReview(SourceMutationPolicy.inspectionDigest(inspection),Set.of("main"),Set.of("schema.sql"),true,false,false);
        policy.validate(inspection,rejected,Optional.of(proof)); assertTrue(rejected.isEmpty());
        Files.writeString(root.resolve("new.properties"),"flyway=true");
        policy.validate(new BoundedSourceInspector().inspect(root,new ArrayList<>()),rejected,Optional.of(proof));
        assertFalse(rejected.isEmpty());
    }
    @Test void declaredSpringInitializationOrdersSchemaBeforeDataAndRejectsAmbiguousFiles() throws Exception {
        Files.writeString(root.resolve("application.properties"),"spring.datasource.url=jdbc:postgresql://localhost/demo\nspring.sql.init.mode=always\n");
        Files.writeString(root.resolve("schema.sql"),"CREATE TABLE sample(id integer);");
        Files.writeString(root.resolve("data.sql"),"INSERT INTO sample VALUES (1);");
        assertEquals(List.of("schema.sql","data.sql"),new DatabaseProjectInspector().inspect(root).databases().getFirst().initializationFiles());
        Files.createDirectory(root.resolve("alternate"));
        Files.writeString(root.resolve("alternate/schema.sql"),"CREATE TABLE different(id integer);");
        assertTrue(new DatabaseProjectInspector().inspect(root).databases().getFirst().initializationFiles().isEmpty());
    }
    @Test void jpaSchemaChangesRequireReviewAndSqlContentChangesInvalidateProof() throws Exception {
        Files.writeString(root.resolve("application.properties"),"spring.jpa.hibernate.ddl-auto=update\n");
        assertTrue(new DatabaseProjectInspector().inspect(root).schemaReviewRequired());
        Files.writeString(root.resolve("schema.sql"),"CREATE TABLE sample(id integer);");
        var inspector = new BoundedSourceInspector();
        String before = SourceMutationPolicy.inspectionDigest(inspector.inspect(root,new ArrayList<>()));
        Files.writeString(root.resolve("schema.sql"),"CREATE TABLE sample(id bigint);");
        assertNotEquals(before,SourceMutationPolicy.inspectionDigest(inspector.inspect(root,new ArrayList<>())));
    }
}
