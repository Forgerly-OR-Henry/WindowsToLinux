package gold.debug.windowstolinux.app.mod.development;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/** Captures Maven's resolved model before compilation; never reparses dependency declarations. / 编译前捕获 Maven 已解析模型，不重新解析依赖声明。 */
public final class MavenDevelopmentInputs extends AbstractMojo {
    /** Actual Maven build session, injected by the build goal descriptor. / 构建目标描述注入的实际 Maven 构建会话。 */
    private MavenSession session;

    /** Invalidates previous generated evidence and captures inputs for this compilation. / 使先前生成证据失效并捕获本次编译输入。
     * @throws MojoExecutionException if original build preparation fails / 原构建准备失败时 */
    @Override
    public void execute() throws MojoExecutionException {
        try {
            prepare();
        } catch (IOException | IllegalArgumentException failure) {
            throw new MojoExecutionException("CLASS build inputs could not be prepared", failure);
        }
    }

    /** Uses only resolved projects and artifacts to produce the tool's build envelope. / 仅使用已解析项目和制品生成工具构建封套。
     * @return generated input path / 已生成输入路径
     * @throws IOException if workspace, model or actual resolved artifacts are invalid / 工作区、模型或实际已解析制品无效时 */
    public Path prepare() throws IOException {
        var project = Objects.requireNonNull(session.getCurrentProject());
        Path workspace = DevelopmentDescriptor.workspace(Path.of(property(project, "wtl.workspace", "")));
        Path module = project.getBasedir().toPath().toRealPath();
        if (!module.startsWith(workspace.resolve("src/mod")) || module.equals(workspace.resolve("src/mod")))
            throw new IOException("CLASS author projects must be inside the declared workspace Mod root");
        Path output = Path.of(project.getBuild().getOutputDirectory()).toAbsolutePath().normalize();
        Path target = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        if (!output.equals(module.resolve("target/classes")) || !target.equals(module.resolve("target")))
            throw new IOException("CLASS author output must use the standard module target layout");
        Files.createDirectories(target);
        if (!target.toRealPath().equals(target))
            throw new IOException("Generated build inputs cannot follow a redirected target");
        for (Path old : List.of(target.resolve("wtlmod-development.json"),
                output.resolve("META-INF/wtlmod-development-input.sha256"))) {
            if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                if (!old.toRealPath().equals(old) || !Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Previous generated evidence was redirected");
                Files.delete(old);
            }
        }
        Path manifest = module.resolve("mod.json");
        DevelopmentDescriptor.manifest(workspace, manifest);
        var inputs = new LinkedHashSet<Path>();
        inputs.add(workspace.resolve("pom.xml"));
        inputs.add(workspace.resolve("src/mod/pom.xml"));
        parents(project, workspace, inputs);
        for (String config : List.of(".mvn/maven.config", ".mvn/jvm.config"))
            if (Files.exists(workspace.resolve(config)))
                inputs.add(workspace.resolve(config).toRealPath());
        for (String config : tokens(property(project, "wtl.buildInputs", "")))
            inputs.add(module.resolve(config).normalize().toRealPath());
        for (String filter : project.getBuild().getFilters())
            inputs.add(module.resolve(filter).normalize().toRealPath());
        var companions = new LinkedHashSet<>(tokens(property(project, "wtl.authorArtifacts", "")));
        var remaining = new LinkedHashSet<>(companions);
        var authorOutputs = new ArrayList<Path>();
        var authorJars = new ArrayList<Path>();
        var libraries = new ArrayList<Path>();
        boolean api = false;
        for (var dependency : project.getArtifacts()) {
            boolean platform = dependency.getGroupId().equals("gold.debug.windowstolinux")
                    && dependency.getArtifactId().equals("windowstolinux-appapi");
            if (platform) {
                if (!Artifact.SCOPE_PROVIDED.equals(dependency.getScope()))
                    throw new IOException("Platform API must have provided scope");
                api = true;
            }
            if (!Set.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME).contains(dependency.getScope()))
                continue;
            if (dependency.getGroupId().equals("com.h2database")
                    || dependency.getGroupId().equals("gold.debug.windowstolinux")
                            && !dependency.getArtifactId().startsWith("windowstolinux-mod-"))
                throw new IOException("Host dependencies cannot become private Mod runtime artifacts");
            if (!"jar".equals(dependency.getType()))
                throw new IOException("CLASS runtime artifacts must be ordinary JAR dependencies");
            for (var provider : session.getAllProjects())
                if (provider.getGroupId().equals(dependency.getGroupId())
                        && provider.getArtifactId().equals(dependency.getArtifactId())
                        && provider.getVersion().equals(dependency.getVersion())) {
                    if (Files.exists(provider.getBasedir().toPath().resolve("mod.json")))
                        throw new IOException("Other reactor Mods must have provided scope");
                    if (!companions.contains(dependency.getId()))
                        throw new IOException("Reactor runtime modules must be declared as CLASS companions");
                }
            if (companions.contains(dependency.getId())) {
                var author = companion(dependency);
                if (!author.getGroupId().equals(project.getGroupId())
                        || Files.exists(author.getBasedir().toPath().resolve("mod.json")))
                    throw new IOException("A companion must be a same-author module, not another Mod");
                parents(author, workspace, inputs);
                authorOutputs.add(Path.of(author.getBuild().getOutputDirectory()).toAbsolutePath().normalize());
                authorJars.add(Path.of(author.getBuild().getDirectory())
                        .resolve(author.getBuild().getFinalName() + ".jar").toAbsolutePath().normalize());
                remaining.remove(dependency.getId());
            } else {
                File resolved = dependency.getFile();
                if (resolved == null || !resolved.isFile())
                    throw new IOException("Undeclared companion or missing resolved private JAR");
                libraries.add(resolved.toPath().toRealPath());
            }
        }
        if (!api || !remaining.isEmpty())
            throw new IOException(
                    "Platform API or declared companion artifacts were not resolved with the required scope");
        for (Path input : inputs)
            if (!input.startsWith(workspace) || !Files.isRegularFile(input) || !input.equals(input.toRealPath()))
                throw new IOException("Build input must be an existing unredirected workspace file");
        var json = new ObjectMapper();
        var node = json.createObjectNode().put("formatVersion", 1).put("workspace", key(workspace))
                .put("manifest", key(manifest))
                .put("mainJar", key(target.resolve(project.getBuild().getFinalName() + ".jar")))
                .put("readme", key(module.resolve("README.md"))).put("license", key(module.resolve("LICENSE")))
                .put("mainOutput", key(output));
        for (var group : Map.of("authorJars", authorJars, "authorOutputs", authorOutputs, "libraries", libraries,
                "buildInputs", inputs).entrySet()) {
            var array = node.putArray(group.getKey());
            group.getValue().forEach(path -> array.add(key(path)));
        }
        if (Files.isRegularFile(module.resolve("CHANGELOG.md")))
            node.put("changelog", key(module.resolve("CHANGELOG.md")));
        Path input = target.resolve("wtlmod-build-input.json");
        if (Files.exists(input, LinkOption.NOFOLLOW_LINKS) && !input.toRealPath().equals(input))
            throw new IOException("Build envelope cannot overwrite a redirected path");
        Files.write(input, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        return input;
    }

