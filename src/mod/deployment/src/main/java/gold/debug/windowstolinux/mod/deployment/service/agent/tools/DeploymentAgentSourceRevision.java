package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;

/** Byte semantics shared with source-operations.py, verified by cross-language fixtures. / 与 source-operations.py 一致的字节语义，由跨语言夹具验证。 */
public final class DeploymentAgentSourceRevision {
    /** Prevents construction / 禁止构造。
     */
    private DeploymentAgentSourceRevision() {
    }

    /** Computes the helper tree identity rather than the platform snapshot identity. / 计算 helper 树身份而非平台快照身份。
     * @param root owned private source / 所属私有来源
     * @return actual digest / 实际摘要
     * @throws IOException unsafe or excessive tree / 不安全或超量树 */
    public static String digest(Path root) throws IOException {
        var hash = sha256();
        List<Path> members;
        try (var walk = Files.walk(root)) {
            members = walk.filter(p -> !p.equals(root)).limit(40001).toList();
        }
        if (members.size() > 40000) throw new IOException("Source member limit");
        var sorted = new ArrayList<>(members);
        sorted.sort((a, b) -> Arrays.compare(name(root, a).codePoints().toArray(), name(root, b).codePoints().toArray()));
        int files = 0;
        for (Path member : sorted) {
            if (Files.isSymbolicLink(member)) throw new IOException("Source symlink");
            if (Files.isDirectory(member, LinkOption.NOFOLLOW_LINKS)) continue;
            safe(root, name(root, member));
            if (++files > 20000) throw new IOException("Source file limit");
            byte[] encoded = name(root, member).getBytes(StandardCharsets.UTF_8);
            hash.update((encoded.length + ":").getBytes(StandardCharsets.US_ASCII));
            hash.update(encoded);
            hash.update((":" + Files.size(member) + ":").getBytes(StandardCharsets.US_ASCII));
            try (var input = Files.newInputStream(member)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
        }
        if (files == 0) throw new IOException("Empty source");
        return HexFormat.of().formatHex(hash.digest());
    }

    /** Applies only the exact validated patch in a task-owned writable copy. / 只在任务所属可写副本应用精确已验证补丁。
     * @param root owned private copy / 所属私有副本
     * @param patch exact proposed patch / 精确提议补丁
     * @return predicted helper revision / 预测 helper 修订
     * @throws IOException conflict or unsafe bytes / 冲突或不安全字节 */
    public static String patch(Path root, DeploymentAgentPatch patch) throws IOException {
        if (!digest(root).equals(patch.sourceRevision())) throw new IOException("Source revision conflict");
        Path path = safe(root, patch.path());
        for (String part : patch.path().split("/")) {
            String value = part.toLowerCase(Locale.ROOT);
            if (Set.of(".git", ".ssh", ".env", ".npmrc", ".pypirc", ".w2l").contains(value)
                || value.startsWith(".env.") || value.matches(".*\\.(pem|key|p12|pfx)$"))
                throw new IOException("Protected source path");
        }
        if (Files.size(path) > 262144) throw new IOException("Patch file limit");
        byte[] original = Files.readAllBytes(path);
        if (!HexFormat.of().formatHex(sha256().digest(original)).equals(patch.beforeDigest()))
            throw new IOException("Patch digest conflict");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original)).toString();
        if (text.indexOf('\0') >= 0) throw new IOException("Binary patch");
        String separatorPattern = "\\r\\n|[\\n\\r\\u000b\\u000c\\u001c-\\u001e\\u0085\\u2028\\u2029]";
        var lines = new ArrayList<>(Arrays.asList(text.split(separatorPattern, -1)));
        if (text.isEmpty() || text.matches("(?s).*(" + separatorPattern + ")$")) lines.removeLast();
        var result = new ArrayList<String>();
        int consumed = 0, previous = -1;
        for (var edit : patch.edits()) {
            int start = edit.line() - 1;
            for (String line : java.util.stream.Stream.concat(edit.removed().stream(), edit.added().stream()).toList())
                if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0)
                    throw new IOException("Invalid patch line");
            if (start < consumed || start <= previous || start > lines.size()
                || start + edit.removed().size() > lines.size()
                || !lines.subList(start, start + edit.removed().size()).equals(edit.removed()))
                throw new IOException("Patch line conflict");
            result.addAll(lines.subList(consumed, start));
            result.addAll(edit.added());
            consumed = start + edit.removed().size();
            previous = start;
        }
        result.addAll(lines.subList(consumed, lines.size()));
        String separator = text.contains("\r\n") ? "\r\n" : "\n";
        byte[] updated = (String.join(separator, result) + (text.endsWith("\n") ? separator : "")).getBytes(StandardCharsets.UTF_8);
        if (updated.length > 262144 || Arrays.equals(original, updated))
            throw new IOException("Empty or oversized patch");
        Files.write(path, updated, StandardOpenOption.TRUNCATE_EXISTING);
        return digest(root);
    }

    /** Resolves only a private ordinary member below the exact owned root. / 只解析精确所属根内的私有普通成员。
     * @param root original root / 原根
     * @param name registered action name / 已登记动作名称
     * @return verified member / 已核实成员
     * @throws IOException unsafe member / 不安全成员 */
    public static Path safe(Path root, String name) throws IOException {
        if (name.isEmpty() || name.length() > 500 || name.contains("\\") || name.contains(":"))
            throw new IOException("Source path");
        Path path = root;
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.chars().anyMatch(c -> c < 32))
                throw new IOException("Source traversal");
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) throw new IOException("Source symlink");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source not ordinary");
        try {
            if (!Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS).equals(1))
                throw new IOException("Source hardlink");
        } catch (UnsupportedOperationException unavailable) {
            // Windows snapshots already reject links; copies below are new private files. / Windows 快照已拒绝链接，下游副本均为新私有文件。
        }
        return path;
    }

    /** Normalizes a relative member for helper-compatible digest ordering / 规范相对成员以匹配helper摘要排序。
     * @param root Component source root / 组件源码根
     * @param path Source-relative member / 来源相对成员
     * @return checked current result / 已校验当前结果
     */
    private static String name(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** Creates a SHA-256 accumulator / 创建SHA-256累积器。
     * @return checked current result / 已校验当前结果
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
