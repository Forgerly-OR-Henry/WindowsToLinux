package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;

/**
 * Converts a non-secret input manifest to deterministic helper arguments. / 将非秘密输入清单转换为确定性辅助程序参数。
 */
public final class DeploymentInputArguments {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentInputArguments() {
    }

    /**
     * Reconstructs this typed contract from the supplied source representation.
     * <p>根据所提供的源表示重建当前类型化契约。
     *
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static List<String> from(RemoteDeploymentInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        List<String> values = new ArrayList<>(
                List.of(inputs.configurationSha256(), Integer.toString(inputs.secrets().size())));
        inputs.secrets().forEach(secret -> {
            values.add(secret.identifier());
            values.add(Long.toString(secret.revision()));
            values.add(secret.sha256());
        });
        return List.copyOf(values);
    }
}
