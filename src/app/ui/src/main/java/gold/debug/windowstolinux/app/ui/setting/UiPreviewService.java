package gold.debug.windowstolinux.app.ui.setting;

import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fixed preview data with no production service, storage or transport references. / 固定预览数据，不持有真实服务、存储或传输对象。
 */
final class UiPreviewService {
    /**
     * Supplies preview-only facade behavior to inspect desktop layouts.
     * <p>提供预览门面行为以检查桌面布局。
     */
    interface Facade extends ServerApplicationFacade, AiApplicationFacade, ManagedApplicationFacade { }

    /**
     * Server identity or selected server configuration.
     * <p>服务器身份或所选服务器配置。
     */
    final ServerProfile server;
    /**
     * Configured model identifier sent to the provider.
     * <p>发送给提供者的已配置模型标识。
     */
    final AiProviderSummary model;
    /**
     * Managed target with its server and ownership identity.
     * <p>携带服务器及归属身份的受管目标。
     */
    final ApplicationSummary application;
    /**
     * Bound facade collaborator for facade.
     * <p>处理门面的门面协作对象。
     */
    final Facade facade;

    /**
     * Binds the supplied dependencies and state for ui preview service.
     * <p>为界面预览服务绑定传入的依赖及状态。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @throws UnsupportedOperationException if the requested capability is not implemented by this adapter / 当前适配器未实现所请求能力时
     */
    UiPreviewService(PageMessagePresenter messages) {
        Instant observed = Instant.parse("2026-09-17T08:00:00Z");
        server = new ServerProfile("preview-server", "192.0.2.10", 22, "root", "preview/ssh",
                CredentialStorageMode.MASTER_PASSWORD, messages.text("debug.sample.server"));
        model = new AiProviderSummary(new AiProviderProfile("preview-model",
                URI.create("https://example.invalid/v1/chat/completions"), "preview-model", "ai/provider/preview/api-key",
                CredentialStorageMode.MASTER_PASSWORD), messages.text("debug.sample.model"), 0, 1, Optional.of(observed), Optional.of(observed));
        application = new ApplicationSummary("external:preview-app", messages.text("debug.sample.app"), "WEBSITE",
                server.id(), server.displayName(), server.host(), Optional.empty(), Optional.of(observed), RuntimeState.RUNNING,
                Optional.of(observed), Optional.of(new UserAccessUrl(URI.create("https://example.invalid"))), true, true, true, true);
        var scan = new ApplicationScan(server, List.of(
                new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.SYSTEMD, "preview.service", "a".repeat(64)),
                        messages.text("debug.sample.app"), RuntimeState.RUNNING, true, true, false),
                new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.DOCKER, "b".repeat(64), "c".repeat(64)),
                        "preview-container", RuntimeState.STOPPED, true, true, false)), List.of(), Map.of());
        // Only listed reads are allowed; new facade operations remain blocked by default. / 仅允许列出的读取，新增门面操作默认被阻止。
        facade = (Facade) Proxy.newProxyInstance(Facade.class.getClassLoader(), new Class<?>[]{Facade.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listServerProfiles" -> List.of(server);
                    case "listServerSummaries" -> List.of(new ServerSummary(server, Optional.of(observed), true, "Ubuntu 24.04"));
                    case "findServerProfile" -> server.id().equals(arguments[0]) ? Optional.of(server) : Optional.empty();
                    case "listAiConfigurations" -> List.of(model);
                    case "listApplications" -> List.of(application);
                    case "listManagedApplicationSummaries" -> List.of();
                    case "scanApplications" -> scan;
                    case "invokeAiRole" -> Optional.empty();
                    case "toString" -> "UiPreviewService";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(messages.text("debug.blocked"));
                });
    }
}
