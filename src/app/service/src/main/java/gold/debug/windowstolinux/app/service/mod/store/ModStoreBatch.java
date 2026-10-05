package gold.debug.windowstolinux.app.service.mod.store;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreBatchResult.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStorePlan.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreProgress.Kind;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Executes one fixed proposal, preserving actual receipts and original uncertainty. / 执行一份固定计划，保留实际凭据及原不确定性。 */
final class ModStoreBatch {
    /** Original installer, never replaced with a store implementation. / 原安装器，不替换为商店实现。 */
    private final PlatformModInstallation installer;

    /** Actual transfer boundary. / 实际传输边界。 */
    private final ModStoreGateway gateway;

    /** Actual installed verifier. / 实际安装核验器。 */
    private final ModStoreInstalled installed;

    /** Original complete catalog. / 原完整目录。 */
    private final ModStoreCatalog catalog;

    /** Original confirmed plan. / 原已确认计划。 */
    private final ModStorePlan plan;

    /** Exact original batch identity. / 精确原批次身份。 */
    private final UUID id = UUID.randomUUID();

    /** Cooperative request, never passed as an installer interrupt. / 协作请求，不作为安装器中断传递。 */
    private final CancelToken cancellation;

    /** Actual progress observer. / 实际进度观察者。 */
    private final Consumer<ModStoreProgress> progress;

    /** Immutable real per-package observation publisher. / 不可变真实逐包观察发布者。 */
    private final BiConsumer<UUID, List<PackageResult>> observations;

    /** Actual returned facts under the executing worker. / 执行工作线程下的实际返回事实。 */
    private final List<PackageResult> reports = new ArrayList<>();

    /** Binds one exact proposal and original owners. / 绑定一份精确计划及原所有者。
     * @param installer actual installer / 实际安装器
     * @param gateway actual transport / 实际传输
     * @param installed actual verifier / 实际核验器
     * @param catalog original sources / 原来源
     * @param plan confirmed proposal / 已确认计划
     * @param cancellation original request / 原请求
     * @param progress actual progress observer / 实际进度观察者
     * @param observations actual fact observer / 实际事实观察者 */
    ModStoreBatch(PlatformModInstallation installer, ModStoreGateway gateway, ModStoreInstalled installed,
                  ModStoreCatalog catalog, ModStorePlan plan, CancelToken cancellation,
                  Consumer<ModStoreProgress> progress, BiConsumer<UUID, List<PackageResult>> observations) {
        this.installer = installer;
        this.gateway = gateway;
        this.installed = installed;
        this.catalog = catalog;
        this.plan = plan;
        this.cancellation = cancellation;
        this.progress = progress;
        this.observations = observations;
    }

    /** Executes independent branches while stopping all new effects on uncertainty or cancellation. / 执行独立分支，遇不确定或取消停止全部新效果。
     * @return actual complete batch facts / 实际完整批次事实 */
    ModStoreBatchResult run() {
        Problem stopped = preflight();
        for (var item : plan.items()) {
            PackageResult report;
            if (stopped != null) report = result(item, State.UNATTEMPTED, null, null, stopped);
            else if (cancellation.requested()) {
                stopped = ModStoreFacts.problem(ProblemCode.CANCELLED, "MOD_STORE_CANCELLED", "No additional packages were accepted");
                report = result(item, State.UNATTEMPTED, null, null, stopped);
            } else if (blocked(item)) report = result(item, State.UNATTEMPTED, null, null,
                ModStoreFacts.problem(ProblemCode.UNAVAILABLE, "MOD_STORE_DEPENDENCY_FAILED", "A required provider did not complete"));
            else report = execute(item);
            reports.add(report);
            observations.accept(id, List.copyOf(reports));
            signal(item, Kind.FINISHED, 0, 0);
            if (report.state() == State.UNKNOWN || report.state() == State.CANCELLED) stopped = report.problem().orElse(
                ModStoreFacts.problem(ProblemCode.UNKNOWN, "MOD_STORE_UNCONFIRMED", "Original installation requires reconciliation"));
        }
        return finish();
    }

    /** Rechecks actual pending originals and all reuse facts before any download. / 任一下载前复核实际待核原操作及全部复用事实。
     * @return known preflight problem or absent / 已知预检问题或缺省 */
    private Problem preflight() {
        try {
            for (var item : plan.items()) {
                signal(item, Kind.REVALIDATING, 0, 0);
                pending(item.key().owner());
                if (item.source() == Source.INSTALLED) installed.verify(original(item.key()));
            }
            return null;
        } catch (Exception failure) {
            return ModStoreFacts.failure(failure);
        }
    }

