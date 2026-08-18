package gold.debug.windowstolinux.shared.linux.sshd.protocol.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts the constrained container runtime model to deterministic helper arguments.
 *
 * <p>将受约束的容器运行模型转换为确定性的辅助程序参数。
 */
public final class ContainerRuntimeArguments {
    private ContainerRuntimeArguments() {
    }

    /** Performs the {@code from} operation. / 执行 {@code from} 操作。 */
    public static List<String> from(DeploymentRuntimeSpecification.Container runtime) {
        runtime = Objects.requireNonNull(runtime, "runtime");
        List<String> values = new ArrayList<>();
        values.add(runtime.engine().name().toLowerCase(java.util.Locale.ROOT));
        List<Map.Entry<Integer, Integer>> ports = runtime.publishedPorts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        values.add(Integer.toString(ports.size()));
        ports.forEach(port -> {
            values.add(Integer.toString(port.getKey()));
            values.add(Integer.toString(port.getValue()));
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
