package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.app.windows.workspace.DesktopHandoffEnvelopeCodec;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceFailureType;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict authenticated transport codec for an uninstall handoff crossing the process boundary. / 卸载交接跨进程边界使用的严格认证传输编解码器。
 */
public final class DesktopUninstallHandoffCodec {
    /**
     * PAYLOAD VERSION.
     * <p>载荷版本。
     */
    private static final int PAYLOAD_VERSION = 1;
    /**
     * MAXIMUM EVENTS.
     * <p>最大事件集合。
     */
    private static final int MAXIMUM_EVENTS = 64;
    /**
     * Bound desktop handoff envelope codec collaborator for envelope.
     * <p>处理信封的Desktop交接信封编解码器协作对象。
     */
    private final DesktopHandoffEnvelopeCodec envelope = new DesktopHandoffEnvelopeCodec();

    /**
     * Encodes the explicit uninstall decision and successful task-stop evidence. / 编码显式卸载决定及成功停收任务证据。
     *
     * @param handoff handoff / 交接
     * @param authenticationKey authentication key / 认证键
     * @return the explicit uninstall decision and successful task-stop evidence / 显式卸载决定及成功停收任务证据
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] write(DesktopUninstallHandoff handoff, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        Objects.requireNonNull(handoff, "handoff");
        byte[] payload = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(PAYLOAD_VERSION);
                output.writeUTF(handoff.operationIdentity().toString());
                DesktopUninstallRequest request = handoff.request();
                output.writeUTF(request.decision().orElseThrow().name());
                output.writeUTF(request.installRoot().toString());
                output.writeUTF(request.dataRoot().toString());
                output.writeUTF(request.credentialNamespace());
                writeEvents(output, handoff.preparationEvents());
            }
            payload = bytes.toByteArray();
            return envelope.write(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL, payload, authenticationKey);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("uninstall handoff could not be encoded", exception);
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Authenticates before reconstructing any uninstall decision or path. / 在重建任何卸载决定或路径前完成认证。
     *
     * @param document document / 文档
     * @param authenticationKey authentication key / 认证键
     * @return constructed or resolved desktop uninstall handoff / 构造或解析得到的Desktop卸载交接
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    public DesktopUninstallHandoff read(byte[] document, SecretKey authenticationKey)
            throws WindowsWorkspaceException {
        byte[] payload = envelope.read(DesktopHandoffEnvelopeCodec.PurposeType.UNINSTALL,
                document, authenticationKey);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_VERSION) {
                throw invalid("uninstall handoff payload version is unsupported", null);
            }
            OperationIdentity operation = OperationIdentity.from(input.readUTF());
            DesktopUninstallRequest request = new DesktopUninstallRequest(
                    Optional.of(DesktopUninstallDecisionType.valueOf(input.readUTF())),
                    Path.of(input.readUTF()), Path.of(input.readUTF()), input.readUTF());
            List<DesktopUninstallEvent> events = readEvents(input);
            if (input.available() != 0) throw invalid("uninstall handoff payload has trailing bytes", null);
            return new DesktopUninstallHandoff(operation, request, events);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("authenticated uninstall handoff payload is malformed", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Writes ordered progress or transaction events.
     * <p>写入有序进度或事务事件。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeEvents(DataOutputStream output, List<DesktopUninstallEvent> events) throws IOException {
        output.writeInt(events.size());
        for (DesktopUninstallEvent event : events) {
            output.writeUTF(event.state().name());
            output.writeBoolean(event.succeeded());
            output.writeUTF(event.evidence());
        }
    }

    /**
     * Reads ordered progress or transaction events.
     * <p>读取有序进度或事务事件。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return ordered progress or transaction events / 有序进度或事务事件
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static List<DesktopUninstallEvent> readEvents(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 1 || count > MAXIMUM_EVENTS) throw new IOException("uninstall event count is invalid");
        List<DesktopUninstallEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            events.add(new DesktopUninstallEvent(DesktopUninstallState.valueOf(input.readUTF()),
                    input.readBoolean(), input.readUTF()));
        }
        return List.copyOf(events);
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
