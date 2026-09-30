package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.db.mod.ModRestartLedger;
import gold.debug.windowstolinux.app.mod.development.DevelopmentDescriptor;
import gold.debug.windowstolinux.appapi.db.SchemaAccess;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModInitializationTest {
    @TempDir
    Path root;

    PlatformModCoordinatorTest fixture;

    static final String OWNER = PlatformModCoordinatorTest.P;

    static final String CONNECTION = "wtl.schema.fixture.connection", SCOPE = "wtl.schema.fixture.scope";

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModCoordinatorTest();
        fixture.root = root;
        fixture.workspace();
    }

    @AfterEach
    void cleanup() throws Exception {
        var connection = System.getProperties().remove(CONNECTION);
        System.getProperties().remove(SCOPE);
        if (connection instanceof Connection c)
            c.close();
    }

    void module(String mode, boolean failStart) throws Exception {
        fixture.module(OWNER, Map.of(), failStart);
        Path module = fixture.workspace.resolve("src/mod/" + OWNER);
        Path source = module.resolve("ProviderSchema.java"), classes = module.resolve("target/classes");
        Files.writeString(source,
                """
                        package example.provider.db;
                        import java.nio.file.*;
                        import java.sql.*;
                        import java.util.*;
                        import gold.debug.windowstolinux.appapi.db.*;
                        public final class ProviderSchema implements ModSchemaLifecycle {
                            public static int calls;
                            static final String MODE="%s";
                            static void trace(String action) throws Exception {
                                Files.writeString(Path.of("%s"),"schema:"+action+"\\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                            }
                            public void initialize(SchemaAccess target) throws Exception {
                                calls++;
                                trace("initialize");
                                var c=target.openConnection();
                                if(MODE.equals("writerLeak")) {
                                    System.getProperties().put("%s",c);
                                    System.getProperties().put("%s",target);
                                }
                                try(var s=c.createStatement()) {
                                    s.execute("CREATE TABLE parent(a INT,b INT,PRIMARY KEY(b,a))");
                                    s.execute("INSERT INTO parent VALUES(1,2)");
                                    s.execute("CREATE TABLE item(id INT PRIMARY KEY,value_text VARCHAR NOT NULL,a INT,b INT)");
                                    s.execute("INSERT INTO item VALUES(1,'initial',1,2)");
                                    s.execute("INSERT INTO item VALUES(2,'nullable',NULL,99)");
                                    if(MODE.equals("badForeignKey")) s.execute("INSERT INTO item VALUES(3,'bad',8,9)");
                                    s.execute("ALTER TABLE item ADD CONSTRAINT fk FOREIGN KEY(a,b) REFERENCES parent(a,b) NOCHECK");
                                    s.execute("CREATE TABLE optional_values(a INT,b INT,UNIQUE(a,b))");
                                    s.execute("INSERT INTO optional_values VALUES(NULL,1),(NULL,1)");
                                    s.execute("ALTER TABLE item ADD CONSTRAINT valid_id CHECK(id>"+(MODE.equals("badCheck") ? "8" : "0")+") NOCHECK");
                                    s.execute("CREATE DOMAIN positive AS INT");
                                    s.execute("CREATE TABLE domain_value(v positive)");
                                    s.execute("INSERT INTO domain_value VALUES("+(MODE.equals("badDomain") ? "-1" : "1")+")");
                                    s.execute("ALTER DOMAIN positive ADD CONSTRAINT positive_value CHECK(VALUE>0) NOCHECK");
                                } finally {if(!MODE.equals("writerLeak"))c.close();}
                            }
                            public ValidationReport validate(SchemaAccess target) throws Exception {
                                trace("validate");
                                var c=target.openConnection();
                                if(MODE.equals("readerLeak")) {
                                    System.getProperties().put("%s",c);
                                    System.getProperties().put("%s",target);
                                }
                                try(var s=c.createStatement()) {
                                    try {s.execute("DELETE FROM item");throw new AssertionError("reader could write");}
                                    catch(SQLException expected) {}
                                    try {s.executeQuery("SELECT * FROM app.mod_registry");throw new AssertionError("reader could read platform");}
                                    catch(SQLException expected) {}
                                    try(var r=s.executeQuery("SELECT COUNT(*) FROM item")) {
                                        if(!r.next() || r.getInt(1)<2)throw new AssertionError("lost rows");
                                    }
                                } finally {if(!MODE.equals("readerLeak"))c.close();}
                                return new ValidationReport(!MODE.equals("invalid"),MODE.equals("invalid") ? List.of("private diagnostic must not be stored") : List.of(),Optional.of("business-v1"));
                            }
                        }
                        """
                        .formatted(mode, fixture.trace.toString().replace("\\", "\\\\"), CONNECTION, SCOPE, CONNECTION,
                                SCOPE));
        Path entry = module.resolve("Mod.java");
        Files.writeString(entry, Files.readString(entry).replace("record(\"construct\");",
                "if(example.provider.db.ProviderSchema.calls!=0)throw new AssertionError(\"preparation loader reused\");record(\"construct\");"));
        String cp = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
                "-encoding", "UTF-8", "-classpath", cp, "-d", classes.toString(), source.toString(), entry.toString()));
        Files.writeString(module.resolve("mod.json"), manifest(OWNER, VERSION, Map.of(), "1", List.of()));
        DevelopmentDescriptor.generate(fixture.workspace, module.resolve("mod.json"), classes, List.of(),
                List.of(fixture.workspace.resolve("pom.xml"), fixture.workspace.resolve("src/mod/pom.xml"),
                        module.resolve("pom.xml")),
                List.of());
    }

    @Test
    void firstUsePreparesAndValidatesBeforeBusinessThenReusesTheSameGeneration() throws Exception {
        module("valid", false);
        fixture.enable(OWNER);
        String generation;
        try (var runtime = fixture.open(false)) {
            var report = fixture.start(runtime);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(OWNER), report.running());
            assertEquals(List.of("schema:initialize", "schema:validate", OWNER + ":construct", OWNER + ":start"),
                    fixture.actions());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals("TRUE", text(runtime, "SELECT ever_used FROM mod_schema"));
            assertTrue(text(runtime, "SELECT validation_digest FROM mod_schema").matches("[a-f0-9]{64}"));
            generation = text(runtime, "SELECT generation FROM mod_schema");
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(2, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE category='mod_operation' AND outcome='SUCCEEDED'"));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM information_schema.users WHERE user_name LIKE 'r\\_%' ESCAPE '\\'"));
        }
        try (var runtime = fixture.open(false)) {
            assertEquals(Set.of(OWNER), fixture.start(runtime).running());
            assertEquals(generation, text(runtime, "SELECT generation FROM mod_schema"));
        }
        assertEquals(1, fixture.actions().stream().filter("schema:initialize"::equals).count());
    }

    @Test
    void authorRejectionPreservesFailedDataAndChoiceWithoutAutomaticReinitialization() throws Exception {
        module("invalid", false);
        fixture.enable(OWNER);
        try (var runtime = fixture.open(false)) {
            var report = fixture.start(runtime);
            assertTrue(report.running().isEmpty());
            assertFalse(report.problems().isEmpty());
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals("FALSE", text(runtime, "SELECT ever_used FROM mod_schema"));
            assertEquals("FAILED", PlatformModCoordinatorTest.state(runtime, OWNER));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE message LIKE '%private diagnostic%'"));
        }
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
        }
        assertEquals(List.of("schema:initialize", "schema:validate"), fixture.actions());
    }

    @Test
    void uncheckedTableAndForeignKeyViolationsCannotBecomeReady() throws Exception {
        for (var mode : List.of("badCheck", "badForeignKey", "badDomain")) {
            root = Files.createDirectories(root.resolve(mode));
            setup();
            module(mode, false);
            fixture.enable(OWNER);
            try (var runtime = fixture.open(false)) {
                var report = fixture.start(runtime);
                assertTrue(report.running().isEmpty(), mode);
                assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"), mode + report.problems());
                assertFalse(fixture.actions().contains(OWNER + ":construct"));
            }
        }
    }

    @Test
    void leakedWriterSealsNewConnectionsAndOriginalReleaseAllowsFailureCleanupOnly() throws Exception {
        module("writerLeak", false);
        fixture.enable(OWNER);
        var runtime = fixture.open(false);
        try {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema"));
            assertThrows(SQLException.class, runtime::close);
            var scope = (SchemaAccess) System.getProperties().get(SCOPE);
            assertThrows(SQLException.class, scope::openConnection);
            ((Connection) System.getProperties().remove(CONNECTION)).close();
            runtime.close();
        } finally {
            cleanup();
            runtime.close();
        }
        try (var reopened = fixture.open(false)) {
            assertEquals("FAILED", text(reopened, "SELECT state FROM mod_schema"));
            assertEquals(0, count(reopened.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
        assertEquals(List.of("schema:initialize"), fixture.actions());
    }

    @Test
    void lateReadyArchiveFailureRetriesOnlyOriginalMetadataAndKeepsValidatedData() throws Exception {
        module("valid", false);
        fixture.enable(OWNER);
        var runtime = fixture.open(false);
        try {
            sql(runtime.persistence().database(),
                    "ALTER TABLE runtime_log ADD CONSTRAINT fixture_schema_archive CHECK(event_code<>'MOD_SCHEMA_INITIALIZED')");
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema"));
            String generation = text(runtime, "SELECT generation FROM mod_schema");
            assertThrows(SQLException.class, runtime::close);
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT fixture_schema_archive");
            runtime.close();
            try (var reopened = fixture.open(false)) {
                assertEquals("READY", text(reopened, "SELECT state FROM mod_schema"));
                assertEquals("FALSE", text(reopened, "SELECT ever_used FROM mod_schema"));
                assertEquals(Set.of(OWNER), fixture.start(reopened).running());
                assertEquals(generation, text(reopened, "SELECT generation FROM mod_schema"));
            }
        } finally {
            runtime.close();
        }
        assertEquals(1, fixture.actions().stream().filter("schema:initialize"::equals).count());
        assertEquals(1, fixture.actions().stream().filter("schema:validate"::equals).count());
    }

    @Test
    void leakedValidationReaderRetainsSuccessUntilOriginalReleaseThenDoesNotRevalidate() throws Exception {
        module("readerLeak", false);
        fixture.enable(OWNER);
        var runtime = fixture.open(false);
        try {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema"));
            assertThrows(SQLException.class, runtime::close);
            assertThrows(SQLException.class, ((SchemaAccess) System.getProperties().get(SCOPE))::openConnection);
            ((Connection) System.getProperties().remove(CONNECTION)).close();
            runtime.close();
        } finally {
            cleanup();
            runtime.close();
        }
        try (var reopened = fixture.open(false)) {
            assertEquals("READY", text(reopened, "SELECT state FROM mod_schema"));
            assertEquals(Set.of(OWNER), fixture.start(reopened).running());
        }
        assertEquals(1, fixture.actions().stream().filter("schema:validate"::equals).count());
    }

    @Test
    void acknowledgementRetryUsesOriginalGenerationAndDoesNotRebuildRemovedHistory() throws Exception {
        module("valid", false);
        fixture.enable(OWNER);
        try (var runtime = fixture.open(false)) {
            var ledger = new ModRestartLedger(runtime.persistence().database());
            var intent = UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation"));
            var manifest = text(runtime, "SELECT manifest_json FROM mod_version");
            var digest = text(runtime, "SELECT content_sha256 FROM mod_version");
            assertThrows(SQLException.class, () -> ledger.beginInitialization(intent, manifest, "0".repeat(64)));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            var plan = ledger.beginInitialization(intent, manifest, digest);
            new gold.debug.windowstolinux.app.db.execution.schema.SchemaProvisioner(runtime.persistence().database())
                    .create(plan.target());
            ledger.preparationPhase(plan, false);
            ledger.preparationPhase(plan, true);
            ledger.finishPreparation(plan, gold.debug.windowstolinux.appapi.task.Outcome.SUCCEEDED, "1".repeat(64));
            sql(runtime.persistence().database(), "DELETE FROM runtime_log WHERE event_code='MOD_SCHEMA_INITIALIZED'");
            ledger.finishPreparation(plan, gold.debug.windowstolinux.appapi.task.Outcome.SUCCEEDED, "1".repeat(64));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_SCHEMA_INITIALIZED'"));
            assertThrows(SQLException.class, () -> ledger.finishPreparation(plan,
                    gold.debug.windowstolinux.appapi.task.Outcome.SUCCEEDED, "2".repeat(64)));
        }
    }

    @Test
    void failedBusinessStartupDoesNotDowngradeValidatedSchema() throws Exception {
        module("valid", true);
        fixture.enable(OWNER);
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals("TRUE", text(runtime, "SELECT ever_used FROM mod_schema"));
            assertEquals("FAILED", PlatformModCoordinatorTest.state(runtime, OWNER));
        }
    }

    @Test
    void unfinishedPreparationBecomesUnknownOnReopenWithoutReplayingAuthor() throws Exception {
        module("valid", false);
        fixture.enable(OWNER);
        UUID intent;
        try (var runtime = fixture.open(false)) {
            intent = UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation"));
            var plan = new ModRestartLedger(runtime.persistence().database()).beginInitialization(intent,
                    text(runtime, "SELECT manifest_json FROM mod_version"),
                    text(runtime, "SELECT content_sha256 FROM mod_version"));
            assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(plan.target().generation().toString(), text(runtime, "SELECT generation FROM mod_schema"));
        }
        try (var reopened = fixture.open(false)) {
            assertEquals("UNKNOWN", text(reopened, "SELECT state FROM mod_schema"));
            assertEquals(2, count(reopened.persistence().database(),
                    "SELECT COUNT(*) FROM mod_operation WHERE state='UNKNOWN'"));
            assertTrue(fixture.start(reopened).running().isEmpty());
            assertEquals(intent.toString(),
                    text(reopened, "SELECT operation_id FROM mod_operation WHERE operation_type='ENABLE'"));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void physicalCollisionRetainsUnknownWithoutAdoptingOrDeletingExistingObjects() throws Exception {
        module("valid", false);
        fixture.enable(OWNER);
        String schema = new gold.debug.windowstolinux.app.db.execution.schema.SchemaCoordinates(
                new gold.debug.windowstolinux.appapi.db.SchemaIdentity(OWNER, "1", UUID.randomUUID())).physicalName();
        try (var runtime = fixture.open(false)) {
            sql(runtime.persistence().database(), "CREATE SCHEMA \"" + schema + "\"");
            sql(runtime.persistence().database(), "CREATE TABLE \"" + schema + "\".retained(id INT)");
            sql(runtime.persistence().database(), "INSERT INTO \"" + schema + "\".retained VALUES(42)");
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(2, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM mod_operation WHERE state='UNKNOWN'"));
            assertEquals("42", text(runtime, "SELECT id FROM \"" + schema + "\".retained"));
            assertTrue(fixture.actions().isEmpty());
        }
        try (var reopened = fixture.open(false)) {
            assertTrue(fixture.start(reopened).running().isEmpty());
            assertEquals("42", text(reopened, "SELECT id FROM \"" + schema + "\".retained"));
        }
    }
}
