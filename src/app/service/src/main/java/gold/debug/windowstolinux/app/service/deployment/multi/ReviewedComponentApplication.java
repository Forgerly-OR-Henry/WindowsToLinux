package gold.debug.windowstolinux.app.service.deployment.multi;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Secret-free reviewed deployment request and stable identity for one component. / 一个组件不含秘密的经审阅部署请求与稳定身份。 */
public record ReviewedComponentApplication(
        String componentId,
        ReviewedDeploymentRequest request,
        ManagedApplication application,
        ManagedComponentResourceBindings resourceBindings
) {
    /** Validates the exact managed identity. / 验证精确受管身份。 */
    public ReviewedComponentApplication {
        componentId = Objects.requireNonNull(componentId, "componentId");
        request = Objects.requireNonNull(request, "request");
        application = Objects.requireNonNull(application, "application");
        resourceBindings = Objects.requireNonNull(resourceBindings, "resourceBindings");
        if (!application.id().equals(request.facts().applicationId()) || !application.server().equals(request.server())) {
            throw new IllegalArgumentException("reviewed component request must match its managed identity");
        }
    }

    /** Creates stable opaque file bindings without interpreting logical paths as physical paths. / 创建稳定不透明文件绑定且不把逻辑路径解释为物理路径。 */
    public static ManagedComponentResourceBindings resourceBindings(
            String componentId,
            List<ComponentDataPath> dataPaths,
            Optional<List<ManagedDatabaseBinding>> databaseBindings
    ) {
        componentId = managedIdentifier(componentId);
        String stableComponentId = componentId;
        List<ManagedFileBinding> files = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths").stream()
                .map(path -> new ManagedFileBinding(fileBindingId(stableComponentId, path), path)).toList());
        return new ManagedComponentResourceBindings(files, databaseBindings);
    }

    private static String fileBindingId(String componentId, ComponentDataPath path) {
        Objects.requireNonNull(path, "data path");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        update(digest, "windowstolinux-managed-file-binding-v1".getBytes(StandardCharsets.UTF_8));
        update(digest, componentId.getBytes(StandardCharsets.UTF_8));
        update(digest, path.path().getBytes(StandardCharsets.UTF_8));
        return "file-" + HexFormat.of().formatHex(digest.digest(), 0, 12);
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static String managedIdentifier(String value) {
        value = Objects.requireNonNull(value, "componentId").trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        return value;
    }
}
