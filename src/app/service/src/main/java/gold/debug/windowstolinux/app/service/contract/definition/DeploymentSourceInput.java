package gold.debug.windowstolinux.app.service.contract.definition;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Local source selection or explicit Git form controls. / 本地源码选择或显式 Git 表单控件。
 *
 * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
 * @param gitAddress git address / Git地址
 * @param referenceKind reference kind / 引用种类
 * @param reference immutable public secret identity / 不可变公开秘密身份
 */
public record DeploymentSourceInput(Optional<Path> directory, String gitAddress, int referenceKind, String reference) {
    /**
     * Preserves bounded raw reference notation for service parsing. / 保留有界原始引用记法供服务解析。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param gitAddress git address / Git地址
     * @param referenceKind reference kind / 引用种类
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentSourceInput {
        Objects.requireNonNull(directory, "directory");
        if (Objects.requireNonNull(gitAddress, "gitAddress").length() > 4096
                || Objects.requireNonNull(reference, "reference").length() > 4096)
            throw new IllegalArgumentException("source form value exceeds bound");
    }
}
