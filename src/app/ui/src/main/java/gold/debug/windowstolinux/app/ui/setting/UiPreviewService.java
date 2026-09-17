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

/** Fixed preview data with no production service, storage or transport references. / 固定预览数据，不持有真实服务、存储或传输对象。 */
final class UiPreviewService {
    interface Facade extends ServerApplicationFacade, AiApplicationFacade, ManagedApplicationFacade { }

    final ServerProfile server;
    final AiProviderSummary model;
    final ApplicationSummary application;
    final Facade facade;

    UiPreviewService(PageMessagePresenter messages) {
        Instant observed = Instant.parse("2026-09-17T08:00:00Z");
        server = new ServerProfile("preview-server", "192.0.2.10", 22, "root", "preview/ssh",
                CredentialStorageMode.MASTER_PASSWORD, messages.text("debug.sample.server"));
        model = new AiProviderSummary(new AiProviderProfile("preview-model",
                URI.create("https://example.invalid/v1/chat/completions"), "preview-model", "ai/provider/preview/api-key",
                CredentialStorageMode.MASTER_PASSWORD), messages.text("debug.sample.model"), true, 0, Optional.of(observed));
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
