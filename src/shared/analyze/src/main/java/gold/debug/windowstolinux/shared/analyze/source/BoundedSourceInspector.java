package gold.debug.windowstolinux.shared.analyze.source;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * Performs the source tree's bounded, read-only safety traversal.
 *
 *  <p>对源码树执行有界只读安全遍历。
 */
public final class BoundedSourceInspector {
    /**
     * MAX TEXT FILE BYTES.
     * <p>最大文本文件字节。
     */
    private static final int MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024;
    /**
     * MAX TOTAL TEXT BYTES.
     * <p>最大总文本字节。
     */
    private static final int MAX_TOTAL_TEXT_BYTES = 16 * 1024 * 1024;
    /**
     * MAX SOURCE ENTRIES.
     * <p>最大源码条目。
     */
    private static final int MAX_SOURCE_ENTRIES = 100_000;
    /**
     * TEXT EXTENSIONS.
     * <p>文本EXTENSIONS。
     */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".groovy", ".xml", ".properties", ".yml", ".yaml", ".json", ".toml", ".py", ".js",
            ".ts", ".tsx", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".ini", ".cfg", ".gradle",
            ".go", ".rs", ".cs", ".csproj", ".kts", ".php", ".rb", ".c", ".h", ".cc", ".cpp",
            ".cxx", ".hpp", ".scala", ".sbt", ".clj", ".cljs", ".cljc", ".edn", ".ex", ".exs",
            ".dart", ".lua", ".pl", ".pm", ".swift", ".sh", ".html", ".htm", ".sql"
    );

    /**
     * Inspects source inspection facts.
     * <p>检查源码检查事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return the operation result / 操作结果
     */
    public SourceInspectionFacts inspect(Path root, List<RejectionReason> rejections) {
        TreeInspection visitor = new TreeInspection(root, rejections);
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException exception) {
            rejections.add(reason("SOURCE_READ_FAILED", "analysis.rejection.sourceReadFailed", "input"));
        }
        return visitor.result();
    }

    /**
     * Tests the should read text predicate against the supplied evidence.
     * <p>根据所提供证据检查should读取文本条件。
     *
     * @param file file / 文件
     * @return true when should read text predicate against the supplied evidence, false otherwise / 根据所提供证据检查should读取文本条件时为 true，否则为 false
     */
    private static boolean shouldReadText(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString().toLowerCase(Locale.ROOT);
        return text.equals("pom.xml") || text.startsWith("application.")
                || TEXT_EXTENSIONS.stream().anyMatch(text::endsWith);
    }

    /**
     * Creates a localized source-rejection reason without extra message arguments.
     * <p>创建不附带额外消息参数的本地化源码拒绝原因。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param nextAction next action / 下一动作
     * @return a localized source-rejection reason without extra message arguments / 不附带额外消息参数的本地化源码拒绝原因
     */
    private static RejectionReason reason(String code, String messageKey, String nextAction) {
        return reason(code, messageKey, nextAction, java.util.Map.of());
    }

    /**
     * Creates a localized source-rejection reason without extra message arguments.
     * <p>创建不附带额外消息参数的本地化源码拒绝原因。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param nextAction next action / 下一动作
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return a localized source-rejection reason without extra message arguments / 不附带额外消息参数的本地化源码拒绝原因
     */
    private static RejectionReason reason(
            String code,
            String messageKey,
            String nextAction,
            java.util.Map<String, ?> arguments
    ) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey, arguments), nextAction);
    }

    /**
     * Collects bounded source-tree findings without executing project code.
     * <p>在不执行项目代码的情况下汇总有界源码树发现。
     */
    private static final class TreeInspection implements FileVisitor<Path> {
        /**
         * Root directory defining the filesystem boundary.
         * <p>定义文件系统边界的根目录。
         */
        private final Path root;
        /**
         * Reasons preventing admission to the next stage.
         * <p>阻止进入下一阶段的原因。
         */
        private final List<RejectionReason> rejections;
        /**
         * Bounded text consumed or produced by the current formatter.
         * <p>当前格式化器消费或生成的有界文本。
         */
        private final StringBuilder text = new StringBuilder();
        /**
         * The bounded relative source paths.
         * <p>有界相对源码路径。
         */
        private final List<Path> relativeFiles = new ArrayList<>();
        /**
         * Scanned files.
         * <p>已扫描文件集合。
         */
        private int scannedFiles;
        /**
         * Scanned text bytes.
         * <p>已扫描文本字节。
         */
        private long scannedTextBytes;

        /**
         * Binds the supplied dependencies and state for tree inspection.
         * <p>为树检查绑定传入的依赖及状态。
         *
         * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
         * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
         */
        private TreeInspection(Path root, List<RejectionReason> rejections) {
            this.root = root;
            this.rejections = rejections;
        }

        /**
         * Checks the directory boundary before visiting its contents.
         * <p>在访问目录内容前检查目录边界。
         *
         * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
         * @param attributes attributes / 属性
         * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
         */
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(root) && Files.isSymbolicLink(directory)) {
                rejections.add(reason("SYMBOLIC_LINK_DETECTED", "analysis.rejection.symbolicLinkDetected",
                        "input"));
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        /**
         * Validates one source entry, accounts for count and byte limits and collects only admitted metadata files.
         * <p>校验一个源码条目、累计数量及字节限制，并仅采集已准入元数据文件。
         *
         * @param file file / 文件
         * @param attributes attributes / 属性
         * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
         * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
         */
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                rejections.add(reason("UNSAFE_SOURCE_ENTRY", "analysis.rejection.unsafeSourceEntry",
                        "input"));
                return FileVisitResult.CONTINUE;
            }
            if (relativeFiles.size() >= MAX_SOURCE_ENTRIES) {
                rejections.add(reason("SOURCE_ENTRY_LIMIT_EXCEEDED", "analysis.rejection.sourceEntryLimitExceeded", "input"));
                return FileVisitResult.TERMINATE;
            }
            relativeFiles.add(root.relativize(file));
            if (shouldReadText(file)) {
                long size = Files.size(file);
                if (size > MAX_TEXT_FILE_BYTES) {
                    rejections.add(reason("SOURCE_TEXT_ENTRY_TOO_LARGE",
                            "analysis.rejection.sourceTextEntryTooLarge", "input"));
                    return FileVisitResult.CONTINUE;
                }
                if (scannedTextBytes + size > MAX_TOTAL_TEXT_BYTES) {
                    rejections.add(reason("SOURCE_TEXT_TOTAL_TOO_LARGE",
                            "analysis.rejection.sourceTextTotalTooLarge", "input"));
                    return FileVisitResult.CONTINUE;
                }
                text.append('\n').append(Files.readString(file, StandardCharsets.UTF_8));
                scannedFiles++;
                scannedTextBytes += size;
            }
            return FileVisitResult.CONTINUE;
        }

        /**
         * Visits file failed.
         * <p>遍历文件失败。
         *
         * @param file file / 文件
         * @param exception original exception being classified or translated / 正在分类或转换的原始异常
         * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
         */
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            rejections.add(reason("SOURCE_READ_FAILED", "analysis.rejection.sourceEntryReadFailed",
                    "input", java.util.Map.of("entry", file.getFileName())));
            return FileVisitResult.CONTINUE;
        }

        /**
         * Completes the directory traversal step and propagates any traversal failure.
         * <p>完成目录遍历步骤并传播遍历失败。
         *
         * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
         * @param exception original exception being classified or translated / 正在分类或转换的原始异常
         * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
         */
        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
            if (exception != null) {
                rejections.add(reason("SOURCE_READ_FAILED", "analysis.rejection.sourceDirectoryReadFailed",
                        "input", java.util.Map.of("directory", directory.getFileName())));
            }
            return FileVisitResult.CONTINUE;
        }

        /**
         * Builds source inspection facts from the supplied result inputs.
         * <p>根据所提供结果输入构建源码检查事实。
         *
         * @return source inspection facts from the supplied result inputs / 根据所提供结果输入构建源码检查事实
         */
        private SourceInspectionFacts result() {
            return new SourceInspectionFacts(scannedFiles, relativeFiles, text.toString());
        }
    }
}
