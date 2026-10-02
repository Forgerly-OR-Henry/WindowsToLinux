package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.app.file.FileCopyPlan.Step;

/** Bounded file operations with no-follow traversal, exact planning and durable effects. / 有界文件操作、不跟随遍历、精确计划及持久副作用依据。 */
public final class FileEngine {
    /** Maximum objects in one operation. / 单次操作对象上限。 */
    public static final int MAX_ENTRIES = 100000;

    /** Maximum content bytes in one operation. / 单次操作内容字节上限。 */
    public static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

    /** Actual account-level filesystem primitives / 实际账户级文件系统原语.
     */
    private final FileBackend backend;

    /** Binds the account-level backend. / 绑定账户级后端。
     * @param backend account-level host primitives / 账户级主机原语
     */
    public FileEngine(FileBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /** Actual backend for coordinated cross-host operations. / 跨主机协调操作的实际后端。
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    public FileBackend backend() {
        return backend;
    }

    /** Resolves a File target or observes a Link without reading its target. / 解析 File 目标或观测 Link，不读取链接目标内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileInfo inspect(FilePath path, boolean link, CancelToken cancel) throws IOException {
        check(cancel);
        var chain = new ArrayList<FileInfo.Link>();
        var seen = new HashSet<String>();
        FilePath current = path;
        for (int depth = 0; depth <= 64; depth++) {
            if (!seen.add(current.key())) throw new IOException("Link resolution cycle");
            List<FilePath> parts = prefixes(current);
            boolean restarted = false;
            for (int i = 0; i < parts.size(); i++) {
                check(cancel);
                FilePath part = parts.get(i);
                boolean finalPart = i == parts.size() - 1;
                FileInfo value = (finalPart ? backend.stat(part) : backend.ancestor(part)).orElseThrow(() -> new FileNotFoundException("Object does not exist"));
                if (value.kind() == FileInfo.Kind.LINK && (!finalPart || !link)) {
                    String target = value.target().orElseThrow(() -> new IOException("Link target is unavailable"));
                    chain.add(new FileInfo.Link(part, value.identity(), target));
                    if (chain.size() > 64) throw new IOException("Link resolution exceeds the limit");
                    FilePath resolved = resolveTarget(part, target);
                    for (int rest = i + 1; rest < parts.size(); rest++)
                        resolved = resolved.child(parts.get(rest).name());
                    current = resolved;
                    restarted = true;
                    break;
                }
                if (!finalPart && value.kind() != FileInfo.Kind.DIRECTORY)
                    throw new IOException("An ancestor is not a directory");
                if (finalPart) {
                    if (link && value.kind() != FileInfo.Kind.LINK)
                        throw new IOException("The object is not a supported link");
                    if (value.kind() == FileInfo.Kind.OTHER) throw new IOException("Unsupported object type");
                    return new FileInfo(path, current, value.kind(), value.bytes(), value.modified(), value.identity(), value.target(), chain);
                }
            }
            if (!restarted) break;
        }
        throw new IOException("Link resolution exceeds the limit");
    }

    /** Lists immediate no-follow observations, sorted by name and optionally filtered. / 按名称排序并可筛选的直接不跟随观测。
     * @param path exact host-qualified position / 精确带主机位置
     * @param limit maximum accepted immediate entries or bytes / 允许的直接子项或字节上限
     * @param kind optional object type filter / 可选对象类型筛选
     * @param nameContains case-sensitive name filter / 区分大小写的名称筛选
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public List<FileInfo> list(FilePath path, int limit, Optional<FileInfo.Kind> kind, String nameContains, CancelToken cancel) throws IOException {
        FileInfo directory = inspect(path, false, cancel);
        if (directory.kind() != FileInfo.Kind.DIRECTORY) throw new IOException("Not a directory");
        var result = new ArrayList<FileInfo>();
        for (String name : backend.list(directory.actual(), limit)) {
            check(cancel);
            FileInfo child = backend.stat(directory.actual().child(name)).orElseThrow(() -> new IOException("Directory changed during listing"));
            if (kind.map(k -> k == child.kind()).orElse(true) && name.contains(nameContains)) result.add(child);
        }
        same(directory, inspect(path, false, cancel));
        return List.copyOf(result);
    }

    /** Reads copied content with a caller-provided hard limit. / 按调用方硬限制读取独立内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @param maxBytes maximum copied content bytes / 复制内容字节上限
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public byte[] read(FilePath path, int maxBytes, CancelToken cancel) throws IOException {
        if (maxBytes < 1 || maxBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Invalid read bound");
        FileInfo info = inspect(path, false, cancel);
        if (info.kind() != FileInfo.Kind.FILE || info.bytes() > maxBytes)
            throw new IOException("Content is not a bounded regular file");
        byte[] bytes;
        try (InputStream stream = backend.read(info.actual())) {
            var output = new ByteArrayOutputStream();
            var buffer = new byte[32768];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                check(cancel);
                if (output.size() + count > maxBytes) throw new IOException("Read capacity exceeded");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        same(info, inspect(path, false, cancel));
        if (bytes.length != info.bytes()) throw new IOException("Content changed during reading");
        return bytes;
    }

    /** Preplans all collision decisions, then performs actual per-item copy or move. / 先计划全部冲突决定，再逐项复制或移动。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param move whether source removal is requested / 是否请求移除来源
     * @param choices explicit directory and file choices / 明确目录及文件选择
     * @param destination observed destination host engine / 已观测目标主机引擎
     * @param decisions host conflict presenter / 宿主冲突呈现器
     * @param approval exact effect approval callback / 精确副作用批准回调
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @param progress actual byte progress callback / 实际字节进度回调
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport copy(FilePath from, FilePath to, boolean link, boolean move, FileChoice choices,
                           FileEngine destination, Decisions decisions, Approval approval, FileJournal journal,
                           CancelToken cancel, Progress progress) throws IOException, ApiException {
        return copy(from, to, link, move, choices, destination, decisions, approval, journal, cancel, progress, null);
    }

    /** Uses the original transfer state machine for preplanned cross-host regular files. / 对已计划跨主机普通文件使用原传输状态机。
     * @param from original source position / 原来源位置
     * @param to requested destination position / 请求目标位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param move whether source removal is requested / 是否请求移除来源
     * @param choices explicit directory and file choices / 明确目录及文件选择
     * @param destination observed destination host engine / 已观测目标主机引擎
     * @param decisions host conflict presenter / 宿主冲突呈现器
     * @param approval exact effect approval callback / 精确副作用批准回调
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @param progress actual byte progress callback / 实际字节进度回调
     * @param transfer original resumable cross-host transport / 原可恢复跨主机传输
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport copy(FilePath from, FilePath to, boolean link, boolean move, FileChoice choices,
                           FileEngine destination, Decisions decisions, Approval approval, FileJournal journal,
                           CancelToken cancel, Progress progress, ContentTransfer transfer) throws IOException, ApiException {
        Objects.requireNonNull(choices);
        Objects.requireNonNull(destination);
        if (from.server().isPresent() && to.server().isPresent() && !from.server().equals(to.server()))
            throw new IOException("Cross-server operations are outside this contract");
        var plan = new FileCopyPlan(this, destination, from, to, link, move, choices, decisions, journal, cancel);
        return new FileCopyRun(this, destination, plan, move, journal, cancel, progress, transfer).execute(from, link, approval);
    }

    /** Creates or replaces regular content only after exact approval and collision choice. / 精确批准及冲突选择后创建或替换普通内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @param content copied content to publish / 待发布独立内容
     * @param directory whether to create a directory / 是否创建目录
     * @param createOnly whether existing objects must be rejected / 是否须拒绝已有对象
     * @param decisions host conflict presenter / 宿主冲突呈现器
     * @param approval exact effect approval callback / 精确副作用批准回调
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport write(FilePath path, byte[] content, boolean directory, boolean createOnly, Decisions decisions,
                            Approval approval, FileJournal journal, CancelToken cancel) throws IOException, ApiException {
        if (content.length > 64 * 1024 * 1024) throw new IllegalArgumentException("Write capacity exceeded");
        FilePath target = destination(path, false, cancel);
        FileInfo existing = backend.stat(target).orElse(null);
        if (existing != null && (createOnly || directory || existing.kind() != FileInfo.Kind.FILE))
            throw new IOException("Target already exists or has a different type");
        FileInfo parent = inspect(target.parent(), false, cancel);
        if (parent.kind() != FileInfo.Kind.DIRECTORY) throw new IOException("Parent is not a directory");
        if (existing != null) {
            FileChoice.Conflict choice = decisions.file(existing, existing);
            if (choice == FileChoice.Conflict.SKIP)
                return failedWithoutEffect(path, "User chose not to process the target");
            if (choice == FileChoice.Conflict.COPY) {
                target = unused(target, cancel);
                existing = null;
            }
        }
        String digest = hash(content);
        approval.approve(existing == null ? parent : existing, directory ? "file-create-directory" : "file-write", target.key() + "\n" + digest + "\n" + content.length);
        UUID id = journal.begin(directory ? "CREATE_DIRECTORY" : "WRITE", path, Optional.of(target), digest);
        journal.record(id, "BEFORE_WRITE", path, Optional.of(target), existing == null ? "absent" : existing.identity());
        try {
            check(cancel);
            same(parent, inspect(target.parent(), false, cancel));
            if (existing != null) same(existing, inspect(target, false, cancel));
            else if (backend.stat(target).isPresent()) throw new FilePreconditionException("New target appeared");
            if (directory) backend.mkdir(target);
            else {
                FilePath temp = target.parent().child(".wtl-file-" + id + ".part");
                journal.record(id, "BEFORE_STAGING", path, Optional.of(temp), "exclusive regular file");
                try (OutputStream output = backend.create(temp)) {
                    output.write(content);
                }
                if (!digest.equals(digest(temp, content.length, cancel)))
                    throw new IOException("Staging verification failed");
                if (existing != null) same(existing, inspect(target, false, cancel));
                else if (backend.stat(target).isPresent()) throw new IOException("Target changed before publication");
                journal.record(id, "BEFORE_PUBLISH", path, Optional.of(target), temp.key());
                backend.publish(temp, target, existing != null);
            }
            refreshTargetLinks(target, journal, cancel);
            journal.record(id, "COMPLETE", path, Optional.of(target), inspect(target, false, cancel).identity());
            return new FileReport(id, Outcome.SUCCEEDED, List.of(item(path, target, Outcome.SUCCEEDED, "Written and verified")));
        } catch (Exception failure) {
            journal.record(id, "UNKNOWN", path, Optional.of(target), failure.getClass().getSimpleName());
            return new FileReport(id, Outcome.UNKNOWN, List.of(item(path, target, Outcome.UNKNOWN, "Write requires reconciliation")));
        }
    }

    /** Creates and registers a new link after approval. / 批准后创建并登记新链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param targetText explicit link target text / 明确链接目标文本
     * @param approval exact effect approval callback / 精确副作用批准回调
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport createLink(FilePath path, String targetText, Approval approval, FileJournal journal, CancelToken cancel) throws IOException, ApiException {
        FilePath pathActual = inspect(path.parent(), false, cancel).actual().child(path.name());
        FilePath target = resolveTarget(pathActual, targetText);
        if (backend.stat(pathActual).isPresent()) throw new IOException("Link path already exists");
        FileInfo parent = inspect(pathActual.parent(), false, cancel);
        approval.approve(parent, "file-create-link", pathActual.key() + "\n" + target.key());
        UUID operation = journal.begin("CREATE_LINK", pathActual, Optional.of(target), targetText);
        journal.record(operation, "BEFORE_WRITE", pathActual, Optional.of(target), parent.identity());
        check(cancel);
        same(parent, inspect(pathActual.parent(), false, cancel));
        try {
            backend.link(pathActual, targetText);
            FileInfo observed = inspect(pathActual, true, cancel);
            journal.saveLink(observed, optionalTarget(observed, cancel));
            journal.record(operation, "COMPLETE", pathActual, Optional.of(target), observed.identity());
            return new FileReport(operation, Outcome.SUCCEEDED, List.of(item(pathActual, target, Outcome.SUCCEEDED, "Link created and registered")));
        } catch (Exception failure) {
            journal.record(operation, "UNKNOWN", pathActual, Optional.of(target), failure.getClass().getSimpleName());
            return new FileReport(operation, Outcome.UNKNOWN, List.of(item(pathActual, target, Outcome.UNKNOWN, "Link effect or registration requires reconciliation")));
        }
    }

    /** Maintains only original unfinished links after new exact approval, never moving content again. / 新精确审批后仅维护原未完成链接，绝不再次移动内容。
     * @param plan exact original unfinished link evidence / 精确原未完成链接依据
     * @param targets mapped destinations and their durable confirmed identities / 映射目标及其持久已确认身份
     * @param approval new exact continuation approval / 新精确续作审批
     * @param journal new continuation evidence linked to the original operation / 关联原操作的新续作依据
     * @param cancel current ordinary task cancellation / 当前普通任务取消
     * @return actual per-link outcomes without content replay / 不重放内容的真实逐链接结果
     * @throws IOException if original prerequisites or durable effects cannot be verified / 原前置条件或持久副作用无法核实时
     * @throws ApiException if the new approval is refused / 新审批拒绝时 */
    public FileReport maintainLinks(List<FileLinkMaintenance> plan, Map<FilePath, String> targets, Approval approval, FileJournal journal, CancelToken cancel) throws IOException, ApiException {
        return FileMaintenanceRun.execute(this, List.copyOf(plan), Map.copyOf(targets), approval, journal, cancel);
    }

    /** Executes the already authorized exact effect without additional deletion prompts. / 执行已授权精确效果，不额外询问删除。
     * @param info exact observed object and link chain / 精确观测对象及链接链
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param effect exact recoverability approved for deletion / 删除获批的精确可恢复性
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public FileReport delete(FileInfo info, boolean link, DeleteEffect effect, FileJournal journal, CancelToken cancel) throws IOException {
        same(info, inspect(info.requested(), link, cancel));
        if (effect == DeleteEffect.RECYCLE && !backend.canRecycle(info.actual()))
            throw new IOException("Recoverable trash is unavailable");
        var nodes = new ArrayList<FileInfo>();
        collectDelete(info, nodes, cancel);
        UUID operation = journal.begin("DELETE_" + (link ? "LINK" : "FILE"), info.requested(), Optional.empty(), effect + "\n" + FileActions.stamp(info) + "\n" + nodes.stream().map(FileActions::stamp).toList());
        var results = new ArrayList<FileReport.Item>();
        try {
            for (FileInfo node : nodes)
                same(node, inspect(node.requested(), node.kind() == FileInfo.Kind.LINK, cancel));
            check(cancel);
            journal.record(operation, "BEFORE_DELETE", info.actual(), Optional.empty(), effect.name());
            if (effect == DeleteEffect.RECYCLE) {
                FileRecycleReceipt receipt = backend.recycle(info.actual());
                journal.record(operation, "RECYCLED_ITEM", info.actual(), Optional.of(receipt.recycled()), receipt.identity());
                if (receipt.information().isPresent())
                    journal.record(operation, "RECYCLE_METADATA", info.actual(), receipt.information(), receipt.informationIdentity().orElseThrow());
                for (FileInfo node : nodes)
                    results.add(item(node.actual(), null, Outcome.SUCCEEDED, "Recycled with restoration information"));
            } else {
                Collections.reverse(nodes);
                for (FileInfo node : nodes) {
                    check(cancel);
                    same(node, inspect(node.requested(), node.kind() == FileInfo.Kind.LINK, cancel));
                    journal.record(operation, "BEFORE_DELETE_ITEM", node.actual(), Optional.empty(), node.identity());
                    backend.remove(node.actual());
                    journal.record(operation, "DELETED_ITEM", node.actual(), Optional.empty(), "confirmed");
                    results.add(item(node.actual(), null, Outcome.SUCCEEDED, "Permanently deleted"));
                }
            }
            for (FileInfo node : nodes) if (node.kind() == FileInfo.Kind.LINK) journal.removeLink(node.actual());
            var removed = nodes.stream().map(FileInfo::actual).collect(java.util.stream.Collectors.toSet());
            for (FileInfo registered : journal.links(info.actual()))
                if (removed.contains(resolveTarget(registered.actual(), registered.target().orElseThrow()))) {
                    same(registered, inspect(registered.actual(), true, cancel));
                    journal.saveLink(registered, Optional.empty());
                }
            journal.record(operation, "COMPLETE", info.actual(), Optional.empty(), effect.name());
        } catch (Exception failure) {
            results.add(item(info.actual(), null, Outcome.UNKNOWN, "Deletion effect requires reconciliation"));
            journal.record(operation, "UNKNOWN", info.actual(), Optional.empty(), failure.getClass().getSimpleName());
        }
        return new FileReport(operation, aggregate(results), results);
    }

    /** Captures a bounded no-follow deletion tree before any removal. / 删除任何对象前捕获有界不跟随删除树。
     * @param root exact original no-follow directory or containment root / 精确原不跟随目录或包含根
     * @param nodes nodes bound by this exact operation / 此精确操作绑定的nodes参数
     * @param cancel original cooperative cancellation / 原协作取消
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private void collectDelete(FileInfo root, List<FileInfo> nodes, CancelToken cancel) throws IOException {
        var pending = new ArrayDeque<FileInfo>();
        pending.add(root);
        while (!pending.isEmpty()) {
            check(cancel);
            if (nodes.size() + pending.size() > MAX_ENTRIES) throw new IOException("Directory capacity exceeded");
            FileInfo current = pending.removeLast();
            nodes.add(current);
            if (current.kind() == FileInfo.Kind.DIRECTORY)
                for (String name : backend.list(current.actual(), MAX_ENTRIES)) {
                    pending.add(backend.stat(current.actual().child(name)).orElseThrow(() -> new IOException("Directory changed")));
                    if (nodes.size() + pending.size() > MAX_ENTRIES)
                        throw new IOException("Directory capacity exceeded");
                }
        }
    }

    /** Rechecks destination parents and the exact old collision identity. / 重新核对目标父位置及精确旧冲突身份。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @param cancel original cooperative cancellation / 原协作取消
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void verifyTarget(Step step, CancelToken cancel) throws IOException {
        FileInfo parent = inspect(step.target.parent(), false, cancel);
        if (parent.kind() != FileInfo.Kind.DIRECTORY || !parent.actual().equals(step.target.parent()))
            throw new FilePreconditionException("Target ancestor was replaced or redirected");
        if (step.parent == null)
            throw new FilePreconditionException("Target parent has no approved or verified publication identity");
        same(step.parent, parent);
        Optional<FileInfo> now = backend.stat(step.target);
        if (step.existing == null && now.isPresent())
            throw new FilePreconditionException("Target appeared after planning");
        if (step.existing != null && (now.isEmpty() || !FileActions.stamp(step.existing).equals(FileActions.stamp(now.orElseThrow()))))
            throw new FilePreconditionException("Target changed after planning");
    }

    /** Refreshes registered target identities after approved replacement. / 已批准替换后刷新登记目标身份。
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param cancel original cooperative cancellation / 原协作取消
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void refreshTargetLinks(FilePath target, FileJournal journal, CancelToken cancel) throws IOException {
        for (FileInfo link : journal.links(target))
            if (resolveTarget(link.actual(), link.target().orElseThrow()).equals(target)) {
                same(link, inspect(link.actual(), true, cancel));
                journal.saveLink(link, optionalTarget(link, cancel));
            }
    }

    /** Attempts target observation without requiring target accessibility for Link operations. / 尝试观测目标，不要求 Link 操作的目标可访问。
     * @param link already verified no-follow link observation / 已验证的不跟随链接观测
     * @param cancel original cooperative cancellation / 原协作取消
     * @return target identity when verifiable, otherwise an unverified association / 可核实时的目标身份，否则未证实关联
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public Optional<FileInfo> optionalTarget(FileInfo link, CancelToken cancel) throws IOException {
        try {
            return Optional.of(inspect(link.actual(), false, cancel));
        } catch (IOException unverified) {
            return Optional.empty();
        }
    }

    /** Resolves the actual destination while preserving a final Link operation. / 保留末级 Link 操作语义地解析实际目标。
     * @param requested original caller position / 原调用方位置
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FilePath destination(FilePath requested, boolean link, CancelToken cancel) throws IOException {
        if (backend.stat(requested).isPresent())
            return inspect(requested, link && backend.stat(requested).orElseThrow().kind() == FileInfo.Kind.LINK, cancel).actual();
        return inspect(requested.parent(), false, cancel).actual().child(requested.name());
    }

    /** Selects an actually absent sibling name within a bounded search. / 在有界搜索中选择实际缺失的同级名称。
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FilePath unused(FilePath target, CancelToken cancel) throws IOException {
        String name = target.name();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 10000; i++) {
            check(cancel);
            FilePath candidate = target.parent().child(stem + " (" + i + ")" + extension);
            if (backend.stat(candidate).isEmpty()) return candidate;
        }
        throw new IOException("No unused destination name is available");
    }

    /** Hashes exact bounded content or plan text for identity comparison. / 散列精确有界内容或计划文本用于身份比较。
     * @param path exact host-qualified position / 精确带主机位置
     * @param expected previously observed expected identity or extent / 原已观测预期身份或范围
     * @param cancel original cooperative cancellation / 原协作取消
     * @return exact bounded SHA-256 content or plan digest / 精确有界 SHA-256 内容或计划摘要
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    String digest(FilePath path, long expected, CancelToken cancel) throws IOException {
        MessageDigest hash = sha();
        long count = 0;
        try (InputStream in = backend.read(path)) {
            byte[] buffer = new byte[32768];
            int size;
            while ((size = in.read(buffer)) != -1) {
                check(cancel);
                count += size;
                if (count > expected) throw new IOException("Extent exceeds expected length");
                hash.update(buffer, 0, size);
            }
        }
        if (count != expected) throw new IOException("Extent differs");
        return HexFormat.of().formatHex(hash.digest());
    }

    /** Creates a SHA-256 accumulator for content verification. / 创建用于内容核对的 SHA-256 累加器。
     * @return fresh independent SHA-256 accumulator / 新独立 SHA-256 累加器
     */
    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Returns the digest of the exact provided content. / 返回精确输入内容的摘要。
     * @param value content whose exact digest is required / 需要精确摘要的内容
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static String hash(byte[] value) {
        return HexFormat.of().formatHex(sha().digest(value));
    }

    /** Rejects objects or resolution chains changed after observation. / 拒绝观测后已改变的对象或解析链。
     * @param expected previously observed expected identity or extent / 原已观测预期身份或范围
     * @param actual resolved actual object position / 已解析实际对象位置
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    static void same(FileInfo expected, FileInfo actual) throws IOException {
        if (!FileActions.stamp(expected).equals(FileActions.stamp(actual)))
            throw new FilePreconditionException("Object or link chain changed");
    }

    /** Stops admitted file work on cancellation or interruption. / 在取消或中断时停止准入文件工作。
     * @param cancel original cooperative cancellation / 原协作取消
     */
    static void check(CancelToken cancel) {
        if (cancel.requested() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("File operation cancelled");
    }

    /** Enumerates canonical ancestors for no-follow link resolution. / 列举规范祖先用于不跟随链接解析。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static List<FilePath> prefixes(FilePath path) {
        var result = new ArrayList<FilePath>();
        FilePath current = path;
        while (true) {
            result.add(current);
            try {
                current = current.parent();
            } catch (IllegalArgumentException root) {
                break;
            }
        }
        Collections.reverse(result);
        return result;
    }

    /** Resolves a link target using the link's host and actual parent. / 根据链接主机和实际父位置解析指向。
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param text validated link or filter text / 已校验链接或筛选文本
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    public static FilePath resolveTarget(FilePath link, String text) {
        if (link.server().isEmpty()) {
            Path p = Path.of(text);
            return FilePath.local(p.isAbsolute() ? p : Path.of(link.parent().path()).resolve(p));
        }
        return FilePath.remote(link.server().orElseThrow(), text.startsWith("/") ? text : link.parent().path() + "/" + text);
    }

    /** Builds an expressible same-host target without changing the destination meaning. / 构造同主机可表达指向，保持目标含义。
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    static String linkText(FilePath link, FilePath target) {
        if (!link.server().equals(target.server()))
            throw new IllegalArgumentException("A cross-host target is not expressible");
        if (link.server().isEmpty()) {
            try {
                return Path.of(link.parent().path()).relativize(Path.of(target.path())).toString();
            } catch (IllegalArgumentException crossVolume) {
                return target.path();
            }
        }
        return target.path();
    }

    /** Checks strict host-qualified descendant containment. / 核对严格带主机后代包含关系。
     * @param path exact host-qualified position / 精确带主机位置
     * @param root exact original no-follow directory or containment root / 精确原不跟随目录或包含根
     * @return whether the path is a strict same-host descendant / 位置是否为严格同主机后代
     */
    static boolean isInside(FilePath path, FilePath root) {
        if (!path.server().equals(root.server())) return false;
        if (path.server().isEmpty()) return !path.equals(root) && Path.of(path.path()).startsWith(Path.of(root.path()));
        return path.path().startsWith(root.path().equals("/") ? "/" : root.path() + "/");
    }

    /** Captures one observed per-object result. / 捕获单个已观测对象结果。
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param outcome verified operation result category / 已验证操作结果类别
     * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
     * @return immutable per-object observed result / 不可变逐对象观测结果
     */
    static FileReport.Item item(FilePath source, FilePath target, Outcome outcome, String detail) {
        return new FileReport.Item(source, Optional.ofNullable(target), outcome, detail);
    }

    /** Reports refusal before dispatching any file effect. / 发出任何文件副作用前报告拒绝。
     * @param path exact host-qualified position / 精确带主机位置
     * @param reason safe explicit wait or result reason / 安全明确等待或结果原因
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static FileReport failedWithoutEffect(FilePath path, String reason) {
        return new FileReport(UUID.randomUUID(), Outcome.FAILED, List.of(item(path, null, Outcome.FAILED, reason)));
    }

    /** Preserves uncertainty and cancellation while combining independent outcomes. / 合并独立结果时保留不确定及取消状态。
     * @param items immutable per-object results / 不可变逐对象结果
     * @return combined result preserving unknown and cancellation / 保留未知及取消的合并结果
     */
    static Outcome aggregate(List<FileReport.Item> items) {
        if (items.stream().anyMatch(r -> r.outcome() == Outcome.UNKNOWN)) return Outcome.UNKNOWN;
        if (items.stream().anyMatch(r -> r.outcome() == Outcome.CANCELLED)) return Outcome.CANCELLED;
        return items.stream().anyMatch(r -> r.outcome() == Outcome.FAILED) ? Outcome.FAILED : Outcome.SUCCEEDED;
    }

    /** A proven refusal or invalid identity before a required object effect. / 所需对象副作用前已证明的拒绝或无效身份。
     */
    static final class FilePreconditionException extends IOException {
        /** Structured evidence that this object effect was refused before dispatch. / 此对象副作用在发出前被拒绝的结构化依据。 */
        private final Problem problem;

        /** Retains the safe reason for a proven object refusal. / 保留已证明对象拒绝的安全原因。
         * @param message safe diagnostic or proven refusal reason / 安全诊断或已证明拒绝原因
         */
        FilePreconditionException(String message) {
            super(message);
            problem = new Problem(ProblemCode.ACCESS_DENIED, "FILE_OBJECT_PRECONDITION", message, Map.of(), Optional.empty());
        }

        /** Exposes the exact refusal without classifying a dispatched effect as safe. / 公开精确拒绝，不将已发出副作用分类为安全。
         * @return immutable public precondition failure / 不可变的公开前置条件失败 */
        public Problem problem() {
            return problem;
        }
    }

    /** Actual conflict decisions collected before changing collision targets. / 变更冲突目标前收集的实际决定。 */
    public interface Decisions {
        /** Directory conflict choice. / 目录冲突选择。
         * @param source original observed source object / 原观测来源对象
         * @param target exact planned destination or link target / 精确预定位置或链接目标
         * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
         * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        FileChoice.Directory directory(FileInfo source, FileInfo target) throws ApiException;

        /** File conflict choice. / 文件冲突选择。
         * @param source original observed source object / 原观测来源对象
         * @param target exact planned destination or link target / 精确预定位置或链接目标
         * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
         * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        FileChoice.Conflict file(FileInfo source, FileInfo target) throws ApiException;
    }

    /** Exact-plan authorization, never inferred from a conflict choice. / 精确计划授权，不能从冲突选择推导。 */
    @FunctionalInterface
    public interface Approval {
        /** Approves before dispatch. / 发出前批准。
         * @param source original observed source object / 原观测来源对象
         * @param type exact operation category / 精确操作类别
         * @param plan exact preapproved operation plan / 精确预批准操作计划
         * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        void approve(FileInfo source, String type, String plan) throws ApiException;
    }

    /** Progress reflects actual copied bytes. / 进度反映实际复制字节。 */
    @FunctionalInterface
    public interface Progress {
        /** Publishes copied byte progress. / 发布实际字节进度。
         * @param completed actual copied byte count / 实际复制字节数
         * @param total planned total regular content bytes / 预定普通内容总字节数
         * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        void update(long completed, long total) throws IOException;
    }

    /** Existing resumable transport used by cross-host content steps. / 跨主机内容步骤使用的既有可恢复传输。 */
    @FunctionalInterface
    public interface ContentTransfer {
        /** Transfers to the exact preplanned object. / 传输到精确预定对象。
         * @param source original observed source object / 原观测来源对象
         * @param target exact planned destination or link target / 精确预定位置或链接目标
         * @param expected previously observed expected identity or extent / 原已观测预期身份或范围
         * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
         * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        FileReport.Item transfer(FileInfo source, FilePath target, Optional<FileInfo> expected) throws ApiException;
    }
}
