package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentReferenceAssociations.AssociationState.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRevision;
import org.junit.jupiter.api.Test;

class DeploymentReferenceAssociationsTest extends DeploymentExecutionFixture {
    private static final ServerRef SERVER = new ServerRef("server");

    @Test
    void currentRowsProveExactServerButWrongOrMissingRowsDoNot() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var identity = identity(task, UUID.randomUUID());
                var associations = new DeploymentReferenceAssociations(runtime.modContexts().get(OWNER).context().db());
                var application = DeploymentReferenceKey.application(SERVER, "application");
                var unit = DeploymentReferenceKey.unit(SERVER, "web");
                assertEquals(ABSENT, associations.observe(task, identity, application));
                assertEquals(ABSENT, associations.observe(task, identity, unit));

                var graph = graph("application");
                new DeploymentPublicationRepository(runtime.modContexts().get(OWNER).context().db())
                    .saveSuccessfulApplication(task, graph, publications(graph, 1, List.of()), OBSERVED);
                assertEquals(REFERENCED, associations.observe(task, identity, application));
                assertEquals(REFERENCED, associations.observe(task, identity, unit));
                sql(runtime, task,
                    "UPDATE managed_application SET server_profile_id='other' WHERE application_id='application'");
                sql(runtime, task,
                    "UPDATE deployment_unit SET server_profile_id='other' WHERE deployment_unit_id='web'");
                assertEquals(CONFLICT, associations.observe(task, identity, application));
                assertEquals(CONFLICT, associations.observe(task, identity, unit));
                return success();
            });
        }
    }

    @Test
    void retiredHistoryProtectsOriginalServerAfterCurrentRowChangesOrUnitDisappears() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                UUID executionId = UUID.randomUUID();
                var reservation = prepare(runtime, task, executionId);
                var journal = journal(runtime);
                journal.commitApplication(task, executionId, graph, publications(graph, 1, List.of()),
                    OBSERVED.plusSeconds(10));
                runtime.modContexts().get(OWNER).context().servers().resolve(reservation.id(),
                    ReferenceResolution.REFERENCED, task);
                journal.finishCleanup(task, executionId, OBSERVED.plusSeconds(11));

                sql(runtime, task, "UPDATE deployment_release SET state='RETIRED'");
                sql(runtime, task,
                    "UPDATE managed_application SET server_profile_id='other' WHERE application_id='application'");
                sql(runtime, task, "DELETE FROM managed_application_dependency WHERE application_id='application'");
                sql(runtime, task, "DELETE FROM managed_application_component WHERE deployment_unit_id='worker'");
                sql(runtime, task, "DELETE FROM deployment_unit WHERE deployment_unit_id='worker'");
                var associations = new DeploymentReferenceAssociations(runtime.modContexts().get(OWNER).context().db());
                var identity = identity(task, executionId);
                assertEquals(REFERENCED, associations.observe(task, identity,
                    DeploymentReferenceKey.application(SERVER, "application")));
                assertEquals(REFERENCED,
                    associations.observe(task, identity, DeploymentReferenceKey.unit(SERVER, "worker")));

                try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
                     var update = connection.prepareStatement(
                         "UPDATE deployment_release SET runtime_payload=? WHERE application_id='application'")) {
                    update.setBytes(1, "{\"formatVersion\":999}".getBytes(StandardCharsets.UTF_8));
                    assertEquals(1, update.executeUpdate());
                }
                assertThrows(SQLException.class, () -> associations.observe(task, identity,
                    DeploymentReferenceKey.application(SERVER, "application")));
                assertThrows(SQLException.class,
                    () -> associations.observe(task, identity, DeploymentReferenceKey.unit(SERVER, "worker")));
                return success();
            });
        }
    }

    @Test
    void exactSecretRevisionAndUnitBindingRemainIndependentEvidence() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var identity = identity(task, UUID.randomUUID());
                var associations = new DeploymentReferenceAssociations(context.db());
                var revision = new DeploymentSecretReference("db-password", 1);
                byte[] value = "fixture-secret".getBytes(StandardCharsets.UTF_8);
                SecretRef secret;
                try {
                    secret = context.secrets().create("deployment.configuration", value, task);
                } finally {
                    Arrays.fill(value, (byte) 0);
                }
                new DeploymentSecretRepository(context.db()).saveRevision(task,
                    new DeploymentSecretRevision(revision, secret.id(), OBSERVED, Optional.of(secret.purpose())));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_unit_release_secret_reference"));
                assertEquals(REFERENCED, associations.observe(task, identity,
                    DeploymentReferenceKey.secretRevision(SERVER, secret, revision)));
                assertEquals(CONFLICT, associations.observe(task, identity, DeploymentReferenceKey
                    .secretRevision(SERVER, new SecretRef("different-credential", secret.purpose()), revision)));
                assertEquals(ABSENT, associations.observe(task, identity, DeploymentReferenceKey.secretRevision(SERVER,
                    secret, new DeploymentSecretReference("db-password", 2))));
                assertThrows(IllegalArgumentException.class, () -> associations.observe(task, identity,
                    DeploymentReferenceKey.secretRevision(new ServerRef("other"), secret, revision)));

                var unit = DeploymentReferenceKey.unit(SERVER, "orphan-unit");
                assertEquals(ABSENT, associations.observe(task, identity, unit));
                var configuration = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot
                    .create("orphan-unit", 1, "1", OBSERVED, List.of());
                new DeploymentConfigurationRepository(context.db()).saveAndBindRelease(task, configuration,
                    "a".repeat(64));
                assertEquals(CONFLICT, associations.observe(task, identity, unit));
                return success();
            });
        }
    }
}
