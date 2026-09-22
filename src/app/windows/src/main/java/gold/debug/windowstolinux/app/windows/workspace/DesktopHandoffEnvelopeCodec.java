package gold.debug.windowstolinux.app.windows.workspace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Authenticated binary envelope shared by independently transported desktop handoffs. / 独立传输桌面交接共用的认证二进制信封。
 */
public final class DesktopHandoffEnvelopeCodec {
    /**
     * MAGIC.
     * <p>格式标记。
     */
    private static final int MAGIC = 0x57544846;

    /**
     * VERSION.
     * <p>版本。
     */
    private static final int VERSION = 1;

    /**
     * TAG BYTES.
     * <p>标签字节。
     */
    private static final int TAG_BYTES = 32;

    /**
     * MAXIMUM PAYLOAD BYTES.
     * <p>最大载荷字节。
     */
    private static final int MAXIMUM_PAYLOAD_BYTES = 1024 * 1024;

    /**
     * MAXIMUM DOCUMENT BYTES.
     * <p>最大文档字节。
     */
    private static final int MAXIMUM_DOCUMENT_BYTES = MAXIMUM_PAYLOAD_BYTES + TAG_BYTES + 32;

    /**
     * Closed handoff purposes prevent one authenticated document from crossing maintenance operations. / 封闭交接用途防止认证文档跨维护操作使用。
     */
    public enum PurposeType {
        /**
         * UPDATE classification within purpose type.
         * <p>用途类型中的更新分类。
         */
        UPDATE(1),
        /**
         * UNINSTALL classification within purpose type.
         * <p>用途类型中的卸载分类。
         */
        UNINSTALL(2);

        /**
         * Stable machine-readable classification code.
         * <p>稳定的机器可读分类码。
         */
        private final int code;

        /**
         * Binds the supplied dependencies and state for purpose type.
         * <p>为用途类型绑定传入的依赖及状态。
         *
         * @param code stable machine-readable classification code / 稳定的机器可读分类码
         */
        PurposeType(int code) {
            this.code = code;
        }
    }

    /**
     * Authenticates one bounded payload without retaining the caller-owned key. / 认证一个有界载荷且不持有调用方密钥。
     *
     * @param purpose purpose / 用途
     * @param payload payload / 载荷
     * @param authenticationKey authentication key / 认证键
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] write(PurposeType purpose, byte[] payload, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        purpose = Objects.requireNonNull(purpose, "purpose");
        if (payload == null)
            throw invalid("desktop handoff payload is missing", null);
        payload = payload.clone();
        if (payload.length == 0 || payload.length > MAXIMUM_PAYLOAD_BYTES) {
            throw invalid("desktop handoff payload is outside the supported boundary", null);
        }
        try {
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(payload.length + 16);
            try (DataOutputStream body = new DataOutputStream(bodyBytes)) {
                body.writeInt(MAGIC);
                body.writeInt(VERSION);
                body.writeByte(purpose.code);
                body.writeInt(payload.length);
                body.write(payload);
            }
            byte[] authenticated = bodyBytes.toByteArray();
            byte[] tag = tag(authenticated, authenticationKey);
            try {
                ByteArrayOutputStream document = new ByteArrayOutputStream(authenticated.length + tag.length);
                document.write(authenticated);
                document.write(tag);
                return document.toByteArray();
            } finally {
                Arrays.fill(authenticated, (byte) 0);
                Arrays.fill(tag, (byte) 0);
            }
        } catch (IOException | RuntimeException exception) {
            throw invalid("desktop handoff envelope could not be encoded", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Authenticates the complete envelope before parsing or returning any payload bytes. / 在解析或返回任何载荷字节前认证完整信封。
     *
     * @param expectedPurpose expected purpose / 预期用途
     * @param document document / 文档
     * @param authenticationKey authentication key / 认证键
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] read(PurposeType expectedPurpose, byte[] document, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        expectedPurpose = Objects.requireNonNull(expectedPurpose, "expectedPurpose");
        if (document == null)
            throw invalid("desktop handoff envelope is missing", null);
        document = document.clone();
        if (document.length <= TAG_BYTES || document.length > MAXIMUM_DOCUMENT_BYTES) {
            Arrays.fill(document, (byte) 0);
            throw invalid("desktop handoff envelope is outside the supported boundary", null);
        }
        int bodyLength = document.length - TAG_BYTES;
        byte[] body = Arrays.copyOf(document, bodyLength);
        byte[] suppliedTag = Arrays.copyOfRange(document, bodyLength, document.length);
        byte[] expectedTag = null;
        try {
            expectedTag = tag(body, authenticationKey);
            if (!MessageDigest.isEqual(expectedTag, suppliedTag)) {
                throw invalid("desktop handoff authentication failed", null);
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION
                        || input.readUnsignedByte() != expectedPurpose.code) {
                    throw invalid("desktop handoff envelope header is unsupported", null);
                }
                int payloadLength = input.readInt();
                if (payloadLength < 1 || payloadLength > MAXIMUM_PAYLOAD_BYTES || payloadLength != input.available()) {
                    throw invalid("desktop handoff payload length is inconsistent", null);
                }
                return input.readNBytes(payloadLength);
            }
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("authenticated desktop handoff envelope is malformed", exception);
        } finally {
            Arrays.fill(document, (byte) 0);
            Arrays.fill(body, (byte) 0);
            Arrays.fill(suppliedTag, (byte) 0);
            if (expectedTag != null)
                Arrays.fill(expectedTag, (byte) 0);
        }
    }

    /**
     * Authenticates the handoff body with the required HmacSHA256 key.
     * <p>使用所需 HmacSHA256 密钥认证交接正文。
     *
     * @param body body / 正文
     * @param authenticationKey authentication key / 认证键
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    private static byte[] tag(byte[] body, SecretKey authenticationKey) throws WindowsWorkspaceException {
        if (authenticationKey == null) {
            throw invalid("desktop handoff authentication key is missing", null);
        }
        byte[] keyBytes = authenticationKey.getEncoded();
        if (!"HmacSHA256".equalsIgnoreCase(authenticationKey.getAlgorithm()) || keyBytes == null || keyBytes.length < 32
                || keyBytes.length > 64) {
            if (keyBytes != null)
                Arrays.fill(keyBytes, (byte) 0);
            throw invalid("desktop handoff authentication key is invalid", null);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(authenticationKey);
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw invalid("desktop handoff authentication is unavailable", exception);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * Creates the owning module's failure for rejected input or evidence.
     * <p>为被拒绝输入或证据创建所属模块的失败。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return the owning module's failure for rejected input or evidence / 为被拒绝输入或证据创建所属模块的失败
     */
    private static WindowsWorkspaceException invalid(String diagnostic, Throwable cause) {
        return WindowsWorkspaceException.create(WindowsWorkspaceFailureType.HANDOFF_INVALID, diagnostic, cause);
    }
}