    /** Resolves a companion against exact reactor coordinates, not a repository path guess. / 按精确 reactor 坐标解析从模块，不猜测仓库路径。
     * @param artifact explicitly named resolved dependency / 明示命名的已解析依赖
     * @return original reactor project / 原 reactor 项目
     * @throws IOException if no unique ordinary companion matches / 无唯一普通从模块匹配时 */
    private MavenProject companion(Artifact artifact) throws IOException {
        if (artifact.hasClassifier())
            throw new IOException("CLASS companion classifiers do not identify a module output");
        var candidates = session.getAllProjects().stream()
                .filter(project -> project.getGroupId().equals(artifact.getGroupId())
                        && project.getArtifactId().equals(artifact.getArtifactId())
                        && project.getVersion().equals(artifact.getVersion()) && "jar".equals(project.getPackaging()))
                .toList();
        if (candidates.size() != 1)
            throw new IOException("Declared CLASS companion is not a unique reactor project");
        return candidates.getFirst();
    }

    /** Collects real local parent POMs and rejects undeclared external configuration. / 收集实际本地父 POM，并拒绝未声明的外部配置。
     * @param project original Maven model / 原 Maven 模型
     * @param workspace validated source root / 已校验源码根
     * @param inputs exact input set / 精确输入集合
     * @throws IOException if a parent is outside this CLASS workspace / 父配置不在此 CLASS 工作区时 */
    private static void parents(MavenProject project, Path workspace, Set<Path> inputs) throws IOException {
        for (var current = project; current != null; current = current.getParent()) {
            if (current.getFile() != null) {
                Path file = current.getFile().toPath().toRealPath();
                if (!file.startsWith(workspace))
                    throw new IOException("CLASS parents must be available inside the verified workspace");
                inputs.add(file);
            }
        }
    }

    /** Reads explicit user overrides before interpolated project properties. / 在已插值项目属性前读取明示用户覆盖。
     * @param project current author project / 当前作者项目
     * @param name build property / 构建属性
     * @param fallback absent value / 缺省值
     * @return effective property / 实际属性 */
    private String property(MavenProject project, String name, String fallback) {
        return session.getUserProperties().getProperty(name, project.getProperties().getProperty(name, fallback));
    }

    /** Parses an explicit comma-separated list without inventing additional artifacts. / 解析明示逗号分隔列表，不推断额外制品。
     * @param value configured list / 已配置列表
     * @return nonempty trimmed entries / 去除两端空白的非空项 */
    private static List<String> tokens(String value) {
        return value.isBlank() ? List.of() : Arrays.stream(value.split(",", -1)).map(String::trim).map(item -> {
            if (item.isEmpty())
                throw new IllegalArgumentException("Empty build list item");
            return item;
        }).toList();
    }

    /** Uses the same portable spelling as development descriptors. / 使用与开发描述相同的可移植拼写。
     * @param path absolute local path / 本机绝对路径
     * @return forward-slash path / 正斜线路径 */
    private static String key(Path path) {
        return path.toString().replace('\\', '/');
    }
}
