package gold.debug.windowstolinux.shared.analyze.ecosystem.c.cmake;

import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.util.List;
import java.util.Set;

/** Fixed CMake preset, target, source-language, and rejection facts. / 固定的 CMake preset、目标、源码语言与拒绝事实。 */
public record CmakeFacts(
        String preset,
        String target,
        Set<SourceLanguageType> sourceLanguages,
        List<String> targetSources,
        List<String> missingItems,
        List<String> conflicts
) {
    /** Makes CMake architecture facts immutable. / 使 CMake 架构事实不可变。 */
    public CmakeFacts {
        sourceLanguages = Set.copyOf(sourceLanguages);
        targetSources = List.copyOf(targetSources);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
    }
}
