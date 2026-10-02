package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;

import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.*;

import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentPublicationRepositoryTest extends DeploymentGraphFixture {
    static final String PURPOSE = "deployment.configuration";

    DeploymentSecretRevision secret(PlatformRuntime runtime, TaskContext task, DeploymentSecretReference reference)
        throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        byte[] value = "fixture-value-never-in-business-payload".getBytes(StandardCharsets.UTF_8);
        SecretRef secret;
        try {
            secret = context.secrets().create(PURPOSE, value, task);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
        var reservation = context.secrets().reserve(secret,
            "secret:" + reference.identifier() + ":" + reference.revision(), task);
        var metadata = new DeploymentSecretRevision(reference, secret.id(), OBSERVED, Optional.of(secret.purpose()));
        new DeploymentSecretRepository(context.db()).saveRevision(task, metadata);
        context.secrets().resolve(reservation.id(), ReferenceResolution.REFERENCED, task);
        return metadata;
    }

    @Test
    void actualSecretReferencesAndEveryPublicationFieldSurviveRealEngineRestart() throws Exception {
        enable();
        var graph = graph("application");
        var reference = new DeploymentSecretReference("db-password", 1);
        var records = publications(graph, 1, List.of(reference));
        var metadata = new AtomicReference<DeploymentSecretRevision>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                metadata.set(secret(runtime, task, reference));
                var context = runtime.modContexts().get(OWNER).context();
                var reservations = new ArrayList<ServerReference>();
                for (var component : graph.components())
                    reservations.add(context.servers().reserve(component.unit().server(),
                        "unit:" + component.unit().id(), task));
                var repository = new DeploymentPublicationRepository(context.db());
                repository.saveSuccessfulApplication(task, graph, records, OBSERVED);
                for (var reservation : reservations)
                    context.servers().resolve(reservation.id(), ReferenceResolution.REFERENCED, task);
                repository.saveSuccessfulApplication(task, graph, records, OBSERVED);
                verify(runtime, task, graph, records);
                assertTrue(repository.findUnit(task, "absent").isEmpty());
                assertTrue(repository.findRelease(task, "absent").isEmpty());
                assertTrue(repository.findRuntime(task, "absent").isEmpty());
                assertTrue(new DeploymentSecretRepository(context.db()).isReferenced(task, reference));
                return success();
            });
            valid(runtime);
            assertEquals(2, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM mod_credential_reference WHERE state='ACTIVE'"));
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                verify(runtime, task, graph, records);
                var context = runtime.modContexts().get(OWNER).context();
                var original = new DeploymentSecretRepository(context.db()).findRevision(task, reference).orElseThrow();
                assertEquals(metadata.get(), original);
                context.secrets().use(new SecretRef(original.credentialId(), PURPOSE), PURPOSE, task, bytes -> {
                    assertArrayEquals("fixture-value-never-in-business-payload".getBytes(StandardCharsets.UTF_8),
                        bytes);
                    return null;
                });
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void exactEmptySecretSetAndBothBindingOrdersPreserveConfiguration() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var secrets = new DeploymentSecretRepository(context.db());
                var configurations = new DeploymentConfigurationRepository(context.db());
                var snapshot = publications(graph("application"), 1, List.of()).getFirst().configuration();
                assertTrue(secrets.findRelease(task, "web", "one").isEmpty());
                configurations.saveAndBindRelease(task, snapshot, "one");
                assertTrue(secrets.findRelease(task, "web", "one").isEmpty());
                secrets.bindRelease(task, "web", "one", List.of());
                assertEquals(Optional.of(List.of()), secrets.findRelease(task, "web", "one"));
                secrets.bindRelease(task, "web", "two", List.of());
                configurations.saveAndBindRelease(task, snapshot, "two");
                assertEquals(snapshot, configurations.findRelease(task, "web", "one").orElseThrow());
                assertEquals(snapshot, configurations.findRelease(task, "web", "two").orElseThrow());
                var reference = new DeploymentSecretReference("password", 1);
                secret(runtime, task, reference);
                assertThrows(SQLException.class, () -> secrets.bindRelease(task, "web", "one", List.of(reference)));
                assertEquals(Optional.of(List.of()), secrets.findRelease(task, "web", "one"));
                assertFalse(secrets.isReferenced(task, reference));
                return success();
            });
            valid(runtime);
        }
    }

    /** Preserves a pre-saved configuration through canonical publication binding and restart. / 保留预存配置，经规范发布绑定及重启仍完整一致。 */
    @Test
    void presavedSubmillisecondUnsortedConfigurationKeepsItsOriginalDigestAfterPublicationAndRestart() throws Exception {
        enable();
        var graph = graph("application");
        var createdAt = OBSERVED.plusNanos(123456789);
        var entries = DeploymentConfigurationInput.parse("ZETA=ready;PORT=8081");
        assertEquals(List.of("ZETA", "PORT"), entries.stream().map(DeploymentConfigurationEntry::key).toList());
        var records = publications(graph, 1, List.of()).stream().map(original -> new DeploymentUnitCommit(
            original.unit(), original.runtime(), original.release(),
            DeploymentConfigurationSnapshot.create(original.unit().id(), 1, "1", createdAt, entries),
            original.secrets())).toList();
        var canonicalEntries = entries.stream().sorted(Comparator.comparing(DeploymentConfigurationEntry::key)).toList();
        var canonical = records.stream().map(original -> new DeploymentUnitCommit(original.unit(), original.runtime(),
            original.release(), DeploymentConfigurationSnapshot.create(original.unit().id(), 1, "1",
            Instant.ofEpochMilli(createdAt.toEpochMilli()), canonicalEntries), original.secrets())).toList();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var configurations = new DeploymentConfigurationRepository(context.db());
                var reservations = new ArrayList<ServerReference>();
                for (var record : records) {
                    configurations.save(task, record.configuration());
                    var saved = configurations.find(task, record.unit().id(), 1).orElseThrow();
                    assertEquals(record.configuration().sha256(), saved.sha256());
                    assertEquals(Instant.ofEpochMilli(createdAt.toEpochMilli()), saved.createdAt());
                    assertEquals(canonicalEntries, saved.entries());
                    assertTrue(configurations.findRelease(task, record.unit().id(), record.release().sha256()).isEmpty());
                    reservations.add(context.servers().reserve(record.unit().server(), "unit:" + record.unit().id(), task));
                }
                new DeploymentPublicationRepository(context.db()).saveSuccessfulApplication(task, graph, records, OBSERVED);
                for (var reservation : reservations)
                    context.servers().resolve(reservation.id(), ReferenceResolution.REFERENCED, task);
                verify(runtime, task, graph, canonical);
                return success();
            });
            valid(runtime);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                verify(runtime, task, graph, canonical);
                return success();
            });
            valid(runtime);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void revisionMetadataIsImmutableUsesExactOpaqueIdsAndEnforcesIntegerRange() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var repository = new DeploymentSecretRepository(runtime.modContexts().get(OWNER).context().db());
                var original = secret(runtime, task, new DeploymentSecretReference("password", Integer.MAX_VALUE));
                repository.saveRevision(task, new DeploymentSecretRevision(original.reference(),
                    original.credentialId(), original.createdAt().plusNanos(123), original.purpose()));
                assertThrows(SQLException.class,
                    () -> repository.saveRevision(task, new DeploymentSecretRevision(original.reference(),
                        "another-opaque-id", original.createdAt(), original.purpose())));
                assertThrows(SQLException.class,
                    () -> repository.saveRevision(task, new DeploymentSecretRevision(original.reference(),
                        original.credentialId(), original.createdAt().plusMillis(1), original.purpose())));
                assertThrows(SQLException.class,
                    () -> repository.saveRevision(task, new DeploymentSecretRevision(original.reference(),
                        original.credentialId(), original.createdAt(), Optional.of("another-purpose"))));
                assertEquals(original, repository.findRevision(task, original.reference()).orElseThrow());
                var oversized = new DeploymentSecretReference("password", (long) Integer.MAX_VALUE + 1);
                assertThrows(IllegalArgumentException.class,
                    () -> repository.saveRevision(task, new DeploymentSecretRevision(oversized,
                        original.credentialId(), OBSERVED, original.purpose())));
                assertThrows(IllegalArgumentException.class,
                    () -> repository.bindRelease(task, "web", "release", List.of(oversized)));
                assertThrows(IllegalArgumentException.class, () -> repository.bindRelease(task, "web", "release",
                    List.of(original.reference(), original.reference())));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM secret_revision"));
                assertTrue(repository.findRevision(task, new DeploymentSecretReference("absent", 1)).isEmpty());
                var opaque = new DeploymentSecretRevision(new DeploymentSecretReference("opaque", 1),
                    " Exact/Platform..Identity ", OBSERVED, Optional.of(PURPOSE));
                repository.saveRevision(task, opaque);
                assertEquals(opaque, repository.findRevision(task, opaque.reference()).orElseThrow());
                return success();
            });
        }
    }

    @Test
    void missingSecretInLaterComponentRollsBackAllUnitsSnapshotsBindingsAndGraph() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                var records = new ArrayList<>(publications(graph, 1, List.of()));
                var last = records.getLast();
                records.set(records.size() - 1, new DeploymentUnitCommit(last.unit(), last.runtime(), last.release(),
                    last.configuration(), List.of(new DeploymentSecretReference("absent", 1))));
                var database = runtime.modContexts().get(OWNER).context().db();
                assertThrows(SQLException.class, () -> new DeploymentPublicationRepository(database)
                    .saveSuccessfulApplication(task, graph, records, OBSERVED));
                for (var table : List.of("deployment_unit", "deployment_unit_configuration_snapshot",
                    "deployment_unit_configuration_entry", "deployment_unit_release_binding", "managed_application",
                    "managed_application_component"))
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM " + table), table);
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void newReleaseRetainsOldExactBindingsAndUnrelatedDisplayAndObservationFields() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                var first = new DeploymentSecretReference("password", 1);
                var second = new DeploymentSecretReference("password", 2);
                secret(runtime, task, first);
                secret(runtime, task, second);
                var database = runtime.modContexts().get(OWNER).context().db();
                var publication = new DeploymentPublicationRepository(database);
                publication.saveSuccessfulApplication(task, graph, publications(graph, 1, List.of(first)), OBSERVED);
                sql(runtime, task,
                    "UPDATE deployment_unit SET has_display_override=1,display_name='Retained',application_category='APP',runtime_state='RUNNING',autostart_state='ENABLED',is_ownership_verified=1,observation_evidence='original',observed_at=1");
                var nextComponents = graph.components().stream()
                    .map(c -> new DeploymentApplicationGraph.Component(c.componentId(),
                        new DeploymentUnitIdentity(c.unit().id(), c.unit().server(), c.unit().systemdUnit(),
                            c.unit().releaseRoot(), "c".repeat(64)),
                        c.runtime(), c.dependencies(), c.reviewedRuntime(), c.dataPaths(), c.resources()))
                    .toList();
                var nextGraph = new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(),
                    graph.healthCheck(), nextComponents);
                var next = publications(nextGraph, 2, List.of(second));
                publication.saveSuccessfulApplication(task, nextGraph, next, OBSERVED.plusSeconds(2));
                verify(runtime, task, nextGraph, next);
                var secrets = new DeploymentSecretRepository(database);
                var configurations = new DeploymentConfigurationRepository(database);
                for (var component : graph.components()) {
                    assertEquals(List.of(first),
                        secrets.findRelease(task, component.unit().id(), "a".repeat(64)).orElseThrow());
                    assertEquals(1, configurations.findRelease(task, component.unit().id(), "a".repeat(64))
                        .orElseThrow().revision());
                }
                assertEquals(2, count(runtime, task,
                    "SELECT COUNT(*) FROM deployment_unit WHERE display_name='Retained' AND observation_evidence='original' AND observed_at=1"));
                assertTrue(secrets.isReferenced(task, first));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lateGraphOwnershipConflictRollsBackAlreadyUpdatedReleasesAndNewConfiguration() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                var database = runtime.modContexts().get(OWNER).context().db();
                var publication = new DeploymentPublicationRepository(database);
                var original = publications(graph, 1, List.of());
                publication.saveSuccessfulApplication(task, graph, original, OBSERVED);
                var other = graph("other");
                assertThrows(SQLException.class, () -> publication.saveSuccessfulApplication(task, other,
                    publications(other, 2, List.of()), OBSERVED));
                verify(runtime, task, graph, original);
                assertTrue(new DeploymentGraphRepository(database).find(task, "other").isEmpty());
                assertEquals(0, count(runtime, task,
                    "SELECT COUNT(*) FROM deployment_unit_configuration_snapshot WHERE configuration_revision=2"));
                assertEquals(0,
                    count(runtime, task,
                        "SELECT COUNT(*) FROM deployment_unit_release_binding WHERE release_sha256='"
                            + "b".repeat(64) + "'"));
                return success();
            });
        }
    }

    @Test
    void lostCommitAcknowledgementKeepsTheWholePublicationAvailableForExplicitReconciliation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                var records = publications(graph, 1, List.of());
                var database = runtime.modContexts().get(OWNER).context().db();
                var failure = assertThrows(ApiException.class,
                    () -> new DeploymentPublicationRepository(commitFault(database)).saveSuccessfulApplication(task,
                        graph, records, OBSERVED));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                verify(runtime, task, graph, records);
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"identifier", "credential", "partial"})
    void readOnlySchemaValidationRejectsMalformedMetadataAndPartialSecretBindings(String corruption) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var reference = new DeploymentSecretReference("password", 1);
                secret(runtime, task, reference);
                var repository = new DeploymentSecretRepository(runtime.modContexts().get(OWNER).context().db());
                if (corruption.equals("partial")) {
                    repository.bindRelease(task, "web", "release", List.of(reference));
                    sql(runtime, task, "UPDATE deployment_unit_release_binding SET is_secret_binding_complete=0");
                    assertThrows(SQLException.class, () -> repository.findRelease(task, "web", "release"));
                } else {
                    sql(runtime, task,
                        corruption.equals("identifier")
                            ? "UPDATE secret_revision SET secret_id='INVALID'"
                            : "UPDATE secret_revision SET credential_id=''");
                    if (corruption.equals("credential"))
                        assertThrows(SQLException.class, () -> repository.findRevision(task, reference));
                }
                try (var scope = scope(runtime, true)) {
                    var report = new DeploymentSchema().validate(scope);
                    assertFalse(report.valid());
                    assertEquals(List.of("Stored deployment secret metadata or bindings are invalid"),
                        report.violations());
                    assertTrue(report.businessDigest().isEmpty());
                }
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM secret_revision"));
                return success();
            });
        }
    }

    @Test
    void concurrentDifferentSecretSetsCannotOverwriteTheSameRelease() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var first = new DeploymentSecretReference("password", 1);
            var second = new DeploymentSecretReference("password", 2);
            run(runtime, task -> {
                secret(runtime, task, first);
                secret(runtime, task, second);
                return success();
            });
            var context = runtime.modContexts().get(OWNER).context();
            var repository = new DeploymentSecretRepository(context.db());
            var barrier = new CyclicBarrier(2);
            var handles = new ArrayList<TaskHandle<Boolean>>();
            for (var reference : List.of(first, second))
                handles.add(context.tasks().submit(
                    new TaskRequest("secret_binding_race", "Secret binding race", List.of(), true), task -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        boolean saved;
                        try {
                            repository.bindRelease(task, "web", "release", List.of(reference));
                            saved = true;
                        } catch (SQLException conflict) {
                            saved = false;
                        }
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(saved), Optional.empty());
                    }));
            int winners = 0;
            for (var handle : handles) {
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                if (result.value().orElseThrow())
                    winners++;
            }
            assertEquals(1, winners);
            run(runtime, task -> {
                assertEquals(1, repository.findRelease(task, "web", "release").orElseThrow().size());
                return success();
            });
        }
    }

    @Test
    void coverageAndForeignOwnerAreRejectedBeforeJdbcAndCurrentReleaseCannotBeRewritten() throws Exception {
        var opened = new AtomicBoolean();
        var database = new ModDatabase() {
            public SchemaIdentity identity() {
                return new SchemaIdentity("foreign.owner", "1", UUID.randomUUID());
            }

            public Connection openConnection(TaskContext task) {
                opened.set(true);
                throw new AssertionError("Foreign JDBC cannot open");
            }
        };
        var graph = graph("application");
        var records = publications(graph, 1, List.of());
        var repository = new DeploymentPublicationRepository(database);
        assertThrows(IllegalArgumentException.class,
            () -> repository.saveSuccessfulApplication(null, graph, records.subList(0, 1), OBSERVED));
        assertThrows(IllegalArgumentException.class, () -> repository.saveSuccessfulApplication(null, graph,
            List.of(records.getFirst(), records.getFirst()), OBSERVED));
        assertThrows(IllegalArgumentException.class,
            () -> repository.saveSuccessfulApplication(null, graph, records, OBSERVED));
        assertThrows(IllegalArgumentException.class, () -> repository.findUnit(null, "web"));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentSecretRepository(database).findRevision(null,
            new DeploymentSecretReference("password", 1)));
        assertFalse(opened.get());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var original = new DeploymentPublicationRepository(runtime.modContexts().get(OWNER).context().db());
                original.saveSuccessfulApplication(task, graph, records, OBSERVED);
                var changed = records.stream()
                    .map(r -> new DeploymentUnitCommit(r.unit(), r.runtime(),
                        new DeploymentCurrentRelease(r.unit().id(), r.release().sha256(),
                            r.release().publishedAt().plusMillis(1)),
                        r.configuration(), r.secrets()))
                    .toList();
                assertThrows(SQLException.class,
                    () -> original.saveSuccessfulApplication(task, graph, changed, OBSERVED));
                var retargeted = graph.components().stream().map(c -> new DeploymentApplicationGraph.Component(
                    c.componentId(),
                    new DeploymentUnitIdentity(c.unit().id(), new ServerRef("other-server"), c.unit().systemdUnit(),
                        c.unit().releaseRoot(), c.unit().ownershipManifestSha256()),
                    c.runtime(), c.dependencies(), c.reviewedRuntime(), c.dataPaths(), c.resources())).toList();
                var other = new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(),
                    graph.healthCheck(), retargeted);
                assertThrows(SQLException.class, () -> original.saveSuccessfulApplication(task, other,
                    publications(other, 2, List.of()), OBSERVED));
                verify(runtime, task, graph, records);
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"configuration", "secret-completion", "release-digest"})
    void currentPublicationRequiresCompleteBindingsAndValidReleaseIdentity(String corruption) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                var publication = new DeploymentPublicationRepository(runtime.modContexts().get(OWNER).context().db());
                publication.saveSuccessfulApplication(task, graph, publications(graph, 1, List.of()), OBSERVED);
                sql(runtime, task, switch (corruption) {
                    case "configuration" ->
                        "UPDATE deployment_unit_release_binding SET configuration_revision=NULL,configuration_sha256=NULL";
                    case "secret-completion" ->
                        "UPDATE deployment_unit_release_binding SET is_secret_binding_complete=0";
                    case "release-digest" -> "UPDATE deployment_unit SET current_release_sha256='INVALID'";
                    default -> throw new AssertionError();
                });
                try (var access = scope(runtime, true)) {
                    var report = new DeploymentSchema().validate(access);
                    assertFalse(report.valid());
                    assertEquals(List.of("Stored current publication facts are invalid"), report.violations());
                }
                return success();
            });
        }
    }

    @Test
    void reviewedDatabasePasswordMustBeIncludedInTheExactPublishedSecretSet() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var password = new DeploymentSecretReference("db-password", 1);
                var initial = component("web", "server", List.of());
                var databaseBinding = new DeploymentDatabaseBinding("database", new DeploymentDatabaseConnection.Server(
                    DeploymentDatabaseEngineType.POSTGRESQL, "localhost", 5432, "demo", "app", password, true));
                var resources = new DeploymentResourceBindings(initial.resources().fileBindings(),
                    Optional.of(List.of(databaseBinding)));
                var component = new DeploymentApplicationGraph.Component(initial.componentId(), initial.unit(),
                    initial.runtime(), initial.dependencies(), initial.reviewedRuntime(), initial.dataPaths(),
                    resources);
                var graph = new DeploymentApplicationGraph("application", "web", initial.runtime().healthCheck(),
                    List.of(component));
                var publication = new DeploymentPublicationRepository(runtime.modContexts().get(OWNER).context().db());
                assertThrows(IllegalArgumentException.class, () -> publication.saveSuccessfulApplication(task, graph,
                    publications(graph, 1, List.of()), OBSERVED));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_unit"));
                secret(runtime, task, password);
                var records = publications(graph, 1, List.of(password));
                publication.saveSuccessfulApplication(task, graph, records, OBSERVED);
                verify(runtime, task, graph, records);
                valid(runtime);
                sql(runtime, task, "DELETE FROM deployment_unit_release_secret_reference");
                try (var access = scope(runtime, true)) {
                    var report = new DeploymentSchema().validate(access);
                    assertFalse(report.valid());
                    assertEquals(List.of("Stored current publication facts are invalid"), report.violations());
                }
                return success();
            });
        }
    }
}
