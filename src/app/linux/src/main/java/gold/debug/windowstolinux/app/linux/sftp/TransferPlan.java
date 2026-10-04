package gold.debug.windowstolinux.app.linux.sftp;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;

/** Immutable observations persisted before staging one original transfer. / 暂存一次原传输前持久化的不可变观测。
 * @param id original transfer identity / 原传输身份
 * @param request originally reviewed endpoints and limit / 原已审核端点及限额
 * @param upload whether destination is remote / 目标是否在远端
 * @param localPath actual selected local endpoint / 实际选定本地路径
 * @param remotePath actual selected remote endpoint / 实际选定远端路径
 * @param source verified original source facts / 已核验原源事实
 * @param target original replacement target, null for confirmed absence / 原替换目标，确认缺失时为 null */
public record TransferPlan(UUID id, TransferRequest request, boolean upload, Path localPath, String remotePath,
                           FileStamp source, FileStamp target) {
    /** Requires the exact immutable observations. / 要求精确不可变观测。
     * @param id original identity / 原身份
     * @param request reviewed request / 已审核请求
     * @param upload transfer direction / 传输方向
     * @param localPath selected local endpoint / 选定本地路径
     * @param remotePath selected remote endpoint / 选定远端路径
     * @param source verified source / 已核验源
     * @param target original target or null / 原目标或 null */
    public TransferPlan {
        Objects.requireNonNull(id);
        Objects.requireNonNull(request);
        Objects.requireNonNull(localPath);
        Objects.requireNonNull(remotePath);
        Objects.requireNonNull(source);
        validateLocal(request.localPath());
        validateLocal(localPath);
        validateRemote(request.remoteAbsolutePath());
        validateRemote(remotePath);
        if (source.size() > request.maxBytes()
            || upload && (!localPath.equals(request.localPath())
            || !remoteParent(remotePath).equals(remoteParent(request.remoteAbsolutePath())))
            || !upload && (!remotePath.equals(request.remoteAbsolutePath())
            || !localPath.getParent().equals(request.localPath().getParent()))
            || target != null
            && (!localPath.equals(request.localPath()) || !remotePath.equals(request.remoteAbsolutePath())))
            throw new IllegalArgumentException("Transfer endpoints differ from original scope");
        if (!source.identity().matches(upload ? "[0-9a-f]{48}" : "remote")
            || target != null && !target.identity().matches(upload ? "remote" : "[0-9a-f]{48}"))
            throw new IllegalArgumentException("Transfer identity does not match its endpoint role");
    }

    /** Derives the staging path from the original identity and selected destination. / 根据原身份及选定目标推导暂存路径。
     * @return exact sibling staging path / 精确同目录暂存路径 */
    public String temporaryPath() {
        String name = ".wtl-transfer-" + id + ".part";
        return upload
            ? remotePath.substring(0, remotePath.lastIndexOf('/') + 1) + name
            : localPath.resolveSibling(name).toString();
    }

    /** Produces bounded flat facts without content, credentials or an unverified offset. / 生成有界扁平事实，不含正文、凭据或未核验偏移。
     * @return immutable version-protocol payload / 不可变版本协议载荷 */
    public Map<String, String> recoveryFields() {
        var fields = new TreeMap<String, String>();
        fields.put("transferId", id.toString());
        fields.put("upload", Boolean.toString(upload));
        fields.put("requestedLocal", request.localPath().toString());
        fields.put("requestedRemote", request.remoteAbsolutePath());
        fields.put("maxBytes", Long.toString(request.maxBytes()));
        fields.put("selectedLocal", localPath.toString());
        fields.put("selectedRemote", remotePath);
        fields.put("temporaryPath", temporaryPath());
        fields.put("overwrite", Boolean.toString(target != null));
        source.append(fields, "source");
        if (target != null)
            target.append(fields, "target");
        return Map.copyOf(fields);
    }

    /** Reconstructs only an exact supported plan, rejecting unknown fields and altered staging names. / 仅重建精确受支持计划，拒绝未知字段及被更改的暂存名。
     * @param fields exact transport facts without host server fields / 不含宿主服务器字段的精确传输事实
     * @return validated original plan / 已校验原计划 */
    public static TransferPlan fromRecoveryFields(Map<String, String> fields) {
        boolean upload = bool(fields, "upload"), overwrite = bool(fields, "overwrite");
        var plan = new TransferPlan(UUID.fromString(fields.get("transferId")),
            new TransferRequest(Path.of(fields.get("requestedLocal")), fields.get("requestedRemote"),
                Long.parseLong(fields.get("maxBytes"))),
            upload, Path.of(fields.get("selectedLocal")), fields.get("selectedRemote"), stamp(fields, "source"),
            overwrite ? stamp(fields, "target") : null);
        if (!plan.recoveryFields().equals(fields))
            throw new IllegalArgumentException("Recovery plan facts are not canonical or complete");
        return plan;
    }

    /** Reads one complete stored file observation. / 读取一个完整存储文件观测。
     * @param fields original facts / 原事实
     * @param prefix source or target role / 源或目标角色
     * @return validated observation / 已校验观测 */
    private static FileStamp stamp(Map<String, String> fields, String prefix) {
        return new FileStamp(fields.get(prefix + ".identity"), Long.parseLong(fields.get(prefix + ".size")),
            fields.get(prefix + ".modified"), fields.get(prefix + ".sha256"));
    }

    /** Reads an exact boolean without silently accepting malformed data. / 读取精确布尔值，不默默接受损坏数据。
     * @param fields original facts / 原事实
     * @param key exact field / 精确字段
     * @return boolean value / 布尔值 */
    private static boolean bool(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (!"true".equals(value) && !"false".equals(value))
            throw new IllegalArgumentException("Invalid recovery boolean");
        return "true".equals(value);
    }

    /** Requires an ordinary absolute normalized local file spelling. / 要求普通本地文件的绝对规范拼写。
     * @param path exact local file / 精确本地文件 */
    private static void validateLocal(Path path) {
        if (!path.isAbsolute() || !path.normalize().equals(path) || path.getParent() == null
            || path.toString().codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid transfer local path");
    }

    /** Requires the original portable remote absolute file spelling. / 要求原可移植远端绝对文件拼写。
     * @param path exact remote file / 精确远端文件 */
    private static void validateRemote(String path) {
        if (path.length() > 4096 || !path.startsWith("/") || path.equals("/") || path.indexOf('\\') >= 0
            || path.codePoints().anyMatch(Character::isISOControl)
            || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(path))
            throw new IllegalArgumentException("Invalid transfer remote path");
        for (String part : path.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Invalid transfer remote component");
    }

    /** Returns the lexical parent of a validated remote file. / 返回已校验远端文件的词法父目录。
     * @param path validated path / 已校验路径
     * @return remote parent / 远端父目录 */
    private static String remoteParent(String path) {
        return path.substring(0, path.lastIndexOf('/') + 1);
    }

    /** Content and metadata bound to an observed ordinary file. / 绑定到已观察普通文件的内容及元数据。
     * @param identity native local identity or remote protocol marker / 本地原生身份或远端协议标记
     * @param size exact observed byte length / 精确已观察字节长度
     * @param modified observed modification time / 已观察修改时间
     * @param sha256 observed content digest / 已观察内容摘要 */
    public record FileStamp(String identity, long size, String modified, String sha256) {
        /** Validates the complete observation. / 校验完整观测。
         * @param identity observed identity / 已观察身份
         * @param size observed length / 已观察长度
         * @param modified observed modification time / 已观察修改时间
         * @param sha256 observed digest / 已观察摘要 */
        public FileStamp {
            if (Objects.requireNonNull(identity).isBlank() || size < 0 || Objects.requireNonNull(modified).isBlank()
                || !Objects.requireNonNull(sha256).matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Incomplete transfer file observation");
        }

        /** Appends this observation under an explicit role. / 在明确角色下追加此观测。
         * @param fields destination recovery facts / 目标恢复事实
         * @param prefix source or target role / 源或目标角色 */
        private void append(Map<String, String> fields, String prefix) {
            fields.put(prefix + ".identity", identity);
            fields.put(prefix + ".size", Long.toString(size));
            fields.put(prefix + ".modified", modified);
            fields.put(prefix + ".sha256", sha256);
        }
    }
}
