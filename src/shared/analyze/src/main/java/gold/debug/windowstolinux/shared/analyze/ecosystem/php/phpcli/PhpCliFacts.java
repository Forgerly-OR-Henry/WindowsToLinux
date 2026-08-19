package gold.debug.windowstolinux.shared.analyze.ecosystem.php.phpcli;

import java.util.List;

/** Fixed dependency-free PHP CLI architecture facts. / 固定的无依赖 PHP CLI 架构事实。 */
public record PhpCliFacts(String version, String documentRoot, String entrypoint, List<String> missingItems) {
    /** Makes missing facts immutable. / 使缺失事实不可变。 */
    public PhpCliFacts { missingItems = List.copyOf(missingItems); }
}
