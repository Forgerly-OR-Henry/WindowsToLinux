package gold.debug.windowstolinux.app.linux.ssh;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.TransferRequest;

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
