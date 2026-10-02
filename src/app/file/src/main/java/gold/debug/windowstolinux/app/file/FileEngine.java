package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Bounded file operations with no-follow traversal, exact planning and durable effects. / 有界文件操作、不跟随遍历、精确计划及持久副作用依据。 */
public final class FileEngine {
    /** Maximum objects in one operation. / 单次操作对象上限。 */
    public static final int MAX_ENTRIES = 100000;
    /** Maximum content bytes in one operation. / 单次操作内容字节上限。 */
    public static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    private final FileBackend backend;
    /** Binds the account-level backend. / 绑定账户级后端。 */
    public FileEngine(FileBackend backend) { this.backend = Objects.requireNonNull(backend); }
    /** Actual backend for coordinated cross-host operations. / 跨主机协调操作的实际后端。 */
    public FileBackend backend() { return backend; }

    /** Resolves a File target or observes a Link without reading its target. / 解析 File 目标或观测 Link，不读取链接目标内容。 */
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
                    for (int rest = i + 1; rest < parts.size(); rest++) resolved = resolved.child(parts.get(rest).name());
                    current = resolved; restarted = true; break;
                }
                if (!finalPart && value.kind() != FileInfo.Kind.DIRECTORY) throw new IOException("An ancestor is not a directory");
                if (finalPart) {
                    if (link && value.kind() != FileInfo.Kind.LINK) throw new IOException("The object is not a supported link");
                    if (value.kind() == FileInfo.Kind.OTHER) throw new IOException("Unsupported object type");
                    return new FileInfo(path, current, value.kind(), value.bytes(), value.modified(), value.identity(), value.target(), chain);
                }
            }
            if (!restarted) break;
        }
        throw new IOException("Link resolution exceeds the limit");
    }

    /** Lists immediate no-follow observations, sorted by name and optionally filtered. / 按名称排序并可筛选的直接不跟随观测。 */
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

    /** Reads copied content with a caller-provided hard limit. / 按调用方硬限制读取独立内容。 */
    public byte[] read(FilePath path, int maxBytes, CancelToken cancel) throws IOException {
        if (maxBytes < 1 || maxBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Invalid read bound");
        FileInfo info = inspect(path, false, cancel);
        if (info.kind() != FileInfo.Kind.FILE || info.bytes() > maxBytes) throw new IOException("Content is not a bounded regular file");
        byte[] bytes;
        try (InputStream stream = backend.read(info.actual())) {
            var output = new ByteArrayOutputStream(); var buffer = new byte[32768]; int count;
            while ((count = stream.read(buffer)) != -1) { check(cancel); if (output.size() + count > maxBytes) throw new IOException("Read capacity exceeded"); output.write(buffer, 0, count); }
            bytes = output.toByteArray();
        }
        same(info, inspect(path, false, cancel));
        if (bytes.length != info.bytes()) throw new IOException("Content changed during reading");
        return bytes;
    }

    /** Preplans all collision decisions, then performs actual per-item copy or move. / 先计划全部冲突决定，再逐项复制或移动。 */
    public FileReport copy(FilePath from, FilePath to, boolean link, boolean move, FileChoice choices,
                           FileEngine destination, Decisions decisions, Approval approval, FileJournal journal,
                           CancelToken cancel, Progress progress) throws IOException, ApiException {
        return copy(from,to,link,move,choices,destination,decisions,approval,journal,cancel,progress,null);
    }
    /** Uses the original transfer state machine for preplanned cross-host regular files. / 对已计划跨主机普通文件使用原传输状态机。 */
    public FileReport copy(FilePath from, FilePath to, boolean link, boolean move, FileChoice choices,
                           FileEngine destination, Decisions decisions, Approval approval, FileJournal journal,
                           CancelToken cancel, Progress progress, ContentTransfer transfer) throws IOException, ApiException {
        Objects.requireNonNull(choices); Objects.requireNonNull(destination);
        if (from.server().isPresent() && to.server().isPresent() && !from.server().equals(to.server()))
            throw new IOException("Cross-server operations are outside this contract");
        FileInfo source = inspect(from, link, cancel);
        FilePath target = destination.destination(to, link, cancel);
        if (source.actual().server().equals(target.server()) && (source.actual().equals(target) || isInside(target, source.actual())))
            throw new IOException("A source cannot be copied or moved into itself");
        var plan = new ArrayList<Step>();
        var mapping = new LinkedHashMap<FilePath, FilePath>();
        plan(source, target, choices, destination, decisions, plan, mapping, cancel);
        long total = 0;
        for (Step step : plan) if (step.source != null && !step.failed && step.source.kind() == FileInfo.Kind.FILE) {
            total = Math.addExact(total, step.source.bytes()); if (total > MAX_BYTES) throw new IOException("Operation byte capacity exceeded");
        }
        List<LinkMove> managed = move ? managedLinks(source, mapping, journal, cancel) : List.of();
        String serialized = describe(plan, managed);
        approval.approve(source, "file-" + (move ? "move" : "copy") + (link ? "-link" : "-file"), serialized);
        same(source, inspect(from, link, cancel));
        UUID operation = journal.begin((move ? "MOVE" : "COPY") + (link ? "_LINK" : "_FILE"), from, Optional.of(target), serialized);
        var results = new ArrayList<FileReport.Item>();
        var succeeded = new HashSet<FilePath>();
        var failed = new HashSet<FilePath>();
        long completed = 0;
        List<Step> ordered = new ArrayList<>(plan);
        ordered.sort(Comparator.comparingInt(s -> s.source == null || s.source.kind() != FileInfo.Kind.LINK ? 0 : 1));
        orderLinks(ordered,source.actual());
        for (Step step : ordered) {
            FilePath original = step.source == null ? step.original : step.source.actual();
            if (step.failed) { failed.add(original); results.add(item(original, step.target, Outcome.FAILED, step.detail)); continue; }
            boolean dispatched = false;
            try {
                check(cancel);
                if (hasFailedAncestor(original, failed)) throw new IOException("A required source directory failed");
                same(step.source, inspect(step.source.requested(), step.source.kind() == FileInfo.Kind.LINK, cancel));
                destination.verifyTarget(step, cancel);
                journal.record(operation, "BEFORE_WRITE", original, Optional.of(step.target), step.source.identity());
                dispatched = true;
                if (step.source.kind() == FileInfo.Kind.DIRECTORY) {
                    if (step.existing == null) destination.backend.mkdir(step.target);
                } else if (step.source.kind() == FileInfo.Kind.LINK) {
                    FilePath oldTarget = resolveTarget(original, step.source.target().orElseThrow());
                    FilePath mapped = mapping.get(oldTarget);
                    if (isInside(oldTarget, source.actual()) && source.kind() == FileInfo.Kind.DIRECTORY) {
                        boolean originallyAbsent=backend.stat(oldTarget).isEmpty();
                        if (!originallyAbsent || mapped != null) {
                            if (mapped == null || !succeeded.contains(oldTarget) || failedBelow(oldTarget,failed)) throw new KnownFailure("Internal link target was skipped or failed");
                            oldTarget = mapped;
                        }
                    }
                    if (!oldTarget.server().equals(step.target.server())) throw new KnownFailure("A link target cannot be expressed across hosts");
                    destination.publishLink(step, oldTarget, cancel,journal,operation);
                } else {
                    if (transfer == null) copyContent(step, destination, cancel, progress, completed, total,journal,operation);
                    else {
                        FileReport.Item result=transfer.transfer(step.source,step.target,Optional.ofNullable(step.existing));
                        if(result.outcome()!=Outcome.SUCCEEDED) {
                            results.add(result); failed.add(original); journal.record(operation,result.outcome().name(),original,Optional.of(step.target),result.detail());
                            if(result.outcome()==Outcome.UNKNOWN||result.outcome()==Outcome.CANCELLED) break;
                            continue;
                        }
                        if(!result.target().equals(Optional.of(step.target))) throw new IOException("Transfer published to an unplanned destination");
                    }
                    completed += step.source.bytes();
                }
                journal.record(operation, "WRITTEN", original, Optional.of(step.target), destination.inspect(step.target, step.source.kind() == FileInfo.Kind.LINK, cancel).identity());
                succeeded.add(original);
                if (step.source.kind() == FileInfo.Kind.LINK) {
                    FileInfo observed = destination.inspect(step.target, true, cancel);
                    journal.saveLink(observed, destination.optionalTarget(observed, cancel));
                } else if (step.existing != null) destination.refreshTargetLinks(step.target, journal, cancel);
                if (move && step.source.kind() != FileInfo.Kind.DIRECTORY) {
                    same(step.source, inspect(step.source.requested(), step.source.kind() == FileInfo.Kind.LINK, cancel));
                    journal.record(operation, "BEFORE_SOURCE_REMOVE", original, Optional.of(step.target), step.source.identity());
                    backend.remove(original);
                    journal.record(operation, "SOURCE_REMOVED", original, Optional.of(step.target), "confirmed");
                    if (step.source.kind() == FileInfo.Kind.LINK) journal.removeLink(original);
                }
                results.add(item(original, step.target, Outcome.SUCCEEDED, move ? "Moved and verified" : "Copied and verified"));
            } catch (KnownFailure error) {
                failed.add(original); results.add(item(original, step.target, Outcome.FAILED, error.getMessage()));
                journal.record(operation, "FAILED", original, Optional.of(step.target), error.getMessage());
            } catch (java.util.concurrent.CancellationException cancelled) {
                results.add(item(original, step.target, dispatched ? Outcome.UNKNOWN : Outcome.CANCELLED, "Cancelled; dispatched effects require reconciliation"));
                journal.record(operation, dispatched ? "UNKNOWN" : "CANCELLED", original, Optional.of(step.target), "cancelled"); break;
            } catch (IOException error) {
                failed.add(original);
                Outcome outcome = dispatched ? Outcome.UNKNOWN : Outcome.FAILED;
                results.add(item(original, step.target, outcome, dispatched ? "Effect requires reconciliation" : "Object, permission or identity check failed"));
                journal.record(operation, outcome.name(), original, Optional.of(step.target), error.getClass().getSimpleName());
                if (outcome == Outcome.UNKNOWN) break;
            }
        }
        if (move && results.stream().noneMatch(r -> r.outcome() == Outcome.UNKNOWN || r.outcome() == Outcome.CANCELLED)) {
            List<Step> directories = plan.stream().filter(s -> s.source != null && s.source.kind() == FileInfo.Kind.DIRECTORY && !s.failed).sorted(Comparator.comparingInt((Step s) -> s.source.actual().path().length()).reversed()).toList();
            for (Step directory : directories) {
                try {
                    check(cancel);
                    if (!backend.list(directory.source.actual(), MAX_ENTRIES).isEmpty()) continue;
                    same(directory.source, inspect(directory.source.requested(), false, cancel));
                    journal.record(operation, "BEFORE_SOURCE_REMOVE", directory.source.actual(), Optional.of(directory.target), directory.source.identity());
                    backend.remove(directory.source.actual());
                    journal.record(operation, "SOURCE_REMOVED", directory.source.actual(), Optional.of(directory.target), "confirmed");
                } catch (Exception failure) { results.add(item(directory.source.actual(), directory.target, Outcome.UNKNOWN, "Source directory removal requires reconciliation")); break; }
            }
            boolean complete = results.stream().allMatch(r -> r.outcome() == Outcome.SUCCEEDED) && backend.stat(source.actual()).isEmpty();
            for (LinkMove maintenance : managed) {
                if (maintenance.target.equals(source.actual()) && source.kind() == FileInfo.Kind.DIRECTORY && !complete) continue;
                if (backend.stat(maintenance.target).map(i -> i.kind() == FileInfo.Kind.DIRECTORY).orElse(false)) continue;
                if (!succeeded.contains(maintenance.target) || failed.contains(maintenance.target)) continue;
                try {
                    same(maintenance.link, inspect(maintenance.link.actual(), true, cancel));
                    journal.record(operation, "BEFORE_LINK_UPDATE", maintenance.link.actual(), Optional.of(maintenance.newTarget), maintenance.link.identity());
                    replaceLink(maintenance.link, maintenance.newTarget, cancel,journal,operation);
                    FileInfo updated = inspect(maintenance.link.actual(), true, cancel);
                    journal.saveLink(updated, optionalTarget(updated, cancel));
                    journal.record(operation, "LINK_UPDATED", updated.actual(), Optional.of(maintenance.newTarget), updated.identity());
                } catch (IOException error) { results.add(item(maintenance.link.actual(), maintenance.newTarget, Outcome.UNKNOWN, "File moved; registered link maintenance requires reconciliation")); }
            }
        }
        Outcome outcome = aggregate(results);
        journal.record(operation, outcome == Outcome.UNKNOWN ? "UNKNOWN" : "COMPLETE", from, Optional.of(target), outcome.name());
        return new FileReport(operation, outcome, results);
    }

    /** Creates or replaces regular content only after exact approval and collision choice. / 精确批准及冲突选择后创建或替换普通内容。 */
    public FileReport write(FilePath path, byte[] content, boolean directory, boolean createOnly, Decisions decisions,
                            Approval approval, FileJournal journal, CancelToken cancel) throws IOException, ApiException {
        if (content.length > 64 * 1024 * 1024) throw new IllegalArgumentException("Write capacity exceeded");
        FilePath target = destination(path, false, cancel);
        FileInfo existing = backend.stat(target).orElse(null);
        if (existing != null && (createOnly || directory || existing.kind() != FileInfo.Kind.FILE)) throw new IOException("Target already exists or has a different type");
        FileInfo parent = inspect(target.parent(), false, cancel);
        if (parent.kind() != FileInfo.Kind.DIRECTORY) throw new IOException("Parent is not a directory");
        if (existing != null) {
            FileChoice.Conflict choice = decisions.file(existing, existing);
            if (choice == FileChoice.Conflict.SKIP) return failedWithoutEffect(path, "User chose not to process the target");
            if (choice == FileChoice.Conflict.COPY) { target = unused(target, cancel); existing = null; }
        }
        String digest = hash(content);
        approval.approve(existing == null ? parent : existing, directory ? "file-create-directory" : "file-write", target.key() + "\n" + digest + "\n" + content.length);
        UUID id = journal.begin(directory ? "CREATE_DIRECTORY" : "WRITE", path, Optional.of(target), digest);
        journal.record(id, "BEFORE_WRITE", path, Optional.of(target), existing == null ? "absent" : existing.identity());
        try {
            check(cancel); same(parent, inspect(target.parent(), false, cancel));
            if (existing != null) same(existing, inspect(target, false, cancel));
            else if (backend.stat(target).isPresent()) throw new KnownFailure("New target appeared");
            if (directory) backend.mkdir(target);
            else {
                FilePath temp = target.parent().child(".wtl-file-" + id + ".part");
                try (OutputStream output = backend.create(temp)) { output.write(content); }
                if (!digest.equals(digest(temp, content.length, cancel))) throw new IOException("Staging verification failed");
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

    /** Creates and registers a new link after approval. / 批准后创建并登记新链接。 */
    public FileReport createLink(FilePath path, String targetText, Approval approval, FileJournal journal, CancelToken cancel) throws IOException, ApiException {
        FilePath pathActual = inspect(path.parent(), false, cancel).actual().child(path.name());
        FilePath target = resolveTarget(pathActual, targetText);
        if (backend.stat(pathActual).isPresent()) throw new IOException("Link path already exists");
        FileInfo parent = inspect(pathActual.parent(), false, cancel);
        approval.approve(parent, "file-create-link", pathActual.key() + "\n" + target.key());
        UUID operation = journal.begin("CREATE_LINK", pathActual, Optional.of(target), targetText);
        journal.record(operation, "BEFORE_WRITE", pathActual, Optional.of(target), parent.identity());
        check(cancel); same(parent, inspect(pathActual.parent(), false, cancel));
        backend.link(pathActual, targetText);
        FileInfo observed = inspect(pathActual, true, cancel); journal.saveLink(observed, optionalTarget(observed, cancel));
        journal.record(operation, "COMPLETE", pathActual, Optional.of(target), observed.identity());
        return new FileReport(operation, Outcome.SUCCEEDED, List.of(item(pathActual, target, Outcome.SUCCEEDED, "Link created and registered")));
    }

    /** Executes the already authorized exact effect without additional deletion prompts. / 执行已授权精确效果，不额外询问删除。 */
    public FileReport delete(FileInfo info, boolean link, DeleteEffect effect, FileJournal journal, CancelToken cancel) throws IOException {
        same(info, inspect(info.requested(), link, cancel));
        if (effect == DeleteEffect.RECYCLE && !backend.canRecycle(info.actual())) throw new IOException("Recoverable trash is unavailable");
        var nodes = new ArrayList<FileInfo>(); collectDelete(info, nodes, cancel);
        UUID operation = journal.begin("DELETE_" + (link ? "LINK" : "FILE"), info.requested(), Optional.empty(), effect + "\n" + FileActions.stamp(info) + "\n" + nodes.stream().map(FileActions::stamp).toList());
        var results = new ArrayList<FileReport.Item>();
        try {
            for (FileInfo node : nodes) same(node, inspect(node.requested(), node.kind() == FileInfo.Kind.LINK, cancel));
            check(cancel);
            journal.record(operation, "BEFORE_DELETE", info.actual(), Optional.empty(), effect.name());
            if (effect == DeleteEffect.RECYCLE) {
                backend.recycle(info.actual());
                for (FileInfo node : nodes) results.add(item(node.actual(), null, Outcome.SUCCEEDED, "Recycled with restoration information"));
            } else {
                Collections.reverse(nodes);
                for (FileInfo node : nodes) {
                    check(cancel); same(node, inspect(node.requested(), node.kind() == FileInfo.Kind.LINK, cancel));
                    journal.record(operation, "BEFORE_DELETE_ITEM", node.actual(), Optional.empty(), node.identity());
                    backend.remove(node.actual());
                    journal.record(operation, "DELETED_ITEM", node.actual(), Optional.empty(), "confirmed");
                    results.add(item(node.actual(), null, Outcome.SUCCEEDED, "Permanently deleted"));
                }
            }
            for (FileInfo node : nodes) if (node.kind() == FileInfo.Kind.LINK) journal.removeLink(node.actual());
            journal.record(operation, "COMPLETE", info.actual(), Optional.empty(), effect.name());
        } catch (Exception failure) {
            results.add(item(info.actual(), null, Outcome.UNKNOWN, "Deletion effect requires reconciliation"));
            journal.record(operation, "UNKNOWN", info.actual(), Optional.empty(), failure.getClass().getSimpleName());
        }
        return new FileReport(operation, aggregate(results), results);
    }

    private void collectDelete(FileInfo root, List<FileInfo> nodes, CancelToken cancel) throws IOException {
        check(cancel); if (nodes.size() >= MAX_ENTRIES) throw new IOException("Directory capacity exceeded"); nodes.add(root);
        if (root.kind() == FileInfo.Kind.DIRECTORY) for (String name : backend.list(root.actual(), MAX_ENTRIES)) {
            FileInfo child = backend.stat(root.actual().child(name)).orElseThrow(() -> new IOException("Directory changed")); collectDelete(child, nodes, cancel);
        }
    }
    private void plan(FileInfo source, FilePath target, FileChoice choices, FileEngine dest, Decisions decisions,
                      List<Step> plan, Map<FilePath, FilePath> mapping, CancelToken cancel) throws IOException, ApiException {
        check(cancel); if (plan.size() >= MAX_ENTRIES) throw new IOException("Operation object capacity exceeded");
        FileInfo existing = dest.backend.stat(target).orElse(null);
        if (existing != null) {
            if (source.kind() != existing.kind()) { plan.add(new Step(source, target, existing, true, "Object type conflict")); return; }
            if (source.kind() == FileInfo.Kind.DIRECTORY) {
                FileChoice.Directory choice = choices.directory().isPresent() ? choices.directory().orElseThrow() : decisions.directory(source, existing);
                if (choice == FileChoice.Directory.SKIP) { plan.add(new Step(source, target, existing, true, "User chose not to process this directory")); return; }
                if (choice == FileChoice.Directory.COPY) { target = dest.unused(target, cancel); existing = null; }
            } else {
                FileChoice.Conflict choice = choices.file().isPresent() ? choices.file().orElseThrow() : decisions.file(source, existing);
                if (choice == FileChoice.Conflict.SKIP) { plan.add(new Step(source, target, existing, true, "User chose not to process this item")); return; }
                if (choice == FileChoice.Conflict.COPY) { target = dest.unused(target, cancel); existing = null; }
            }
        }
        plan.add(new Step(source, target, existing, false, "")); mapping.put(source.actual(), target);
        if (source.kind() == FileInfo.Kind.DIRECTORY) {
            for (String name : backend.list(source.actual(), MAX_ENTRIES)) {
                FilePath child = source.actual().child(name);
                try {
                    FileInfo childInfo = backend.stat(child).orElseThrow(() -> new IOException("Source disappeared"));
                    if (childInfo.kind() == FileInfo.Kind.OTHER) throw new IOException("Unsupported source object");
                    plan(childInfo, target.child(name), choices, dest, decisions, plan, mapping, cancel);
                } catch (IOException failure) { plan.add(new Step(child, target.child(name), "Source cannot be safely observed")); }
            }
        }
    }
    private void verifyTarget(Step step, CancelToken cancel) throws IOException {
        FileInfo parent = inspect(step.target.parent(), false, cancel);
        if (parent.kind() != FileInfo.Kind.DIRECTORY || !parent.actual().equals(step.target.parent())) throw new KnownFailure("Target ancestor was replaced or redirected");
        Optional<FileInfo> now = backend.stat(step.target);
        if (step.existing == null && now.isPresent()) throw new KnownFailure("Target appeared after planning");
        if (step.existing != null && (now.isEmpty() || !FileActions.stamp(step.existing).equals(FileActions.stamp(now.orElseThrow())))) throw new KnownFailure("Target changed after planning");
    }
    private void copyContent(Step step, FileEngine dest, CancelToken cancel, Progress progress, long completed, long total,FileJournal journal,UUID operation) throws IOException {
        FilePath staging = step.target.parent().child(".wtl-file-" + UUID.randomUUID() + ".part");
        journal.record(operation,"BEFORE_STAGING",step.source.actual(),Optional.of(step.target),staging.key());
        MessageDigest hash = sha(); long count = 0;
        InputStream opened;
        try { opened=backend.read(step.source.actual()); } catch(IOException denied) { throw new KnownFailure("Source content cannot be read"); }
        try (InputStream in = opened; OutputStream out = dest.backend.create(staging)) {
            byte[] buffer = new byte[32768]; int read;
            while ((read = in.read(buffer)) != -1) { check(cancel); count += read; if (count > step.source.bytes()) throw new IOException("Source grew during transfer"); out.write(buffer, 0, read); hash.update(buffer, 0, read); progress.update(completed + count, total); }
        }
        if (count != step.source.bytes()) throw new IOException("Source extent changed during transfer");
        same(step.source, inspect(step.source.requested(), false, cancel));
        String digest = HexFormat.of().formatHex(hash.digest());
        if (!digest.equals(dest.digest(staging, count, cancel))) throw new IOException("Staged content digest differs");
        dest.verifyTarget(step, cancel);
        dest.backend.publish(staging, step.target, step.existing != null);
        if (!digest.equals(dest.digest(step.target, count, cancel))) throw new IOException("Published content digest differs");
    }
    private void publishLink(Step step, FilePath target, CancelToken cancel,FileJournal journal,UUID operation) throws IOException {
        FilePath staging = step.target.parent().child(".wtl-link-" + UUID.randomUUID() + ".part");
        journal.record(operation,"BEFORE_LINK_STAGING",step.source.actual(),Optional.of(step.target),staging.key()+" -> "+target.key());
        backend.link(staging, linkText(step.target, target)); verifyTarget(step, cancel); backend.publish(staging, step.target, step.existing != null);
        FileInfo actual = inspect(step.target, true, cancel);
        if (!resolveTarget(step.target, actual.target().orElseThrow()).equals(target)) throw new IOException("Published link differs");
    }
    private void replaceLink(FileInfo link, FilePath target, CancelToken cancel,FileJournal journal,UUID operation) throws IOException {
        same(link, inspect(link.actual(), true, cancel));
        publishLink(new Step(link, link.actual(), link, false, ""), target, cancel,journal,operation);
    }
    private List<LinkMove> managedLinks(FileInfo source, Map<FilePath, FilePath> mapping, FileJournal journal, CancelToken cancel) throws IOException {
        var moves = new ArrayList<LinkMove>();
        for (FileInfo link : journal.links(source.actual())) {
            if (isInside(link.actual(), source.actual()) || link.actual().equals(source.actual())) continue;
            FilePath target = resolveTarget(link.actual(), link.target().orElseThrow());
            FilePath moved = mapping.get(target); if (moved == null) continue;
            same(link, inspect(link.actual(), true, cancel));
            FileInfo original=inspect(target,false,cancel);
            if(!journal.targetMatches(link,original))throw new IOException("Registered target identity changed outside the APP");
            if (!moved.server().equals(link.actual().server())) throw new IOException("Registered link maintenance cannot cross hosts");
            moves.add(new LinkMove(link, target, moved));
        }
        return List.copyOf(moves);
    }
    private void refreshTargetLinks(FilePath target, FileJournal journal, CancelToken cancel) throws IOException {
        for (FileInfo link : journal.links(target)) if (resolveTarget(link.actual(), link.target().orElseThrow()).equals(target)) {
            same(link, inspect(link.actual(), true, cancel)); journal.saveLink(link, optionalTarget(link, cancel));
        }
    }
    /** Reads the target when it exists; an originally broken link remains valid. / 目标存在时观测，原断链仍是有效链接。 */
    public Optional<FileInfo> optionalTarget(FileInfo link, CancelToken cancel) throws IOException {
        try { return Optional.of(inspect(link.actual(), false, cancel)); } catch (FileNotFoundException absent) { return Optional.empty(); }
    }
    private FilePath destination(FilePath requested, boolean link, CancelToken cancel) throws IOException {
        if (backend.stat(requested).isPresent()) return inspect(requested, link && backend.stat(requested).orElseThrow().kind() == FileInfo.Kind.LINK, cancel).actual();
        return inspect(requested.parent(), false, cancel).actual().child(requested.name());
    }
    private FilePath unused(FilePath target, CancelToken cancel) throws IOException {
        String name = target.name(); int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name; String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 10000; i++) { check(cancel); FilePath candidate = target.parent().child(stem + " (" + i + ")" + extension); if (backend.stat(candidate).isEmpty()) return candidate; }
        throw new IOException("No unused destination name is available");
    }
    private String digest(FilePath path, long expected, CancelToken cancel) throws IOException {
        MessageDigest hash = sha(); long count = 0; try (InputStream in = backend.read(path)) { byte[] buffer = new byte[32768]; int size; while ((size = in.read(buffer)) != -1) { check(cancel); count += size; if (count > expected) throw new IOException("Extent exceeds expected length"); hash.update(buffer, 0, size); } }
        if (count != expected) throw new IOException("Extent differs"); return HexFormat.of().formatHex(hash.digest());
    }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static String hash(byte[] value) { return HexFormat.of().formatHex(sha().digest(value)); }
    private static void same(FileInfo expected, FileInfo actual) throws IOException { if (!FileActions.stamp(expected).equals(FileActions.stamp(actual))) throw new KnownFailure("Object or link chain changed"); }
    private static void check(CancelToken cancel) { if (cancel.requested() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("File operation cancelled"); }
    private static List<FilePath> prefixes(FilePath path) {
        var result = new ArrayList<FilePath>(); FilePath current = path;
        while (true) { result.add(current); try { current = current.parent(); } catch (IllegalArgumentException root) { break; } }
        Collections.reverse(result); return result;
    }
    /** Resolves a link target using the link's host and actual parent. / 根据链接主机和实际父位置解析指向。 */
    public static FilePath resolveTarget(FilePath link, String text) {
        if (link.server().isEmpty()) { Path p = Path.of(text); return FilePath.local(p.isAbsolute() ? p : Path.of(link.parent().path()).resolve(p)); }
        return FilePath.remote(link.server().orElseThrow(), text.startsWith("/") ? text : link.parent().path() + "/" + text);
    }
    private static String linkText(FilePath link, FilePath target) {
        if (!link.server().equals(target.server())) throw new IllegalArgumentException("A cross-host target is not expressible");
        if (link.server().isEmpty()) { try { return Path.of(link.parent().path()).relativize(Path.of(target.path())).toString(); } catch (IllegalArgumentException crossVolume) { return target.path(); } }
        return target.path();
    }
    private static boolean isInside(FilePath path, FilePath root) {
        if (!path.server().equals(root.server())) return false;
        if (path.server().isEmpty()) return !path.equals(root) && Path.of(path.path()).startsWith(Path.of(root.path()));
        return path.path().startsWith(root.path().equals("/") ? "/" : root.path() + "/");
    }
    private static boolean hasFailedAncestor(FilePath path, Set<FilePath> failed) { return failed.stream().anyMatch(p -> isInside(path, p)); }
    private static boolean failedBelow(FilePath target,Set<FilePath> failed){return failed.stream().anyMatch(p->p.equals(target)||isInside(p,target));}
    private static FileReport.Item item(FilePath source, FilePath target, Outcome outcome, String detail) { return new FileReport.Item(source, Optional.ofNullable(target), outcome, detail); }
    private static FileReport failedWithoutEffect(FilePath path, String reason) { return new FileReport(UUID.randomUUID(), Outcome.FAILED, List.of(item(path, null, Outcome.FAILED, reason))); }
    private static Outcome aggregate(List<FileReport.Item> items) { if (items.stream().anyMatch(r -> r.outcome() == Outcome.UNKNOWN)) return Outcome.UNKNOWN; if (items.stream().anyMatch(r -> r.outcome() == Outcome.CANCELLED)) return Outcome.CANCELLED; return items.stream().anyMatch(r -> r.outcome() == Outcome.FAILED) ? Outcome.FAILED : Outcome.SUCCEEDED; }
    private static String describe(List<Step> plan, List<LinkMove> managed) {
        var text = new StringBuilder();
        for (Step s : plan) { text.append(s.original.key()).append(" -> ").append(s.target.key()).append(' ').append(s.failed ? "SKIP" : s.source.identity()).append(' ').append(s.existing == null ? "ABSENT" : s.existing.identity()).append('\n'); }
        for (LinkMove l : managed) text.append("LINK ").append(l.link.actual().key()).append(' ').append(l.link.identity()).append(" -> ").append(l.newTarget.key()).append('\n');
        if (text.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Plan capacity exceeded"); return text.toString();
    }
    private static void orderLinks(List<Step> steps,FilePath root) {
        var links=new ArrayList<Step>();steps.removeIf(s->{if(s.source!=null&&s.source.kind()==FileInfo.Kind.LINK&&!s.failed){links.add(s);return true;}return false;});
        var pending=new HashSet<FilePath>();for(Step s:links)pending.add(s.source.actual());
        while(!links.isEmpty()){
            boolean advanced=false;
            for(var it=links.iterator();it.hasNext();){Step s=it.next();FilePath target=resolveTarget(s.source.actual(),s.source.target().orElseThrow());
                if(!isInside(target,root)||!pending.contains(target)){steps.add(s);pending.remove(s.source.actual());it.remove();advanced=true;}
            }
            if(!advanced){steps.addAll(links);break;}
        }
    }
    private static final class Step {
        final FileInfo source, existing; final FilePath original, target; final boolean failed; final String detail;
        Step(FileInfo source, FilePath target, FileInfo existing, boolean failed, String detail) { this.source = source; original = source.actual(); this.target = target; this.existing = existing; this.failed = failed; this.detail = detail; }
        Step(FilePath original, FilePath target, String detail) { source = null; this.original = original; this.target = target; existing = null; failed = true; this.detail = detail; }
    }
    private record LinkMove(FileInfo link, FilePath target, FilePath newTarget) { }
    private static final class KnownFailure extends IOException { KnownFailure(String message) { super(message); } }
    /** Actual conflict decisions collected before changing collision targets. / 变更冲突目标前收集的实际决定。 */
    public interface Decisions {
        /** Directory conflict choice. / 目录冲突选择。 */ FileChoice.Directory directory(FileInfo source, FileInfo target) throws ApiException;
        /** File conflict choice. / 文件冲突选择。 */ FileChoice.Conflict file(FileInfo source, FileInfo target) throws ApiException;
    }
    /** Exact-plan authorization, never inferred from a conflict choice. / 精确计划授权，不能从冲突选择推导。 */
    @FunctionalInterface public interface Approval { /** Approves before dispatch. / 发出前批准。 */ void approve(FileInfo source, String type, String plan) throws ApiException; }
    /** Progress reflects actual copied bytes. / 进度反映实际复制字节。 */
    @FunctionalInterface public interface Progress { /** Publishes copied byte progress. / 发布实际字节进度。 */ void update(long completed, long total) throws IOException; }
    /** Existing resumable transport used by cross-host content steps. / 跨主机内容步骤使用的既有可恢复传输。 */
    @FunctionalInterface public interface ContentTransfer { /** Transfers to the exact preplanned object. / 传输到精确预定对象。 */ FileReport.Item transfer(FileInfo source,FilePath target,Optional<FileInfo> expected) throws ApiException; }
}
