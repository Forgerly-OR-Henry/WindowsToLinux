package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.file.*;

/** Owned derivative snapshots; never changes the borrowed original. / 所属衍生快照，绝不修改借用原来源。 */
public final class DeploymentAgentSources implements AutoCloseable {
    /** Predicted immutable patch result held until a trusted remote receipt. / 持有至可信远端回执的预测不可变补丁结果。
     * @param material derivative material / 衍生素材
     * @param revision expected helper revision / 预期 helper 修订 */
    public record Predicted(DeploymentSourceMaterial material, String revision) implements AutoCloseable {
        /** Releases only this owned derivative. / 只释放此所属衍生素材。
         * @throws Exception cleanup failure / 清理失败 */
        @Override
        public void close() throws Exception {
            material.close();
        }
    }

    /** Original platform capabilities / 原平台能力。
     */
    private final ModContext context;

    /** Original admitted task / 原已准入任务。
     */
    private final TaskContext task;

    /** Borrowed immutable original material / 借用不可变原素材。
     */
    private final DeploymentSourceMaterial original;

    /** Current promoted component snapshots / 当前启用组件快照。
     */
    private final Map<String, DeploymentSourceMaterial> current = new HashMap<>();

    /** Owned derivative snapshot lifetime / 所属衍生快照寿命。
     */
    private final List<DeploymentSourceMaterial> retained = new ArrayList<>();

    /** Borrows the original and owns later snapshots. / 借用原素材并持有后续快照。
     * @param context platform capabilities / 平台能力
     * @param task original task / 原任务
     * @param original borrowed original / 借用原素材 */
    public DeploymentAgentSources(ModContext context, TaskContext task, DeploymentSourceMaterial original) {
        this.context = context;
        this.task = task;
        this.original = original;
    }

    /** Returns the current component material. / 返回当前组件素材。
     * @param component component identity / 组件身份
     * @return borrowed live material / 借用活动素材 */
    public DeploymentSourceMaterial material(String component) {
        return current.getOrDefault(component, original);
    }

    /** Reads only a current immutable snapshot. / 只读取当前不可变快照。
     * @param component component identity / 组件身份
     * @return current browser / 当前浏览器
     * @throws Exception unavailable source / 来源不可用 */
    public DeploymentSourceBrowser browser(String component) throws Exception {
        var material = material(component);
        return new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
    }

    /** Stages and freezes a bounded patch before remote execution. / 远端执行前暂存并冻结有界补丁。
     * @param component selected component / 选定组件
     * @param patch original patch / 原补丁
     * @return predicted owned snapshot / 预测所属快照
     * @throws Exception conflict or unavailable snapshot / 冲突或快照不可用 */
    public Predicted predict(String component, DeploymentAgentPatch patch) throws Exception {
        var source = material(component);
        var root = source.snapshot().directory();
        try (var workspace = context.file().workspaces().workspace(task)) {
            Path copy = workspace.directory();
            for (String name : source.includedFiles()) {
                Path member = DeploymentAgentSourceRevision.safe(root, name);
                Path destination = copy.resolve(name);
                Files.createDirectories(destination.getParent());
                Files.copy(member, destination);
                var dos = Files.getFileAttributeView(destination, java.nio.file.attribute.DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null) dos.setReadOnly(false);
                var posix = Files.getFileAttributeView(destination, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (posix != null) {
                    var permissions = new HashSet<>(posix.readAttributes().permissions());
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                    posix.setPermissions(permissions);
                }
            }
            String revised = DeploymentAgentSourceRevision.patch(copy, patch);
            var snapshot = context.file().snapshots().snapshot(workspace.selection(),
                new SnapshotRequest(source.includedFiles(), totalBytes(copy, source.includedFiles())), task);
            var derived = new DeploymentSourceMaterial(source.displayName(), snapshot, source.includedFiles(), source.excludedEntries());
            retained.add(derived);
            return new Predicted(derived, revised);
        }
    }

    /** Promotes only a matching helper revision. / 只启用一致的 helper 修订。
     * @param component component / 组件
     * @param predicted prepared derivative / 已准备衍生素材
     * @param actual trusted remote result / 可信远端结果 */
    public void promote(String component, Predicted predicted, String actual) {
        if (!predicted.revision().equals(actual))
            throw new IllegalStateException("Local and remote patch revisions disagree");
        current.put(component, predicted.material());
    }

    /** Measures only validated derivative members / 只计量已校验衍生成员。
     * @param root Component source root / 组件源码根
     * @param names Validated included members / 已校验包含成员
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private static long totalBytes(Path root, List<String> names) throws Exception {
        long bytes = 0;
        for (String name : names) bytes = Math.addExact(bytes, Files.size(root.resolve(name)));
        return bytes;
    }

    /** Closes task-owned derivatives, leaving original lifetime with its owner. / 关闭任务所属衍生素材，原素材寿命仍由原所属方管理。
     * @throws Exception unconfirmed cleanup / 清理未确认 */
    @Override
    public void close() throws Exception {
        for (var material : retained) material.close();
        retained.clear();
        current.clear();
    }
}
