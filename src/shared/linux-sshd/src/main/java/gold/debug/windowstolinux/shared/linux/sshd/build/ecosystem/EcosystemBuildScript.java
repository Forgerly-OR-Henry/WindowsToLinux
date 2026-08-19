package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;

/** Shares the safe script mechanics used by single-architecture ecosystem renderers. / 共享单一架构生态渲染器使用的安全脚本机制。 */
final class EcosystemBuildScript {
    private EcosystemBuildScript() {
    }

    static String render(DeploymentProjectType projectType, DeploymentProjectFacts facts,
                         DeploymentRuntimeSpecification runtime, RemoteWorkspace workspace,
                         BuildLimitConfiguration limits) {
        projectType = Objects.requireNonNull(projectType, "projectType");
        if (!switch (projectType) {
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE, PHP_SERVICE, RUBY_SERVICE -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("ecosystem renderer requires an ecosystem service project type");
        }
        Inputs inputs = Inputs.from(runtime);
        if (inputs.projectType() != projectType || facts.buildTool() != buildTool(projectType)) {
            throw new IllegalArgumentException("ecosystem renderer requires matching reviewed language inputs");
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command(projectType, inputs));
    }

    private static String command(DeploymentProjectType projectType, Inputs inputs) {
        String version = SafeBuildScriptEnvelope.shellQuote(inputs.version());
        String artifact = SafeBuildScriptEnvelope.shellQuote(inputs.artifactName());
        return switch (projectType) {
            case GO_SERVICE -> """
                    artifact_name=%s
                    command -v go >/dev/null
                    go version | grep -Eq %s
                    test -f ./go.mod
                    test -f ./go.sum
                    test -f ./main.go
                    mkdir -p ./.w2l/bin
                    CGO_ENABLED=0 run go build -mod=readonly -trimpath -o "./.w2l/bin/$artifact_name" .
                    test -x "./.w2l/bin/$artifact_name"
                    test ! -L "./.w2l/bin/$artifact_name"
                    printf 'ARTIFACT=%%s\n' "./.w2l/bin/$artifact_name"
                    """.formatted(artifact, SafeBuildScriptEnvelope.shellQuote("^go version go"
                            + inputs.version().replace(".", "[.]") + "([.][0-9]+)? "));
            case RUST_SERVICE -> """
                    artifact_name=%s
                    command -v rustc >/dev/null
                    command -v cargo >/dev/null
                    rustc --version | grep -Eq %s
                    test -f ./Cargo.toml
                    test -f ./Cargo.lock
                    test -f ./rust-toolchain.toml
                    run cargo build --locked --release
                    mkdir -p ./.w2l/bin
                    test -x "./target/release/$artifact_name"
                    cp -- "./target/release/$artifact_name" "./.w2l/bin/$artifact_name"
                    test -x "./.w2l/bin/$artifact_name"
                    test ! -L "./.w2l/bin/$artifact_name"
                    printf 'ARTIFACT=%%s\n' "./.w2l/bin/$artifact_name"
                    """.formatted(artifact, SafeBuildScriptEnvelope.shellQuote("^rustc "
                            + inputs.version().replace(".", "[.]") + " "));
            case DOTNET_SERVICE -> """
                    artifact_name=%s
                    export DOTNET_CLI_TELEMETRY_OPTOUT=1
                    export DOTNET_NOLOGO=1
                    export DOTNET_GCHeapHardLimit=0x40000000
                    command -v dotnet >/dev/null
                    dotnet --version | grep -Eq %s
                    test -f ./global.json
                    test -f ./packages.lock.json
                    run dotnet restore --locked-mode
                    mkdir -p ./.w2l/dotnet
                    run dotnet publish --no-restore --configuration Release --output ./.w2l/dotnet
                    test -f "./.w2l/dotnet/$artifact_name.dll"
                    test ! -L "./.w2l/dotnet/$artifact_name.dll"
                    printf 'ARTIFACT=%%s\n' "./.w2l/dotnet/$artifact_name.dll"
                    """.formatted(artifact, SafeBuildScriptEnvelope.shellQuote("^"
                            + inputs.version().replace(".", "[.]") + "$"));
            case KOTLIN_SERVICE -> """
                    export GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m'
                    command -v java >/dev/null
                    command -v curl >/dev/null
                    command -v flock >/dev/null
                    java -version 2>&1 | grep -Eq 'version "21([.]|")'
                    test -f ./build.gradle.kts
                    test -f ./gradle.lockfile
                    test -f ./gradle/wrapper/gradle-wrapper.jar
                    test -f ./gradle/wrapper/gradle-wrapper.properties
                    test -f ./gradlew
                    chmod u+x ./gradlew
                    wrapper_properties=./gradle/wrapper/gradle-wrapper.properties
                    mapfile -t distribution_urls < <(sed -n 's/^distributionUrl=//p' "$wrapper_properties")
                    [ "${#distribution_urls[@]}" -eq 1 ]
                    distribution_url="${distribution_urls[0]//\\\\:/:}"
                    case "$distribution_url" in
                      https://services.gradle.org/distributions/*|https://downloads.gradle.org/distributions/*)
                        mapfile -t distribution_sums < <(sed -n 's/^distributionSha256Sum=//p' "$wrapper_properties")
                        [ "${#distribution_sums[@]}" -eq 1 ]
                        printf '%s\n' "${distribution_sums[0]}" | grep -Eq '^[0-9a-f]{64}$'
                        gradle_cache_root=/var/lib/windowstolinux/cache/gradle-distributions
                        install -d -m 0700 -- "$gradle_cache_root"
                        test -d "$gradle_cache_root"
                        test ! -L "$gradle_cache_root"
                        gradle_distribution="$gradle_cache_root/${distribution_sums[0]}.zip"
                        exec 9> "$gradle_distribution.lock"
                        flock 9
                        if [ -f "$gradle_distribution" ] \
                            && ! printf '%s  %s\n' "${distribution_sums[0]}" "$gradle_distribution" \
                              | sha256sum --check --status; then
                          rm -f -- "$gradle_distribution"
                        fi
                        if [ ! -f "$gradle_distribution" ]; then
                          run curl --fail --location --silent --show-error --continue-at - \
                            --connect-timeout 30 --max-time 540 --retry 4 --retry-all-errors \
                            --retry-delay 2 --retry-max-time 540 \
                            --output "$gradle_distribution.partial" "$distribution_url"
                          printf '%s  %s\n' "${distribution_sums[0]}" "$gradle_distribution.partial" \
                            | sha256sum --check --status
                          chmod 0600 -- "$gradle_distribution.partial"
                          mv -- "$gradle_distribution.partial" "$gradle_distribution"
                        fi
                        test -f "$gradle_distribution"
                        test ! -L "$gradle_distribution"
                        printf '%s  %s\n' "${distribution_sums[0]}" "$gradle_distribution" | sha256sum --check --status
                        flock --unlock 9
                        awk '$0 !~ /^distributionUrl=/' "$wrapper_properties" > "$wrapper_properties.local"
                        printf 'distributionUrl=file\\:%s\n' "$gradle_distribution" >> "$wrapper_properties.local"
                        mv -- "$wrapper_properties.local" "$wrapper_properties"
                        ;;
                    esac
                    retry_run 3 ./gradlew --no-daemon --version
                    run ./gradlew --no-daemon installDist
                    mapfile -t distributions < <(find ./build/install -mindepth 1 -maxdepth 1 -type d -print)
                    [ "${#distributions[@]}" -eq 1 ]
                    test -d "${distributions[0]}/lib"
                    test -z "$(find "${distributions[0]}" -xdev -type l -print -quit)"
                    mkdir -p ./.w2l/kotlin
                    cp -a -- "${distributions[0]}/lib" ./.w2l/kotlin/lib
                    test -n "$(find ./.w2l/kotlin/lib -maxdepth 1 -type f -name '*.jar' -print -quit)"
                    printf 'ARTIFACT=%%s\n' ./.w2l/kotlin/lib
                    """;
            case PHP_SERVICE -> """
                    command -v php >/dev/null
                    command -v composer >/dev/null
                    php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);' | grep -Fx %s
                    test -f ./composer.json
                    test -f ./composer.lock
                    test -f ./public/index.php
                    COMPOSER_ALLOW_SUPERUSER=0 run composer install --no-dev --no-interaction --no-progress --prefer-dist --classmap-authoritative --no-plugins --no-scripts
                    test -f ./vendor/autoload.php
                    test -z "$(find ./vendor -xdev -type l -print -quit)"
                    printf 'ARTIFACT=%%s\n' ./vendor
                    """.formatted(version);
            case RUBY_SERVICE -> """
                    command -v ruby >/dev/null
                    command -v bundle >/dev/null
                    ruby -e 'print RUBY_VERSION' | grep -Fx %s
                    test -f ./Gemfile
                    test -f ./Gemfile.lock
                    test -f ./config.ru
                    bundle config set --local deployment true
                    bundle config set --local path vendor/bundle
                    run bundle install --jobs 1 --retry 0
                    run bundle exec ruby -e 'require "rack"; require "webrick"'
                    test -d ./vendor/bundle
                    test -z "$(find ./vendor/bundle -xdev -type l -print -quit)"
                    printf 'ARTIFACT=%%s\n' ./vendor/bundle
                    """.formatted(version);
            default -> throw new IllegalStateException("validated ecosystem service project type is unavailable");
        };
    }

    private static DeploymentBuildToolType buildTool(DeploymentProjectType projectType) {
        return switch (projectType) {
            case GO_SERVICE -> DeploymentBuildToolType.GO_MODULE;
            case RUST_SERVICE -> DeploymentBuildToolType.CARGO_LOCKED;
            case DOTNET_SERVICE -> DeploymentBuildToolType.DOTNET_LOCKED;
            case KOTLIN_SERVICE -> DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER;
            case PHP_SERVICE -> DeploymentBuildToolType.COMPOSER_LOCKED;
            case RUBY_SERVICE -> DeploymentBuildToolType.BUNDLER_LOCKED;
            default -> throw new IllegalStateException("validated ecosystem service project type is unavailable");
        };
    }

    private record Inputs(DeploymentProjectType projectType, String version, String artifactName, String entrypoint) {
        private static Inputs from(DeploymentRuntimeSpecification runtime) {
            return switch (runtime) {
                case DeploymentRuntimeSpecification.GoService service -> new Inputs(service.projectType(), service.version(), service.artifactName(), service.entrypoint());
                case DeploymentRuntimeSpecification.RustService service -> new Inputs(service.projectType(), service.version(), service.artifactName(), service.entrypoint());
                case DeploymentRuntimeSpecification.DotNetService service -> new Inputs(service.projectType(), service.version(), service.artifactName(), service.entrypoint());
                case DeploymentRuntimeSpecification.KotlinService service -> new Inputs(service.projectType(), service.version(), service.artifactName(), service.entrypoint());
                case DeploymentRuntimeSpecification.PhpService service -> new Inputs(service.projectType(), service.version(), service.artifactName(), service.entrypoint());
                case DeploymentRuntimeSpecification.RubyService service -> new Inputs(service.projectType(), service.version(), service.artifactName(), service.entrypoint());
                default -> throw new IllegalArgumentException("ecosystem renderer requires an ecosystem service runtime");
            };
        }
    }
}
