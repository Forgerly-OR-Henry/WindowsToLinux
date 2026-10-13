package gold.debug.windowstolinux.mod.deployment.file;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;

/** Online custody of complete authorized bytes, without retaining any old task grant. */
public final class DeploymentAgentContinuationSource implements AutoCloseable {
    public record Member(long bytes, String sha256) {
        public Member {
            if (bytes < 0 || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid source member");
        }
    }

    private record Owned(Object fileKey, java.nio.file.attribute.FileTime created, String owner) {
    }

    private final UUID id;

    private final UUID originalSnapshot;

    private final String source;

    private final String manifest;

    private final String displayName;

    private final List<String> excluded;

    private final Map<String, Member> members;

    private final long maximumBytes;

    private final int maximumEntries;

    private final Path directory;

    private final Owned directoryIdentity;

    private final Map<String, Owned> memberIdentities;

    private boolean closed;

    private DeploymentAgentContinuationSource(UUID id, DeploymentSourceMaterial material, Map<String, Member> members,
                                              Path directory, long maximumBytes, int maximumEntries) throws IOException {
        this.id = id;
        this.originalSnapshot = material.snapshot().id();
        this.source = material.snapshot().sha256();
        this.displayName = material.displayName();
        this.excluded = List.copyOf(material.excludedEntries());
        this.members = Collections.unmodifiableMap(new TreeMap<>(members));
        this.manifest = manifest(this.members);
        this.maximumBytes = maximumBytes;
        this.maximumEntries = maximumEntries;
        this.directory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        this.directoryIdentity = owned(this.directory);
        var identities = new TreeMap<String, Owned>();
        for (String name : members.keySet()) identities.put(name, owned(member(this.directory, name)));
        this.memberIdentities = Map.copyOf(identities);
    }

