package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment.MissingInputType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Assesses exact persisted backup inputs without connecting to or changing a server. / 在不连接或修改服务器的情况下评估精确持久化备份输入。 */
public final class ManagedBackupInputUseCase {
    private final ManagedApplicationGraphRepository graphs;
    private final ManagedApplicationRepository applications;
    private final ConfigurationSnapshotRepository configurations;
    private final ApplicationSecretRepository secrets;

    /** Creates the read-only persisted-input use case. / 创建只读持久化输入用例。 */
    public ManagedBackupInputUseCase(ManagedApplicationGraphRepository graphs,
                                     ManagedApplicationRepository applications,
                                     ConfigurationSnapshotRepository configurations,
                                     ApplicationSecretRepository secrets) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** Returns structured blockers and never guesses missing historical deployment inputs. / 返回结构化阻塞项且绝不猜测缺失的历史部署输入。 */
    public ManagedBackupInputAssessment assess(String applicationId) throws SQLException {
        applicationId = managedId(applicationId);
        Optional<ManagedApplicationGraph> initial = graphs.find(applicationId);
        if (initial.isEmpty()) {
            return new ManagedBackupInputAssessment(applicationId, List.of(), Map.of(),
                    List.of(MissingInputType.MANAGED_APPLICATION_GRAPH), Map.of());
        }
        ManagedApplicationGraph graph = initial.orElseThrow();
        List<String> componentIds = graph.components().stream()
                .map(ManagedApplicationGraph.Component::componentId).toList();
        LinkedHashMap<String, CurrentRelease> releases = new LinkedHashMap<>();
        LinkedHashMap<String, String> releaseIdentities = new LinkedHashMap<>();
        LinkedHashMap<String, List<MissingInputType>> missing = new LinkedHashMap<>();
        for (ManagedApplicationGraph.Component component : graph.components()) {
            List<MissingInputType> componentMissing = new ArrayList<>();
            if (component.reviewedRuntime().isEmpty()) componentMissing.add(MissingInputType.REVIEWED_RUNTIME);
            if (component.reviewedDataPaths().isEmpty()) componentMissing.add(MissingInputType.REVIEWED_DATA_PATHS);
            if (component.reviewedResourceBindings().isEmpty()) {
                componentMissing.add(MissingInputType.REVIEWED_RESOURCE_BINDINGS);
            } else if (component.reviewedResourceBindings().orElseThrow().databaseBindings().isEmpty()) {
                componentMissing.add(MissingInputType.REVIEWED_DATABASE_BINDINGS);
            }
            Optional<CurrentRelease> release = applications.findRelease(component.application().id());
            if (release.isEmpty()) {
                componentMissing.add(MissingInputType.CURRENT_RELEASE);
            } else {
                CurrentRelease current = release.orElseThrow();
                releases.put(component.componentId(), current);
                releaseIdentities.put(component.componentId(), current.releaseSha256());
                if (configurations.findRelease(component.application().id(), current.releaseSha256()).isEmpty()) {
                    componentMissing.add(MissingInputType.RELEASE_CONFIGURATION);
                }
                if (secrets.findRelease(component.application().id(), current.releaseSha256()).isEmpty()) {
                    componentMissing.add(MissingInputType.RELEASE_SECRET_REFERENCES);
                }
            }
            if (!componentMissing.isEmpty()) missing.put(component.componentId(), List.copyOf(componentMissing));
        }
        List<MissingInputType> applicationMissing = stable(graph, releases)
                ? List.of() : List.of(MissingInputType.LOCAL_STATE_CHANGED_DURING_ASSESSMENT);
        return new ManagedBackupInputAssessment(applicationId, componentIds, releaseIdentities,
                applicationMissing, missing);
    }

    private boolean stable(ManagedApplicationGraph initial, Map<String, CurrentRelease> initialReleases)
            throws SQLException {
        if (graphs.find(initial.applicationId()).filter(initial::equals).isEmpty()) return false;
        for (ManagedApplicationGraph.Component component : initial.components()) {
            Optional<CurrentRelease> current = applications.findRelease(component.application().id());
            CurrentRelease expected = initialReleases.get(component.componentId());
            if (!current.equals(Optional.ofNullable(expected))) return false;
        }
        return true;
    }

    private static String managedId(String value) {
        value = Objects.requireNonNull(value, "applicationId").trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must be a bounded managed identifier");
        }
        return value;
    }
}
