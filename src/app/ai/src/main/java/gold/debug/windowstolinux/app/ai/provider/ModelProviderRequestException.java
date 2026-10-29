package gold.debug.windowstolinux.app.ai.provider;

/** Rejects a documented model/protocol combination before transport. / 在线传输前拒绝官方不支持的型号协议组合。
 */
public final class ModelProviderRequestException extends IllegalArgumentException {
    /** Captures a non-secret incompatibility reason. / 捕获不含秘密的不兼容原因。
     * @param message documented incompatibility / 官方不支持的组合
     */
    public ModelProviderRequestException(String message) {
        super(message);
    }
}
