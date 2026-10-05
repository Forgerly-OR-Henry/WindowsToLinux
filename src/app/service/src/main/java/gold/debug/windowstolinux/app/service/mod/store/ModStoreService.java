package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreBatchResult.PackageResult;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Composes actual catalog, global proposals and original installation without enabling programs. / 装配实际目录、全局计划及原安装，不启用程序。 */
public final class ModStoreService {
    /** Original configured source. / 原配置来源。 */
    private final Optional<URI> base;

    /** Actual catalog and package transport. / 实际目录及包传输。 */
    private final ModStoreGateway gateway;

    /** Original installer, absent in CLASS. / 原安装器，CLASS 中缺省。 */
    private final Optional<PlatformModInstallation> installation;

    /** Actual accepted-operation boundary. / 实际已接纳操作边界。 */
    private final DesktopSecurityService security;

    /** Actual installed byte verifier. / 实际安装字节核验器。 */
    private final ModStoreInstalled installed;

    /** Actual compiled API version. / 实际编译 API 版本。 */
    private final SemanticVersion api;

    /** Original snapshot/plan sequence under this monitor. / 此监视器下的原快照及计划序列。 */
    private long sequence;

    /** Current exact complete source observations. / 当前精确完整来源观察。 */
    private ModStoreCatalog catalog;

    /** Current unconsumed internally owned proposal. / 当前未消费内部所属计划。 */
    private ModStorePlan plan;

    /** Whether an original worker currently owns this service. / 原工作线程是否当前持有此服务。 */
    private boolean busy;

    /** Last complete returned batch, never a synthetic in-flight UNKNOWN. / 最近完整返回批次，不是伪造进行中 UNKNOWN。 */
    private volatile ModStoreBatchResult last;

    /** Current or last exact immutable per-package facts. / 当前或最近精确不可变逐包事实。 */
    private volatile Observation observation;

    /** One bounded real batch observation. / 一个有界真实批次观察。
     * @param id original batch identity / 原批次身份
     * @param reports actual returned package facts / 实际返回包事实 */
    private record Observation(UUID id, List<PackageResult> reports) {
    }

    /** Captures actual host inputs without database or network work on construction. / 构造时捕获实际宿主输入，不访问数据库或网络。
     * @param base configured source / 配置来源
     * @param gateway actual transport / 实际传输
     * @param installation original installer or absent / 原安装器或缺省
     * @param security actual accepted-operation owner / 实际已接纳操作所有者
     * @param database original platform database / 原平台数据库
     * @param applicationHome exact installed application home / 精确安装应用根
     * @param actualApi actual compiled API / 实际编译 API */
    public ModStoreService(Optional<URI> base, ModStoreGateway gateway, Optional<PlatformModInstallation> installation,
                           DesktopSecurityService security, PlatformDatabase database, Path applicationHome, SemanticVersion actualApi) {
        this.base = Objects.requireNonNull(base);
        this.gateway = Objects.requireNonNull(gateway);
        this.installation = Objects.requireNonNull(installation);
        this.security = Objects.requireNonNull(security);
        this.installed = new ModStoreInstalled(database, applicationHome);
        api = Objects.requireNonNull(actualApi);
    }

    /** Reports actual installation capability without conflating source modes. / 报告实际安装能力，不混淆来源模式。
     * @return false when no release installer exists / 无发行安装器时为假 */
    public boolean canInstall() {
        return installation.isPresent();
    }

    /** Fetches complete remote and physically verified installed observations once. / 一次获取完整远端及物理核验安装观察。
     * @param cancellation original cooperative request / 原协作请求
     * @return immutable actual source snapshot / 不可变实际来源快照
     * @throws Exception if admission, transport or inventory fails / 准入、传输或清单失败时 */
    public ModStoreCatalog catalog(CancelToken cancellation) throws Exception {
        enter();
        try (var operation = security.enterOperation()) {
            URI source = base.orElseThrow(UnavailableModStoreGateway::unavailable);
            var remote = gateway.catalog(source, cancellation);
            var actual = installed.read(cancellation);
            var issues = new ArrayList<>(actual.issues());
            if (installation.isPresent())
                for (var original : installation.get().scan().pending())
                    issues.add(new ModStoreCatalog.InstalledIssue(new ReleaseKey(original.owner(), original.version()),
                        new Problem(ProblemCode.UNKNOWN, "MOD_STORE_ORIGINAL_PENDING", "Original maintenance requires reconciliation",
                            Map.of("operationId", original.id().toString(), "operationType", original.type()), Optional.empty())));
            ModStoreFacts.cancellation(cancellation);
            synchronized (this) {
                catalog = new ModStoreCatalog(++sequence, source, remote.releases(), actual.installed(), issues);
                plan = null;
                return catalog;
            }
        } finally {
            leave();
        }
    }

