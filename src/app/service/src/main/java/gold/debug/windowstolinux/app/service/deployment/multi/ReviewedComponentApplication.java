package gold.debug.windowstolinux.app.service.deployment.multi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;

/**
 * Secret-free reviewed deployment request and stable identity for one component. / 一个组件不含秘密的经审阅部署请求与稳定身份。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param resourceBindings resource bindings / 资源绑定集合
 */
public record ReviewedComponentApplication(String componentId, ReviewedDeploymentRequest request,
        ManagedApplication application, ManagedComponentResourceBindings resourceBindings) {
    /**
     * Validates the exact managed identity. / 验证精确受管身份。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param resourceBindings resource bindings / 资源绑定集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedComponentApplication {
        componentId = Objects.requireNonNull(componentId, "componentId");
        request = Objects.requireNonNull(request, "request");
        application = Objects.requireNonNull(application, "application");
        resourceBindings = Objects.requireNonNull(resourceBindings, "resourceBindings");
        if (!application.id().equals(request.facts().applicationId())
                || !application.server().equals(request.server())) {
            throw new IllegalArgumentException("reviewed component request must match its managed identity");
        }
    }

    /**
     * Creates stable opaque file bindings without interpreting logical paths as physical paths. / 创建稳定不透明文件绑定且不把逻辑路径解释为物理路径。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param dataPaths persistent-data contracts / 持久化数据契约
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @return stable opaque file bindings without interpreting logical paths as physical paths / 稳定不透明文件绑定且不把逻辑路径解释为物理路径
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static ManagedComponentResourceBindings resourceBindings(String componentId,
            List<ComponentDataPath> dataPaths, Optional<List<ManagedDatabaseBinding>> databaseBindings) {
        componentId = managedIdentifier(componentId);
        String stableComponentId = componentId;
        List<ManagedFileBinding> files = List.copyOf(Objects.requireNonNull(dataPaths, "dataPaths").stream()
                .map(path -> new ManagedFileBinding(fileBindingId(stableComponentId, path), path)).toList());
        return new ManagedComponentResourceBindings(files, databaseBindings);
    }

    /**
     * Derives a deterministic file-binding identifier from the component and reviewed data path.
     * <p>根据组件及已审阅数据路径派生确定的文件绑定标识。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return file binding id text / 文件绑定标识文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Updates reviewed component application.
     * <p>更新已审阅组件应用。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    /**
     * Validates a bounded identifier used for a managed resource.
     * <p>验证受管资源使用的有界标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return managed identifier text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedIdentifier(String value) {
        value = Objects.requireNonNull(value, "componentId").trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        return value;
    }
}
