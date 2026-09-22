package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

/**
 * Converts reviewed managed content bindings to deterministic helper scalars. / 将经审阅受管内容绑定转换为确定性 helper 标量。
 */
public final class ManagedContentArguments {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedContentArguments() {
    }

    /**
     * Returns application, component and exact binding arguments in canonical order. / 以规范顺序返回应用、组件及精确绑定参数。
     *
     * @param publication publication / 发布
     * @return application, component and exact binding arguments in canonical order / 以规范顺序返回应用、组件及精确绑定参数
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static List<String> from(ManagedContentPublication publication) {
        publication = Objects.requireNonNull(publication, "publication");
        List<String> values = new ArrayList<>();
        values.add(publication.applicationId());
        values.add(publication.componentId());
        values.add(Integer.toString(publication.fileBindings().size()));
        publication.fileBindings().forEach(binding -> {
            values.add(binding.bindingId());
            values.add(binding.dataPath().path());
            values.add(binding.dataPath().access() == ComponentDataPath.AccessMode.READ_ONLY ? "ro" : "rw");
            values.add(binding.resourceType().name());
            values.add(binding.location().type().name());
            values.add(binding.location().path().isEmpty() ? "-" : binding.location().path());
            values.add(binding.databaseFileName().isEmpty() ? "-" : binding.databaseFileName());
            values.add(binding.seedFile().isEmpty() ? "-" : binding.seedFile());
            values.add(binding.initializationFiles().isEmpty() ? "-" : String.join(",", binding.initializationFiles()));
            values.add(binding.configurationSha256().isEmpty() ? "-" : binding.configurationSha256());
        });
        return List.copyOf(values);
    }
}
