package gold.debug.windowstolinux.shared.analyze.ecosystem.c.cmake;

import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.util.List;
import java.util.Set;

/**
 * Fixed CMake preset, target, source-language, and rejection facts. / 固定的 CMake preset、目标、源码语言与拒绝事实。
 *
 * @param preset preset / 预设
 * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
 * @param sourceLanguages observed source languages / 观察到的源码语言
 * @param targetSources target sources / 目标源码集合
 * @param missingItems missing items / 缺失项目集合
 * @param conflicts the observed conflicting facts / 观察到的冲突事实
 */
public record CmakeFacts(
        String preset,
        String target,
        Set<SourceLanguageType> sourceLanguages,
        List<String> targetSources,
        List<String> missingItems,
        List<String> conflicts
) {
    /**
     * Makes CMake architecture facts immutable. / 使 CMake 架构事实不可变。
     *
     * @param preset preset / 预设
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param sourceLanguages observed source languages / 观察到的源码语言
     * @param targetSources target sources / 目标源码集合
     * @param missingItems missing items / 缺失项目集合
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     */
    public CmakeFacts {
        sourceLanguages = Set.copyOf(sourceLanguages);
        targetSources = List.copyOf(targetSources);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
    }
}
