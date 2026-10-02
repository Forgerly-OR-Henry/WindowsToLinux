package gold.debug.windowstolinux.app.service.file;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.file.*;
import gold.debug.windowstolinux.app.db.file.FileRepository;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Reconciles original physical evidence and repairs only already-proved link metadata. / 核对原物理依据，仅修复已证明的链接元数据。 */
final class FileOperationRecovery {
    /** Managed host access with current task admission. / 按当前任务准入的受管主机访问。 */
    private final FileBackendAccess backends;

    /** Exact owner and release evidence. / 精确所有者及发布依据。 */
    private final FileRepository repository;

    /** Binds existing host access and durable evidence. / 绑定既有主机访问及持久依据。
     * @param backends admitted actual host access / 准入实际主机访问
     * @param repository exact release evidence / 精确发布依据 */
    FileOperationRecovery(FileBackendAccess backends, FileRepository repository) {
        this.backends = backends;
        this.repository = repository;
    }

    /** Preserves every unproved effect without replaying file modifications. / 不重放文件修改地保留全部未证明副作用。
     * @param original original operation / 原操作
     * @param task current genuine task / 当前真实任务
     * @param cancel current cancellation / 当前取消
     * @return exact original per-object reconciliation / 精确原逐对象核对结果
     * @throws ApiException if current task or host admission fails / 当前任务或主机准入失败时
     * @throws SQLException if proved registry metadata cannot be saved / 已证明登记元数据无法保存时 */
    FileReport check(FileOperation original, TaskContext task, CancelToken cancel) throws ApiException, SQLException {
        var results = new ArrayList<FileReport.Item>();
        var planned = plans(original);
        var targets = targets(original);
        var grouped = new LinkedHashMap<FilePath, List<FileOperation.Step>>();
        for (var step : original.steps()) grouped.computeIfAbsent(step.source(), key -> new ArrayList<>()).add(step);
        for (var item : planned) results.add(checkLink(item, targets, task, cancel));
        var linkPaths = planned.stream().map(FileLinkMaintenance::link).collect(java.util.stream.Collectors.toSet());
        for (var entry : grouped.entrySet())
            if (!linkPaths.contains(entry.getKey()))
                results.add(checkObject(original, entry.getKey(), entry.getValue(), task));
        if (results.isEmpty())
            results.add(result(original.source(), original.target(), Outcome.UNKNOWN, "No original object effect was confirmed"));
        if (original.steps().stream().anyMatch(s -> s.phase().equals("COMPLETE") && s.detail().equals("FAILED")))
            results.add(result(original.source(), original.target(), Outcome.FAILED, "Original required items were skipped or failed"));
        Outcome overall = results.stream().anyMatch(r -> r.outcome() == Outcome.UNKNOWN) ? Outcome.UNKNOWN : results.stream().anyMatch(r -> r.outcome() == Outcome.FAILED) ? Outcome.FAILED : Outcome.SUCCEEDED;
        return new FileReport(original.id(), overall, results);
    }

    /** Selects only unchanged old links with absent old sources and identity-proved mapped targets. / 仅选择旧来源缺失且映射目标身份可证明的未改变旧链接。
     * @param original original move operation / 原移动操作
     * @param task current ordinary authorized task / 当前普通授权任务
     * @param cancel current cancellation / 当前取消
     * @return original pending maintenance items / 原待维护项
     * @throws IOException if any required pending-link fact remains unproved / 任一所需待维护链接事实未证明时
     * @throws ApiException if host admission fails / 主机准入失败时 */
    List<FileLinkMaintenance> pending(FileOperation original, TaskContext task, CancelToken cancel) throws IOException, ApiException {
        if (!original.type().startsWith("MOVE"))
            throw new IOException("Only original move link maintenance can continue");
        var pending = new ArrayList<FileLinkMaintenance>();
        var targets = targets(original);
        for (var item : plans(original)) {
            boolean old = backends.withBackend(item.link(), task, backend -> {
                var engine = new FileEngine(backend);
                FileInfo link = engine.inspect(item.link(), true, cancel);
                FileInfo target = engine.inspect(item.target(), false, cancel);
                if (backend.stat(item.source()).isPresent() || !target.actual().equals(item.target()) || !target.identity().equals(targets.get(item.target())))
                    throw new IOException("Moved target or old source cannot be proved");
                if (FileEngine.resolveTarget(item.link(), link.target().orElseThrow()).equals(item.target()))
                    return false;
                if (!link.identity().equals(item.linkIdentity()) || !link.target().orElseThrow().equals(item.targetText()))
                    throw new IOException("Original registered link changed outside the APP");
                return true;
            });
            if (old) pending.add(item);
        }
        return List.copyOf(pending);
    }

