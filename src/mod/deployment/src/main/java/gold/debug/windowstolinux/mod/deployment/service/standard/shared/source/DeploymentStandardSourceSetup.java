package gold.debug.windowstolinux.mod.deployment.service.standard.shared.source;

import java.util.*;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiver;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentProjectSourceRecorder;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;

/** Freezes graph source archives and releases only the original verified preparation resources. / 冻结图来源归档，仅释放原已确认准备资源。 */
public final class DeploymentStandardSourceSetup {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentStandardSourceSetup() {
    }

    /** Owns even a partially prepared original source so later validation failures retain exact cleanup. / 持有部分准备原来源，使后续校验失败仍保留精确清理。 */
    public static final class Capture {
        /** Actual still-owned source, assigned immediately after acquisition. / 取得后立即赋值的实际仍所属来源。 */
        private DeploymentSourceMaterial material;

        /** Original optional-provider journal and material. / 原可选提供方日志及素材。 */
        private DeploymentGitSourceFlow.PublishSource pinned;

        /** Reviewed same-source local project metadata. / 已审阅同源本地项目元数据。 */
        private Optional<DeploymentProjectRecord> localProject = Optional.empty();

        /** Source-bound logical name. / 来源绑定逻辑名。 */
        private String logicalName;

        /** Creates no source or task. / 不创建来源或任务。 */
        public Capture() {
        }

        /** Reauthorizes the original route and captures one frozen source. / 重新授权原路径并捕获一份冻结来源。
         * @param context original owner capabilities / 原所属能力
         * @param draft captured metadata and limits / 抓取元数据及限额
         * @param git optional original Git route / 可选原 Git 路径
         * @param task original accepted task / 原已接纳任务
         * @throws ApiException if original rememberAuthorization or capture is unconfirmed / 原授权或捕获未确认时 */
        public void prepare(ModContext context, DeploymentStandardInputs.Draft draft,
                            Optional<DeploymentStandardInputs.GitSource> git, TaskContext task) throws ApiException {
            if (material != null || pinned != null) throw new IllegalStateException("Original source already captured");
            if (git.isPresent()) {
                var source = git.orElseThrow();
                var flow = new DeploymentGitSourceFlow(context);
                var edit = draft.sourceOptions().project();
                pinned = edit.isPresent() ? flow.prepareForPublication(source.request(), source.executable(),
                    edit.orElseThrow().displayName(), draft.sourceBytes(), draft.sourceEntries(), task,
                    edit.orElseThrow().id(), edit.orElseThrow().expected(), edit.orElseThrow().observedAt())
                    : flow.prepareForPublication(source.request(), source.executable(), draft.logicalRootName(),
                    draft.sourceBytes(), draft.sourceEntries(), task);
                material = pinned.material().source();
                logicalName = draft.logicalRootName();
            } else {
                var selection = draft.sourceOptions().localLocation().isPresent()
                    ? context.file().sources().selectDirectory(java.nio.file.Path.of(draft.sourceOptions().localLocation().orElseThrow()), task)
                    : context.file().sources().selectDirectory("Deployment source", task);
                if (task.cancelToken().requested())
                    throw new java.util.concurrent.CancellationException("Original source selection cancelled");
                material = new DeploymentSourceSetup(context.file()).prepare(selection, draft.sourceBytes(), draft.sourceEntries(), task);
                logicalName = draft.logicalRootName().isBlank() ? selection.displayName() : draft.logicalRootName();
                if (draft.sourceOptions().project().isPresent()) {
                    var edit = draft.sourceOptions().project().orElseThrow();
                    localProject = Optional.of(new DeploymentProjectSourceRecorder(context).localRecord(task, selection,
                        material, edit.id(), edit.displayName(), edit.expected(), edit.observedAt()));
                }
            }
        }

        /** Returns the actual original capture for cleanup even after a failed edit. / 即使编辑失败也返回实际原捕获供清理。
         * @return actual source or not yet captured / 实际来源或尚未捕获 */
        public DeploymentSourceMaterial material() {
            return material;
        }

        /** Returns the exact original optional source evidence. / 返回精确原可选来源依据。
         * @return original journal or local route / 原日志或本地路径 */
        public DeploymentGitSourceFlow.PublishSource pinned() {
            return pinned;
        }

