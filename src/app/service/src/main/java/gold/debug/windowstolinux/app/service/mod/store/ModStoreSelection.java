package gold.debug.windowstolinux.app.service.mod.store;

import java.util.Map;
import java.util.Set;

/** Explicit immutable user choices; optional relationships never disappear silently. / 明确不可变用户选择，可选关系不会静默消失。
 * @param root fixed root release / 固定主发布
 * @param optionals selected optional relationships / 选中的可选关系
 * @param pins exact user version pins / 用户精确版本固定
 */
public record ModStoreSelection(ReleaseKey root, Set<OptionalKey> optionals, Map<String, String> pins) {
    /** Copies the confirmed choice collections. / 复制已确认选择集合。
     * @param root fixed root release / 固定主发布
     * @param optionals selected relationships / 选中关系
     * @param pins exact version pins / 精确版本固定
     */
    public ModStoreSelection {
        java.util.Objects.requireNonNull(root);
        optionals = Set.copyOf(optionals);
        pins = Map.copyOf(pins);
        pins.keySet().forEach(ModStoreFacts::owner);
        pins.values().forEach(gold.debug.windowstolinux.app.mod.version.SemanticVersion::parse);
    }

    /** Exact installed or remote release identity. / 精确已装或远端发布身份。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version canonical release / 规范发布版本
     */
    public record ReleaseKey(String owner, String version) {
        /** Validates the exact identity before planning. / 计划前验证精确身份。
         * @param owner exact Mod identity / 精确 Mod 身份
         * @param version canonical release / 规范发布版本
         */
        public ReleaseKey {
            ModStoreFacts.owner(owner);
            gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse(version);
        }
    }

    /** Stable optional relationship across consumer version changes. / 跨消费方版本变化的稳定可选关系。
     * @param consumer declaring Mod identity / 声明关系的 Mod 身份
     * @param dependency provider Mod identity / 提供方 Mod 身份
     */
    public record OptionalKey(String consumer, String dependency) {
        /** Validates both declared relationship identities. / 验证两端声明关系身份。
         * @param consumer declaring Mod identity / 声明关系的 Mod 身份
         * @param dependency provider identity / 提供方身份
         */
        public OptionalKey {
            ModStoreFacts.owner(consumer);
            ModStoreFacts.owner(dependency);
        }
    }
}
