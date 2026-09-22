package gold.debug.windowstolinux.app.service.backup;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment.MissingInputType;

/**
 * Assesses exact persisted backup inputs without connecting to or changing a server. / 在不连接或修改服务器的情况下评估精确持久化备份输入。
 */
public final class ManagedBackupInputUseCase {
    /**
     * Bound managed application graph repository collaborator for graphs.
     * <p>处理图集合的受管应用图仓库协作对象。
     */
    private final ManagedApplicationGraphRepository graphs;

    /**
     * Bound managed application repository collaborator for applications.
     * <p>处理应用集合的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository applications;

    /**
     * Bound configuration snapshot repository collaborator for configurations.
     * <p>处理配置集合的配置快照仓库协作对象。
     */
    private final ConfigurationSnapshotRepository configurations;

    /**
     * Bound application secret repository collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository secrets;

    /**
     * Creates the read-only persisted-input use case. / 创建只读持久化输入用例。
     *
     * @param graphs graphs / 图集合
     * @param applications applications / 应用集合
     * @param configurations configurations / 配置集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedBackupInputUseCase(ManagedApplicationGraphRepository graphs,
            ManagedApplicationRepository applications, ConfigurationSnapshotRepository configurations,
            ApplicationSecretRepository secrets) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * Returns structured blockers and never guesses missing historical deployment inputs. / 返回结构化阻塞项且绝不猜测缺失的历史部署输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return structured blockers and never guesses missing historical deployment inputs / 结构化阻塞项且绝不猜测缺失的历史部署输入
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public ManagedBackupInputAssessment assess(String applicationId) throws SQLException {
        applicationId = managedId(applicationId);
        Optional<ManagedApplicationGraph> initial = graphs.find(applicationId);
        if (initial.isEmpty()) {
            return new ManagedBackupInputAssessment(applicationId, List.of(), Map.of(),
                    List.of(MissingInputType.MANAGED_APPLICATION_GRAPH), Map.of());
        }
        ManagedApplicationGraph graph = initial.orElseThrow();
        List<String> componentIds = graph.components().stream().map(ManagedApplicationGraph.Component::componentId)
                .toList();
        LinkedHashMap<String, CurrentRelease> releases = new LinkedHashMap<>();
        LinkedHashMap<String, String> releaseIdentities = new LinkedHashMap<>();
        LinkedHashMap<String, List<MissingInputType>> missing = new LinkedHashMap<>();
        long databaseCount = graph.components().stream()
                .flatMap(component -> component.reviewedResourceBindings().stream())
                .flatMap(resources -> resources.databaseBindings().stream()).mapToLong(List::size).sum();
        for (ManagedApplicationGraph.Component component : graph.components()) {
            List<MissingInputType> componentMissing = new ArrayList<>();
            if (component.reviewedRuntime().isEmpty())
                componentMissing.add(MissingInputType.REVIEWED_RUNTIME);
            if (component.reviewedDataPaths().isEmpty())
                componentMissing.add(MissingInputType.REVIEWED_DATA_PATHS);
            if (component.reviewedResourceBindings().isEmpty()) {
                componentMissing.add(MissingInputType.REVIEWED_RESOURCE_BINDINGS);
            } else if (component.reviewedResourceBindings().orElseThrow().databaseBindings().isEmpty()) {
                componentMissing.add(MissingInputType.REVIEWED_DATABASE_BINDINGS);
            }
            component.reviewedResourceBindings().flatMap(resources -> resources.databaseBindings())
                    .ifPresent(databases -> {
                        if (!databases.isEmpty() && (databaseCount > 1 || databases.stream().anyMatch(binding -> binding
                                .connection()
                                .engine() == gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType.REDIS)))
                            componentMissing.add(MissingInputType.UNSUPPORTED_DATABASE_BACKUP);
                    });
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
            if (!componentMissing.isEmpty())
                missing.put(component.componentId(), List.copyOf(componentMissing));
        }
        List<MissingInputType> applicationMissing = new ArrayList<>();
        if (graph.applicationHealthCheck().isEmpty()) {
            applicationMissing.add(MissingInputType.APPLICATION_HEALTH_CHECK);
        }
        if (!stable(graph, releases)) {
            applicationMissing.add(MissingInputType.LOCAL_STATE_CHANGED_DURING_ASSESSMENT);
        }
        return new ManagedBackupInputAssessment(applicationId, componentIds, releaseIdentities,
                List.copyOf(applicationMissing), missing);
    }

    /**
     * Tests the stable predicate against the supplied evidence.
     * <p>根据所提供证据检查稳定条件。
     *
     * @param initial initial / 初始
     * @param initialReleases initial releases / 初始发布集合
     * @return true when stable predicate against the supplied evidence, false otherwise / 根据所提供证据检查稳定条件时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private boolean stable(ManagedApplicationGraph initial, Map<String, CurrentRelease> initialReleases)
            throws SQLException {
        if (graphs.find(initial.applicationId()).filter(initial::equals).isEmpty())
            return false;
        for (ManagedApplicationGraph.Component component : initial.components()) {
            Optional<CurrentRelease> current = applications.findRelease(component.application().id());
            CurrentRelease expected = initialReleases.get(component.componentId());
            if (!current.equals(Optional.ofNullable(expected)))
                return false;
        }
        return true;
    }

    /**
     * Validates a managed identifier before it reaches a remote resource boundary.
     * <p>在标识到达远端资源边界前验证受管标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return managed id text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedId(String value) {
        value = Objects.requireNonNull(value, "applicationId").trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must be a bounded managed identifier");
        }
        return value;
    }
}
