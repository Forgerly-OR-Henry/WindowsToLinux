package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;

/**
 * Renders the only target-host build scripts accepted for reviewed deployment types.
 *
 * <p>渲染经审阅部署类型唯一允许的目标机构建脚本。
 */
public final class DeploymentBuildSupport {
    private DeploymentBuildSupport() {
    }

    /**
     * Renders a bounded build script for one matching project type and runtime definition.
     *
     * <p>为一个匹配的项目类型和运行定义渲染有界构建脚本。
     *
     * @param facts the statically observed project facts / 静态观察到的项目事实
     * @param runtime the reviewed runtime definition / 经审阅的运行定义
     * @param workspace the controlled remote workspace / 受控远程工作区
     * @param limits the reviewed build limits / 经审阅的构建限制
     * @return the implementation-owned script / 实现持有的脚本
     */
    public static String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                RemoteWorkspace workspace, BuildLimits limits) {
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        workspace = Objects.requireNonNull(workspace, "workspace");
        limits = Objects.requireNonNull(limits, "limits");
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("runtime must match the analyzed project type");
        }
        String source = workspace.candidateRoot() + "/mutable/source";
        String mutable = workspace.candidateRoot() + "/mutable";
        String command = switch (runtime) {
            case DeploymentRuntimeSpecification.GradleSpringBoot ignored -> """
                    test -f ./gradlew
                    test -f ./gradle/wrapper/gradle-wrapper.properties
                    chmod 700 -- ./gradlew
                    run ./gradlew --no-daemon -x test bootJar
                    mapfile -t artifacts < <(find ./build/libs -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' -printf '%p\\n' | LC_ALL=C sort)
                    test "${#artifacts[@]}" -eq 1
                    printf 'ARTIFACT=%s\\n' "${artifacts[0]}"
                    """;
            case DeploymentRuntimeSpecification.JavaJar javaJar -> """
                    artifact=%s
                    test -f "$artifact"
                    test ! -L "$artifact"
                    printf 'ARTIFACT=%%s\\n' "$artifact"
                    """.formatted(shellQuote("./" + javaJar.jarRelativePath()));
            case DeploymentRuntimeSpecification.NodeService node -> nodeBuild(facts.buildTool(), node.nodeMajorVersion(), false, null);
            case DeploymentRuntimeSpecification.PythonService python -> pythonBuild(python.pythonVersion());
            case DeploymentRuntimeSpecification.StaticSite staticSite -> staticBuild(facts.buildTool(), staticSite.outputDirectory());
            case DeploymentRuntimeSpecification.Container container -> containerBuild(
                    container.engine().name().toLowerCase(java.util.Locale.ROOT), workspace.candidateId());
        };
        return """
                set -euo pipefail
                candidate=%s
                mutable=%s
                source=%s
                archive="$mutable/source.tar.gz"
                log="$mutable/build.log"
                test -f "$archive"
                test ! -L "$archive"
                test "$(sha256sum "$archive" | awk '{print $1}')" = %s
                rm -rf -- "$source"
                mkdir -p -- "$source" "$mutable/home"
                tar -tzf "$archive" > "$mutable/archive-entries.txt"
                test -s "$mutable/archive-entries.txt"
                while IFS= read -r entry; do
                  case "$entry" in ''|/*|./*|../*|*/../*|..|*//*) exit 64 ;; esac
                done < "$mutable/archive-entries.txt"
                tar -tvzf "$archive" | awk 'substr($0, 1, 1) != "-" { exit 1 }'
                tar --extract --gzip --file "$archive" --directory "$source" --no-same-owner --no-same-permissions --numeric-owner
                test -z "$(find "$source" -xdev -type l -print -quit)"
                test -z "$(find "$source" -xdev ! -type f ! -type d -print -quit)"
                cd "$source"
                ulimit -u %d
                ulimit -v %d
                run() {
                  set +e
                  setsid /usr/bin/timeout --signal=TERM %d "$@" 2>&1 | head -c %d > "$log"
                  statuses=("${PIPESTATUS[@]}")
                  set -e
                  if [ "$(stat -c %%s "$log")" -ge %d ]; then
                    printf 'BUILD_LIMIT=output\\n'
                    exit 43
                  fi
                  if [ "${statuses[0]}" -ne 0 ]; then
                    exit "${statuses[0]}"
                  fi
                }
                %s
                used=$(du -sb "$mutable" | awk '{print $1}')
                if [ "$used" -gt %d ]; then
                  printf 'BUILD_LIMIT=workspace\\n'
                  exit 42
                fi
                printf 'BUILD_TOOL=%s\\n'
                """.formatted(shellQuote(workspace.candidateRoot()), shellQuote(mutable), shellQuote(source),
                shellQuote(workspace.sourceSha256()), limits.maxProcesses(), limits.maxMemoryMiB() * 1024L,
                limits.timeoutSeconds(), limits.maxOutputBytes(), limits.maxOutputBytes(), command,
                limits.maxWorkspaceBytes(), facts.buildTool().name());
    }

    private static String nodeBuild(DeploymentBuildTool tool, int nodeMajorVersion, boolean staticSite, String outputDirectory) {
        String installAndBuild = switch (tool) {
            case NPM -> "test -f ./package-lock.json\nrun npm ci --ignore-scripts\nrun npm run build";
            case PNPM -> "test -f ./pnpm-lock.yaml\nrun pnpm install --frozen-lockfile --ignore-scripts\nrun pnpm run build";
            case YARN -> "test -f ./yarn.lock\nrun yarn install --immutable --ignore-scripts\nrun yarn run build";
            default -> throw new IllegalArgumentException("Node source requires one fixed package manager");
        };
        String artifact = staticSite
                ? "test -d " + shellQuote("./" + outputDirectory) + "\nprintf 'ARTIFACT=%s\\n' " + shellQuote("./" + outputDirectory)
                : "printf 'ARTIFACT=%s\\n' ./package.json";
        return """
                node --version | grep -Eq %s
                %s
                %s
                """.formatted(shellQuote("^v" + nodeMajorVersion + "\\."), installAndBuild, artifact);
    }

    private static String pythonBuild(String version) {
        String python = "python" + version;
        return """
                command -v %s >/dev/null
                %s -m venv ./.venv
                if [ -f ./requirements.lock ]; then
                  run ./.venv/bin/python -m pip install --disable-pip-version-check --require-hashes -r ./requirements.lock
                elif [ -f ./poetry.lock ]; then
                  command -v poetry >/dev/null
                  POETRY_VIRTUALENVS_IN_PROJECT=true run poetry install --only main --sync --no-root
                elif [ -f ./uv.lock ]; then
                  command -v uv >/dev/null
                  run uv sync --frozen --no-dev
                elif [ -f ./Pipfile.lock ]; then
                  command -v pipenv >/dev/null
                  PIPENV_VENV_IN_PROJECT=1 run pipenv sync --deploy
                else
                  exit 64
                fi
                test -x ./.venv/bin/python
                printf 'ARTIFACT=%%s\\n' ./.venv
                """.formatted(shellQuote(python), shellQuote(python));
    }

    private static String staticBuild(DeploymentBuildTool tool, String outputDirectory) {
        if (tool == DeploymentBuildTool.STATIC_SITE_BUILD) {
            return """
                    test -f ./index.html
                    test -d %s
                    printf 'ARTIFACT=%%s\\n' %s
                    """.formatted(shellQuote("./" + outputDirectory), shellQuote("./" + outputDirectory));
        }
        return nodeBuild(tool, 20, true, outputDirectory);
    }

    private static String containerBuild(String engine, String candidateId) {
        String tag = "windowstolinux-candidate:" + candidateId;
        return """
                command -v %s >/dev/null
                test -f ./Dockerfile
                run %s build --pull=false --tag %s --file ./Dockerfile .
                image_id=$(%s image inspect --format '{{.Id}}' %s)
                printf 'ARTIFACT=%%s\\n' "$image_id"
                """.formatted(shellQuote(engine), shellQuote(engine), shellQuote(tag), shellQuote(engine), shellQuote(tag));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
