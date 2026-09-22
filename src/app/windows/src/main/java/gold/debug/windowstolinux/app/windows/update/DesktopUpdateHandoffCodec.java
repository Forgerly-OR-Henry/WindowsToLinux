package gold.debug.windowstolinux.app.windows.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.crypto.SecretKey;

import gold.debug.windowstolinux.app.windows.workspace.DesktopHandoffEnvelopeCodec;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceFailureType;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Strict authenticated transport codec for an update handoff crossing the process boundary. / 更新交接跨进程边界使用的严格认证传输编解码器。
 */
public final class DesktopUpdateHandoffCodec {
    /**
     * PAYLOAD VERSION.
     * <p>载荷版本。
     */
    private static final int PAYLOAD_VERSION = 1;

    /**
     * MAXIMUM ITEMS.
     * <p>最大项目集合。
     */
    private static final int MAXIMUM_ITEMS = 64;

    /**
     * Bound desktop handoff envelope codec collaborator for envelope.
     * <p>处理信封的Desktop交接信封编解码器协作对象。
     */
    private final DesktopHandoffEnvelopeCodec envelope = new DesktopHandoffEnvelopeCodec();

    /**
     * Encodes the complete verified update and paired backup evidence. / 编码完整的已验证更新及成对备份证据。
     *
     * @param handoff handoff / 交接
     * @param authenticationKey authentication key / 认证键
     * @return the complete verified update and paired backup evidence / 完整的已验证更新及成对备份证据
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] write(DesktopUpdateHandoff handoff, SecretKey authenticationKey) throws WindowsWorkspaceException {
        Objects.requireNonNull(handoff, "handoff");
        byte[] payload = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(PAYLOAD_VERSION);
                output.writeUTF(handoff.operationIdentity().toString());
                writeVerification(output, handoff.update());
                writeBackup(output, handoff.backup());
                writeEvents(output, handoff.preparationEvents());
            }
            payload = bytes.toByteArray();
            return envelope.write(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE, payload, authenticationKey);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("update handoff could not be encoded", exception);
        } finally {
            if (payload != null)
                Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Authenticates before reconstructing any update handoff state. / 在重建任何更新交接状态前完成认证。
     *
     * @param document document / 文档
     * @param authenticationKey authentication key / 认证键
     * @return constructed or resolved desktop update handoff / 构造或解析得到的Desktop更新交接
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    public DesktopUpdateHandoff read(byte[] document, SecretKey authenticationKey) throws WindowsWorkspaceException {
        byte[] payload = envelope.read(DesktopHandoffEnvelopeCodec.PurposeType.UPDATE, document, authenticationKey);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_VERSION) {
                throw invalid("update handoff payload version is unsupported", null);
            }
            OperationIdentity operation = OperationIdentity.from(input.readUTF());
            DesktopUpdateVerification update = readVerification(input);
            DesktopUpdatePort.BackupEvidence backup = readBackup(input);
            List<DesktopUpdateEvent> events = readEvents(input);
            if (input.available() != 0)
                throw invalid("update handoff payload has trailing bytes", null);
            return new DesktopUpdateHandoff(operation, update, backup, events);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("authenticated update handoff payload is malformed", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Writes verification.
     * <p>写入验证。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param update update / 更新
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeVerification(DataOutputStream output, DesktopUpdateVerification update)
            throws IOException {
        output.writeUTF(update.packageFile().toString());
        output.writeUTF(update.releaseId());
        output.writeUTF(update.version().toString());
        output.writeUTF(update.architecture().name());
        output.writeLong(update.packageBytes());
        output.writeUTF(update.packageSha256());
        output.writeLong(update.verifiedAt().getEpochSecond());
        output.writeInt(update.verifiedAt().getNano());
        writeTexts(output, update.evidence());
    }

    /**
     * Reads verification.
     * <p>读取验证。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return verification / 验证
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static DesktopUpdateVerification readVerification(DataInputStream input) throws IOException {
        Path packageFile = Path.of(input.readUTF());
        String releaseId = input.readUTF();
        DesktopReleaseVersion version = DesktopReleaseVersion.parse(input.readUTF());
        DesktopArchitectureType architecture = DesktopArchitectureType.valueOf(input.readUTF());
        long packageBytes = input.readLong();
        String packageSha256 = input.readUTF();
        Instant verifiedAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
        return new DesktopUpdateVerification(packageFile, releaseId, version, architecture, packageBytes, packageSha256,
                verifiedAt, readTexts(input));
    }

    /**
     * Writes the local backup page state.
     * <p>写入本地备份页面状态。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param backup the local backup page state / 本地备份页面状态
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeBackup(DataOutputStream output, DesktopUpdatePort.BackupEvidence backup)
            throws IOException {
        output.writeUTF(backup.backupToken());
        output.writeBoolean(backup.programBackedUp());
        output.writeBoolean(backup.databaseBackedUp());
        output.writeBoolean(backup.dataLocationPreserved());
        output.writeBoolean(backup.credentialModePreserved());
        writeTexts(output, backup.evidence());
    }

    /**
     * Reads the local backup page state.
     * <p>读取本地备份页面状态。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return the local backup page state / 本地备份页面状态
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static DesktopUpdatePort.BackupEvidence readBackup(DataInputStream input) throws IOException {
        return new DesktopUpdatePort.BackupEvidence(input.readUTF(), input.readBoolean(), input.readBoolean(),
                input.readBoolean(), input.readBoolean(), readTexts(input));
    }

    /**
     * Writes ordered progress or transaction events.
     * <p>写入有序进度或事务事件。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeEvents(DataOutputStream output, List<DesktopUpdateEvent> events) throws IOException {
        output.writeInt(events.size());
        for (DesktopUpdateEvent event : events) {
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
    private static List<DesktopUpdateEvent> readEvents(DataInputStream input) throws IOException {
        int count = count(input.readInt(), "update event");
        List<DesktopUpdateEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            events.add(new DesktopUpdateEvent(DesktopUpdateState.valueOf(input.readUTF()), input.readBoolean(),
                    input.readUTF()));
        }
        return List.copyOf(events);
    }

    /**
     * Writes texts.
     * <p>写入文本集合。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeTexts(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values)
            output.writeUTF(value);
    }

    /**
     * Reads texts.
     * <p>读取文本集合。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return texts / 文本集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static List<String> readTexts(DataInputStream input) throws IOException {
        int count = count(input.readInt(), "update evidence");
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            values.add(input.readUTF());
        return List.copyOf(values);
    }

    /**
     * Checks the item or byte count against the explicit bound before accepting more content.
     * <p>在接受更多内容前按显式边界检查条目数或字节数。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return count as a numeric result / 数量的数值结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static int count(int value, String field) throws IOException {
        if (value < 1 || value > MAXIMUM_ITEMS)
            throw new IOException(field + " count is invalid");
        return value;
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
