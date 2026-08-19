package gold.debug.windowstolinux.shared.analyze.service;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeArchitectureInspectionTest {
    @TempDir Path temporaryDirectory;
    private int sequence;

    @Test
    void admitsEveryDependencyFreeNativeArchitecture() throws Exception {
        for (Fixture fixture : List.of(javaSource(), kotlin(), php(), ruby(), cmake())) {
            DeploymentProjectAssessment assessment = analyze(fixture);
            assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission(), fixture.type().name());
            assertEquals(fixture.tool(), assessment.facts().orElseThrow().buildTool(), fixture.type().name());
            assertTrue(assessment.facts().orElseThrow().conflicts().isEmpty(), fixture.type().name());
            assertTrue(assessment.facts().orElseThrow().missingInformation().isEmpty(), fixture.type().name());
        }
        var cmakeFacts = analyze(cmake()).facts().orElseThrow().languageFacts();
        assertTrue(cmakeFacts.sourceLanguages().containsAll(List.of(SourceLanguageType.C, SourceLanguageType.CPP)));
    }

    @Test
    void stopsEachNativeArchitectureOnExternalOrAmbiguousBuildInputs() throws Exception {
        Fixture java = javaSource();
        write(java.root(), "pom.xml", "<project/>\n");
        Fixture kotlin = kotlin();
        write(kotlin.root(), "build.gradle.kts", "plugins { kotlin(\"jvm\") version \"2.0.21\" }\n");
        Fixture php = php();
        write(php.root(), "composer.json", "{}\n");
        Fixture ruby = ruby();
        write(ruby.root(), "Gemfile", "source \"https://example.test\"\n");
        Fixture cmake = cmake();
        Files.writeString(cmake.root().resolve("CMakeLists.txt"), Files.readString(cmake.root().resolve("CMakeLists.txt"))
                + "\nFetchContent_Declare(remote URL https://example.test/archive.tar.gz)\n"
                + "add_executable(second src/main.c)\n");

        for (Fixture fixture : List.of(java, kotlin, php, ruby, cmake)) {
            DeploymentProjectAssessment assessment = analyze(fixture);
            assertFalse(assessment.admission() == DeploymentAdmissionStatus.READY_FOR_PLANNING,
                    fixture.type().name());
        }
    }

    private DeploymentProjectAssessment analyze(Fixture fixture) throws Exception {
        return new DeploymentAnalysisCoordinator().analyze(fixture.root(), fixture.type());
    }

    private Fixture javaSource() throws Exception {
        Path root = root("java-native");
        write(root, "windowstolinux-java.properties", "sourceRoot=src\nmainClass=demo.Main\njavaVersion=21\n");
        write(root, "src/demo/Main.java", "package demo;\npublic final class Main { public static void main(String[] args) {} }\n");
        return new Fixture(root, DeploymentProjectType.JAVA_SOURCE, DeploymentBuildToolType.JDK);
    }

    private Fixture kotlin() throws Exception {
        Path root = root("kotlin-native");
        write(root, "windowstolinux-kotlin.properties",
                "compilerVersion=2.0.21\nsourceRoot=src\nmainClass=demo.MainKt\njvmTarget=21\n");
        write(root, "src/demo/Main.kt", "package demo\nfun main() {}\n");
        return new Fixture(root, DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildToolType.KOTLINC);
    }

    private Fixture php() throws Exception {
        Path root = root("php-native");
        write(root, "windowstolinux-php.properties",
                "phpVersion=8.3\ndocumentRoot=public\nentrypoint=public/index.php\n");
        write(root, "public/index.php", "<?php echo 'ok';\n");
        return new Fixture(root, DeploymentProjectType.PHP_SERVICE, DeploymentBuildToolType.PHP_CLI);
    }

    private Fixture ruby() throws Exception {
        Path root = root("ruby-native");
        write(root, "windowstolinux-ruby.properties", "rubyVersion=3.3.5\nentrypoint=server.rb\n");
        write(root, "server.rb", "require 'socket'\nputs 'ok'\n");
        return new Fixture(root, DeploymentProjectType.RUBY_SERVICE, DeploymentBuildToolType.RUBY_CLI);
    }

    private Fixture cmake() throws Exception {
        Path root = root("cmake-native");
        write(root, "CMakeLists.txt", """
                cmake_minimum_required(VERSION 3.25)
                project(demo LANGUAGES C CXX)
                add_executable(demo src/main.c src/helper.cpp)
                target_compile_features(demo PRIVATE c_std_17 cxx_std_20)
                """);
        write(root, "CMakePresets.json", """
                {
                  "version": 6,
                  "configurePresets": [{
                    "name": "w2l-release",
                    "generator": "Ninja",
                    "binaryDir": "${sourceDir}/.w2l/cmake-build",
                    "cacheVariables": {"CMAKE_BUILD_TYPE": "Release"}
                  }],
                  "buildPresets": [{"name": "w2l-release-build", "configurePreset": "w2l-release"}]
                }
                """);
        write(root, "src/main.c", "int helper(void); int main(void) { return helper(); }\n");
        write(root, "src/helper.cpp", "extern \"C\" int helper(void) { return 0; }\n");
        return new Fixture(root, DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE);
    }

    private Path root(String name) throws Exception {
        return Files.createDirectory(temporaryDirectory.resolve(name + "-" + sequence++));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private record Fixture(Path root, DeploymentProjectType type, DeploymentBuildToolType tool) {
    }
}
