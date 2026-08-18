package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.locking.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.lifecycle.ManagedComponentLifecycle;
import gold.debug.windowstolinux.shared.deploy.lifecycle.MultiComponentLifecycleService;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Owns durable multi-component recovery and lifecycle operations. / 负责持久多组件应用恢复与生命周期操作。 */
public final class MultiComponentLifecycleUseCase {
    private final ManagedApplicationRepository applications;
    private final ManagedApplicationGraphRepository graphs;
    private final MultiComponentLifecycleService lifecycleService;
    private final DeploymentLinuxGateway gateway;
    private final ServerUseCases servers;
    private final ServerOperationLocks locks;

    /** Creates the bounded lifecycle use case. / 创建有界生命周期用例。 */
    public MultiComponentLifecycleUseCase(ManagedApplicationRepository applications,
                                          ManagedApplicationGraphRepository graphs,
                                          MultiComponentLifecycleService lifecycleService,
                                          DeploymentLinuxGateway gateway,
                                          ServerUseCases servers,
                                          ServerOperationLocks locks) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /** Loads one durable graph for lifecycle work after a desktop restart. / 在桌面应用重启后加载一个用于生命周期工作的持久图。 */
    public Optional<ManagedMultiComponentApplication> findManagedApplication(String applicationId) throws SQLException {
        return graphs.find(applicationId).map(graph -> {
            Map<String, String> namespaces = new LinkedHashMap<>();
            Map<String, List<String>> dependencies = new LinkedHashMap<>();
            List<ManagedComponentLifecycle> components = new ArrayList<>();
            for (ManagedApplicationGraph.Component component : graph.components()) {
                namespaces.put(component.componentId(), component.application().id());
                dependencies.put(component.componentId(), component.dependencies());
                components.add(new ManagedComponentLifecycle(component.componentId(), component.application(),
                        component.runtimeConfiguration().healthCheck()));
            }
            MultiComponentDeploymentPlan plan = new MultiComponentDeploymentPlanner().restore(
                    graph.applicationId(), namespaces, dependencies);
            return new ManagedMultiComponentApplication(plan, components, graph.healthComponentId());
        });
    }

    /** Executes a dependency-safe lifecycle action using authoritative remote observations. / 使用权威远端观测执行依赖安全的应用生命周期动作。 */
    public MultiComponentLifecycleResult executeLifecycleWithStoredPassword(
            String applicationId,
            Set<String> targetComponentIds,
            LifecycleAction action,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword
    ) throws SecretStoreException, SQLException {
        try {
            ManagedMultiComponentApplication managed = findManagedApplication(applicationId)
                    .orElseThrow(() -> new LocalizedOperationException(
                            LocalizedMessage.of("lifecycle.applicationNotManaged"),
                            "The selected whole-application graph has no verified successful deployment"));
            validateProfile(managed, profile, mode);
            ReentrantLock lock = locks.forServer(profile.id());
            lock.lock();
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                MultiComponentLifecycleResult result = lifecycleService.execute(managed.plan(), managed.components(),
                        targetComponentIds, action, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, ignored -> false));
                result.componentResults().stream().flatMap(value -> value.observation().stream())
                        .forEach(this::saveObservationQuietly);
                return result;
            } finally {
                lock.unlock();
            }
        } finally {
            clear(masterPassword);
        }
    }

    private static void validateProfile(ManagedMultiComponentApplication application, ServerProfile profile,
                                        CredentialStorageMode mode) {
        Objects.requireNonNull(application, "application");
        ServerProfile selectedProfile = Objects.requireNonNull(profile, "profile");
        if (selectedProfile.credentialMode() != mode || application.components().stream().anyMatch(component ->
                !component.application().server().id().equals(selectedProfile.id())
                        || !component.application().server().host().equals(selectedProfile.endpoint().host())
                        || component.application().server().sshPort() != selectedProfile.endpoint().port())) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.lifecycleContextMismatch"),
                    "Managed application, server endpoint, and credential storage mode must match");
        }
    }

    private void saveObservationQuietly(LifecycleObservation observation) {
        try {
            applications.saveObservation(observation);
        } catch (SQLException ignored) {
            // Remote truth remains authoritative when local history recording fails. / 本地历史记录失败时，远端事实仍然具有权威性。
        }
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }
}
