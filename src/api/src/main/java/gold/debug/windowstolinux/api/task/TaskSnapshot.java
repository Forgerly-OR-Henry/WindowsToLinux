package gold.debug.windowstolinux.api.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable task state with terminal result consistency. / 终态结果保持一致的不可变任务状态。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param ownerId stable Mod identity or reserved platform identity / 稳定插件身份或保留平台身份
 * @param ownerVersion actual owning Mod or application release / 所属插件或应用的实际版本
 * @param type owner-local task type / 所有者内部的任务类型
 * @param state observed lifecycle state / 已观察的生命周期状态
 * @param outcome observed business result independent of resource release / 独立于资源释放的已观察业务结果
 * @param progressText current human-readable task progress / 当前可读的任务进度
 * @param startedAt time the task was admitted / 任务被接纳的时间
 * @param endedAt terminal timestamp, absent until actually ended / 终态时间，实际结束前为空
 * @param blockers copied reasons preventing terminal completion / 阻止任务结束的原因列表副本
 */
public record TaskSnapshot(UUID id, String ownerId, String ownerVersion, String type, TaskState state,
        Optional<Outcome> outcome, String progressText, Instant startedAt, Optional<Instant> endedAt,
        List<String> blockers) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param ownerId stable Mod identity or reserved platform identity / 稳定插件身份或保留平台身份
     * @param ownerVersion actual owning Mod or application release / 所属插件或应用的实际版本
     * @param type owner-local task type / 所有者内部的任务类型
     * @param state observed lifecycle state / 已观察的生命周期状态
     * @param outcome observed business result independent of resource release / 独立于资源释放的已观察业务结果
     * @param progressText current human-readable task progress / 当前可读的任务进度
     * @param startedAt time the task was admitted / 任务被接纳的时间
     * @param endedAt terminal timestamp, absent until actually ended / 终态时间，实际结束前为空
     * @param blockers copied reasons preventing terminal completion / 阻止任务结束的原因列表副本
     */
    public TaskSnapshot {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(ownerId, "ownerId");
        java.util.Objects.requireNonNull(ownerVersion, "ownerVersion");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(progressText, "progressText");
        java.util.Objects.requireNonNull(startedAt, "startedAt");
        java.util.Objects.requireNonNull(endedAt, "endedAt");
        blockers = List.copyOf(blockers);
        if ((state == TaskState.ENDED) != outcome.isPresent() || (state == TaskState.ENDED) != endedAt.isPresent())
            throw new IllegalArgumentException("Only ended tasks have terminal outcomes and timestamps");
        if (outcome.orElse(null) == Outcome.UNKNOWN)
            throw new IllegalArgumentException("An uncertain task cannot have a terminal outcome");
        if (endedAt.isPresent() && endedAt.get().isBefore(startedAt))
            throw new IllegalArgumentException("End precedes start");
    }
}
