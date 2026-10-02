package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

import static gold.debug.windowstolinux.app.file.FileEngine.*;

/** FileCopyRun owns the documented exact file responsibility. / FileCopyRun负责已描述的精确文件职责。
 */
final class FileCopyRun {

    /** Original source host engine / 原来源主机引擎.
     */
    private final FileEngine origin, destination;

    /** exact preapproved operation plan / 精确预批准操作计划.
     */
    private final FileCopyPlan plan;

    /** whether source removal is requested / 是否请求移除来源.
     */
    private final boolean move;

    /** durable operation and link registry / 持久操作及链接登记.
     */
    private final FileJournal journal;

    /** original cooperative cancellation / 原协作取消.
     */
    private final CancelToken cancel;

    /** actual byte progress callback / 实际字节进度回调.
     */
    private final Progress progress;

    /** original resumable cross-host transport / 原可恢复跨主机传输.
     */
    private final ContentTransfer transfer;

    /** Per-object verified or uncertain outcomes / 逐对象已验证或不确定结果.
     */
    private final List<FileReport.Item> results = new ArrayList<>();

    /** Sources with verified published counterparts / 已验证发布对应副本的来源.
     */
    private final Set<FilePath> succeeded = new HashSet<>(), failed = new HashSet<>();

    /** Identities of directories actually published by this operation before processing their children. / 处理子项前由此操作实际发布的目录身份。 */
    private final Map<FilePath, FileInfo> directories = new HashMap<>();

    /** Actual copied regular content bytes / 实际复制普通内容字节数.
     */
    private long completed;

    /** Original durable operation identity / 原持久操作身份.
     */
    private UUID operation;

    /** Binds one original copy plan to its host, journal and actual transport. / 将单个原复制计划绑定至宿主、记录及实际传输。
     * @param origin original source host engine / 原来源主机引擎
     * @param destination observed destination host engine / 已观测目标主机引擎
     * @param plan exact preapproved operation plan / 精确预批准操作计划
     * @param move whether source removal is requested / 是否请求移除来源
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @param progress actual byte progress callback / 实际字节进度回调
     * @param transfer original resumable cross-host transport / 原可恢复跨主机传输
     */
    FileCopyRun(FileEngine origin, FileEngine destination, FileCopyPlan plan, boolean move, FileJournal journal, CancelToken cancel, Progress progress, ContentTransfer transfer) {
        this.origin = origin;
        this.destination = destination;
        this.plan = plan;
        this.move = move;
        this.journal = journal;
        this.cancel = cancel;
        this.progress = progress;
        this.transfer = transfer;
    }

