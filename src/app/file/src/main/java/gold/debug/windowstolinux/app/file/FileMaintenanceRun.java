package gold.debug.windowstolinux.app.file;

import static gold.debug.windowstolinux.app.file.FileEngine.*;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.app.file.model.FileActionLog;
import gold.debug.windowstolinux.app.file.model.FileLinkMaintenance;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.action.FileReport;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Newly authorizes only unfinished original links whose moved targets remain proved. / 仅对移动目标仍可证明的未完成原链接重新授权。 */
final class FileMaintenanceRun {
    /** Prevents construction of the narrow continuation utility. / 禁止构造专用续作工具。 */
    private FileMaintenanceRun() {
    }

    /** Checks all original links before approval, then records each actual maintenance effect. / 审批前检查全部原链接，再记录每个实际维护副作用。
     * @param engine actual same-host file engine / 实际同主机文件引擎
     * @param plan original unfinished registered links / 原未完成登记链接
     * @param targets verified mapped target identities from durable publication evidence / 持久发布依据中的已核实映射目标身份
     * @param approval new exact authority for this continuation / 此续作的新精确授权
     * @param journal new continuation journal linked to the parent operation / 关联父操作的新续作记录
     * @param cancel current ordinary task cancellation / 当前普通任务取消
     * @return actual link-only continuation results / 真实仅链接续作结果
     * @throws IOException if original links, moved targets or durable evidence cannot be verified / 原链接、移动目标或持久依据无法核实时
     * @throws ApiException if fresh approval is rejected / 新审批拒绝时 */
    static FileReport execute(FileEngine engine, List<FileLinkMaintenance> plan, Map<FilePath, String> targets,
                              Approval approval, FileActionLog journal, CancelToken cancel) throws IOException, ApiException {
        if (plan.isEmpty()) throw new IOException("There are no proved unfinished links to continue");
        var observed = new LinkedHashMap<FileLinkMaintenance, FileInfo>();
        var text = new StringBuilder();
        for (var item : plan) {
            FileInfo link = verify(engine, item, targets, cancel);
            observed.put(item, link);
            text.append(item.link().key()).append('\n').append(item.target().key()).append('\n').append(FileActions.stamp(link)).append('\n').append(targets.get(item.target())).append('\n');
        }
        approval.approve(observed.values().iterator().next(), "file-maintain-links", text.toString());
        for (var item : plan) same(observed.get(item), verify(engine, item, targets, cancel));
        var first = plan.getFirst();
        UUID operation = journal.begin("MAINTAIN_LINKS", first.link(), Optional.of(first.target()), text.toString());
        var results = new ArrayList<FileReport.Item>();
        for (var item : plan) {
            boolean dispatched = false;
            try {
                FileInfo before = verify(engine, item, targets, cancel);
                journal.record(operation, "PLANNED_LINK", item.link(), Optional.of(item.target()), item.encode());
                journal.record(operation, "TARGET_CONFIRMED", item.source(), Optional.of(item.target()), targets.get(item.target()));
                journal.record(operation, "BEFORE_LINK_UPDATE", item.link(), Optional.of(item.target()), before.identity());
                dispatched = true;
                FileContentPublish.retarget(before, item.target(), engine, cancel, journal, operation);
                FileInfo after = engine.inspect(item.link(), true, cancel);
                journal.saveLink(after, engine.optionalTarget(after, cancel));
                journal.record(operation, "LINK_UPDATED", item.link(), Optional.of(item.target()), after.identity());
                results.add(item(item.link(), item.target(), TaskResult.SUCCEEDED, "Original unfinished link maintained after new approval"));
            } catch (IOException | java.util.concurrent.CancellationException failure) {
                TaskResult state = dispatched ? TaskResult.UNKNOWN : failure instanceof java.util.concurrent.CancellationException ? TaskResult.CANCELLED : TaskResult.FAILED;
                results.add(item(item.link(), item.target(), state, dispatched ? "Link effect requires reconciliation" : "Original link or moved target changed"));
                journal.record(operation, state.name(), item.link(), Optional.of(item.target()), failure.getClass().getSimpleName());
                break;
            }
        }
        TaskResult outcome = aggregate(results);
        journal.record(operation, outcome == TaskResult.UNKNOWN ? "UNKNOWN" : "COMPLETE", first.link(), Optional.of(first.target()), outcome.name());
        return new FileReport(operation, outcome, results);
    }

    /** Refuses replay whenever the source returned, the old link changed or the destination lost its identity. / 原来源返回、旧链接改变或目标失去身份时拒绝续作。
     * @param engine actual same-host engine / 实际同主机引擎
     * @param item original unfinished maintenance item / 原未完成维护项
     * @param targets durable mapped destination identities / 持久映射目标身份
     * @param cancel current ordinary cancellation / 当前普通取消
     * @return unchanged original link observation / 未改变的原链接观测
     * @throws IOException if any exact prerequisite is unproved / 任一精确前置条件未证实时 */
    private static FileInfo verify(FileEngine engine, FileLinkMaintenance item, Map<FilePath, String> targets, CancelToken cancel) throws IOException {
        check(cancel);
        FileInfo link = engine.inspect(item.link(), true, cancel);
        FileInfo target = engine.inspect(item.target(), false, cancel);
        if (engine.backend().stat(item.source()).isPresent() || !link.identity().equals(item.linkIdentity()) || !link.target().orElseThrow().equals(item.targetText())
            || !target.actual().equals(item.target()) || !target.identity().equals(targets.get(item.target())))
            throw new IOException("Original maintenance prerequisite changed");
        return link;
    }
}
