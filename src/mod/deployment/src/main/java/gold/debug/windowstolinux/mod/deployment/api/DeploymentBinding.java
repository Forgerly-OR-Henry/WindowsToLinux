package gold.debug.windowstolinux.mod.deployment.api;

import java.util.Optional;

/** Reviewed data binding without private deployment model types. / 不含部署私有模型类型的已审阅数据绑定。
 * @param id stable binding identity / 稳定绑定身份
 * @param componentId owning component / 所属组件
 * @param kind CONFIG, FILE or DATABASE / 配置、文件或数据库
 * @param path reviewed remote path / 已审阅远端路径
 * @param engine database engine or empty text / 数据库引擎或空文本
 * @param needsQuiescence whether collection must stop writes / 采集是否须停写
 * @param secretReferenceId opaque original secret reference / 不透明原秘密引用 */
public record DeploymentBinding(String id, String componentId, String kind, String path, String engine,
        boolean needsQuiescence, Optional<String> secretReferenceId) {
    /** Validates the shared binding shape. / 校验共享绑定形状。
     * @param id stable binding identity / 稳定绑定身份
     * @param componentId owning component / 所属组件
     * @param kind supported binding kind / 受支持绑定类别
     * @param path reviewed path or database endpoint / 已审阅路径或数据库端点
     * @param engine database engine or empty text / 数据库引擎或空文本
     * @param needsQuiescence whether collection must stop writes / 采集是否须停写
     * @param secretReferenceId opaque original secret reference / 不透明原秘密引用 */
    public DeploymentBinding {
        if (id == null || id.isBlank() || componentId == null || componentId.isBlank()
                || !java.util.Set.of("CONFIG", "FILE", "DATABASE").contains(kind)
                || path == null || !(path.startsWith("/") || "DATABASE".equals(kind)
                        && path.matches("[a-z]+://[^/]+/.+")) || engine == null)
            throw new IllegalArgumentException("Invalid deployment binding");
        java.util.Objects.requireNonNull(secretReferenceId);
    }
}
