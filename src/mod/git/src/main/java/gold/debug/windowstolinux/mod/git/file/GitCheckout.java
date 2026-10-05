package gold.debug.windowstolinux.mod.git.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Fetches one exact ref, validates tree features and checks out its immutable commit. / 抓取一个精确引用、验证树特性并检出不可变提交。 */
final class GitCheckout {
    /** Prevents utility instances. / 禁止工具实例。 */
    private GitCheckout() {
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
    static String prepare(GitRequest request, Path executable, Path checkout, Path control,
                          BooleanSupplier cancelled) throws ApiException, IOException {
        try (var files = Files.list(checkout)) {
            if (files.findAny().isPresent())
                throw new IOException("Git checkout must be empty");
        }
        Files.createDirectory(control.resolve("hooks"));
        Files.createDirectory(control.resolve("template"));
        Files.createFile(control.resolve("empty-config"));
        var commands = new GitCommands(executable, checkout, control, cancelled,
            request.permitLocalFileRemote());
        commands.run(List.of("init", "--initial-branch=windowstolinux-snapshot",
            "--template=" + control.resolve("template")), 16384);
        commands.run(List.of("remote", "add", "origin", request.remote().location().toString()), 16384);
        String ref = switch (request.reference()) {
            case GitRef.DefaultBranch ignored -> "HEAD";
            case GitRef.Branch branch -> "refs/heads/" + branch.value();
            case GitRef.Tag tag -> "refs/tags/" + tag.value();
            case GitRef.Commit commit -> commit.value();
        };
        fetch(commands, ref, cancelled);
        String commit = new String(commands.run(List.of("rev-parse", "--verify", "FETCH_HEAD^{commit}"), 16384),
            StandardCharsets.UTF_8).trim();
        if (!commit.matches("[0-9a-f]{40}")
            || request.reference() instanceof GitRef.Commit exact && !exact.value().equals(commit))
            throw new IOException("Git did not resolve the original exact commit");
        byte[] originalTree = commands.run(List.of("ls-tree", "-rz", "--full-tree", commit), 32 * 1024 * 1024);
        List<String> tracked = members(originalTree);
        try (var channel = java.nio.channels.FileChannel.open(control.resolve("git-tree.bin"),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var content = ByteBuffer.wrap(originalTree);
            while (content.hasRemaining()) channel.write(content);
            channel.force(true);
        }
        commands.run(List.of("checkout", "--detach", commit), 16384);
        for (String name : tracked) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals(".gitattributes") && !lower.endsWith("/.gitattributes")) continue;
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
    private static void fetch(GitCommands commands, String ref, BooleanSupplier cancelled)
        throws ApiException {
        for (int attempt = 0; ; attempt++) {
            try {
                commands.run(List.of("fetch", "--no-tags", "--depth", "1", "origin", ref), 16384);
                return;
            } catch (ApiException failure) {
                if (attempt >= 2 || !failure.problem().eventCode().equals("GIT_SOURCE_TRANSIENT"))
                    throw failure;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException signal) {
                    Thread.currentThread().interrupt();
                    throw GitCommands.problem(ProblemCode.CANCELLED, "GIT_SOURCE_CANCELLED");
                }
                if (cancelled.getAsBoolean())
                    throw GitCommands.problem(ProblemCode.CANCELLED, "GIT_SOURCE_CANCELLED");
            }
        }
    }

    /** Parses NUL-separated Git tree entries before any symlink or submodule can be checked out. / 在检出任何符号链接或子模块前解析 NUL 分隔的 Git 树条目。
     * @param bytes complete bounded tree output / 完整有界树输出
     * @return all original tracked ordinary files / 全部原被跟踪普通文件
     * @throws IOException if a path, mode or unsupported feature is found / 发现无效路径、模式或不支持特性时 */
    static List<String> members(byte[] bytes) throws IOException {
        if (bytes.length > 32 * 1024 * 1024) throw new IOException("Git tree exceeds its original bound");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (!text.isEmpty() && !text.endsWith("\0"))
            throw new IOException("Incomplete Git tree");
        var tracked = new ArrayList<String>();
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
            tracked.add(name);
        }
        return List.copyOf(tracked);
    }

    /** Reads original bounded worker tree evidence after native exit. / 原生退出后读取原有界 worker 树依据。
     * @param control original owned control directory / 原所属控制目录
     * @return exact tracked members / 精确跟踪项
     * @throws IOException if the original file is unsafe or exceeds its bound / 原文件不安全或超限时 */
    static List<String> readMembers(Path control) throws IOException {
        var file = control.resolve("git-tree.bin");
        var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isOther() || attributes.isSymbolicLink()
            || attributes.size() > 32 * 1024 * 1024) throw new IOException("Original Git tree is unavailable");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return members(input.readNBytes(32 * 1024 * 1024 + 1));
        }
    }

    /** Deletes Git metadata only in the original quiescent owned checkout, without following links. / 只在原静止所属检出内删除 Git 元数据，不跟随链接。
     * @param checkout physically verified original checkout / 已物理核验原检出
     * @throws IOException if ordinary metadata ownership or deletion is unconfirmed / 普通元数据归属或删除未确认时 */
    static void removeMetadata(Path checkout) throws IOException {
        Path root = checkout.resolve(".git");
        if (!root.normalize().getParent().equals(checkout) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Original Git metadata is unavailable");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /** Refuses linked metadata directories. / 拒绝链接元数据目录。
             * @param path current original directory / 当前原目录
             * @param attributes non-following attributes / 不跟随链接属性
             * @return continued traversal / 继续遍历
             * @throws IOException if metadata is linked or foreign / 元数据是链接或外来项时 */
            @Override
            public FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attributes)
                throws IOException {
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                    || !path.normalize().startsWith(root)) throw new IOException("Unsafe Git metadata directory");
                return FileVisitResult.CONTINUE;
            }

            /** Removes only an original metadata file. / 仅移除原元数据文件。
             * @param path original file / 原文件
             * @param attributes non-following attributes / 不跟随链接属性
             * @return continued traversal / 继续遍历
             * @throws IOException if an entry is unsafe or cannot be removed / 项不安全或无法移除时 */
            @Override
            public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes)
                throws IOException {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("Unsafe Git metadata file");
                var dos = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (dos != null) dos.setReadOnly(false);
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            /** Removes an emptied metadata directory. / 移除已空元数据目录。
             * @param path original directory / 原目录
             * @param failure actual traversal failure / 实际遍历失败
             * @return continued traversal / 继续遍历
             * @throws IOException if traversal or deletion failed / 遍历或删除失败时 */
            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
