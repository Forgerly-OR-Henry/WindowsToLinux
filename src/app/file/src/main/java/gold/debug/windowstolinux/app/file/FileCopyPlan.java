package gold.debug.windowstolinux.app.file;

import static gold.debug.windowstolinux.app.file.FileEngine.*;

import java.io.*;
import java.util.*;

import gold.debug.windowstolinux.app.file.model.FileActionLog;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Captures exact copy destinations, collision choices and registered-link dependencies before effects. / 副作用前捕获精确复制位置、冲突选择及登记链接依赖。 */
final class FileCopyPlan {
    /** Exact original observed source / 精确原观测来源.
     */
    final FileInfo source;

    /** Exact planned target position / 精确预定目标位置.
     */
    final FilePath target;

    /** Exact immutable collision observations collected before effects / 副作用前收集的精确冲突观测.
     */
    final List<Step> steps = new ArrayList<>();

    /** Actual source-to-destination copy positions / 实际来源至复制目标位置映射.
     */
    final Map<FilePath, FilePath> mapping = new LinkedHashMap<>();

    /** Verified external registered links affected by moves / 已验证受移动影响的外部登记链接.
     */
    final List<LinkMove> managed;

    /** Planned total regular content bytes / 预定普通内容总字节数.
     */
    final long total;

    /** Original source host engine / 原来源主机引擎.
     */
    private final FileEngine origin, destination;

