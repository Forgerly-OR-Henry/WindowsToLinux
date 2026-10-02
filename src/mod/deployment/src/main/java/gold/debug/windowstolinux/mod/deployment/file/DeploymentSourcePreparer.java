package gold.debug.windowstolinux.mod.deployment.file;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.FileContent;
import gold.debug.windowstolinux.appapi.file.FileEntry;
import gold.debug.windowstolinux.appapi.file.FileReadRequest;
import gold.debug.windowstolinux.appapi.file.FileSelection;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.FileSnapshot;
import gold.debug.windowstolinux.appapi.file.SnapshotRequest;


/** Selects deployment files through public capabilities and freezes them before analysis. / 通过公开能力筛选部署文件，并在分析前冻结。
 */
public final class DeploymentSourcePreparer {
    /** Existing bounded declaration that can include sample logs. / 可纳入样例日志的既有有界声明。 */
    private static final String DECLARATION = "windowstolinux-application.properties";

    /** Original-task platform source capability. / 原任务平台来源能力。 */
    private final FileService source;

    /** Binds source access without receiving a raw user directory. / 绑定来源访问，不接收原始用户目录。
     * @param source original public capability / 原公开能力 */
    public DeploymentSourcePreparer(FileService source) {
        this.source = Objects.requireNonNull(source);
    }

