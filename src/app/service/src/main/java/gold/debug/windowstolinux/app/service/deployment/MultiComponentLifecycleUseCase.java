package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.deployment.multi.ManagedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.ManagedComponentLifecycle;
import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.MultiComponentLifecycleService;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
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

/**
 * Owns durable multi-component recovery and lifecycle operations. / 负责持久多组件应用恢复与生命周期操作。
 */
public final class MultiComponentLifecycleUseCase {
    /**
     * Bound managed application repository collaborator for applications.
     * <p>处理应用集合的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository applications;
    /**
     * Bound managed application graph repository collaborator for graphs.
     * <p>处理图集合的受管应用图仓库协作对象。
     */
    private final ManagedApplicationGraphRepository graphs;
    /**
     * Bound multi component lifecycle service collaborator for lifecycle service.
     * <p>处理生命周期服务的多组件生命周期服务协作对象。
     */
    private final MultiComponentLifecycleService lifecycleService;
    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ServerOperationLockRegistry locks;

    /**
     * Creates the bounded lifecycle use case. / 创建有界生命周期用例。
     *
     * @param applications applications / 应用集合
     * @param graphs graphs / 图集合
     * @param lifecycleService lifecycle service / 生命周期服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentLifecycleUseCase(ManagedApplicationRepository applications,
                                          ManagedApplicationGraphRepository graphs,
                                          MultiComponentLifecycleService lifecycleService,
                                          DeploymentLinuxGateway gateway,
                                          ServerUseCaseFacade servers,
                                          ServerOperationLockRegistry locks) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Loads one durable graph for lifecycle work after a desktop restart. / 在桌面应用重启后加载一个用于生命周期工作的持久图。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
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

    /**
     * Executes a dependency-safe lifecycle action using authoritative remote observations. / 使用权威远端观测执行依赖安全的应用生命周期动作。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetComponentIds target component ids / 目标组件标识集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return constructed or resolved multi component lifecycle result / 构造或解析得到的多组件生命周期结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
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
                    .orElseThrow(() -> ApplicationServiceException.create(
                            ApplicationServiceFailureType.APPLICATION_NOT_MANAGED,
                            "The selected whole-application graph has no verified successful deployment"));
            validateProfile(managed, profile, mode);
            ReentrantLock lock = locks.forServer(profile.id());
            lock.lock();
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                MultiComponentLifecycleResult result = lifecycleService.execute(managed.plan(), managed.components(),
                        targetComponentIds, action, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, ignored -> false));
                boolean observationSaveFailed = false;
                for (var observation : result.componentResults().stream()
                        .flatMap(value -> value.observation().stream()).toList()) {
                    try {
                        applications.saveObservation(observation);
                    } catch (SQLException failure) {
                        observationSaveFailed = true;
                    }
                }
                if (observationSaveFailed) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.LOCAL_OBSERVATION_SAVE_FAILED,
                            result.operationIdentity(), "At least one remote component observation could not be stored locally"));
                }
                return result;
            } finally {
                lock.unlock();
            }
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Validates connection or provider settings supplied to the operation.
     * <p>校验提供给操作的连接或提供者设置。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static void validateProfile(ManagedMultiComponentApplication application, ServerProfile profile,
                                        CredentialStorageMode mode) {
        Objects.requireNonNull(application, "application");
        ServerProfile selectedProfile = Objects.requireNonNull(profile, "profile");
        if (selectedProfile.credentialMode() != mode || application.components().stream().anyMatch(component ->
                !component.application().server().id().equals(selectedProfile.id())
                        || !component.application().server().host().equals(selectedProfile.endpoint().host())
                        || component.application().server().sshPort() != selectedProfile.endpoint().port())) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.LIFECYCLE_CONTEXT_MISMATCH,
                    "Managed application, server endpoint, and credential storage mode must match");
        }
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }
}
