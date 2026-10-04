package gold.debug.windowstolinux.mod.deployment.file;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspace;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Builds deterministic archives using only platform-owned staging and snapshots. / 仅使用平台所属暂存及快照构建确定性归档。 */
public final class DeploymentSourceArchiver {
    /** Original activation's source capability. / 原启用的来源能力。 */
    private final FileService source;

    /** Binds the original public source capability. / 绑定原公开来源能力。
     * @param source original source capability / 原来源能力 */
    public DeploymentSourceArchiver(FileService source) {
        this.source = Objects.requireNonNull(source);
    }

    /** Writes, freezes and verifies one archive without consuming the input material. / 写入、冻结并核验一份归档，不消耗输入素材。
     * @param material already frozen exact source files / 已冻结精确来源文件
     * @param maximumBytes compressed archive ceiling up to two GiB / 最多二 GiB 的压缩归档上限
     * @param task original task / 原任务
     * @return independently owned frozen archive / 独立所属冻结归档
     * @throws ApiException if writing, cancellation, verification or cleanup fails / 写入、取消、核验或清理失败时 */
    public DeploymentSourceArchive prepare(DeploymentSourceMaterial material, long maximumBytes, TaskContext task)
        throws ApiException {
        return prepare(material, "", maximumBytes, task);
    }

    /** Archives one discovered subtree while keeping the original snapshot identity. / 归档一棵已发现子树，同时保持原快照身份。
     * @param material original frozen material / 原冻结素材
     * @param relativeRoot canonical snapshot-relative root, empty for the whole source / 规范快照相对根，全来源为空
     * @param maximumBytes compressed byte ceiling / 压缩字节上限
     * @param task original task / 原任务
     * @return original-task component archive / 原任务组件归档
     * @throws ApiException if creation or verification fails / 创建或核验失败时 */
    public DeploymentSourceArchive prepare(DeploymentSourceMaterial material, String relativeRoot, long maximumBytes,
                                           TaskContext task) throws ApiException {
        Objects.requireNonNull(material);
        Objects.requireNonNull(relativeRoot);
        if (maximumBytes < 1 || maximumBytes > 2L * 1024 * 1024 * 1024)
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_ARCHIVE_BUDGET", "Source archive budget is invalid");
        FileWorkspace workspace = null;
        FileSnapshot frozen = null;
        boolean published = false;
        try {
            cancelled(task);
            UUID originSnapshotId = material.snapshot().id();
            String sourceManifestSha256 = material.snapshot().sha256();
            workspace = source.workspaces().workspace(task);
            Path file = workspace.directory().resolve("source.tar.gz");
            var digest = digest();
            long bytes, originalBytes;
            try (var bounded = new BoundedOutput(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW),
                maximumBytes); var output = new DigestOutputStream(bounded, digest)) {
                originalBytes = DeploymentSourceTar.write(material, relativeRoot, output, task);
                bytes = bounded.count;
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            frozen = source.snapshots().snapshot(workspace.selection(), new SnapshotRequest(List.of("source.tar.gz"), maximumBytes),
                task);
            verify(frozen.directory().resolve("source.tar.gz"), sha256, bytes, task);
            cancelled(task);
            if (!originSnapshotId.equals(material.snapshot().id())
                || !sourceManifestSha256.equals(material.snapshot().sha256()))
                throw new IOException("Original source snapshot identity changed");
            workspace.close();
            var archive = new DeploymentSourceArchive(frozen, sha256, bytes, originalBytes, originSnapshotId,
                sourceManifestSha256, relativeRoot);
            published = true;
            return archive;
        } catch (IOException | IllegalStateException failure) {
            cancelled(task);
            throw problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_ARCHIVE_FAILED",
                "Source archive could not be written or verified");
        } catch (IllegalArgumentException failure) {
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_ARCHIVE_INPUT", "Source archive inputs are invalid");
        } finally {
            try {
                if (workspace != null)
                    workspace.close();
            } finally {
                if (!published && frozen != null)
                    frozen.close();
            }
        }
    }

    /** Verifies the actual frozen archive against the bytes accepted by the output stream. / 按输出流实际接收字节核验冻结归档。
     * @param file frozen archive / 冻结归档
     * @param expected original compressed digest / 原压缩摘要
     * @param length original compressed length / 原压缩长度
     * @param task original task / 原任务
     * @throws IOException if the frozen output changed / 冻结输出变化时
     * @throws ApiException if cancelled / 已取消时 */
    private static void verify(Path file, String expected, long length, TaskContext task)
        throws IOException, ApiException {
        var digest = digest();
        long bytes = 0;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                cancelled(task);
                bytes += count;
                if (bytes > length)
                    throw new IOException("Frozen archive grew");
                digest.update(buffer, 0, count);
            }
        }
        if (bytes != length || !expected.equals(HexFormat.of().formatHex(digest.digest())))
            throw new IOException("Frozen archive differs from produced bytes");
    }

    /** Creates the required content hash. / 创建必需的内容散列。
     * @return independent digest / 独立摘要 */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 unavailable", failure);
        }
    }

    /** Stops archive production before publishing a cancelled result. / 在交付已取消结果前停止归档生成。
     * @param task original task / 原任务
     * @throws ApiException if cancellation was requested / 已请求取消时 */
    static void cancelled(TaskContext task) throws ApiException {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            throw problem(ProblemCode.CANCELLED, "DEPLOYMENT_ARCHIVE_CANCELLED",
                "Source archive preparation was cancelled");
    }

    /** Builds a fixed diagnostic without local paths or source contents. / 构建不含本地路径或源码内容的固定诊断。
     * @param code public category / 公开类别
     * @param event fixed event / 固定事件
     * @param message safe description / 安全说明
     * @return public failure / 公开失败 */
    private static ApiException problem(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Bounds the actual compressed output, including gzip headers and trailers. / 限制实际压缩输出，包括 gzip 头及尾。 */
    private static final class BoundedOutput extends FilterOutputStream {
        /** Maximum compressed length. / 最大压缩长度。 */
        private final long maximum;

        /** Bytes actually written. / 实际写入字节。 */
        private long count;

        /** Binds the exact output and budget. / 绑定精确输出及预算。
         * @param output owned file stream / 所属文件流
         * @param maximum compressed byte budget / 压缩字节预算 */
        private BoundedOutput(OutputStream output, long maximum) {
            super(output);
            this.maximum = maximum;
        }

        /** Writes one bounded byte. / 写入一个有界字节。
         * @param value byte value / 字节值
         * @throws IOException if capacity or writing fails / 容量或写入失败时 */
        @Override
        public void write(int value) throws IOException {
            if (count == maximum)
                throw new IOException("Source archive budget exceeded");
            out.write(value);
            count++;
        }

        /** Writes one bounded chunk without exceeding the destination budget. / 写入一块有界数据，不超过目标预算。
         * @param buffer source bytes / 来源字节
         * @param offset first byte / 首字节
         * @param length chunk length / 数据块长度
         * @throws IOException if capacity or writing fails / 容量或写入失败时 */
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length > maximum - count)
                throw new IOException("Source archive budget exceeded");
            out.write(buffer, offset, length);
            count += length;
        }
    }
}
