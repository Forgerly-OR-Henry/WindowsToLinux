package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.*;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.source.*;

/** Freezes graph source archives and releases only the original verified preparation resources. / 冻结图来源归档，仅释放原已确认准备资源。 */
final class DeploymentStaticSourcePreparation {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentStaticSourcePreparation() {}

    /** Keeps both original tar handles and their exact immutable byte identities. / 同时保留原tar句柄及其精确不可变字节身份。
     * @param archives complete owned component archives / 完整所属组件归档
     * @param digests exact component tar digests / 精确组件tar摘要 */
    record ArchivedComponents(Map<String, DeploymentSourceArchive> archives, Map<String, String> digests) {
        /** Copies the complete two views without reopening source. / 复制完整双视图，不重开来源。
         * @param archives owned original tar handles / 所属原tar句柄
         * @param digests their exact byte digests / 其精确字节摘要 */
        ArchivedComponents { archives = Map.copyOf(archives); digests = Map.copyOf(digests); }
    }

    /** Retains every actual archive immediately so failed graph preparation keeps exact original cleanup ownership. / 立即留存每份实际归档，使失败图准备保留精确原清理归属。
     * @param material original frozen material / 原冻结素材
     * @param source original platform source capability / 原平台来源能力
     * @param components original discovered roots / 原发现根
     * @param limit original archive byte limit / 原归档字节限额
     * @param owned original preparation cleanup list / 原准备清理列表
     * @param task authentic original task / 真实原任务
     * @return complete component handles and byte identities / 完整组件句柄及字节身份
     * @throws Exception if original archive creation fails / 原归档创建失败时 */
    static ArchivedComponents archives(DeploymentSourceMaterial material, SourceService source,
            List<DiscoveredProjectComponent> components, long limit, List<DeploymentSourceArchive> owned,
            TaskContext task) throws Exception {
        var result = new LinkedHashMap<String, DeploymentSourceArchive>();
        var digests = new LinkedHashMap<String, String>();
        for (var component : components) {
            String root = component.relativeRoot().toString().replace('\\', '/');
            var archive = new DeploymentSourceArchiver(source).prepare(material, root, limit, task);
            owned.add(archive); result.put(component.id(), archive); digests.put(component.id(), archive.sha256());
        }
        return new ArchivedComponents(result, digests);
    }

    /** Releases owned preparation resources only after their actual prior cleanup is known. / 仅在先前清理确知后释放所属准备资源。
     * @param archives child archives still owned here / 此处仍持有的组件归档
     * @param material original frozen source / 原冻结来源
     * @param pinned optional original Git material and checkpoint / 可选原Git素材及检查点
     * @param marker original preparation marker / 原准备标记
     * @param retainMarker unresolved effects retain all original resources / 未决效果保留全部原资源
     * @throws Exception if any original release is unconfirmed / 任一原释放未确认时 */
    static void close(List<DeploymentSourceArchive> archives, DeploymentSourceMaterial material,
            DeploymentGitSourceFlow.PublicationSource pinned, DeploymentStaticPreparationJournal marker,
            boolean retainMarker) throws Exception {
        if (retainMarker) return;
        Exception cleanup = null;
        for (var archive : archives) try { archive.close(); }
        catch (Exception failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
        try { if (material != null) material.close(); if (pinned != null) pinned.close(); }
        catch (Exception failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
        if (cleanup == null) try { marker.close(); } catch (Exception failure) { cleanup = failure; }
        if (cleanup != null) throw cleanup;
    }
}
