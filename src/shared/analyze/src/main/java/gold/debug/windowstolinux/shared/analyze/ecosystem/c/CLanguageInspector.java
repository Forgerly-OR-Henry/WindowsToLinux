package gold.debug.windowstolinux.shared.analyze.ecosystem.c;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.source.SourceLanguageEvidence;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.util.Locale;
import java.util.Optional;

/**
 * Detects C and C++ markers independently of CMake. / 独立于 CMake 识别 C 与 C++ 语言标记。
 */
public final class CLanguageInspector {
    /**
     * Returns source and header markers without selecting a build target. / 返回源码与头文件标记，不选择构建目标。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return source and header markers without selecting a build target / 源码与头文件标记，不选择构建目标
     */
    public ProjectLanguageFacts inspect(SourceInspectionFacts source) {
        return SourceLanguageEvidence.collect(source, LanguageEcosystemType.NATIVE, name -> {
            if (name.endsWith(".h")) return SourceLanguageType.C;
            if (name.endsWith(".hpp")) return SourceLanguageType.CPP;
            return compilationLanguage(name).orElse(null);
        });
    }

    /**
     * Classifies compilation units; headers alone do not select a target compiler. / 识别编译单元，头文件不单独决定目标编译器。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public static Optional<SourceLanguageType> compilationLanguage(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".c")) return Optional.of(SourceLanguageType.C);
        if (name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx")) {
            return Optional.of(SourceLanguageType.CPP);
        }
        return Optional.empty();
    }
}
