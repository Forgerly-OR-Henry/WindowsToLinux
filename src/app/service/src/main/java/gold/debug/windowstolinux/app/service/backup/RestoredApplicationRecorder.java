package gold.debug.windowstolinux.app.service.backup;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/**
 * Atomically records an exact restored graph only after formal remote health succeeded. / 仅在远端正式健康成功后原子记录精确恢复图。
 */
final class RestoredApplicationRecorder {
    /**
     * Bound managed application graph repository collaborator for graphs.
     * <p>处理图集合的受管应用图仓库协作对象。
     */
    private final ManagedApplicationGraphRepository graphs;

    /**
     * Validates and binds the inputs required by restored application recorder.
     * <p>校验并绑定已恢复应用记录器所需输入。
     *
     * @param graphs graphs / 图集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    RestoredApplicationRecorder(ManagedApplicationGraphRepository graphs) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
    }

    /**
     * Tests the source graph retained predicate against the supplied evidence.
     * <p>根据所提供证据检查源码图已保留条件。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param targetServerId target server id / 目标服务器标识
     * @return true when source graph retained predicate against the supplied evidence, false otherwise / 根据所提供证据检查源码图已保留条件时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    boolean sourceGraphRetained(RestoreArchiveModel model, String targetServerId) throws SQLException {
        return graphs.find(model.activation().validation().manifest().applicationId())
                .filter(graph -> !graph.components().getFirst().application().server().id().equals(targetServerId))
                .isPresent();
    }

    /**
     * Tests the existing owned target predicate against the supplied evidence.
     * <p>根据所提供证据检查既有已持有目标条件。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param targetServerId target server id / 目标服务器标识
     * @return true when existing owned target predicate against the supplied evidence, false otherwise / 根据所提供证据检查既有已持有目标条件时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    boolean existingOwnedTarget(RestoreArchiveModel model, String targetServerId) throws SQLException {
        return graphs.find(model.activation().validation().manifest().applicationId())
                .filter(graph -> graph.components().getFirst().application().server().id().equals(targetServerId))
                .isPresent();
    }

    /**
     * Records restored application recorder.
     * <p>记录已恢复应用记录器。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
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
        graphs.recordSuccessfulApplication(
                new ManagedApplicationGraph(manifest.applicationId(),
                        manifest.inventory().applicationHealthComponentId(),
                        Optional.of(manifest.inventory().applicationHealthCheck().toHealthCheck()), graphComponents),
                deployments);
    }

    /**
     * Builds backup configuration document state from the supplied state inputs.
     * <p>根据所提供状态输入构建备份配置文档状态。
     *
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @return backup configuration document state from the supplied state inputs / 根据所提供状态输入构建备份配置文档状态
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static BackupConfigurationDocumentState state(RestoreArchiveModel model, String componentId) {
        var document = model.configurations().get(componentId);
        if (document == null)
            throw new IllegalStateException("restored component configuration disappeared");
        return new BackupConfigurationDocumentState(document.configuration(), document.resourceBindings(),
                document.runtimeConfiguration());
    }

    /**
     * Holds the decoded configuration state used to record a restored application.
     * <p>持有登记已恢复应用所用的已解码配置状态。
     *
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param runtimeConfiguration runtime configuration / 运行时配置
     */
    private record BackupConfigurationDocumentState(
            gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot configuration,
            gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings resources,
            gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration runtimeConfiguration) {
    }
}
