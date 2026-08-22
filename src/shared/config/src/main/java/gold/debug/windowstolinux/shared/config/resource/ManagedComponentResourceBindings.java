package gold.debug.windowstolinux.shared.config.resource;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact reviewed file bindings plus optional reviewed database binding state. / 精确审阅的文件绑定及可选数据库绑定审阅状态。 */
public record ManagedComponentResourceBindings(
        List<ManagedFileBinding> fileBindings,
        Optional<List<ManagedDatabaseBinding>> databaseBindings
) {
    /** Canonicalizes bindings while preserving unknown versus explicitly empty databases. / 规范化绑定并保留数据库未知与显式为空的区别。 */
    public ManagedComponentResourceBindings {
        fileBindings = List.copyOf(Objects.requireNonNull(fileBindings, "fileBindings").stream()
                .map(value -> Objects.requireNonNull(value, "file binding"))
                .sorted(Comparator.comparing(ManagedFileBinding::bindingId)).toList());
        databaseBindings = Objects.requireNonNull(databaseBindings, "databaseBindings")
                .map(values -> List.copyOf(values.stream()
                        .map(value -> Objects.requireNonNull(value, "database binding"))
                        .sorted(Comparator.comparing(ManagedDatabaseBinding::databaseId)).toList()));
        if (fileBindings.stream().map(ManagedFileBinding::bindingId).distinct().count() != fileBindings.size()
                || fileBindings.stream().map(value -> value.dataPath().path()).distinct().count() != fileBindings.size()) {
            throw new IllegalArgumentException("managed file binding identities and logical paths must be unique");
        }
        if (databaseBindings.isPresent() && databaseBindings.orElseThrow().stream()
                .map(ManagedDatabaseBinding::databaseId).distinct().count() != databaseBindings.orElseThrow().size()) {
            throw new IllegalArgumentException("managed database binding identities must be unique");
        }
    }
}
