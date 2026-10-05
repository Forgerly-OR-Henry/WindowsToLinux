package gold.debug.windowstolinux.mod.files.service;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;

/** Bounded activation-owned facts; unfinished tasks are never evicted to imply success. / 有界启用所属事实；不淘汰未结束任务来暗示成功。 */
final class FilesObservations {
    /** Maximum retained original task observations. / 保留原任务观测的上限。 */
    private static final int LIMIT = 100;

    /** Admission-order observations and original handles. / 准入顺序观测及原句柄。 */
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();

    /** Refuses more work when all retained slots still need attention. / 所有保留槽仍待处理时拒绝新工作。
     * @throws ApiException if no ended slot can be released / 无已结束槽可释放时 */
    synchronized void requireRoom() throws ApiException {
        if (entries.size() < LIMIT) return;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next().getValue();
            if (entry.handle != null && entry.handle.snapshot().state() == TaskState.ENDED) {
                iterator.remove();
                return;
            }
        }
        throw FilesProblems.error(ProblemCode.BUSY, "FILES_RETAINED_TASK_LIMIT",
            "Retained file tasks must be reviewed in platform task management before more operations are admitted");
    }

    /** Associates the genuine admitted handle before callbacks publish. / 回调发布前关联真实已准入句柄。
     * @param handle original handle / 原句柄 */
    synchronized void track(TaskHandle<?> handle) {
        entries.computeIfAbsent(handle.id(), key -> new Entry()).handle = handle;
    }

    /** Retains actual callback facts for an already admitted task. / 保留已准入任务的实际回调事实。
     * @param task original identity / 原身份
     * @param observation actual immutable facts / 实际不可变事实 */
    synchronized void publish(UUID task, FilesObservation observation) {
        var entry = entries.get(task);
        if (entry == null && entries.size() < LIMIT) {
            entry = new Entry();
            entries.put(task, entry);
        }
        if (entry != null) entry.observation = observation;
    }

    /** Reads immutable facts without I/O or extra task authority. / 不执行I/O或授予额外任务权地读取不可变事实。
     * @param task exact original identity / 精确原身份
     * @return actual facts if retained / 保留时的实际事实 */
    synchronized Optional<FilesObservation> read(UUID task) {
        var entry = entries.get(task);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.observation);
    }

    /** One authentic handle and its latest actual callback observation. / 一个真实句柄及最近实际回调观测。 */
    private static final class Entry {
        /** Original task handle, absent for previous-boot recovery. / 原任务句柄，前次启动恢复时不存在。 */
        private TaskHandle<?> handle;

        /** Actual immutable callback facts. / 实际不可变回调事实。 */
        private FilesObservation observation;
    }
}
