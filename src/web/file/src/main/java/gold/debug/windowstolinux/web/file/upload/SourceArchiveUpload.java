package gold.debug.windowstolinux.web.file.upload;

import gold.debug.windowstolinux.web.file.workspace.WebWorkspace;
import gold.debug.windowstolinux.web.file.workspace.WorkspaceAddress;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;

/**
 * Extracts bounded source archives and checks ZIP central-directory metadata before accepting members.
 * <p>提取有界源码归档，并在接受成员前检查 ZIP 中央目录元数据。
 */
public final class SourceArchiveUpload {
    /**
     * Platform-owned work area with enforced path boundaries.
     * <p>具有路径边界约束的平台工作区。
     */
    private final WebWorkspace workspace;
    /**
     * Binds the supplied dependencies and state for source archive upload.
     * <p>为源码归档上传绑定传入的依赖及状态。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     */
    public SourceArchiveUpload(WebWorkspace workspace) { this.workspace = workspace; }

    /**
     * Serializes archive extraction against the workspace to preserve quota and ownership checks.
     * <p>相对于工作区串行执行归档提取，以保持配额及归属检查。
     *
     * @param address address / 地址
     * @param format format / 格式
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public void extract(WorkspaceAddress address, String format, InputStream input) throws IOException {
        synchronized(workspace) { extractLocked(address,format,input); }
    }
    /**
     * Spools an accepted ZIP or tar.gz upload to owned storage, extracts bounded contents and cleans up the spool.
     * <p>将准入的 ZIP 或 tar.gz 上传暂存到自有存储，提取有界内容并清理暂存文件。
     *
     * @param address address / 地址
     * @param format format / 格式
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void extractLocked(WorkspaceAddress address,String format,InputStream input) throws IOException {
        if (!format.equals("zip") && !format.equals("tar.gz")) throw new IOException("Unsupported source archive");
        Path spool = workspace.directory(address).resolve("upload.archive");
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(spool, StandardOpenOption.CREATE_NEW)) {
                created = true;
                long count = 0;
                byte[] buffer = new byte[32768];
                for (int read; (read = input.read(buffer)) != -1;) {
                    count += read;
                    if (count > workspace.quota().projectBytes()) throw new IOException("Archive quota exceeded");
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Archive upload cancelled");
                    workspace.checkCapacity(address,read);
                    output.write(buffer, 0, read);
                }
            }
            if (format.equals("zip")) zip(address, spool); else tar(address, spool);
        } finally { if (created) Files.deleteIfExists(spool); }
    }

    /**
     * Extracts readable regular ZIP members while enforcing member count, path and workspace quotas.
     * <p>提取可读常规 ZIP 成员，并执行成员数量、路径及工作区配额约束。
     *
     * @param address address / 地址
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void zip(WorkspaceAddress address, Path archive) throws IOException {
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            var entries = zip.getEntries();
            int count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > workspace.quota().members() || entry.isUnixSymlink() || !zip.canReadEntryData(entry))
                    throw new IOException("Unsafe ZIP member");
                int mode = entry.getUnixMode() & 0170000;
                if (mode != 0 && mode != 0100000 && mode != 0040000) throw new IOException("Special ZIP member");
                String name = entry.getName();
                if (entry.isDirectory()) { workspace.safeMember(workspace.source(address), name.replaceFirst("/$", "")); continue; }
                if (entry.getSize() < 0 || entry.getSize() > workspace.quota().fileBytes()) throw new IOException("Oversized ZIP member");
                try (var input = zip.getInputStream(entry)) {
                    long bytes = workspace.upload(address, name, input);
                    if (bytes != entry.getSize()) throw new IOException("ZIP member size mismatch");
                }
            }
        }
    }

    /**
     * Extracts bounded regular tar.gz members while rejecting links and unsupported entry types.
     * <p>提取有界常规 tar.gz 成员，并拒绝链接及不支持的条目类型。
     *
     * @param address address / 地址
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void tar(WorkspaceAddress address, Path archive) throws IOException {
        try (var input = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            int count = 0;
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (++count > workspace.quota().members() || entry.isLink() || entry.isSymbolicLink()
                        || entry.isSparse() || (!entry.isFile() && !entry.isDirectory())) throw new IOException("Unsafe TAR member");
                String name = entry.getName();
                if (entry.isDirectory()) { workspace.safeMember(workspace.source(address), name.replaceFirst("/$", "")); continue; }
                if (entry.getSize() < 0 || entry.getSize() > workspace.quota().fileBytes()) throw new IOException("Oversized TAR member");
                if (workspace.upload(address, name, input) != entry.getSize()) throw new IOException("TAR member size mismatch");
            }
        }
    }
}
