package gold.debug.windowstolinux.app.service.contract.definition;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Local source selection or explicit Git form controls. / 本地源码选择或显式 Git 表单控件。 */
public record DeploymentSourceInput(Optional<Path> directory, String gitAddress, int referenceKind, String reference) {
    /** Preserves bounded raw reference notation for service parsing. / 保留有界原始引用记法供服务解析。 */
    public DeploymentSourceInput {
        Objects.requireNonNull(directory, "directory");
        if (Objects.requireNonNull(gitAddress, "gitAddress").length() > 4096
                || Objects.requireNonNull(reference, "reference").length() > 4096)
            throw new IllegalArgumentException("source form value exceeds bound");
    }
}
