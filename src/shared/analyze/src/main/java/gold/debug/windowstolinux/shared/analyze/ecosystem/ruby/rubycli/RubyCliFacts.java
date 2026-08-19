package gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.rubycli;

import java.util.List;

/** Fixed dependency-free Ruby CLI architecture facts. / 固定的无依赖 Ruby CLI 架构事实。 */
public record RubyCliFacts(String version, String entrypoint, List<String> missingItems) {
    /** Makes missing facts immutable. / 使缺失事实不可变。 */
    public RubyCliFacts { missingItems = List.copyOf(missingItems); }
}