    public static DeploymentAgentContinuationSource capture(DeploymentSourceMaterial material,
                                                            DeploymentAgentInputs.Request limits, TaskContext task) throws Exception {
        Objects.requireNonNull(task);
        var root = material.snapshot().directory().toRealPath(LinkOption.NOFOLLOW_LINKS);
        var browser = new DeploymentSourceBrowser(root, material.snapshot().sha256());
        browser.verify();
        var names = new TreeSet<>(material.includedFiles());
        if (names.size() != material.includedFiles().size() || names.size() > limits.sourceEntries())
            throw new IOException("Source custody entry limit exceeded");
        try (var paths = Files.walk(root)) {
            var actual = new TreeSet<String>();
            for (var path : paths.toList()) {
                var facts = attributes(path);
                if (facts.isDirectory()) continue;
                if (!facts.isRegularFile()) throw new IOException("Source member is not ordinary");
                actual.add(root.relativize(path).toString().replace('\\', '/'));
            }
            if (!actual.equals(names)) throw new IOException("Source custody requires the complete original manifest");
        }
        UUID capsuleId = UUID.randomUUID();
        Path copy = Files.createTempDirectory("deployment-agent-source-" + capsuleId + "-").toRealPath(LinkOption.NOFOLLOW_LINKS);
        Owned copyIdentity = owned(copy);
        DeploymentAgentContinuationSource capsule = null;
        boolean retained = false;
        try {
            var members = new TreeMap<String, Member>();
            long bytes = 0;
            for (var name : names) {
                cancelled(task);
                var original = member(root, name);
                var before = attributes(original);
                if (!before.isRegularFile() || before.size() > limits.sourceBytes() - bytes)
                    throw new IOException("Source custody bytes or member type changed");
                var destination = member(copy, name);
                Files.createDirectories(destination.getParent());
                var digest = MessageDigest.getInstance("SHA-256");
                long written = 0;
                try (var input = Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS);
                     var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    try {
                        for (int count; (count = input.read(buffer)) >= 0; ) {
                            cancelled(task);
                            if (count > limits.sourceBytes() - bytes - written)
                                throw new IOException("Source custody byte limit exceeded");
                            output.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            written += count;
                        }
                    } finally {
                        Arrays.fill(buffer, (byte) 0);
                    }
                }
                var after = attributes(original);
                String sha = HexFormat.of().formatHex(digest.digest());
                if (written != before.size() || !sameFile(before, after) || !sha.equals(browser.contentSha256(name)))
                    throw new IOException("Source changed during custody capture");
                bytes = Math.addExact(bytes, written);
                members.put(name, new Member(written, sha));
            }
            browser.verify();
            capsule = new DeploymentAgentContinuationSource(capsuleId, material, members, copy, limits.sourceBytes(), limits.sourceEntries());
            capsule.verify();
            retained = true;
            return capsule;
        } finally {
            if (!retained) removeOwned(copy, copyIdentity);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID originalSnapshot() {
        return originalSnapshot;
    }

    public String source() {
        return source;
    }

    public String manifest() {
        return manifest;
    }

    public long bytes() {
        return members.values().stream().mapToLong(Member::bytes).sum();
    }

    public int entries() {
        return members.size();
    }

    /** Only a freshly claimed child creates its own workspace selection and immutable snapshot. */
    public synchronized DeploymentSourceMaterial rebind(FileService files, TaskContext child) throws Exception {
        cancelled(child);
        verify();
        try (var work = files.workspaces().workspace(child)) {
            var root = work.directory().toRealPath(LinkOption.NOFOLLOW_LINKS);
            for (var entry : members.entrySet()) {
                cancelled(child);
                var original = member(directory, entry.getKey());
                var destination = member(root, entry.getKey());
                Files.createDirectories(destination.getParent());
                Files.copy(original, destination, LinkOption.NOFOLLOW_LINKS);
                if (!entry.getValue().equals(observe(destination)))
                    throw new IOException("Child source transfer changed");
            }
            verify();
            var snapshot = files.snapshots().snapshot(work.selection(),
                new SnapshotRequest(List.copyOf(members.keySet()), maximumBytes), child);
            boolean retained = false;
            try {
                if (snapshot.id().equals(originalSnapshot) || !snapshot.sha256().equals(source))
                    throw new IOException("Child source manifest differs from its original authorized bytes");
                var browser = new DeploymentSourceBrowser(snapshot.directory(), source);
                browser.verify();
                var material = new DeploymentSourceMaterial(displayName, snapshot, List.copyOf(members.keySet()), excluded);
                retained = true;
                return material;
            } finally {
                if (!retained) snapshot.close();
            }
        }
    }

    public synchronized void verify() throws Exception {
        if (closed || !attributes(directory).isDirectory() || !directoryIdentity.equals(owned(directory)))
            throw new IOException("Online source custody lost");
        var actual = new TreeMap<String, Member>();
        int entries = 0;
        long bytes = 0;
        try (var paths = Files.walk(directory)) {
            for (var path : paths.toList()) {
                if (path.equals(directory)) continue;
                if (++entries > maximumEntries) throw new IOException("Source custody inventory limit exceeded");
                var facts = attributes(path);
                String name = directory.relativize(path).toString().replace('\\', '/');
                if (facts.isDirectory()) {
                    if (members.keySet().stream().noneMatch(member -> member.startsWith(name + "/")))
                        throw new IOException("Source custody acquired an unowned directory");
                    continue;
                }
                if (!facts.isRegularFile()) throw new IOException("Source custody link or special member rejected");
                if (!members.containsKey(name) || !memberIdentities.get(name).equals(owned(path)))
                    throw new IOException("Source custody acquired an unowned member");
                if (facts.size() > maximumBytes - bytes) throw new IOException("Source custody byte limit exceeded");
                var value = observe(path);
                bytes = Math.addExact(bytes, value.bytes());
                if (bytes > maximumBytes) throw new IOException("Source custody byte limit exceeded");
                actual.put(name, value);
            }
        }
        if (!members.equals(actual) || !manifest.equals(manifest(actual)))
            throw new IOException("Online source custody manifest changed");
        if (!directoryIdentity.equals(owned(directory)))
            throw new IOException("Online source custody identity changed");
    }

    private static Member observe(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var before = attributes(path);
        var identity = owned(path);
        long bytes = 0;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            try {
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, count);
                    bytes = Math.addExact(bytes, count);
                }
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }
        if (!sameFile(before, attributes(path)) || !identity.equals(owned(path)) || bytes != before.size())
            throw new IOException("Source custody member changed while reading");
        return new Member(bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static Path member(Path root, String name) throws IOException {
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.contains("\\") || name.contains(":")
            || name.startsWith("/") || Arrays.stream(name.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IOException("Source member path rejected");
        var path = root.resolve(name).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Source member escaped its owned root");
        for (var current = path; current != null && current.startsWith(root); current = current.getParent())
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                attributes(current);
                if (!current.toRealPath().startsWith(root.toRealPath()))
                    throw new IOException("Source member link escaped its owned root");
            }
        return path;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        var facts = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (facts.isSymbolicLink() || facts.isOther())
            throw new IOException("Source custody link or special member rejected");
        return facts;
    }

    private static boolean sameFile(BasicFileAttributes a, BasicFileAttributes b) {
        return a.isRegularFile() && b.isRegularFile() && a.size() == b.size()
            && Objects.equals(a.fileKey(), b.fileKey()) && a.creationTime().equals(b.creationTime())
            && a.lastModifiedTime().equals(b.lastModifiedTime());
    }

    private static Owned owned(Path path) throws IOException {
        var facts = attributes(path);
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
        if (owner.isBlank() || facts.creationTime().toMillis() <= 0)
            throw new IOException("Source custody identity unavailable");
        // Windows BasicFileAttributes has no fileKey. Its exact private creation/owner facts remain mandatory.
        return new Owned(facts.fileKey(), facts.creationTime(), owner);
    }

    private static String manifest(Map<String, Member> members) throws IOException {
        return DeploymentAgentInputs.hash(new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(new TreeMap<>(members)));
    }

    private static void cancelled(TaskContext task) {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Source custody cancelled");
    }

    private static void removeOwned(Path directory, Owned identity) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source custody deletion is unconfirmed");
        if (!identity.equals(owned(directory)) || !attributes(directory).isDirectory())
            throw new IOException("Source custody deletion identity changed");
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!identity.equals(owned(directory)))
                    throw new IOException("Source custody deletion identity changed");
                if (!path.equals(directory))
                    member(directory, directory.relativize(path).toString().replace('\\', '/'));
                attributes(path);
                Files.delete(path);
            }
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source custody deletion is unconfirmed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            removeOwned(directory, directoryIdentity);
            closed = true;
        }
    }
}
