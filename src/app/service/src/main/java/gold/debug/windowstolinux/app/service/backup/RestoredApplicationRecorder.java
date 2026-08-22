package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomically records an exact restored graph only after formal remote health succeeded. / 仅在远端正式健康成功后原子记录精确恢复图。 */
final class RestoredApplicationRecorder {
    private final ManagedApplicationGraphRepository graphs;

    RestoredApplicationRecorder(ManagedApplicationGraphRepository graphs) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
    }

    boolean sourceGraphRetained(RestoreArchiveModel model, String targetServerId) throws SQLException {
        return graphs.find(model.activation().validation().manifest().applicationId())
                .filter(graph -> !graph.components().getFirst().application().server().id().equals(targetServerId))
                .isPresent();
    }

    boolean existingOwnedTarget(RestoreArchiveModel model, String targetServerId) throws SQLException {
        return graphs.find(model.activation().validation().manifest().applicationId())
                .filter(graph -> graph.components().getFirst().application().server().id().equals(targetServerId))
                .isPresent();
    }

    void record(RestoreArchiveModel model, ServerIdentity target) throws SQLException {
        var manifest = model.activation().validation().manifest();
        Instant publishedAt = Instant.now();
        List<ManagedApplicationGraph.Component> graphComponents = new ArrayList<>();
        List<SuccessfulManagedDeployment> deployments = new ArrayList<>();
        for (var component : manifest.inventory().components()) {
            BackupConfigurationDocumentState state = state(model, component.componentId());
            ManagedApplication application = ManagedApplication.forManaged(component.managedApplicationId(), target,
                    component.ownershipManifestSha256());
            graphComponents.add(new ManagedApplicationGraph.Component(component.componentId(), application,
                    state.runtimeConfiguration(), component.dependsOn(),
                    Optional.of(component.runtime().toSpecification()),
                    Optional.of(state.resources().fileBindings().stream().map(binding -> binding.dataPath()).toList()),
                    Optional.of(state.resources())));
            deployments.add(new SuccessfulManagedDeployment(application, state.runtimeConfiguration(),
                    new CurrentRelease(application.id(), component.releaseSha256().orElseThrow(), publishedAt),
                    state.configuration(), component.secretReferences().orElseThrow()));
        }
        graphs.recordSuccessfulApplication(new ManagedApplicationGraph(manifest.applicationId(),
                manifest.inventory().applicationHealthComponentId(),
                Optional.of(manifest.inventory().applicationHealthCheck().toHealthCheck()), graphComponents),
                deployments);
    }

    private static BackupConfigurationDocumentState state(RestoreArchiveModel model, String componentId) {
        var document = model.configurations().get(componentId);
        if (document == null) throw new IllegalStateException("restored component configuration disappeared");
        return new BackupConfigurationDocumentState(document.configuration(), document.resourceBindings(),
                document.runtimeConfiguration());
    }

    private record BackupConfigurationDocumentState(
            gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot configuration,
            gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings resources,
            gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration runtimeConfiguration
    ) { }
}
