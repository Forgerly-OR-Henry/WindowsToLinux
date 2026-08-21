package gold.debug.windowstolinux.app.service.deployment.multi;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Secret-free reviewed deployment request and stable identity for one component. / 一个组件不含秘密的经审阅部署请求与稳定身份。 */
public record ReviewedComponentApplication(
        String componentId,
        ReviewedDeploymentRequest request,
        ManagedApplication application,
        List<ComponentDataPath> dataPaths
) {
    /** Validates the exact managed identity. / 验证精确受管身份。 */
    public ReviewedComponentApplication {
        componentId = Objects.requireNonNull(componentId, "componentId");
        request = Objects.requireNonNull(request, "request");
        application = Objects.requireNonNull(application, "application");
        dataPaths = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths").stream()
                .map(value -> Objects.requireNonNull(value, "data path"))
                .sorted(Comparator.comparing(ComponentDataPath::path)).toList());
        if (!application.id().equals(request.facts().applicationId()) || !application.server().equals(request.server())) {
            throw new IllegalArgumentException("reviewed component request must match its managed identity");
        }
        if (dataPaths.stream().map(ComponentDataPath::path).distinct().count() != dataPaths.size()) {
            throw new IllegalArgumentException("reviewed component data paths must be unique");
        }
    }
}
