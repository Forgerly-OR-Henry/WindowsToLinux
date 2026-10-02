package gold.debug.windowstolinux.appapi.file;

import java.util.*;

/** Original durable operation evidence; it never restores an old authorization. / 原持久操作依据，不恢复旧授权。 */
public record FileOperation(UUID id, UUID taskId, String type, String phase, FilePath source,
                            Optional<FilePath> target, List<Step> steps) {
    /** Copies bounded original evidence. / 复制有界原依据。 */
    public FileOperation { java.util.Objects.requireNonNull(id); java.util.Objects.requireNonNull(taskId); java.util.Objects.requireNonNull(type); java.util.Objects.requireNonNull(phase); java.util.Objects.requireNonNull(source); java.util.Objects.requireNonNull(target); steps = List.copyOf(steps); }
    /** An original observed execution phase. / 原观测执行阶段。 */
    public record Step(String phase, FilePath source, Optional<FilePath> target, String detail) {
        /** Validates phase evidence. / 校验阶段依据。 */
        public Step { java.util.Objects.requireNonNull(phase); java.util.Objects.requireNonNull(source); java.util.Objects.requireNonNull(target); java.util.Objects.requireNonNull(detail); }
    }
}
