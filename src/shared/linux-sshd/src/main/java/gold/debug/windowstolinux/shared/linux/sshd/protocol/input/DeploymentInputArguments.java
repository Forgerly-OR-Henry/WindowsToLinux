package gold.debug.windowstolinux.shared.linux.sshd.protocol.input;

import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts a non-secret input manifest to deterministic helper arguments. / 将非秘密输入清单转换为确定性辅助程序参数。 */
public final class DeploymentInputArguments {
    private DeploymentInputArguments() { }

    /** Performs the {@code from} operation. / 执行 {@code from} 操作。 */
    public static List<String> from(DeploymentInputManifest inputs) {
        Objects.requireNonNull(inputs, "inputs");
        List<String> values = new ArrayList<>(List.of(inputs.configurationSha256(),
                Integer.toString(inputs.secrets().size())));
        inputs.secrets().forEach(secret -> {
            values.add(secret.reference().identifier());
            values.add(Long.toString(secret.reference().revision()));
            values.add(secret.sha256());
        });
        return List.copyOf(values);
    }
}
