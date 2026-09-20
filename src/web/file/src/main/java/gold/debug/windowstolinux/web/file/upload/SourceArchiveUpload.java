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

/** Bounded extraction; ZIP central-directory metadata is checked before accepting members. */
public final class SourceArchiveUpload {
    private final WebWorkspace workspace;
    public SourceArchiveUpload(WebWorkspace workspace) { this.workspace = workspace; }

    public void extract(WorkspaceAddress address, String format, InputStream input) throws IOException {
        synchronized(workspace) { extractLocked(address,format,input); }
    }
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
