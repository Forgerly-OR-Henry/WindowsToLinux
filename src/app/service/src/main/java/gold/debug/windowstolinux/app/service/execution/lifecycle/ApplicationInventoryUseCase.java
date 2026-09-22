package gold.debug.windowstolinux.app.service.execution.lifecycle;

import java.net.URI;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationPresentation;
import gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Joins native deployment evidence and external registration metadata for the desktop list. / 为桌面列表汇合原生部署证据与外部登记元数据。
 */
public final class ApplicationInventoryUseCase {
    /**
     * Bound managed application repository collaborator for managed.
     * <p>处理受管的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository managed;

    /**
     * Bound external application repository collaborator for external.
     * <p>处理外部的外部应用仓库协作对象。
     */
    private final ExternalApplicationRepository external;

    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;

    /**
     * Binds inventory reads to their repositories. / 将清单读取绑定到所属仓库。
     *
     * @param managed managed / 受管
     * @param external external / 外部
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     */
    public ApplicationInventoryUseCase(ManagedApplicationRepository managed, ExternalApplicationRepository external,
            ServerUseCaseFacade servers) {
        this.managed = managed;
        this.external = external;
        this.servers = servers;
    }

    /**
     * Returns stable newest-first summaries without claiming saved observations are live. / 返回最新优先的稳定摘要，不把已保存观测宣称为实时状态。
     *
     * @return stable newest-first summaries without claiming saved observations are live / 最新优先的稳定摘要，不把已保存观测宣称为实时状态
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<ApplicationSummary> list() throws SQLException {
        List<ApplicationSummary> result = new ArrayList<>();
        var profiles = servers.list().stream()
                .collect(java.util.stream.Collectors.toMap(value -> value.id(), value -> value));
        for (var app : managed.list()) {
            var profile = Optional.ofNullable(profiles.get(app.server().id()));
            var runtime = managed.findRuntime(app.id());
            var observation = managed.findObservation(app);
            var usage = gold.debug.windowstolinux.shared.model.managed.ApplicationUsage.from(app,
                    runtime.map(value -> value.workload())
                            .orElse(gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload
                                    .unspecified()));
            var url = runtime.flatMap(value -> value.userAccessUrl());
            var presentation = external.presentation("managed:" + app.id()).orElse(new StoredApplicationPresentation(
                    "managed:" + app.id(), app.id(), usage.category().equals("WEBSITE") ? "WEBSITE" : "APP", url));
            result.add(new ApplicationSummary(presentation.key(), presentation.name(), usage.category(),
                    app.server().id(), profile.map(value -> value.displayName()).orElse(app.server().id()),
                    app.server().host(), managed.findRelease(app.id()).map(CurrentRelease::publishedAt),
                    Optional.empty(), observation.map(value -> value.runtimeState()).orElse(RuntimeState.UNKNOWN),
                    observation.map(value -> value.observedAt()),
                    presentation.accessUrl().filter(value -> usage.category().equals("WEBSITE")), false,
                    usage.lifecycle(), usage.lifecycle(),
                    profile.map(value -> value.credentialMode() == CredentialStorageMode.MASTER_PASSWORD).orElse(false),
                    Optional.of(usage)));
        }
        for (var registered : external.list()) {
            var profile = Optional.ofNullable(profiles.get(registered.serverId()));
            var app = registered.application();
            var presentation = external.presentation(registered.id())
                    .orElse(new StoredApplicationPresentation(registered.id(), app.name(), "APP", Optional.empty()));
            result.add(new ApplicationSummary(presentation.key(), presentation.name(), presentation.category(),
                    registered.serverId(), profile.map(value -> value.displayName()).orElse(registered.serverId()),
                    registered.host(), Optional.empty(), Optional.of(registered.adoptedAt()), app.state(),
                    Optional.of(registered.observedAt()), presentation.accessUrl(), true, app.canStart(), app.canStop(),
                    profile.map(value -> value.credentialMode() == CredentialStorageMode.MASTER_PASSWORD)
                            .orElse(false)));
        }
        return result.stream().sorted(ApplicationSummary.newestFirst()).toList();
    }

    /**
     * Saves explicit local presentation overrides, not runtime configuration. / 保存明确的本地显示覆盖，不修改运行配置。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param category category / 类别
     * @param url URL address / URL 地址
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void savePresentation(String key, String name, String category, String url) throws SQLException {
        if (key.startsWith("managed:") ? managed.find(key.substring(8)).isEmpty() : external.find(key).isEmpty())
            throw new IllegalArgumentException("application no longer exists");
        if (key.startsWith("managed:")) {
            var app = managed.find(key.substring(8)).orElseThrow();
            var runtime = managed.findRuntime(app.id());
            String derived = runtime.filter(value -> value.workload().reviewed())
                    .map(value -> value.workload().category().name()).orElse("UNKNOWN");
            if (!derived.equals(category))
                throw new IllegalArgumentException("Managed category comes from reviewed service declarations");
        }
        Optional<UserAccessUrl> access = url.isBlank()
                ? Optional.empty()
                : Optional.of(new UserAccessUrl(URI.create(url.trim())));
        external.savePresentation(
                new StoredApplicationPresentation(key, name, category.equals("UNKNOWN") ? "APP" : category, access));
    }
}
