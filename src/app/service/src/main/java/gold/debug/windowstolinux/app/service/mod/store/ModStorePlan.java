package gold.debug.windowstolinux.app.service.mod.store;

import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;

/** One revision-bound complete acquisition proposal. / 一份修订绑定的完整获取计划。
 * @param revision service plan revision / 服务计划修订
 * @param catalogRevision original catalog revision / 原目录修订
 * @param selection unchanged explicit choices / 未擅改的明确选择
 * @param items resolved dependency-first items / 按依赖先序解析的项
 * @param relations actual dependency relationships / 实际依赖关系
 * @param conflicts specific unsatisfied requirements / 具体未满足要求
 */
public record ModStorePlan(long revision, long catalogRevision, ModStoreSelection selection,
                           List<Item> items, List<Relation> relations, List<Conflict> conflicts) {
    /** Copies immutable proposal facts. / 复制不可变计划事实。
     * @param revision service plan revision / 服务计划修订
     * @param catalogRevision original catalog revision / 原目录修订
     * @param selection explicit choices / 明确选择
     * @param items resolved items / 解析项
     * @param relations actual relationships / 实际关系
     * @param conflicts specific conflicts / 具体冲突
     */
    public ModStorePlan {
        items = List.copyOf(items);
        relations = List.copyOf(relations);
        conflicts = List.copyOf(conflicts);
    }

    /** Reports whether every selected requirement was resolved. / 报告全部选中要求是否已解析。
     * @return true only for a conflict-free proposal / 仅无冲突计划为真 */
    public boolean valid() {
        return conflicts.isEmpty();
    }

    /** Physical reuse or actual remote acquisition. / 物理复用或实际远端获取。 */
    public enum Source {
        /** Proposes reuse of a registered READY program whose actual files were verified. / 计划复用实际文件已核验的已登记 READY 程序。 */
        INSTALLED,
        /** Proposes acquisition of the exact release from the original catalog source. / 计划从原目录来源获取精确发布。 */
        REMOTE
    }

    /** One exact proposed program and its root-required role. / 一个精确计划程序及其主项必需角色。
     * @param key exact release identity / 精确发布身份
     * @param manifest complete actual candidate declaration / 候选完整实际声明
     * @param source verified source kind / 已核验来源类型
     * @param requiredForRoot whether root base functionality requires this program / 主项基础功能是否需要此程序
     */
    public record Item(ReleaseKey key, ModManifest manifest, Source source, boolean requiredForRoot) {
    }

    /** One explicit dependency edge, including unselected optional choices. / 一条明确依赖边，包含未选可选项。
     * @param key stable consumer-provider relation / 稳定消费提供关系
     * @param consumerVersion exact consumer release / 精确消费发布
     * @param range original supported range / 原受支持范围
     * @param optional author-declared optional flag / 作者声明可选标志
     * @param selected whether this relationship participates / 此关系是否参与
     * @param purpose catalog-provided purpose or empty / 目录提供用途或空
     * @param resolved actual selected provider release / 实际选定提供发布
     */
    public record Relation(OptionalKey key, String consumerVersion, String range, boolean optional,
                           boolean selected, String purpose, Optional<ReleaseKey> resolved) {
    }

    /** One original constraint contributing to a conflict. / 一个导致冲突的原约束。
     * @param consumer requiring Mod identity / 要求方 Mod 身份
     * @param dependency required provider identity / 要求提供方身份
     * @param range original accepted range / 原接受范围
     */
    public record Constraint(String consumer, String dependency, String range) {
    }

    /** An explicit conflict; no package can be acquired from this proposal. / 明确冲突，此计划不能获取任何包。
     * @param code stable diagnostic code / 稳定诊断代码
     * @param owner affected Mod identity / 受影响 Mod 身份
     * @param constraints actual participating constraints / 实际参与约束
     * @param detail bounded non-secret explanation / 有界非秘密说明
     */
    public record Conflict(String code, String owner, List<Constraint> constraints, String detail) {
        /** Preserves all contributing requirements. / 保留全部参与要求。
         * @param code diagnostic code / 诊断代码
         * @param owner affected identity / 受影响身份
         * @param constraints participating requirements / 参与要求
         * @param detail non-secret explanation / 非秘密说明
         */
        public Conflict {
            constraints = List.copyOf(constraints);
        }
    }
}
