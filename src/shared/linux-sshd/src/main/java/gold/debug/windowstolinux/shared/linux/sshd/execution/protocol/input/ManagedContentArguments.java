package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts reviewed managed content bindings to deterministic helper scalars. / 将经审阅受管内容绑定转换为确定性 helper 标量。 */
public final class ManagedContentArguments {
    private ManagedContentArguments() {
    }

    /** Returns application, component and exact binding arguments in canonical order. / 以规范顺序返回应用、组件及精确绑定参数。 */
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
        });
        return List.copyOf(values);
    }
}
