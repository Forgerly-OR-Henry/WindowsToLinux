package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.db.mod.ModRestartLedger;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.db.schema.SchemaSnapshot;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModMigrationTest {
    @TempDir
    Path root;

    PlatformModInitializationTest initial;

    PlatformModFlowTest fixture;

    SchemaIdentity original;

    static final String OWNER = PlatformModInitializationTest.OWNER, NEXT = "2.0.0";

    @BeforeEach
    void initializeOriginalBusinessData() throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        fixture = initial.fixture;
        initial.module("valid", false);
        fixture.enable(OWNER);
        try (var runtime = fixture.open(false)) {
            assertEquals(Set.of(OWNER), fixture.start(runtime).running());
            original = new SchemaIdentity(OWNER, "1",
                UUID.fromString(text(runtime, "SELECT generation FROM mod_schema")));
        }
        try (var runtime = fixture.open(false);
             var writer = new SchemaAccessScope(runtime.persistence().database(), original, UUID.randomUUID(),
                 false);
             var c = writer.openConnection();
             var s = c.createStatement()) {
            s.execute("CREATE SEQUENCE next_id START WITH 1");
        }
    }

    void buildUpgrade(String mode) throws Exception {
        var module = fixture.workspace.resolve("src/mod/" + OWNER);
        var classes = module.resolve("target/classes");
        var sources = new ArrayList<String>();
        for (int from = 1; from <= 2; from++) {
            String name = "Step" + from + (from + 1);
            var source = module.resolve(name + ".java");
            Files.writeString(source,
                """
                    package example.provider.db;
                    import java.nio.file.*;
                    import java.sql.*;
                    import gold.debug.windowstolinux.appapi.db.schema.SchemaAccess;
                    import gold.debug.windowstolinux.appapi.db.schema.SchemaMigration;
                    
                    public final class %s implements SchemaMigration {
                        public void migrate(SchemaAccess source, SchemaAccess target) throws Exception {
                            Files.writeString(Path.of("%s"),"migration:%s->%s\\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                            try(var from=source.openConnection();var to=target.openConnection();var read=from.createStatement();var write=to.createStatement()) {
                                try {read.executeUpdate("DELETE FROM item");throw new AssertionError("source could write");}
                                catch(SQLException denied) {}
                                if("%s".equals("sourceSequence") && %s==1)
                                    try(var r=read.executeQuery("SELECT NEXT VALUE FOR next_id")){r.next();}
                                write.execute("CREATE TABLE item(id INT PRIMARY KEY,value_text VARCHAR NOT NULL,a INT,b INT)");
                                try(var rows=read.executeQuery("SELECT id,value_text,a,b FROM item ORDER BY id");
                                    var insert=to.prepareStatement("INSERT INTO item VALUES(?,?,?,?)")) {
                                    while(rows.next()) {
                                        insert.setInt(1,rows.getInt(1));
                                        insert.setString(2,rows.getString(2)+"-%s");
                                        insert.setObject(3,rows.getObject(3));insert.setObject(4,rows.getObject(4));insert.executeUpdate();
                                    }
                                }
                                if(%s==2) {
                                    switch("%s") {
                                        case "secondError" -> throw new java.util.ServiceConfigurationError("private migration provider failure");
                                        case "secondFatal" -> throw new InternalError("synthetic migration VM error");
                                        case "secondFailure" -> throw new IllegalStateException("private fixture failure");
                                    }
                                }
                                if("%s".equals("writerLeak") && %s==1) {
                                    System.getProperties().put("wtl.migration.fixture.connection",target.openConnection());
                                    System.getProperties().put("wtl.migration.fixture.scope",target);
                                }
                            }
                        }
                    }
                    """
                    .formatted(name, fixture.trace.toString().replace("\\", "\\\\"), from, from + 1, mode, from,
                        from + 1, from, mode, mode, from));
            sources.add(source.toString());
        }
        String cp = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var args = new ArrayList<>(List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-classpath", cp,
            "-d", classes.toString()));
        args.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        Files.writeString(module.resolve("mod.json"),
            manifest(OWNER, NEXT, Map.of(), "3",
                List.of(Map.of("from", "1", "to", "2", "class", "example.provider.db.Step12"),
                    Map.of("from", "2", "to", "3", "class", "example.provider.db.Step23"))));
        DevelopmentInfo.generate(fixture.workspace, module.resolve("mod.json"), classes, List.of(),
            List.of(fixture.workspace.resolve("pom.xml"), fixture.workspace.resolve("src/mod/pom.xml"),
                module.resolve("pom.xml")),
            List.of());
    }

    void upgrade(String mode) throws Exception {
        buildUpgrade(mode);
        try (var runtime = fixture.open(true)) {
            var report = new PlatformModCatalog(runtime.persistence().database()).refresh(fixture.workspace);
            assertTrue(report.rejections().isEmpty(), report.rejections().toString());
            var service = runtime.modIntents();
            service.apply(service.review(OWNER, choice(true, NEXT), Optional.of(original)), false);
        }
        Files.writeString(fixture.trace, "");
    }

    String sourceValue(PlatformRuntime runtime) throws Exception {
        return text(runtime,
            "SELECT value_text FROM \"" + new SchemaCoordinates(original).physicalName() + "\".item WHERE id=1");
    }

    @Test
    void confirmedChainUsesTemporaryIntermediateAndStartsOnlyFinalValidatedTarget() throws Exception {
        upgrade("valid");
        String generation;
        try (var runtime = fixture.open(false)) {
            String before = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(original));
            var report = fixture.start(runtime);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(OWNER), report.running());
            assertEquals(List.of("migration:1->2", "migration:2->3", "schema:validate", OWNER + ":construct",
                OWNER + ":start"), fixture.actions());
            assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 't\\_%' ESCAPE '\\'"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_migration_step"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(before,
                SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(original)));
            assertEquals("initial", sourceValue(runtime));
            String target = text(runtime, "SELECT physical_name FROM mod_schema WHERE schema_version='3'");
            assertEquals("initial-2-3", text(runtime, "SELECT value_text FROM \"" + target + "\".item WHERE id=1"));
            assertEquals(NEXT, text(runtime, "SELECT actual_version FROM mod_registry"));
            generation = text(runtime, "SELECT generation FROM mod_schema WHERE schema_version='3'");
        }
        try (var runtime = fixture.open(false)) {
            assertEquals(Set.of(OWNER), fixture.start(runtime).running());
            assertEquals(generation, text(runtime, "SELECT generation FROM mod_schema WHERE schema_version='3'"));
        }
        assertEquals(2, fixture.actions().stream().filter(x -> x.startsWith("migration:")).count());
    }

    @Test
    void failedSecondStepKeepsOriginalAndSelectedTargetAndRemovesOnlyProvenIntermediates() throws Exception {
        upgrade("secondFailure");
        try (var runtime = fixture.open(false)) {
            var report = fixture.start(runtime);
            assertTrue(report.running().isEmpty());
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='1'"));
            assertEquals("initial", sourceValue(runtime));
            assertEquals(NEXT, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("FAILED", PlatformModFlowTest.state(runtime, OWNER));
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 't\\_%' ESCAPE '\\'"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
        }
        assertEquals(List.of("migration:1->2", "migration:2->3"), fixture.actions());
    }

    @Test
    void migrationProviderErrorKeepsOriginalDataAndDoesNotReplayEdges() throws Exception {
        upgrade("secondError");
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='1'"));
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals("initial", sourceValue(runtime));
            assertEquals(0, runtime.persistence().database().openConnections());
        }
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("initial", sourceValue(runtime));
        }
        assertEquals(List.of("migration:1->2", "migration:2->3"), fixture.actions());
    }

    @Test
    void fatalMigrationPropagatesWithoutLosingOriginalSourceOrScopes() throws Exception {
        upgrade("secondFatal");
        try (var runtime = fixture.open(false)) {
            assertThrows(InternalError.class, () -> fixture.start(runtime));
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='1'"));
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals("initial", sourceValue(runtime));
            assertEquals(0, runtime.persistence().database().openConnections());
        }
        assertEquals(List.of("migration:1->2", "migration:2->3"), fixture.actions());
    }

    @Test
    void lateMigrationArchiveFailureRetriesEvidenceWithoutRepeatingAnyEdge() throws Exception {
        upgrade("valid");
        var runtime = fixture.open(false);
        try {
            sql(runtime.persistence().database(),
                "ALTER TABLE runtime_log ADD CONSTRAINT fixture_migration_archive CHECK(event_code<>'MOD_SCHEMA_MIGRATED')");
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertThrows(SQLException.class, runtime::close);
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT fixture_migration_archive");
            runtime.close();
            try (var reopened = fixture.open(false)) {
                assertEquals("READY", text(reopened, "SELECT state FROM mod_schema WHERE schema_version='3'"));
                assertEquals(Set.of(OWNER), fixture.start(reopened).running());
            }
        } finally {
            runtime.close();
        }
        assertEquals(2, fixture.actions().stream().filter(x -> x.startsWith("migration:")).count());
        assertEquals(1, fixture.actions().stream().filter("schema:validate"::equals).count());
    }

    @Test
    void sourceSequenceSideEffectIsDetectedAndRetainedAsUnknown() throws Exception {
        upgrade("sourceSequence");
        try (var runtime = fixture.open(false)) {
            var report = fixture.start(runtime);
            assertTrue(report.running().isEmpty());
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"),
                report.problems().toString());
            assertEquals(2, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_operation WHERE state='UNKNOWN'"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 't\\_%' ESCAPE '\\'"));
            assertEquals("initial", sourceValue(runtime));
        }
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
        }
        assertEquals(List.of("migration:1->2"), fixture.actions());
    }

    @Test
    void restartRetainsOriginalStepInventoryAndDoesNotReplayInterruptedEdge() throws Exception {
        upgrade("valid");
        String intermediate;
        try (var runtime = fixture.open(false)) {
            var ledger = new ModRestartLedger(runtime.persistence().database());
            UUID id = UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation"));
            var plan = ledger.beginPreparation(id,
                text(runtime, "SELECT manifest_json FROM mod_version WHERE version='" + NEXT + "'"),
                text(runtime, "SELECT content_sha256 FROM mod_version WHERE version='" + NEXT + "'"));
            String snapshot = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(original));
            ledger.sourceDigest(plan, snapshot, false);
            ledger.preparationPhase(plan, false);
            var step = plan.steps().getFirst();
            ledger.beginStep(plan, step, snapshot);
            var coordinates = ModRestartLedger.targetCoordinates(plan, step);
            new SchemaProvisioner(runtime.persistence().database()).create(coordinates);
            intermediate = coordinates.physicalName();
            assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
        }
        try (var reopened = fixture.open(false)) {
            assertEquals("UNKNOWN", text(reopened, "SELECT state FROM mod_migration_step WHERE step_number=0"));
            assertEquals("UNKNOWN", text(reopened, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals(1, count(reopened.persistence().database(),
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + intermediate + "'"));
            assertTrue(fixture.start(reopened).running().isEmpty());
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void retainedMigrationConnectionBlocksExitThenOnlyCleansOriginalAttempt() throws Exception {
        upgrade("writerLeak");
        var runtime = fixture.open(false);
        try {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertThrows(SQLException.class, runtime::close);
            var access = (gold.debug.windowstolinux.appapi.db.schema.SchemaAccess) System.getProperties()
                .get("wtl.migration.fixture.scope");
            assertThrows(SQLException.class, access::openConnection);
            ((Connection) System.getProperties().remove("wtl.migration.fixture.connection")).close();
            runtime.close();
        } finally {
            var connection = System.getProperties().remove("wtl.migration.fixture.connection");
            if (connection instanceof Connection c)
                c.close();
            System.getProperties().remove("wtl.migration.fixture.scope");
            runtime.close();
        }
        try (var reopened = fixture.open(false)) {
            assertEquals("FAILED", text(reopened, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals(0, count(reopened.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("initial", sourceValue(reopened));
        }
        assertEquals(List.of("migration:1->2"), fixture.actions());
    }
}