        /** Returns a complete row from this exact source. / 返回本精确来源的完整行。
         * @return selected same-source edit / 所选同源编辑 */
        public Optional<DeploymentProjectRecord> localProject() {
            return localProject;
        }

        /** Returns the source-bound logical name. / 返回来源绑定逻辑名。
         * @return captured name / 抓取名称 */
        public String logicalName() {
            return logicalName;
        }
    }

    /** Keeps original tar handles, exact immutable byte identities and their unchanged owner review. / 保留原 tar 句柄、精确不可变字节身份及未改变的所属审阅。
     * @param archives complete owned component archives / 完整所属组件归档
     * @param digests exact component tar digests / 精确组件 tar 摘要
     * @param consent actual original owner decision / 实际原所属决定 */
    public record ArchivedParts(Map<String, DeploymentSourceArchive> archives, Map<String, String> digests) {
        /** Copies the complete views and preserves the real decision without reopening source. / 复制完整视图并保留真实决定，不重开来源。
         * @param archives owned original tar handles / 所属原tar句柄
         * @param digests their exact byte digests / 其精确字节摘要
         * @param consent original owner decision / 原所属决定 */
        public ArchivedParts {
            archives = Map.copyOf(archives);
            digests = Map.copyOf(digests);

        }
    }

    /** Retains every actual archive immediately so failed graph preparation keeps exact original cleanup ownership. / 立即留存每份实际归档，使失败图准备保留精确原清理归属。
     * @param material original frozen material / 原冻结素材
     * @param source original platform source capability / 原平台来源能力
     * @param components original discovered roots / 原发现根
     * @param limit original archive byte limit / 原归档字节限额
     * @param owned original preparation cleanup list / 原准备清理列表
     * @param task authentic original task / 真实原任务
     * @return complete component handles and frozen byte identities / 完整组件句柄与冻结字节身份
     * @throws Exception if original archive creation fails / 原归档创建失败时 */
    public static ArchivedParts archiveParts(DeploymentSourceMaterial material, FileService source,
                                             List<DiscoveredProjectPart> components, long limit, List<DeploymentSourceArchive> owned,
                                             TaskContext task) throws Exception {
        var result = new LinkedHashMap<String, DeploymentSourceArchive>();
        var digests = new LinkedHashMap<String, String>();
        for (var component : components) {
            String root = component.relativeRoot().toString().replace('\\', '/');
            var archive = new DeploymentSourceArchiver(source).prepare(material, root, limit, task);
            owned.add(archive);
            result.put(component.id(), archive);
            digests.put(component.id(), archive.sha256());
        }
        return new ArchivedParts(result, digests);
    }

    /** Releases owned preparation resources only after their actual prior cleanup is known. / 仅在先前清理确知后释放所属准备资源。
     * @param archives child archives still owned here / 此处仍持有的组件归档
     * @param material original frozen source / 原冻结来源
     * @param pinned optional original Git material and checkpoint / 可选原Git素材及检查点
     * @param marker original preparation marker / 原准备标记
     * @param retainMarker unresolved effects retain all original resources / 未决效果保留全部原资源
     * @throws Exception if any original release is unconfirmed / 任一原释放未确认时 */
    public static void close(List<DeploymentSourceArchive> archives, DeploymentSourceMaterial material,
                             DeploymentGitSourceFlow.PublishSource pinned, DeploymentStandardSetupActionLog marker,
                             boolean retainMarker) throws Exception {
        if (retainMarker) return;
        Exception cleanup = null;
        for (var archive : archives)
            try {
                archive.close();
            } catch (Exception failure) {
                if (cleanup == null) cleanup = failure;
                else cleanup.addSuppressed(failure);
            }
        try {
            if (material != null) material.close();
            if (pinned != null) pinned.close();
        } catch (Exception failure) {
            if (cleanup == null) cleanup = failure;
            else cleanup.addSuppressed(failure);
        }
        if (cleanup == null) try {
            marker.close();
        } catch (Exception failure) {
            cleanup = failure;
        }
        if (cleanup != null) throw cleanup;
    }
}
