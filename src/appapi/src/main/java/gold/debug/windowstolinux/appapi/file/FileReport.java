package gold.debug.windowstolinux.appapi.file;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.Outcome;

/** Actual per-item results including partial progress and uncertainty. / 包含部分进度和不确定性的逐项真实结果。 */
public record FileReport(UUID operationId, Outcome outcome, List<Item> items) {
    /** Copies bounded results. / 复制有界结果。 */
    public FileReport { java.util.Objects.requireNonNull(operationId); java.util.Objects.requireNonNull(outcome); items = List.copyOf(items); if (items.size() > 100000) throw new IllegalArgumentException("Too many results"); }
    /** One actual operation result. / 单个实际操作结果。 */
    public record Item(FilePath source, Optional<FilePath> target, Outcome outcome, String detail) {
        /** Validates the immutable result. / 校验不可变结果。 */
        public Item { java.util.Objects.requireNonNull(source); java.util.Objects.requireNonNull(target); java.util.Objects.requireNonNull(outcome); java.util.Objects.requireNonNull(detail); }
    }
}