    /** Captures all exact collision and registered-link choices before dispatch. / 发出前捕获全部精确冲突及登记链接选择。
     * @param origin original source host engine / 原来源主机引擎
     * @param destination observed destination host engine / 已观测目标主机引擎
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param move whether source removal is requested / 是否请求移除来源
     * @param choices explicit directory and file choices / 明确目录及文件选择
     * @param decisions host conflict presenter / 宿主冲突呈现器
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FileCopyPlan(FileEngine origin, FileEngine destination, FilePath from, FilePath to, boolean link, boolean move,
                 FileChoice choices, Decisions decisions, FileActionLog journal, CancelToken cancel) throws IOException, ApiException {
        this.origin = origin;
        this.destination = destination;
        source = origin.inspect(from, link, cancel);
        target = destination.destination(to, link, cancel);
        if (source.actual().server().equals(target.server()) && (source.actual().equals(target) || isInside(target, source.actual())))
            throw new IOException("A source cannot be copied or moved into itself");
        plan(source, target, choices, decisions, cancel);
        long bytes = 0;
        for (Step step : steps)
            if (step.source != null && !step.failed && step.source.kind() == FileInfo.Kind.FILE) {
                bytes = Math.addExact(bytes, step.source.bytes());
                if (bytes > MAX_BYTES) throw new IOException("Operation byte capacity exceeded");
            }
        total = bytes;
        managed = move ? managedLinks(journal, cancel) : List.of();
        orderLinks();
    }

    /** Collects collision choices and actual destination mappings without writing files. / 收集冲突选择及实际目标映射，不写文件。
     * @param current current observed child object / 当前观测子项对象
     * @param selected selected collision destination / 选定冲突目标
     * @param choices explicit directory and file choices / 明确目录及文件选择
     * @param decisions host conflict presenter / 宿主冲突呈现器
     * @param cancel original cooperative cancellation / 原协作取消
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private void plan(FileInfo current, FilePath selected, FileChoice choices, Decisions decisions, CancelToken cancel) throws IOException, ApiException {
        check(cancel);
        if (steps.size() >= MAX_ENTRIES) throw new IOException("Operation object capacity exceeded");
        FileInfo existing = destination.backend().stat(selected).orElse(null);
        if (existing != null) {
            if (current.kind() != existing.kind()) {
                steps.add(new Step(current, selected, existing, true, "Object type conflict"));
                return;
            }
            if (current.kind() == FileInfo.Kind.DIRECTORY) {
                FileChoice.Directory choice = choices.directory().isPresent() ? choices.directory().orElseThrow() : decisions.directory(current, existing);
                if (choice == FileChoice.Directory.SKIP) {
                    steps.add(new Step(current, selected, existing, true, "User chose not to process this directory"));
                    return;
                }
                if (choice == FileChoice.Directory.COPY) {
                    selected = destination.unused(selected, cancel);
                    existing = null;
                }
            } else {
                FileChoice.Conflict choice = choices.file().isPresent() ? choices.file().orElseThrow() : decisions.file(current, existing);
                if (choice == FileChoice.Conflict.SKIP) {
                    steps.add(new Step(current, selected, existing, true, "User chose not to process this item"));
                    return;
                }
                if (choice == FileChoice.Conflict.COPY) {
                    selected = destination.unused(selected, cancel);
                    existing = null;
                }
            }
        }
        var step = new Step(current, selected, existing, false, "");
        if (destination.backend().stat(selected.parent()).isPresent())
            step.parent = destination.inspect(selected.parent(), false, cancel);
        steps.add(step);
        mapping.put(current.actual(), selected);
        if (current.kind() == FileInfo.Kind.DIRECTORY)
            for (String name : origin.backend().list(current.actual(), MAX_ENTRIES)) {
                try {
                    FileInfo child = origin.backend().stat(current.actual().child(name)).orElseThrow(() -> new IOException("Source directory changed"));
                    plan(child, selected.child(name), choices, decisions, cancel);
                } catch (IOException failure) {
                    steps.add(new Step(current.actual().child(name), selected.child(name), "Source observation failed"));
                }
            }
    }

    /** Validates original registered links and target identities before moves. / 移动前校验原登记链接及目标身份。
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @return verified original registered-link dependencies / 已验证原登记链接依赖
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private List<LinkMove> managedLinks(FileActionLog journal, CancelToken cancel) throws IOException {
        var result = new ArrayList<LinkMove>();
        for (FileInfo link : journal.links(source.actual())) {
            if (isInside(link.actual(), source.actual()) || link.actual().equals(source.actual())) continue;
            FilePath old = resolveTarget(link.actual(), link.target().orElseThrow()), moved = mapping.get(old);
            if (moved == null) continue;
            same(link, origin.inspect(link.actual(), true, cancel));
            FileInfo original = origin.inspect(old, false, cancel);
            if (!journal.targetMatches(link, original))
                throw new IOException("Registered target identity changed outside the APP");
            if (!moved.server().equals(link.actual().server()))
                throw new IOException("Registered link maintenance cannot cross hosts");
            result.add(new LinkMove(link, old, moved, original.identity()));
        }
        return List.copyOf(result);
    }

    /** Serializes the exact collision and link plan covered by approval. / 序列化批准覆盖的精确冲突及链接计划。
     * @return complete exact immutable effect plan / 完整精确不可变副作用计划
     */
    String describe() {
        var text = new StringBuilder();
        for (Step s : steps)
            text.append(s.original.key()).append(" -> ").append(s.target.key()).append(' ').append(s.failed ? "SKIP" : s.source.identity()).append(' ').append(s.existing == null ? "ABSENT" : s.existing.identity()).append(" PARENT ").append(s.parent == null ? "CREATED_WITHIN_PLAN" : FileActions.stamp(s.parent)).append('\n');
        for (LinkMove l : managed)
            text.append("LINK ").append(l.link.actual().key()).append(' ').append(l.link.identity()).append(" -> ").append(l.newTarget.key()).append('\n');
        if (text.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Plan capacity exceeded");
        return text.toString();
    }

    /** Orders internal link dependencies after successfully copied targets. / 将内部链接依赖排在成功复制目标之后。
     */
    private void orderLinks() {
        var links = new ArrayList<Step>();
        steps.removeIf(s -> {
            if (s.source != null && s.source.kind() == FileInfo.Kind.LINK && !s.failed) {
                links.add(s);
                return true;
            }
            return false;
        });
        var pending = new HashSet<FilePath>();
        for (Step s : links) pending.add(s.source.actual());
        while (!links.isEmpty()) {
            boolean advanced = false;
            for (var it = links.iterator(); it.hasNext(); ) {
                Step s = it.next();
                FilePath target = resolveTarget(s.source.actual(), s.source.target().orElseThrow());
                if (!isInside(target, source.actual()) || !pending.contains(target)) {
                    steps.add(s);
                    pending.remove(s.source.actual());
                    it.remove();
                    advanced = true;
                }
            }
            if (!advanced) {
                steps.addAll(links);
                break;
            }
        }
    }

    /** One exact preplanned object and its collision observation. / 单个精确预定对象及其冲突观测。
     */
    static final class Step {
        /** Exact original observed source / 精确原观测来源.
         */
        final FileInfo source, existing;

        /** Parent captured before approval, or assigned from this operation's verified directory publication. / 审批前捕获的父目录，或此操作已核实目录发布后赋入的父目录。 */
        FileInfo parent;

        /** Original actual source position / 原实际来源位置.
         */
        final FilePath original, target;

        /** Sources skipped or failed during this exact operation / 此精确操作中跳过或失败的来源.
         */
        final boolean failed;

        /** Bounded per-object evidence detail / 有界逐对象依据详情.
         */
        final String detail;

        /** Retains one observed source, chosen target and proven failure state. / 保留单个观测来源、选定目标及已证明失败状态。
         * @param source original observed source object / 原观测来源对象
         * @param target exact planned destination or link target / 精确预定位置或链接目标
         * @param existing old observed collision object / 原观测冲突对象
         * @param failed whether the item was rejected before effects / 子项是否在副作用前被拒绝
         * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
         */
        Step(FileInfo source, FilePath target, FileInfo existing, boolean failed, String detail) {
            this.source = source;
            original = source.actual();
            this.target = target;
            this.existing = existing;
            this.failed = failed;
            this.detail = detail;
        }

        /** Retains one observed source, chosen target and proven failure state. / 保留单个观测来源、选定目标及已证明失败状态。
         * @param original original observed source position / 原观测来源位置
         * @param target exact planned destination or link target / 精确预定位置或链接目标
         * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
         */
        Step(FilePath original, FilePath target, String detail) {
            source = null;
            this.original = original;
            this.target = target;
            existing = null;
            failed = true;
            this.detail = detail;
        }
    }

    /** Original registered-link evidence and its actual mapped moved target. / 原登记链接依据及其实际映射移动目标。
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param newTarget actual mapped moved target / 实际映射移动目标
     * @param targetIdentity original verified target identity / 原已核实目标身份
     */
    record LinkMove(FileInfo link, FilePath target, FilePath newTarget, String targetIdentity) {
    }
}
