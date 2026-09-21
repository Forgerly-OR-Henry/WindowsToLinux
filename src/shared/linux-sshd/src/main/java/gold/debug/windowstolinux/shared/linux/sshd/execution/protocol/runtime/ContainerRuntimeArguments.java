package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts the constrained container runtime model to deterministic helper arguments.
 *
 *  <p>将受约束的容器运行模型转换为确定性的辅助程序参数。
 */
public final class ContainerRuntimeArguments {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ContainerRuntimeArguments() {
    }

    /**
     * Maps the reviewed container runtime into its fixed engine, image, port and volume argument sequence.
     * <p>将已审阅容器运行规格映射为固定的引擎、镜像、端口及卷参数序列。
     *
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static List<String> from(DeploymentRuntimeSpecification.Container runtime) {
        runtime = Objects.requireNonNull(runtime, "runtime");
        List<String> values = new ArrayList<>();
        values.add(runtime.engine().name().toLowerCase(java.util.Locale.ROOT));
        var ports = runtime.workload().endpoints();
        values.add(Integer.toString(ports.size()));
        ports.forEach(port -> {
            values.add(port.protocol().transport()); values.add(port.bindAddress());
            values.add(Integer.toString(port.hostPort())); values.add(Integer.toString(port.targetPort()));
        });
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = runtime.volumes().stream()
                .sorted(Comparator.comparing(DeploymentRuntimeSpecification.ManagedVolume::name)).toList();
        values.add(Integer.toString(volumes.size()));
        volumes.forEach(volume -> {
            values.add(volume.name());
            values.add(volume.containerPath());
            values.add(volume.readOnly() ? "1" : "0");
        });
        return List.copyOf(values);
    }
}