    /** Approves and durably executes one exact original copy plan. / 批准并持久执行单个精确原复制计划。
     * @param from original source position / 原来源位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param approval exact effect approval callback / 精确副作用批准回调
     * @return durable original per-object operation report / 持久原逐对象操作报告
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FileReport execute(FilePath from, boolean link, Approval approval) throws IOException, ApiException {
        String serialized = plan.describe();
        approval.approve(plan.source, "file-" + (move ? "move" : "copy") + (link ? "-link" : "-file"), serialized);
        same(plan.source, origin.inspect(from, link, cancel));
        operation = journal.begin((move ? "MOVE" : "COPY") + (link ? "_LINK" : "_FILE"), from, Optional.of(plan.target), serialized);
        for (var maintenance : plan.managed)
            journal.record(operation, "PLANNED_LINK", maintenance.link().actual(), Optional.of(maintenance.newTarget()), new FileLinkMaintenance(maintenance.link().actual(), maintenance.target(), maintenance.newTarget(), maintenance.link().identity(), maintenance.targetIdentity(), maintenance.link().target().orElseThrow()).encode());
        for (var step : plan.steps) if (!step(step)) break;
        if (move && results.stream().noneMatch(r -> r.outcome() == Outcome.UNKNOWN || r.outcome() == Outcome.CANCELLED)) {
            removeDirectories();
            maintainLinks();
        }
        Outcome outcome = aggregate(results);
        journal.record(operation, outcome == Outcome.UNKNOWN ? "UNKNOWN" : "COMPLETE", from, Optional.of(plan.target), outcome.name());
        return new FileReport(operation, outcome, results);
    }

    /** Executes one independently reportable planned object and preserves unknown effects. / 执行单个可独立报告的预定对象，并保留未知副作用。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @return whether independent processing may continue / 是否可继续独立处理
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private boolean step(FileCopyPlan.Step step) throws IOException, ApiException {
        FilePath original = step.original;
        if (step.failed) {
            failed.add(original);
            results.add(item(original, step.target, Outcome.FAILED, step.detail));
            return true;
        }
        boolean dispatched = false;
        try {
            check(cancel);
            if (failed.stream().anyMatch(p -> isInside(original, p)))
                throw new FilePreconditionException("A required source directory failed");
            same(step.source, origin.inspect(step.source.requested(), step.source.kind() == FileInfo.Kind.LINK, cancel));
            if (step.parent == null) step.parent = directories.get(step.target.parent());
            destination.verifyTarget(step, cancel);
            journal.record(operation, "BEFORE_WRITE", original, Optional.of(step.target), step.source.identity());
            dispatched = true;
            if (!publish(step))
                return results.getLast().outcome() != Outcome.UNKNOWN && results.getLast().outcome() != Outcome.CANCELLED;
            FileInfo written = destination.inspect(step.target, step.source.kind() == FileInfo.Kind.LINK, cancel);
            journal.record(operation, "WRITTEN", original, Optional.of(step.target), written.identity());
            if (written.kind() == FileInfo.Kind.DIRECTORY) directories.put(step.target, written);
            succeeded.add(original);
            finishStep(step);
            results.add(item(original, step.target, Outcome.SUCCEEDED, move ? "Moved and verified" : "Copied and verified"));
            return true;
        } catch (FilePreconditionException error) {
            failed.add(original);
            results.add(item(original, step.target, Outcome.FAILED, error.getMessage()));
            journal.record(operation, "FAILED", original, Optional.of(step.target), error.getMessage());
            return true;
        } catch (java.util.concurrent.CancellationException cancelled) {
            Outcome state = dispatched ? Outcome.UNKNOWN : Outcome.CANCELLED;
            results.add(item(original, step.target, state, "Cancelled; dispatched effects require reconciliation"));
            journal.record(operation, state.name(), original, Optional.of(step.target), "cancelled");
            return false;
        } catch (IOException error) {
            failed.add(original);
            Outcome state = dispatched ? Outcome.UNKNOWN : Outcome.FAILED;
            results.add(item(original, step.target, state, dispatched ? "Effect requires reconciliation" : "Object, permission or identity check failed"));
            journal.record(operation, state.name(), original, Optional.of(step.target), error.getClass().getSimpleName());
            return state != Outcome.UNKNOWN;
        }
    }

    /** Publishes one verified content, directory or link object. / 发布单个已验证内容、目录或链接对象。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @return whether this object was verified and published / 此对象是否已验证发布
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private boolean publish(FileCopyPlan.Step step) throws IOException, ApiException {
        if (step.source.kind() == FileInfo.Kind.DIRECTORY) {
            if (step.existing == null) destination.backend().mkdir(step.target);
        } else if (step.source.kind() == FileInfo.Kind.LINK)
            FileContentPublication.link(step, linkTarget(step), destination, cancel, journal, operation);
        else {
            if (transfer == null)
                FileContentPublication.copy(step, origin, destination, cancel, progress, completed, plan.total, journal, operation);
            else {
                FileReport.Item result = transfer.transfer(step.source, step.target, Optional.ofNullable(step.existing));
                if (result.outcome() != Outcome.SUCCEEDED) {
                    results.add(result);
                    failed.add(step.original);
                    journal.record(operation, result.outcome().name(), step.original, Optional.of(step.target), result.detail());
                    return false;
                }
                if (!result.target().equals(Optional.of(step.target)))
                    throw new IOException("Transfer published to an unplanned destination");
            }
            completed += step.source.bytes();
        }
        return true;
    }

    /** Maps internal links to actual successful copies and retains external or broken targets. / 将内部链接映射至实际成功副本，保留外部或断链目标。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private FilePath linkTarget(FileCopyPlan.Step step) throws IOException {
        FilePath old = resolveTarget(step.original, step.source.target().orElseThrow()), mapped = plan.mapping.get(old);
        if (isInside(old, plan.source.actual()) && plan.source.kind() == FileInfo.Kind.DIRECTORY) {
            boolean absent = origin.backend().stat(old).isEmpty();
            if (!absent || mapped != null) {
                FilePath required = old;
                if (mapped == null || !succeeded.contains(old) || failed.stream().anyMatch(p -> p.equals(required) || isInside(p, required)))
                    throw new FilePreconditionException("Internal link target was skipped or failed");
                old = mapped;
            }
        }
        if (!old.server().equals(step.target.server()))
            throw new FilePreconditionException("A link target cannot be expressed across hosts");
        return old;
    }

    /** Registers verified targets and removes sources only after confirmed publication. / 登记已验证目标，仅在发布确认后移除来源。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private void finishStep(FileCopyPlan.Step step) throws IOException {
        if (step.source.kind() == FileInfo.Kind.LINK) {
            FileInfo observed = destination.inspect(step.target, true, cancel);
            journal.saveLink(observed, destination.optionalTarget(observed, cancel));
        } else if (step.existing != null) destination.refreshTargetLinks(step.target, journal, cancel);
        if (move && step.source.kind() != FileInfo.Kind.DIRECTORY) {
            same(step.source, origin.inspect(step.source.requested(), step.source.kind() == FileInfo.Kind.LINK, cancel));
            journal.record(operation, "BEFORE_SOURCE_REMOVE", step.original, Optional.of(step.target), step.source.identity());
            origin.backend().remove(step.original);
            journal.record(operation, "SOURCE_REMOVED", step.original, Optional.of(step.target), "confirmed");
            if (step.source.kind() == FileInfo.Kind.LINK) journal.removeLink(step.original);
        }
    }

    /** Removes only verified empty source directories after their children finish. / 子项结束后仅移除已验证为空的来源目录。
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private void removeDirectories() throws IOException {
        var directories = plan.steps.stream().filter(s -> s.source != null && s.source.kind() == FileInfo.Kind.DIRECTORY && !s.failed).sorted(Comparator.comparingInt((FileCopyPlan.Step s) -> s.source.actual().path().length()).reversed()).toList();
        for (var directory : directories) {
            try {
                check(cancel);
                if (!origin.backend().list(directory.source.actual(), MAX_ENTRIES).isEmpty()) continue;
                same(directory.source, origin.inspect(directory.source.requested(), false, cancel));
                journal.record(operation, "BEFORE_SOURCE_REMOVE", directory.source.actual(), Optional.of(directory.target), directory.source.identity());
                origin.backend().remove(directory.source.actual());
                journal.record(operation, "SOURCE_REMOVED", directory.source.actual(), Optional.of(directory.target), "confirmed");
            } catch (Exception failure) {
                results.add(item(directory.source.actual(), directory.target, Outcome.UNKNOWN, "Source directory removal requires reconciliation"));
                break;
            }
        }
    }

    /** Retargets original registered links only to verified moved objects. / 仅将原登记链接重新指向已验证移动对象。
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private void maintainLinks() throws IOException {
        boolean complete = results.stream().allMatch(r -> r.outcome() == Outcome.SUCCEEDED) && origin.backend().stat(plan.source.actual()).isEmpty();
        for (var maintenance : plan.managed) {
            if (maintenance.target().equals(plan.source.actual()) && plan.source.kind() == FileInfo.Kind.DIRECTORY && !complete)
                continue;
            if (origin.backend().stat(maintenance.target()).map(i -> i.kind() == FileInfo.Kind.DIRECTORY).orElse(false))
                continue;
            if (!succeeded.contains(maintenance.target()) || failed.contains(maintenance.target())) continue;
            try {
                same(maintenance.link(), origin.inspect(maintenance.link().actual(), true, cancel));
                journal.record(operation, "BEFORE_LINK_UPDATE", maintenance.link().actual(), Optional.of(maintenance.newTarget()), maintenance.link().identity());
                FileContentPublication.retarget(maintenance.link(), maintenance.newTarget(), origin, cancel, journal, operation);
                FileInfo updated = origin.inspect(maintenance.link().actual(), true, cancel);
                journal.saveLink(updated, origin.optionalTarget(updated, cancel));
                journal.record(operation, "LINK_UPDATED", updated.actual(), Optional.of(maintenance.newTarget()), updated.identity());
            } catch (IOException error) {
                results.add(item(maintenance.link().actual(), maintenance.newTarget(), Outcome.UNKNOWN, "File moved; registered link maintenance requires reconciliation"));
            }
        }
    }
}
