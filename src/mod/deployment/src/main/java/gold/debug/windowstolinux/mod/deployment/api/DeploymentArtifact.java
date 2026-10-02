package gold.debug.windowstolinux.mod.deployment.api;

/** Verified remote artifact owned by one collection. / 一次采集所属的已验证远端产物。
 * @param id remote artifact identity / 远端产物身份
 * @param bindingId reviewed binding / 已审阅绑定
 * @param remotePath exact remote path / 精确远端路径
 * @param byteCount observed byte length / 已观察字节长度
 * @param sha256 observed digest / 已观察摘要 */
public record DeploymentArtifact(String id, String bindingId, String remotePath, long byteCount, String sha256) {
}
