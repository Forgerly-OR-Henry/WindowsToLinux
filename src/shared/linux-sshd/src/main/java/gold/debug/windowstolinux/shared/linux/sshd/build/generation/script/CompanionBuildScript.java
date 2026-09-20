package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload;

/** Companion artifacts remain in the same bounded candidate and sealed release as the owner. */
public final class CompanionBuildScript {
    private CompanionBuildScript() { }
    public static String render(ApplicationWorkload workload) {
        StringBuilder script = new StringBuilder();
        for (var unit : workload.companions()) {
            if (unit.projectType() != gold.debug.windowstolinux.shared.model.project.application.ApplicationCompanion.BuildType.CMAKE_SERVICE
                    || !unit.artifactPath().matches("\\.w2l/bin/[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                throw new IllegalArgumentException("C/C++ companion must use its controlled .w2l/bin artifact");
            String target = unit.artifactPath().substring(".w2l/bin/".length());
            script.append("\n(\ncd -- \"$source\"/" + SafeBuildScriptEnvelope.shellQuote(unit.sourcePath()) + "\n")
                    .append("run cmake -S . -B .w2l/cmake-build -G Ninja -DCMAKE_BUILD_TYPE=Release\n")
                    .append("run cmake --build .w2l/cmake-build --target " + SafeBuildScriptEnvelope.shellQuote(target) + " --parallel 1\n")
                    .append("test ! -L .w2l/cmake-build/" + SafeBuildScriptEnvelope.shellQuote(target) + "\n")
                    .append("test -x .w2l/cmake-build/" + SafeBuildScriptEnvelope.shellQuote(target) + "\nmkdir -p .w2l/bin\n")
                    .append("cp -- .w2l/cmake-build/" + SafeBuildScriptEnvelope.shellQuote(target) + " " + SafeBuildScriptEnvelope.shellQuote(unit.artifactPath()) + "\n")
                    .append("chmod 0555 " + SafeBuildScriptEnvelope.shellQuote(unit.artifactPath()) + "\n")
                    .append("readelf -h " + SafeBuildScriptEnvelope.shellQuote(unit.artifactPath()) + " >/dev/null\n")
                    .append("ldd " + SafeBuildScriptEnvelope.shellQuote(unit.artifactPath()) + " > .w2l/companion.ldd\n! grep -F 'not found' .w2l/companion.ldd\n)\n");
        }
        return script.toString();
    }
}
