package gold.debug.windowstolinux.shared.analyze.toolchain;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType;
import gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.*;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.*;

/** Collects bounded declarations before any release admission, without executing build files. / 不执行构建文件，在发布准入前收集有界版本声明。 */
public final class ToolchainDeclarationInspector {
    public List<ToolchainRequirement> inspect(Path root) throws IOException {
        List<ToolchainRequirement> result = new ArrayList<>();
        read(result, root, "windowstolinux-java.properties", JAVA, LANGUAGE_TARGET, "(?m)^javaVersion=([^\\r\\n]+)$");
        read(result, root, "pom.xml", JAVA, LANGUAGE_TARGET, "<(?:java.version|maven.compiler.release|maven.compiler.target)>\\s*([^<]+?)\\s*</");
        for (String build : List.of("build.gradle", "build.gradle.kts")) {
            read(result, root, build, JAVA, LANGUAGE_TARGET,
                    "(?:JavaLanguageVersion[.]of|jvmToolchain)\\s*\\(\\s*([^)]*)\\s*\\)");
            read(result, root, build, JAVA, LANGUAGE_TARGET,
                    "(?:sourceCompatibility|targetCompatibility)\\s*=\\s*(?:JavaVersion[.]VERSION_)?[\"']?([A-Za-z0-9_.-]+)");
            read(result, root, build, KOTLIN, BUILD,
                    "(?:kotlin\\s*\\(\\s*[\"']jvm[\"']\\s*\\)|id\\s*\\(?\\s*[\"']org[.]jetbrains[.]kotlin[.]jvm[\"']\\s*\\)?)\\s*version\\s*[\"']([^\"']+)[\"']");
        }
        read(result, root, "windowstolinux-kotlin.properties", KOTLIN, BUILD, "(?m)^compilerVersion=([^\\r\\n]+)$");
        read(result, root, "windowstolinux-kotlin.properties", JAVA, LANGUAGE_TARGET, "(?m)^jvmTarget=([^\\r\\n]+)$");
        read(result, root, "package.json", NODE, RUNTIME, "\"node\"\\s*:\\s*\"([^\"]+)\"");
        read(result, root, ".nvmrc", NODE, RUNTIME, "(?m)^([^\\r\\n]+)$");
        read(result, root, "pyproject.toml", PYTHON, RUNTIME, "(?m)^\\s*requires-python\\s*=\\s*[\"']([^\"']+)[\"']");
        read(result, root, "go.mod", GO, BUILD, "(?m)^toolchain\\s+(\\S+)\\s*$");
        read(result, root, "go.mod", GO, LANGUAGE_TARGET, "(?m)^go\\s+(\\S+)\\s*$");
        read(result, root, "rust-toolchain.toml", RUST, BUILD, "(?m)^\\s*channel\\s*=\\s*[\"']([^\"']+)[\"']");
        read(result, root, "Cargo.toml", RUST, LANGUAGE_TARGET, "(?m)^\\s*rust-version\\s*=\\s*[\"']([^\"']+)[\"']");
        read(result, root, "global.json", DOTNET, BUILD, "\"version\"\\s*:\\s*\"([^\"]+)\"");
        read(result, root, "composer.json", PHP, RUNTIME, "\"php\"\\s*:\\s*\"([^\"]+)\"");
        read(result, root, "windowstolinux-php.properties", PHP, RUNTIME, "(?m)^(?:phpVersion|version)=([^\\r\\n]+)$");
        read(result, root, ".ruby-version", RUBY, RUNTIME, "(?m)^([^\\r\\n]+)$");
        read(result, root, "windowstolinux-ruby.properties", RUBY, RUNTIME, "(?m)^(?:rubyVersion|version)=([^\\r\\n]+)$");
        read(result, root, "CMakeLists.txt", C, LANGUAGE_TARGET, "\\bc_std_([0-9]+)\\b");
        read(result, root, "CMakeLists.txt", CPP, LANGUAGE_TARGET, "\\bcxx_std_([0-9]+)\\b");
        return List.copyOf(result);
    }

    private static void read(List<ToolchainRequirement> target, Path root, String file, ToolchainEcosystemType ecosystem,
            ToolchainRequirement.PurposeType purpose, String pattern) throws IOException {
        Path path = root.resolve(file);
        if (!BoundedMetadataInspector.regular(path)) return;
        var matcher = Pattern.compile(pattern).matcher(BoundedMetadataInspector.read(path));
        int count = 0;
        while (matcher.find()) {
            if (++count > 32 || target.size() >= 128) throw new IOException("toolchain declaration limit exceeded");
            String value = matcher.group(1).trim();
            if (value.isEmpty() || value.length() > 1024 || value.chars().anyMatch(c -> c < 32)) continue;
            var requirement = ToolchainRequirement.declared(ecosystem, value, file, purpose);
            if ((ecosystem == GO || ecosystem == RUST) && purpose == LANGUAGE_TARGET && requirement.version().isPresent())
                requirement = new ToolchainRequirement(ecosystem, value, file, purpose,
                        ToolchainRequirement.ConstraintType.MINIMUM, requirement.version());
            target.add(requirement);
        }
    }
}