    /** Keeps required consumers unattempted after an actual provider failure. / 实际提供方失败后保持必需消费方未尝试。
     * @param item exact consumer / 精确消费方
     * @return whether a required provider failed / 必需提供方是否失败 */
    private boolean blocked(Item item) {
        return item.manifest().dependencies().stream().filter(d -> !d.optional()).anyMatch(dependency ->
            reports.stream().noneMatch(report -> report.key().owner().equals(dependency.id())
                && (report.state() == State.REUSED || report.state() == State.INSTALLED) && report.problem().isEmpty()));
    }

    /** Reuses freshly verified bytes or acquires the exact original declaration. / 复用新核验字节或获取精确原声明。
     * @param item exact planned item / 精确计划项
     * @return actual returned package facts / 实际返回包事实 */
    private PackageResult execute(Item item) {
        try {
            ModStoreFacts.cancellation(cancellation);
            pending(item.key().owner());
            if (item.source() == Source.INSTALLED) {
                signal(item, Kind.REUSING, 0, 0);
                installed.verify(original(item.key()));
                return result(item, State.REUSED, null, null, null);
            }
            return acquire(item);
        } catch (Exception failure) {
            return result(item, failure instanceof CancellationException ? State.CANCELLED : State.FAILED,
                null, null, ModStoreFacts.failure(failure));
        }
    }

