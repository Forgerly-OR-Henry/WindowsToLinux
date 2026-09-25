package gold.debug.windowstolinux.shared.standard.deploy.build.workload;

import java.util.Locale;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;

/**
 * Renders the fixed Dockerfile container image build. / 渲染固定 Dockerfile 容器镜像构建。
 */
public final class ContainerBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.DOCKERFILE_CONTAINER;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public java.util.Set<DeploymentBuildToolType> buildTools() {
        return java.util.Set.of(DeploymentBuildToolType.CONTAINER_BUILD);
    }

    /**
     * Renders the controlled output. / 渲染受控输出。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.Container container)
                || facts.buildTool() != DeploymentBuildToolType.CONTAINER_BUILD) {
            throw new IllegalArgumentException("Container renderer requires reviewed Dockerfile inputs");
        }
        String engine = container.engine().name().equals("DOCKER")
                ? "docker --host=unix://\"$WTL_BUILD_SOCKET\""
                : "podman --remote --url=unix://\"$WTL_BUILD_SOCKET\"";
        String repository = container.engine().name().equals("PODMAN")
                ? "localhost/windowstolinux-candidate:"
                : "windowstolinux-candidate:";
        String tag = SafeBuildScriptEnvelope.shellQuote(repository + workspace.candidateId());
        String application = SafeBuildScriptEnvelope.shellQuote(facts.applicationId());
        String candidate = SafeBuildScriptEnvelope.shellQuote(workspace.candidateId());
        String command = """
                test -S "$WTL_BUILD_SOCKET"
                %s version >/dev/null
                test -f ./Dockerfile
                run %s build --pull=true --label io.windowstolinux.application=%s --label io.windowstolinux.candidate=%s --tag %s --file ./Dockerfile .
                image_id=$(%s image inspect --format '{{.Id}}' %s)
                %s save %s --output \"$mutable/candidate-image.tar\" %s
                %s
                printf 'ARTIFACT=%%s\n' "$image_id"
                """
                .formatted(engine, engine, application, candidate, tag, engine, tag, engine,
                        container.engine().name().equals("PODMAN") ? "--format=docker-archive" : "", tag,
                        container.engine().name().equals("PODMAN") ? normalizePodmanExport() : "");
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }

    /**
     * Omits only redundant legacy layer aliases before immutable validation. / 不可变校验前仅去除冗余旧格式层别名。
     *
     * @return normalize podman export text / 规范化Podman导出文本
     */
    private static String normalizePodmanExport() {
        return """
                /usr/bin/python3 -I - "$mutable/candidate-image.tar" <<'WTL_PODMAN_EXPORT'
                import json, os, re, sys, tarfile
                archive = sys.argv[1]
                with tarfile.open(archive, 'r:') as source:
                    members = {}
                    for item in source:
                        if len(members) >= 10000 or item.name in members:
                            raise SystemExit('BUILD_REJECT=container-export-members')
                        members[item.name] = item
                    manifest = members.get('manifest.json')
                    if manifest is None or not manifest.isfile() or manifest.size > 524288:
                        raise SystemExit('BUILD_REJECT=container-export-manifest')
                    with source.extractfile(manifest) as stream:
                        metadata = json.load(stream)
                    if not isinstance(metadata, list) or len(metadata) != 1:
                        raise SystemExit('BUILD_REJECT=container-export-manifest')
                    layers = metadata[0].get('Layers', [])
                    aliases = set()
                    for item in members.values():
                        if not item.issym():
                            continue
                        target = item.linkname.removeprefix('../')
                        if not re.fullmatch(r'[0-9a-f]{64}/layer[.]tar', item.name) or not re.fullmatch(
                                r'[.][.]/[0-9a-f]{64}[.]tar', item.linkname) or target not in layers or not (
                                target in members and members[target].isfile()) or item.size != 0:
                            raise SystemExit('BUILD_REJECT=container-export-link')
                        aliases.add(item.name)
                    with tarfile.open(archive + '.normalized', 'x', format=tarfile.PAX_FORMAT) as output:
                        for item in members.values():
                            if item.name in aliases:
                                continue
                            if item.isfile():
                                with source.extractfile(item) as stream:
                                    output.addfile(item, stream)
                            else:
                                output.addfile(item)
                os.replace(archive + '.normalized', archive)
                WTL_PODMAN_EXPORT
                """;
    }
}