    /** Captures one source-only immutable view with explicit bounded discovery. / 以明确有界发现捕获一份纯源码不可变视图。
     * @param selection original user-authorized handle / 原用户授权句柄
     * @param maximumBytes included byte budget up to two GiB / 最多二GiB的纳入字节预算
     * @param maximumEntries discovery budget including excluded children, up to one hundred thousand / 包含排除子项的发现预算，最多十万
     * @param task original accepted task / 原接纳任务
     * @return owned material to close after all consumers finish / 所有使用方结束后须关闭的所属素材
     * @throws ApiException if selection, policy, capture or cleanup fails / 选择、策略、捕获或清理失败时 */
    public DeploymentSourceMaterial prepare(FileSelection selection, long maximumBytes, int maximumEntries,
                                            TaskContext task) throws ApiException {
        if (maximumBytes < 0 || maximumBytes > 2L * 1024 * 1024 * 1024 || maximumEntries < 1
            || maximumEntries > 100_000)
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SOURCE_BUDGET",
                "Source preparation budgets are invalid");
        try {
            var root = source.list(selection, "", maximumEntries, task);
            Optional<FileContent> declaration = declaration(selection, root, task);
            var explicit = declaration.isPresent() ? includes(declaration.get()) : Set.<String>of();
            var manifest = collect(selection, root, explicit, maximumBytes, maximumEntries, task);
            var snapshot = source.snapshot(selection, new SnapshotRequest(manifest.included(), maximumBytes), task);
            boolean returned = false;
            try {
                cancelled(task);
                if (declaration.isPresent())
                    verifyDeclaration(snapshot, declaration.get().sha256());
                cancelled(task);
                var material = new DeploymentSourceMaterial(selection.displayName(), snapshot, manifest.included(),
                    manifest.excluded());
                returned = true;
                return material;
            } finally {
                if (!returned)
                    snapshot.close();
            }
        } catch (IllegalArgumentException invalid) {
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SOURCE_POLICY",
                "Source declarations violate deployment file rules");
        } catch (IOException | IllegalStateException invalid) {
            cancelled(task);
            throw problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SOURCE_CHANGED",
                "Source material could not be verified before analysis");
        }
    }

    /** Reads the declaration only when its root entry is an ordinary file. / 仅当根子项是普通文件时读取声明。
     * @param selection original handle / 原句柄
     * @param root observed root entries / 已观察根子项
     * @param task original task / 原任务
     * @return original bounded declaration if present / 存在时的原有界声明
     * @throws ApiException if access fails / 访问失败时
     * @throws IOException if the declaration is not a regular file / 声明不是普通文件时 */
    private Optional<FileContent> declaration(FileSelection selection, List<FileEntry> root,
                                             TaskContext task) throws ApiException, IOException {
        for (var entry : root)
            if (entry.relativePath().equals(DECLARATION)) {
                if (entry.type() != FileEntry.EntryType.FILE)
                    throw new IOException("Invalid source declaration type");
                return Optional.of(source.read(selection, new FileReadRequest(DECLARATION, 65_536), task));
            }
        return Optional.empty();
    }

    /** Applies deployment policy while retaining the exact bounded selection for the host snapshot. / 应用部署策略并保留用于宿主快照的精确有界选择。
     * @param selection original handle / 原句柄
     * @param root original root observations / 原根观测
     * @param explicit permitted explicit sample resources / 允许的明确样例资源
     * @param maximumBytes included byte budget / 纳入字节预算
     * @param maximumEntries total discovery budget / 总发现预算
     * @param task original task / 原任务
     * @return complete chosen names and omissions / 完整选择名称及排除项
     * @throws ApiException if original access fails / 原访问失败时
     * @throws IOException if types, budgets or explicit resources differ / 类型、预算或明确资源不符时 */
    private Manifest collect(FileSelection selection, List<FileEntry> root, Set<String> explicit,
                             long maximumBytes, int maximumEntries, TaskContext task) throws ApiException, IOException {
        var pending = new ArrayDeque<String>();
        pending.add("");
        var included = new TreeSet<String>();
        var excluded = new TreeSet<String>();
        int visited = 0;
        long bytes = 0;
        while (!pending.isEmpty()) {
            String directory = pending.removeFirst();
            var entries = directory.isEmpty()
                ? root
                : source.list(selection, directory, Math.max(1, maximumEntries - visited), task);
            for (var entry : entries) {
                cancelled(task);
                if (++visited > maximumEntries)
                    throw new IOException("Source discovery exceeds its budget");
                String path = entry.relativePath();
                if (entry.type() == FileEntry.EntryType.OTHER)
                    throw new IOException("Deployment sources require ordinary entries");
                if (entry.type() == FileEntry.EntryType.DIRECTORY) {
                    if (DeploymentSourcePolicy.directory(path))
                        excluded.add(path + "/");
                    else
                        pending.add(path);
                } else if (DeploymentSourcePolicy.file(path, explicit))
                    excluded.add(path);
                else {
                    if (!included.add(path) || entry.byteCount() > maximumBytes - bytes)
                        throw new IOException("Source selection exceeds its content budget");
                    bytes += entry.byteCount();
                }
            }
        }
        if (!included.containsAll(explicit))
            throw new IOException("An explicit resource is absent or not an ordinary admitted file");
        return new Manifest(List.copyOf(included), List.copyOf(excluded));
    }

    /** Parses the original UTF-8 properties syntax and clears the temporary byte copy. / 解析原UTF-8属性语法并清除临时字节副本。
     * @param declaration original source file / 原来源文件
     * @return exact permitted inclusions / 精确允许纳入项
     * @throws IOException if UTF-8 or properties cannot be read / UTF-8或属性无法读取时 */
    private static Set<String> includes(FileContent declaration) throws IOException {
        byte[] bytes = declaration.content();
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder())) {
            var properties = new Properties();
            properties.load(reader);
            return DeploymentSourcePolicy.explicit(properties.getProperty("source.include", ""));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Binds the selection rule to the declaration actually copied into frozen material. / 将选择规则绑定到实际复制到冻结素材的声明。
     * @param snapshot actual immutable host snapshot / 实际不可变宿主快照
     * @param expected original declaration digest / 原声明摘要
     * @throws IOException if the declaration changed before capture / 声明在捕获前改变时 */
    private static void verifyDeclaration(FileSnapshot snapshot, String expected) throws IOException {
        try (var input = Files.newInputStream(snapshot.directory().resolve(DECLARATION))) {
            byte[] bytes = input.readNBytes(65_537);
            try {
                if (bytes.length > 65_536 || !expected
                    .equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                    throw new IOException("Captured source declaration changed");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("Required SHA-256 unavailable", unavailable);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    /** Stops between discovery steps without publishing cancelled material. / 在发现步骤间停止，不交付已取消素材。
     * @param task original task / 原任务
     * @throws ApiException if cancellation was requested / 已请求取消时 */
    private static void cancelled(TaskContext task) throws ApiException {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            throw problem(ProblemCode.CANCELLED, "DEPLOYMENT_SOURCE_CANCELLED", "Source preparation was cancelled");
    }

    /** Creates a stable non-secret failure without exposing source contents. / 创建稳定非秘密失败，不暴露源码内容。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe description / 安全说明
     * @return original public failure / 原公开失败 */
    private static ApiException problem(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Immutable chosen source names. / 不可变所选来源名称。
     * @param included copied regular files / 复制普通文件
     * @param excluded omitted files and directories / 排除文件及目录 */
    private record Manifest(List<String> included, List<String> excluded) {
    }
}
