package gold.debug.windowstolinux.shared.deploy.contract.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical application-wide candidate port decision in dependency order. / 按依赖顺序排列的规范整应用候选端口决定。
 *
 * @param mode selected operating or storage mode / 所选运行或存储模式
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 */
public record CandidatePortPlan(CandidatePortMode mode, Map<String, List<CandidatePortBinding>> components) {
    /**
     * Validates the closed mode-to-binding invariant. / 校验封闭的模式与端口绑定不变量。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public CandidatePortPlan {
        mode = Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(components, "components");
        LinkedHashMap<String, List<CandidatePortBinding>> copied = new LinkedHashMap<>();
        components.forEach((component, bindings) -> {
            if (component == null || !component.matches("[a-z0-9][a-z0-9-]{0,62}")
                    || copied.put(component, List.copyOf(Objects.requireNonNull(bindings, "bindings"))) != null) {
                throw new IllegalArgumentException("candidate port component is invalid or duplicated");
            }
        });
        if (copied.isEmpty()  || mode != CandidatePortMode.PARALLEL_LOOPBACK
                && copied.values().stream().anyMatch(values -> !values.isEmpty())) {
            throw new IllegalArgumentException("candidate port bindings differ from the application mode");
        }
        long distinctCandidates = copied.values().stream().flatMap(List::stream)
                .map(binding -> binding.protocol() + ":" + binding.candidatePort()).distinct().count();
        long candidateCount = copied.values().stream().mapToLong(List::size).sum();
        if (distinctCandidates != candidateCount) {
            throw new IllegalArgumentException("candidate ports must be unique across the application");
        }
        components = java.util.Collections.unmodifiableMap(copied);
    }
}
