package gold.debug.windowstolinux.web.file.workspace;

import gold.debug.windowstolinux.web.file.quota.UploadQuota;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;

/** Owned storage, streaming quotas and strict path checks; never executes uploaded code. */
public final class WebWorkspace {
    private final Path root;
    private final UploadQuota quota;
    private final long minimumFreeBytes;

    public WebWorkspace(Path root, UploadQuota quota, long minimumFreeBytes) throws IOException {
        if (minimumFreeBytes < 0) throw new IllegalArgumentException("Invalid free-space reserve");
        this.root = root.toAbsolutePath().normalize(); this.quota = quota; this.minimumFreeBytes = minimumFreeBytes;
        safeAncestors(this.root); Files.createDirectories(this.root);
    }

    public UploadQuota quota() { return quota; }
    public long minimumFreeBytes() { return minimumFreeBytes; }

    public synchronized java.util.List<WorkspaceAddress> owned(String workspaceId) throws IOException {
        new WorkspaceAddress(workspaceId,"00000000-0000-0000-0000-000000000000");
        Path parent=root.resolve(workspaceId);safeAncestors(parent);
        if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))return java.util.List.of();
        var result=new java.util.ArrayList<WorkspaceAddress>();
        try(var children=Files.list(parent)) {
            for(Path child:children.toList()) {
                if(!child.getFileName().toString().matches("[a-f0-9-]{36}") || !Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))continue;
                var address=new WorkspaceAddress(workspaceId,child.getFileName().toString());
                try { directory(address);result.add(address); } catch(IOException unowned) { /* Preserve anything without exact ownership. */ }
            }
        }
        return java.util.List.copyOf(result);
    }

    public boolean exists(WorkspaceAddress address) throws IOException {
        Path path=root.resolve(address.workspaceId()).resolve(address.resourceId());safeAncestors(path);
        return Files.exists(path,LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized void checkCapacity(WorkspaceAddress address, long additionalBytes) throws IOException {
        Path directory=directory(address);
        if(additionalBytes<0 || size(directory)+additionalBytes>quota.projectBytes() || size(root)+additionalBytes>quota.totalBytes()
                || Files.getFileStore(root).getUsableSpace()<additionalBytes+minimumFreeBytes) throw new IOException("Workspace capacity exceeded");
    }

    public synchronized void discardChild(WorkspaceAddress address,String name) throws IOException {
        if(!name.matches("[a-z][a-z0-9.-]*")) throw new IOException("Invalid cleanup target");
        Path directory=directory(address), child=directory.resolve(name).normalize();
        if(!child.startsWith(directory) || child.equals(directory))throw new IOException("Cleanup boundary mismatch");
        if(!Files.exists(child,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(child)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                if(!path.startsWith(child))throw new IOException("Cleanup boundary mismatch");
                if(!Files.isSymbolicLink(path) && Files.getFileStore(path).supportsFileAttributeView("dos")) Files.setAttribute(path,"dos:readonly",false,LinkOption.NOFOLLOW_LINKS);
                Files.delete(path);
            }
        }
    }

    public synchronized Path create(WorkspaceAddress address) throws IOException {
        Path directory = root.resolve(address.workspaceId()).resolve(address.resourceId());
        safeAncestors(directory); Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        Files.writeString(directory.resolve(".ownership"), address.marker(), StandardOpenOption.CREATE_NEW);
        Files.createDirectory(directory.resolve("source"));
        return directory;
    }

    public Path directory(WorkspaceAddress address) throws IOException {
        Path directory = root.resolve(address.workspaceId()).resolve(address.resourceId());
        safeAncestors(directory);
        Path marker = directory.resolve(".ownership");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 250
                || !Files.readString(marker).equals(address.marker())) throw new IOException("Workspace ownership mismatch");
        return directory;
    }

    public Path source(WorkspaceAddress address) throws IOException { return directory(address).resolve("source"); }

    public synchronized long upload(WorkspaceAddress address, String relative, InputStream input) throws IOException {
        Path directory = directory(address);
        if (Files.exists(directory.resolve(".complete"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source already finalized");
        Path destination = safeMember(directory.resolve("source"), relative);
        long used = size(directory);
        long total = size(root);
        try (var paths = Files.walk(directory.resolve("source"))) {
            if (paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).limit(quota.members()).count() >= quota.members())
                throw new IOException("Too many source members");
        }
        Files.createDirectories(destination.getParent());
        safeAncestors(destination.getParent());
        long count = 0;
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                byte[] buffer = new byte[32768];
                for (int read; (read = input.read(buffer)) != -1;) {
                    count += read;
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Upload cancelled");
                    if (count > quota.fileBytes() || used + count > quota.projectBytes() || total + count > quota.totalBytes())
                        throw new IOException("Upload quota exceeded");
                    output.write(buffer, 0, read);
                }
            }
            return count;
        } catch (IOException failure) {
            if (created) Files.deleteIfExists(destination);
            throw failure;
        }
    }

    public synchronized void complete(WorkspaceAddress address) throws IOException {
        Path directory = directory(address);
        if (size(directory.resolve("source")) == 0) throw new IOException("Source has no content");
        Files.writeString(directory.resolve(".complete"), "ready", StandardOpenOption.CREATE_NEW);
    }

    public synchronized void discard(WorkspaceAddress address) throws IOException {
        Path directory = directory(address);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.normalize().startsWith(root) || path.equals(root)) throw new IOException("Cleanup boundary mismatch");
                if (Files.isSymbolicLink(path)) throw new IOException("Workspace contains a link");
                Files.delete(path);
            }
        }
    }

    public Path safeMember(Path base, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > quota.pathLength() || relative.startsWith("/")
                || relative.contains("\\") || relative.contains(":") || relative.contains("\0")) throw new IOException("Invalid source member");
        for (String part : relative.split("/", -1)) {
            String upper = part.toUpperCase(Locale.ROOT);
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(ch -> ch < 32) || part.matches(".*[<>\"|?*].*")) throw new IOException("Invalid source member");
            if (upper.matches("(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?")) throw new IOException("Reserved source member");
        }
        Path path = base.resolve(relative).normalize();
        if (!path.startsWith(base.normalize()) || path.equals(base)) throw new IOException("Source boundary escape");
        safeAncestors(path);
        return path;
    }

    public static void safeAncestors(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic workspace path");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther()) throw new IOException("Special workspace path");
            }
        }
    }

    public static long size(Path directory) throws IOException {
        long size = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unsafe workspace member");
                if (attributes.isRegularFile()) size = Math.addExact(size, attributes.size());
            }
        }
        return size;
    }
}