    /** Verifies a physically updated link before repairing only its registration. / 仅修复登记前核实物理已更新链接。
     * @param item original dedicated link plan / 原专用链接计划
     * @param targets original verified destination identities / 原已验证目标身份
     * @param task current admitted task / 当前准入任务
     * @param cancel current cancellation / 当前取消
     * @return observed link effect or explicit uncertainty / 已观测链接副作用或明确不确定性
     * @throws ApiException if host admission fails / 主机准入失败时
     * @throws SQLException if proved metadata cannot be committed / 已证明元数据无法提交时 */
    private FileReport.Item checkLink(FileLinkMaintenance item, Map<FilePath, String> targets, TaskContext task, CancelToken cancel) throws ApiException, SQLException {
        try {
            Optional<FileInfo> updated = backends.withBackend(item.link(), task, backend -> {
                var engine = new FileEngine(backend);
                FileInfo link = engine.inspect(item.link(), true, cancel);
                FileInfo target = engine.inspect(item.target(), false, cancel);
                return backend.stat(item.source()).isEmpty() && target.actual().equals(item.target()) && target.identity().equals(targets.get(item.target()))
                    && FileEngine.resolveTarget(item.link(), link.target().orElseThrow()).equals(item.target()) ? Optional.of(link) : Optional.empty();
            });
            if (updated.isPresent()) {
                FileInfo target = backends.withBackend(item.target(), task, backend -> new FileEngine(backend).inspect(item.target(), false, cancel));
                if (!target.identity().equals(targets.get(item.target())))
                    return result(item.link(), Optional.of(item.target()), Outcome.UNKNOWN, "Mapped target changed while checking metadata");
                repository.saveLink(task.id(), updated.orElseThrow(), Optional.of(target));
                return result(item.link(), Optional.of(item.target()), Outcome.SUCCEEDED, "Original mapped link effect confirmed; registration repaired without file modification");
            }
        } catch (IOException inaccessible) {
            return result(item.link(), Optional.of(item.target()), Outcome.UNKNOWN, "Original link or target cannot be observed");
        }
        return result(item.link(), Optional.of(item.target()), Outcome.UNKNOWN, "Original link maintenance remains unfinished or externally changed");
    }

    /** Uses object identity evidence rather than outer summary text to verify publications and removals. / 使用对象身份依据而非外层摘要核实发布及移除。
     * @param original original operation category / 原操作类别
     * @param source original per-object source / 原逐对象来源
     * @param steps complete original stages for this source / 此来源的完整原阶段
     * @param task current admitted task / 当前准入任务
     * @return observed effect or uncertainty / 已观测副作用或不确定性
     * @throws ApiException if current host admission fails / 当前主机准入失败时 */
    private FileReport.Item checkObject(FileOperation original, FilePath source, List<FileOperation.Step> steps, TaskContext task) throws ApiException {
        var last = steps.getLast();
        Optional<FileOperation.Step> identity = steps.stream().filter(s -> s.phase().equals("WRITTEN") || s.phase().equals("TARGET_CONFIRMED")).reduce((a, b) -> b);
        if (identity.isEmpty() && !original.type().startsWith("COPY") && !original.type().startsWith("MOVE"))
            identity = steps.stream().filter(s -> s.phase().equals("COMPLETE")).reduce((a, b) -> b);
        try {
            if (original.type().startsWith("DELETE_")) return checkDeletion(source, steps, task);
            if (identity.isPresent()) {
                var evidence = identity.orElseThrow();
                FilePath observed = Set.of("CREATE_LINK", "REGISTER_LINK").contains(original.type()) ? source : evidence.target().orElse(source);
                boolean proved = backends.withBackend(observed, task, b -> b.stat(observed).map(i -> i.identity().equals(evidence.detail())).orElse(false));
                if (original.type().startsWith("MOVE"))
                    proved = proved && backends.withBackend(source, task, b -> b.stat(source).isEmpty());
                return result(source, evidence.target(), proved ? Outcome.SUCCEEDED : Outcome.UNKNOWN, proved ? "Original publication and required source absence confirmed" : "Original publication or source cleanup remains unproved");
            }
        } catch (IOException inaccessible) {
            return result(source, last.target(), Outcome.UNKNOWN, "Original object effect cannot be observed");
        }
        return result(source, last.target(), Outcome.UNKNOWN, "No original object identity was confirmed");
    }

