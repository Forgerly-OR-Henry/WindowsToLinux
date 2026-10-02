package gold.debug.windowstolinux.app.mod.dev;

import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import gold.debug.windowstolinux.app.mod.load.ModCodeInspection;
import gold.debug.windowstolinux.app.mod.config.ModManifest;

/** Discovers current compiled Mod declarations without executing any author code. / 发现当前已编译 Mod 声明，不执行任何作者代码。 */
public final class DevelopmentModDiscovery {
    /** Prevents instantiation of the stateless scanner. / 禁止实例化无状态扫描器。 */
    private DevelopmentModDiscovery() {
    }

    /** Enumerates explicit development modules, retaining per-source errors and rejecting duplicate identities. / 枚举明示开发模块，保留逐来源错误并拒绝重复身份。
     * @param workspace explicitly supplied absolute workspace / 明示绝对工作区
     * @return immutable discovery facts, without activating any Mod / 不可变发现事实，不启用任何 Mod
     * @throws IOException if the workspace cannot be validated or enumerated / 无法校验或枚举工作区时 */
    public static Result scan(Path workspace) throws IOException {
        Path root = DevelopmentDescriptor.workspace(workspace);
        Path mods = root.resolve("src/mod");
        var found = new ArrayList<Candidate>();
        var failures = new ArrayList<Rejection>();
        var counts = new HashMap<String, Integer>();
        Files.walkFileTree(mods, new SimpleFileVisitor<>() {
            /** Visits only active source modules, not output or archived trees. / 仅访问活动源码模块，不访问输出或归档树。
             * @param directory candidate directory / 候选目录
             * @param attributes observed file attributes / 已观察文件属性
             * @return whether to visit children / 是否访问子节点
             * @throws IOException if directory identity cannot be checked / 无法检查目录身份时 */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
                if (!directory.equals(mods)
                    && Set.of("target", "history-src", ".git").contains(directory.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                if (!directory.toRealPath().equals(directory)) {
                    failures.add(
                        reject(directory, "MOD_DEVELOPMENT_PATH", "Development module directory is redirected"));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path descriptor = directory.resolve("target/wtlmod-development.json");
                if (Files.exists(directory.resolve("mod.json"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        var declared = DevelopmentDescriptor.manifest(root, directory.resolve("mod.json"));
                        counts.merge(declared.id(), 1, Integer::sum);
                        var compiled = DevelopmentDescriptor.read(root, descriptor);
                        if (!declared.json().equals(compiled.manifest().json()))
                            throw new IOException("Development manifest changed during discovery");
                        ModCodeInspection.read(declared, compiled.sources()).validate(false, Map.of());
                        found.add(new Candidate(directory, declared, compiled));
                    } catch (IOException failure) {
                        failures.add(reject(directory, "MOD_DEVELOPMENT_INVALID",
                            "Development Mod inputs or compiled output are invalid; compile the module again"));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            /** Refuses linked module directories without following their contents. / 拒绝链接模块目录，不跟随其内容。
             * @param file visited non-directory entry / 已访问非目录条目
             * @param attributes original entry attributes / 原条目属性
             * @return continue discovery / 继续发现 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() && Files.isDirectory(file))
                    failures.add(reject(file, "MOD_DEVELOPMENT_PATH", "Development module directory is redirected"));
                return FileVisitResult.CONTINUE;
            }

            /** Reports inaccessible source directories without suppressing other valid modules. / 报告不可访问的源码目录，不屏蔽其他有效模块。
             * @param file inaccessible source / 不可访问来源
             * @param failure original traversal failure / 原遍历失败
             * @return continue independent discovery / 继续独立发现 */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                failures.add(reject(file, "MOD_DEVELOPMENT_UNREADABLE", "Development source cannot be inspected"));
                return FileVisitResult.CONTINUE;
            }
        });
        found.removeIf(candidate -> {
            if (counts.get(candidate.manifest.id()) == 1)
                return false;
            failures.add(reject(candidate.module, "MOD_DEVELOPMENT_AMBIGUOUS",
                "The same Mod identity has multiple development sources"));
            return true;
        });
        found.sort(Comparator.comparing(candidate -> candidate.module.toString()));
        failures.sort(Comparator.comparing(rejection -> rejection.source.toString()));
        return new Result(found, failures);
    }

    /** Creates safe source-specific evidence without exposing parser or filesystem messages. / 创建安全的来源专属证据，不暴露解析器或文件系统消息。
     * @param source rejected source / 被拒来源
     * @param event stable event / 稳定事件
     * @param message safe host explanation / 安全宿主说明
     * @return rejection with one diagnostic identity / 带一个诊断身份的拒绝值 */
    private static Rejection reject(Path source, String event, String message) {
        return new Rejection(source, new Problem(ProblemCode.INVALID_INPUT, event, message,
            Map.of("source", source.toString()), Optional.of(UUID.randomUUID())));
    }

    /** One current build, still requiring compatibility, dependency and lifecycle admission. / 一个当前构建，仍需兼容、依赖及生命周期准入。
     * @param module source module directory / 源码模块目录
     * @param manifest validated author declaration / 已校验作者声明
     * @param descriptor current compiled sources / 当前编译来源 */
    public record Candidate(Path module, ModManifest manifest, DevelopmentDescriptor descriptor) {
    }

    /** A source that must not be loaded. / 不得加载的来源。
     * @param source exact source location / 精确来源位置
     * @param problem safe diagnostic / 安全诊断 */
    public record Rejection(Path source, Problem problem) {
    }

    /** Independent candidates and retained failures from one scan. / 一次扫描的独立候选及保留失败。
     * @param candidates unambiguous current builds / 无歧义的当前构建
     * @param rejections sources refused before author execution / 作者执行前拒绝的来源 */
    public record Result(List<Candidate> candidates, List<Rejection> rejections) {
        /** Copies both collections before exposing scan facts. / 公开扫描事实前复制两个集合。
         * @param candidates current builds / 当前构建
         * @param rejections refused sources / 被拒来源 */
        public Result {
            candidates = List.copyOf(candidates);
            rejections = List.copyOf(rejections);
        }
    }
}
