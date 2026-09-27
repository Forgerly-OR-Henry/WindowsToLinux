package gold.debug.windowstolinux.mod.deployment.source.git;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import gold.debug.windowstolinux.appapi.task.*;

/** Fetches one exact ref, validates tree features and checks out its immutable commit. / 抓取一个精确引用、验证树特性并检出不可变提交。 */
final class DeploymentGitCheckout {
    /** Prevents utility instances. / 禁止工具实例。 */
    private DeploymentGitCheckout() {
    }

    /** Resolves only the requested ref without executing repository code. / 仅解析请求的引用，不执行仓库代码。
     * @param request original validated request / 原已验证请求
     * @param executable fixed native Git / 固定原生 Git
     * @param checkout new empty source workspace / 新空来源工作目录
     * @param control owned control directory / 所属控制目录
     * @param cancelled cooperative signal / 协作信号
     * @return full immutable commit / 完整不可变提交
     * @throws ApiException if tools, transport, features or cancellation fail / 工具、传输、特性或取消失败时
     * @throws IOException if tree metadata is malformed / 树元数据无效时 */
    static String prepare(DeploymentGitRequest request, Path executable, Path checkout, Path control,
            BooleanSupplier cancelled) throws ApiException, IOException {
        try (var files = Files.list(checkout)) {
            if (files.findAny().isPresent())
                throw new IOException("Git checkout must be empty");
        }
        Files.createDirectory(control.resolve("hooks"));
        Files.createDirectory(control.resolve("template"));
        Files.createFile(control.resolve("empty-config"));
        var commands = new DeploymentGitCommands(executable, checkout, control, cancelled,
                request.permitLocalFileRemote());
        commands.run(List.of("init", "--initial-branch=windowstolinux-snapshot",
                "--template=" + control.resolve("template")), 16384);
        commands.run(List.of("remote", "add", "origin", request.remote().location().toString()), 16384);
        String ref = switch (request.reference()) {
            case DeploymentGitReference.DefaultBranch ignored -> "HEAD";
            case DeploymentGitReference.Branch branch -> "refs/heads/" + branch.value();
            case DeploymentGitReference.Tag tag -> "refs/tags/" + tag.value();
            case DeploymentGitReference.Commit commit -> commit.value();
        };
        fetch(commands, ref, cancelled);
        String commit = new String(commands.run(List.of("rev-parse", "--verify", "FETCH_HEAD^{commit}"), 16384),
                StandardCharsets.UTF_8).trim();
        if (!commit.matches("[0-9a-f]{40}")
                || request.reference() instanceof DeploymentGitReference.Commit exact && !exact.value().equals(commit))
            throw new IOException("Git did not resolve the original exact commit");
        List<String> attributes = tree(
                commands.run(List.of("ls-tree", "-rz", "--full-tree", commit), 32 * 1024 * 1024));
        commands.run(List.of("checkout", "--detach", commit), 16384);
        for (String name : attributes) {
            Path file = checkout.resolve(name);
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(262145);
                if (bytes.length > 262144 || StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                        .toString().matches("(?s).*\\bfilter=lfs\\b.*"))
                    throw new IOException("Git LFS materialization is unsupported");
            }
        }
        String actual = new String(commands.run(List.of("rev-parse", "--verify", "HEAD"), 16384),
                StandardCharsets.UTF_8).trim();
        if (!actual.equals(commit))
            throw new IOException("Git checkout commit changed");
        return commit;
    }

    /** Retries only known transient read-only fetch failures after complete native cleanup. / 仅在原生清理完成后重试已知暂时只读抓取失败。
     * @param commands owned native runner / 所属原生运行器
     * @param ref exact remote ref / 精确远端引用
     * @param cancelled original signal / 原信号
     * @throws ApiException if fetch or cancellation fails / 抓取或取消失败时 */
    private static void fetch(DeploymentGitCommands commands, String ref, BooleanSupplier cancelled)
            throws ApiException {
        for (int attempt = 0;; attempt++) {
            try {
                commands.run(List.of("fetch", "--no-tags", "--depth", "1", "origin", ref), 16384);
                return;
            } catch (ApiException failure) {
                if (attempt >= 2 || !failure.problem().eventCode().equals("DEPLOYMENT_GIT_TRANSIENT"))
                    throw failure;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException signal) {
                    Thread.currentThread().interrupt();
                    throw DeploymentGitCommands.problem(ProblemCode.CANCELLED, "DEPLOYMENT_GIT_CANCELLED");
                }
                if (cancelled.getAsBoolean())
                    throw DeploymentGitCommands.problem(ProblemCode.CANCELLED, "DEPLOYMENT_GIT_CANCELLED");
            }
        }
    }

    /** Parses NUL-separated Git tree entries before any symlink or submodule can be checked out. / 在检出任何符号链接或子模块前解析 NUL 分隔的 Git 树条目。
     * @param bytes complete bounded tree output / 完整有界树输出
     * @return all attributes files to inspect after checkout / 检出后需检查的全部属性文件
     * @throws IOException if a path, mode or unsupported feature is found / 发现无效路径、模式或不支持特性时 */
    private static List<String> tree(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (!text.isEmpty() && !text.endsWith("\0"))
            throw new IOException("Incomplete Git tree");
        var attributes = new ArrayList<String>();
        for (String entry : text.split("\0")) {
            if (entry.isEmpty())
                continue;
            int separator = entry.indexOf('\t');
            if (separator < 0 || !entry.substring(0, separator).matches("100(644|755) blob [0-9a-f]{40}"))
                throw new IOException("Git links and submodules are unsupported");
            String name = entry.substring(separator + 1);
            if (name.startsWith("/") || name.indexOf('\\') >= 0
                    || name.chars().anyMatch(c -> c < 32 || c == 127 || ":*?\"<>|".indexOf(c) >= 0)
                    || Arrays.stream(name.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".")
                            || part.equals("..") || part.equalsIgnoreCase(".git")))
                throw new IOException("Git member is not a portable source path");
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (lowerName.equals(".gitmodules") || lowerName.endsWith("/.gitmodules"))
                throw new IOException("Git submodule metadata is unsupported");
            if (lowerName.equals(".gitattributes") || lowerName.endsWith("/.gitattributes"))
                attributes.add(name);
        }
        return List.copyOf(attributes);
    }
}
