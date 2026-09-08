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
 * <p>对源码树执行有界只读安全遍历。
 */
public final class BoundedSourceInspector {
    private static final int MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_TEXT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SOURCE_ENTRIES = 100_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".groovy", ".xml", ".properties", ".yml", ".yaml", ".json", ".toml", ".py", ".js",
            ".ts", ".tsx", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".ini", ".cfg", ".gradle",
            ".go", ".rs", ".cs", ".csproj", ".kts", ".php", ".rb", ".c", ".h", ".cc", ".cpp",
            ".cxx", ".hpp", ".scala", ".sbt", ".clj", ".cljs", ".cljc", ".edn", ".ex", ".exs",
            ".dart", ".lua", ".pl", ".pm", ".swift", ".sh", ".html", ".htm", ".sql"
    );

    /**
     * Performs the {@code inspect} operation.
     *
     * <p>执行 {@code inspect} 操作。
     *
     * @param root the {@code root} value / {@code root} 值
     * @param rejections the {@code rejections} value / {@code rejections} 值
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

    private static boolean shouldReadText(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString().toLowerCase(Locale.ROOT);
        return text.equals("pom.xml") || text.startsWith("application.")
                || TEXT_EXTENSIONS.stream().anyMatch(text::endsWith);
    }

    private static RejectionReason reason(String code, String messageKey, String nextAction) {
        return reason(code, messageKey, nextAction, java.util.Map.of());
    }

    private static RejectionReason reason(
            String code,
            String messageKey,
            String nextAction,
            java.util.Map<String, ?> arguments
    ) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey, arguments), nextAction);
    }

    private static final class TreeInspection implements FileVisitor<Path> {
        private final Path root;
        private final List<RejectionReason> rejections;
        private final StringBuilder text = new StringBuilder();
        private final List<Path> relativeFiles = new ArrayList<>();
        private int scannedFiles;
        private long scannedTextBytes;

        private TreeInspection(Path root, List<RejectionReason> rejections) {
            this.root = root;
            this.rejections = rejections;
        }

        /** Performs the {@code preVisitDirectory} operation. / 执行 {@code preVisitDirectory} 操作。 */
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(root) && Files.isSymbolicLink(directory)) {
                rejections.add(reason("SYMBOLIC_LINK_DETECTED", "analysis.rejection.symbolicLinkDetected",
                        "input"));
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        /** Performs the {@code visitFile} operation. / 执行 {@code visitFile} 操作。 */
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

        /** Performs the {@code visitFileFailed} operation. / 执行 {@code visitFileFailed} 操作。 */
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            rejections.add(reason("SOURCE_READ_FAILED", "analysis.rejection.sourceEntryReadFailed",
                    "input", java.util.Map.of("entry", file.getFileName())));
            return FileVisitResult.CONTINUE;
        }

        /** Performs the {@code postVisitDirectory} operation. / 执行 {@code postVisitDirectory} 操作。 */
        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
            if (exception != null) {
                rejections.add(reason("SOURCE_READ_FAILED", "analysis.rejection.sourceDirectoryReadFailed",
                        "input", java.util.Map.of("directory", directory.getFileName())));
            }
            return FileVisitResult.CONTINUE;
        }

        private SourceInspectionFacts result() {
            return new SourceInspectionFacts(scannedFiles, relativeFiles, text.toString());
        }
    }
}