    /** Requires a confirmed permanent removal or the still-existing exact recoverable object and metadata. / 要求已确认永久移除或仍存在的精确可恢复对象及元数据。
     * @param source original actual removed object / 原实际被移除对象
     * @param steps original per-object effect evidence / 原逐对象副作用依据
     * @param task current admitted observation task / 当前准入观测任务
     * @return proved deletion or preserved uncertainty / 已证明删除或保留的不确定性
     * @throws IOException if no-follow observation or restoration metadata reading fails / 不跟随观测或恢复元数据读取失败时
     * @throws ApiException if host admission fails / 主机准入失败时 */
    private FileReport.Item checkDeletion(FilePath source, List<FileOperation.Step> steps, TaskContext task) throws IOException, ApiException {
        boolean absent = backends.withBackend(source, task, b -> b.stat(source).isEmpty());
        if (!absent) return result(source, Optional.empty(), Outcome.UNKNOWN, "Original object exists again");
        if (steps.stream().anyMatch(s -> s.phase().equals("DELETED_ITEM")))
            return result(source, Optional.empty(), Outcome.SUCCEEDED, "Original permanent removal was confirmed before absence checking");
        var receipt = steps.stream().filter(s -> s.phase().equals("RECYCLED_ITEM")).reduce((a, b) -> b);
        if (receipt.isEmpty() || receipt.orElseThrow().target().isEmpty())
            return result(source, Optional.empty(), Outcome.UNKNOWN, "Original source absence has no recoverable object receipt");
        var object = receipt.orElseThrow();
        FilePath recycled = object.target().orElseThrow();
        if (!source.server().equals(recycled.server()))
            return result(source, Optional.of(recycled), Outcome.UNKNOWN, "Recovery receipt host differs from the original object");
        boolean proved = backends.withBackend(recycled, task, b -> b.stat(recycled).map(info -> info.identity().equals(object.detail())).orElse(false));
        var metadata = steps.stream().filter(s -> s.phase().equals("RECYCLE_METADATA")).reduce((a, b) -> b);
        if (metadata.isEmpty()) proved = false;
        if (metadata.isPresent()) {
            var evidence = metadata.orElseThrow();
            proved = proved && evidence.target().isPresent() && backends.withBackend(evidence.target().orElseThrow(), task, b -> {
                var path = evidence.target().orElseThrow();
                var info = b.stat(path);
                if (info.isEmpty() || info.orElseThrow().kind() != FileInfo.Kind.FILE || !info.orElseThrow().identity().equals(evidence.detail()))
                    return false;
                return path.server().equals(recycled.server()) && b.recoveryInformationMatches(source, new FileRecycleReceipt(recycled, object.detail(), Optional.of(path), Optional.of(evidence.detail())));
            });
        }
        return result(source, Optional.of(recycled), proved ? Outcome.SUCCEEDED : Outcome.UNKNOWN, proved ? "Original recoverable object and restoration evidence confirmed" : "Original recovery object or metadata remains unproved");
    }

    /** Reconstructs only structured pre-effect maintenance entries. / 仅重建结构化副作用前维护条目。
     * @param original original durable operation / 原持久操作
     * @return immutable original maintenance items / 不可变原维护项 */
    static List<FileLinkMaintenance> plans(FileOperation original) {
        return original.steps().stream().filter(s -> s.phase().equals("PLANNED_LINK")).map(FileLinkMaintenance::decode).toList();
    }

    /** Retains destination identities independently of later summaries and uncertainty markers. / 独立于后续摘要及不确定性标记保留目标身份。
     * @param original original durable operation / 原持久操作
     * @return confirmed destination identity mapping / 已确认目标身份映射 */
    static Map<FilePath, String> targets(FileOperation original) {
        var result = new LinkedHashMap<FilePath, String>();
        for (var step : original.steps())
            if (Set.of("WRITTEN", "TARGET_CONFIRMED").contains(step.phase()) && step.target().isPresent())
                result.put(step.target().orElseThrow(), step.detail());
        return Map.copyOf(result);
    }

    /** Builds one bounded observed reconciliation result. / 构造单个有界已观测核对结果。
     * @param source original source position / 原来源位置
     * @param target original destination when present / 存在时的原目标
     * @param outcome proved or unproved state / 已证明或未证明状态
     * @param detail readable proof limitation / 可读证明限制
     * @return immutable per-object result / 不可变逐对象结果 */
    private static FileReport.Item result(FilePath source, Optional<FilePath> target, Outcome outcome, String detail) {
        return new FileReport.Item(source, target, outcome, detail);
    }
}
