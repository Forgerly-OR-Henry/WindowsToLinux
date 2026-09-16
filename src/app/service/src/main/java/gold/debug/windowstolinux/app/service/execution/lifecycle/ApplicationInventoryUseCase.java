package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationPresentation;
import gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import java.net.URI;
import java.sql.SQLException;
import java.util.*;

/** Joins native deployment evidence and external registration metadata for the desktop list. / 为桌面列表汇合原生部署证据与外部登记元数据。 */
public final class ApplicationInventoryUseCase {
    private final ManagedApplicationRepository managed;
    private final ExternalApplicationRepository external;
    private final ServerUseCaseFacade servers;

    /** Binds inventory reads to their repositories. / 将清单读取绑定到所属仓库。 */
    public ApplicationInventoryUseCase(ManagedApplicationRepository managed, ExternalApplicationRepository external, ServerUseCaseFacade servers) {
        this.managed = managed; this.external = external; this.servers = servers;
    }

    /** Returns stable newest-first summaries without claiming saved observations are live. / 返回最新优先的稳定摘要，不把已保存观测宣称为实时状态。 */
    public List<ApplicationSummary> list() throws SQLException {
        List<ApplicationSummary> result = new ArrayList<>();
        var profiles = servers.list().stream().collect(java.util.stream.Collectors.toMap(value -> value.id(), value -> value));
        for (var app : managed.list()) {
            var profile = Optional.ofNullable(profiles.get(app.server().id()));
            var runtime = managed.findRuntime(app.id()); var observation = managed.findObservation(app);
            var url = runtime.flatMap(value -> value.userAccessUrl());
            var presentation = external.presentation("managed:" + app.id()).orElse(new StoredApplicationPresentation("managed:" + app.id(), app.id(), url.isPresent() ? "WEBSITE" : "APP", url));
            result.add(new ApplicationSummary(presentation.key(), presentation.name(), presentation.category(), app.server().id(),
                    profile.map(value -> value.displayName()).orElse(app.server().id()), app.server().host(),
                    managed.findRelease(app.id()).map(CurrentRelease::publishedAt), Optional.empty(),
                    observation.map(value -> value.runtimeState()).orElse(RuntimeState.UNKNOWN), observation.map(value -> value.observedAt()),
                    presentation.accessUrl(), false, true, true, profile.map(value -> value.credentialMode() == CredentialStorageMode.MASTER_PASSWORD).orElse(false)));
        }
        for (var registered : external.list()) {
            var profile = Optional.ofNullable(profiles.get(registered.serverId())); var app = registered.application();
            var presentation = external.presentation(registered.id()).orElse(new StoredApplicationPresentation(registered.id(), app.name(), "APP", Optional.empty()));
            result.add(new ApplicationSummary(presentation.key(), presentation.name(), presentation.category(), registered.serverId(),
                    profile.map(value -> value.displayName()).orElse(registered.serverId()), registered.host(), Optional.empty(), Optional.of(registered.adoptedAt()),
                    app.state(), Optional.of(registered.observedAt()), presentation.accessUrl(), true, app.canStart(), app.canStop(),
                    profile.map(value -> value.credentialMode() == CredentialStorageMode.MASTER_PASSWORD).orElse(false)));
        }
        return result.stream().sorted(ApplicationSummary.newestFirst()).toList();
    }

    /** Saves explicit local presentation overrides, not runtime configuration. / 保存明确的本地显示覆盖，不修改运行配置。 */
    public void savePresentation(String key, String name, String category, String url) throws SQLException {
        if (key.startsWith("managed:") ? managed.find(key.substring(8)).isEmpty() : external.find(key).isEmpty())
            throw new IllegalArgumentException("application no longer exists");
        Optional<UserAccessUrl> access = url.isBlank() ? Optional.empty() : Optional.of(new UserAccessUrl(URI.create(url.trim())));
        external.savePresentation(new StoredApplicationPresentation(key, name, category, access));
    }
}