    /** Resolves exactly the requested snapshot and user intent without acquisition effects. / 无获取效果地解析精确请求快照及用户意图。
     * @param catalogRevision exact observed source revision / 精确观察来源修订
     * @param selection complete explicit user choices / 完整明确用户选择
     * @param cancellation original cooperative request / 原协作请求
     * @return original revision-bound proposal / 原修订绑定计划
     * @throws Exception if stale, locked or cancelled / 过期、锁定或取消时 */
    public ModStorePlan plan(long catalogRevision, ModStoreSelection selection, CancelToken cancellation) throws Exception {
        enter();
        try (var operation = security.enterOperation()) {
            ModStoreCatalog sources;
            long revision;
            synchronized (this) {
                plan = null;
                if (catalog == null || catalog.revision() != catalogRevision) throw stale();
                sources = catalog;
                revision = ++sequence;
            }
            var resolved = new ModStoreResolver(sources, selection, api, Runtime.version().feature(), cancellation, 500000).resolve(revision);
            ModStoreFacts.cancellation(cancellation);
            synchronized (this) {
                plan = resolved;
            }
            return resolved;
        } finally {
            leave();
        }
    }

    /** Consumes the exact valid proposal once, preserving complete real outcomes. / 一次消费精确有效计划，保留完整真实结果。
     * @param planRevision exact confirmed proposal / 精确确认计划
     * @param cancellation cooperative request / 协作请求
     * @param progress actual progress observer / 实际进度观察者
     * @return complete actual batch facts / 完整实际批次事实
     * @throws Exception if no valid original proposal is admitted / 未准入有效原计划时 */
    public ModStoreBatchResult install(long planRevision, CancelToken cancellation, Consumer<ModStoreProgress> progress) throws Exception {
        enter();
        try (var operation = security.enterOperation()) {
            var installer = installation.orElseThrow(() -> new IllegalStateException("CLASS mode does not install release packages"));
            ModStorePlan confirmed;
            ModStoreCatalog sources;
            synchronized (this) {
                if (plan == null || plan.revision() != planRevision || !plan.valid() || catalog.revision() != plan.catalogRevision())
                    throw stale();
                confirmed = plan;
                sources = catalog;
                plan = null;
            }
            last = new ModStoreBatch(installer, gateway, installed, sources, confirmed, cancellation, progress,
                (id, reports) -> observation = new Observation(id, reports)).run();
            return last;
        } finally {
            leave();
        }
    }

    /** Reconciles only the original retained installation UUID. / 仅核对原保留安装 UUID。
     * @param originalOperation original installer operation / 原安装器操作
     * @return original actual result / 原实际结果
     * @throws Exception if original evidence or admission is unavailable / 原依据或准入不可用时 */
    public OperationResult<PlatformModInstallation.Receipt> reconcile(UUID originalOperation) throws Exception {
        enter();
        try (var operation = security.enterOperation()) {
            return installation.orElseThrow(() -> new IllegalStateException("CLASS mode has no installer")).reconcile(originalOperation);
        } finally {
            leave();
        }
    }

    /** Returns only the last complete returned batch. / 仅返回最近完整返回批次。
     * @return actual complete facts or absent / 实际完整事实或缺省 */
    public Optional<ModStoreBatchResult> lastResult() {
        return Optional.ofNullable(last);
    }

    /** Returns real package facts saved before the original FINISHED callback. / 返回原 FINISHED 回调前保存的真实包事实。
     * @param batchId original observed batch / 原观察批次
     * @return immutable actual returned facts / 不可变实际返回事实 */
    public List<PackageResult> packageResults(UUID batchId) {
        var current = observation;
        if (current != null && current.id().equals(batchId)) return current.reports();
        var complete = last;
        return complete != null && complete.batchId().equals(batchId) ? complete.packages() : List.of();
    }

    /** Reserves one worker without holding this monitor across network or file work. / 保留一个工作线程，不跨网络或文件工作持有此监视器。 */
    private synchronized void enter() {
        if (SwingUtilities.isEventDispatchThread() || busy)
            throw new IllegalStateException("Store requires one admitted worker");
        busy = true;
    }

    /** Releases only the actual returned original worker. / 仅释放实际已返回原工作线程。 */
    private synchronized void leave() {
        busy = false;
    }

    /** Rejects stale observations rather than silently replacing user confirmation. / 拒绝过期观察，不静默替换用户确认。
     * @return explicit stale rejection / 明确过期拒绝 */
    private static ApiException stale() {
        return new ApiException(ModStoreFacts.problem(ProblemCode.CONTRACT_VIOLATION, "MOD_STORE_PLAN_STALE",
            "Catalog or proposal changed; resolve and confirm the exact list again"));
    }
}