    /** Acquires one package without exposing partial bytes or replaying accepted installation. / 获取一个包，不暴露半包或重放已接纳安装。
     * @param item exact confirmed item / 精确已确认项
     * @return actual installer result / 实际安装器结果
     * @throws Exception if a known pre-admission step fails / 已知准入前步骤失败时 */
    private PackageResult acquire(Item item) throws Exception {
        var release = catalog.releases().stream().filter(r -> r.owner().equals(item.key().owner()) && r.version().equals(item.key().version()))
            .findFirst().orElseThrow(() -> new IOException("Confirmed source disappeared"));
        Path directory = installer.originalsDirectory();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
            throw new IOException("Store original directory is redirected");
        Path target = directory.resolve("store-" + UUID.randomUUID() + ".wtlmod");
        String identity = null;
        boolean accepted = false;
        try {
            signal(item, Kind.DOWNLOADING, 0, release.bytes());
            var download = gateway.download(catalog.source(), release, target, value ->
                signal(item, Kind.DOWNLOADING, value.completed(), value.total()), cancellation);
            if (!download.file().equals(target)) throw new IOException("Gateway returned another package path");
            identity = WindowsFileIdentity.read(target);
            ModStoreFacts.cancellation(cancellation);
            signal(item, Kind.VERIFYING, release.bytes(), release.bytes());
            if (!gateway.verify(release, download).verified())
                throw new IOException("Actual package verification rejected");
            signal(item, Kind.REVIEWING, release.bytes(), release.bytes());
            var review = installer.review(target);
            if (!ModStoreFacts.same(item.manifest(), review.manifest()))
                throw new IOException("Complete package declaration differs from catalog");
            ModStoreFacts.cancellation(cancellation);
            pending(item.key().owner());
            signal(item, Kind.INSTALLING, release.bytes(), release.bytes());
            accepted = true;
            return accepted(item, review);
        } finally {
            if (!accepted && identity != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!identity.equals(WindowsFileIdentity.read(target)))
                    throw new IOException("Unaccepted original identity changed");
                Files.delete(target);
            }
        }
    }

    /** Preserves the exact original installer UUID even when admission is uncertain. / 即使准入不确定也保留精确原安装 UUID。
     * @param item confirmed item / 已确认项
     * @param review original single-use review / 原单次审阅
     * @return original returned installation facts / 原返回安装事实 */
    private PackageResult accepted(Item item, PlatformModInstallation.Review review) {
        try {
            var outcome = installer.install(review);
            return result(item, outcome.outcome() == TaskResult.SUCCEEDED ? State.INSTALLED : State.UNKNOWN,
                outcome.value().orElse(null), review.id(), outcome.problem().orElse(null));
        } catch (IOException changed) {
            return result(item, State.FAILED, null, review.id(), ModStoreFacts.failure(changed));
        } catch (java.sql.SQLException unconfirmed) {
            return result(item, State.UNKNOWN, null, review.id(), ModStoreFacts.problem(ProblemCode.UNKNOWN,
                "MOD_STORE_INSTALL_UNCONFIRMED", "Original installation " + review.id() + " requires reconciliation"));
        }
    }

    /** Rechecks real pending maintenance rather than trusting view-local results. / 复核真实待核维护，不信任视图局部结果。
     * @param owner exact planned owner / 精确计划所有者
     * @throws Exception if original maintenance blocks acquisition / 原维护阻断获取时 */
    private void pending(String owner) throws Exception {
        for (var original : installer.scan().pending())
            if (original.owner().equals(owner)) throw new ApiException(new Problem(ProblemCode.UNKNOWN,
                "MOD_STORE_ORIGINAL_PENDING", "Original maintenance must be reconciled before acquiring this Mod",
                Map.of("operationId", original.id().toString(), "operationType", original.type()), Optional.empty()));
    }

    /** Finds the exact originally verified reuse candidate. / 查找精确原核验复用候选。
     * @param key original identity / 原身份
     * @return original verified observation / 原已核验观察
     * @throws IOException if original reuse evidence is absent / 原复用依据缺失时 */
    private ModStoreCatalog.Installed original(ReleaseKey key) throws IOException {
        return catalog.installed().stream().filter(value -> value.manifest().id().equals(key.owner())
                && value.manifest().version().toString().equals(key.version())).findFirst()
            .orElseThrow(() -> new IOException("Original installed evidence absent"));
    }

    /** Computes readiness separately from returned installation receipts. / 独立于返回安装凭据计算就绪状态。
     * @return complete actual outcome / 完整实际结果 */
    private ModStoreBatchResult finish() {
        var ready = new HashSet<ReleaseKey>();
        for (int index = 0; index < reports.size(); index++) {
            var report = reports.get(index);
            if (report.state() != State.REUSED && report.state() != State.INSTALLED) continue;
            try {
                var actual = installed.read(report.key());
                var item = plan.items().stream().filter(value -> value.key().equals(report.key())).findFirst().orElseThrow();
                if (!ModStoreFacts.same(actual.manifest(), item.manifest()))
                    throw new IOException("Actual READY declaration changed");
                ready.add(report.key());
            } catch (Exception failure) {
                reports.set(index, new PackageResult(report.key(), report.state(), report.receipt(), report.operationId(),
                    Optional.of(ModStoreFacts.failure(failure))));
            }
        }
        observations.accept(id, List.copyOf(reports));
        boolean required = plan.items().stream().filter(Item::requiredForRoot).allMatch(item -> ready.contains(item.key()));
        boolean all = plan.items().stream().allMatch(item -> ready.contains(item.key()));
        TaskResult outcome = reports.stream().anyMatch(report -> report.state() == State.UNKNOWN
            || report.problem().filter(problem -> problem.code() == ProblemCode.UNKNOWN).isPresent()) ? TaskResult.UNKNOWN
            : all ? TaskResult.SUCCEEDED : cancellation.requested() || reports.stream().anyMatch(report -> report.state() == State.CANCELLED)
            ? TaskResult.CANCELLED : TaskResult.FAILED;
        return new ModStoreBatchResult(id, plan.revision(), plan.selection(), outcome, reports, required, all);
    }

    /** Publishes actual stage observations only. / 仅发布实际阶段观察。
     * @param item current item / 当前项
     * @param stage actual stage / 实际阶段
     * @param completed actual received bytes / 实际收到字节
     * @param total exact package bytes / 精确包字节 */
    private void signal(Item item, Kind stage, long completed, long total) {
        progress.accept(new ModStoreProgress(id, plan.revision(), item.key(), reports.size(), plan.items().size(), stage, completed, total));
    }

    /** Creates one real package result. / 创建一个真实包结果。
     * @param item original planned item / 原计划项
     * @param state actual state / 实际状态
     * @param receipt actual receipt or absent / 实际凭据或缺省
     * @param operation original operation UUID or absent / 原操作 UUID 或缺省
     * @param problem actual failure or absent / 实际失败或缺省
     * @return immutable original facts / 不可变原事实 */
    private static PackageResult result(Item item, State state, PlatformModInstallation.Receipt receipt, UUID operation, Problem problem) {
        return new PackageResult(item.key(), state, Optional.ofNullable(receipt), Optional.ofNullable(operation), Optional.ofNullable(problem));
    }
}
