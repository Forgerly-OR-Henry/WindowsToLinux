package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Strict versioned storage codec for reviewed non-secret component data paths. / 经审阅非秘密组件数据路径的严格版本化存储编解码器。
 */
public final class ComponentPathPersistenceCodec {
    /**
     * MAGIC.
     * <p>格式标记。
     */
    private static final int MAGIC = 0x57544c44;
    /**
     * VERSION.
     * <p>版本。
     */
    private static final int VERSION = 1;
    /**
     * MAX DOCUMENT BYTES.
     * <p>最大文档字节。
     */
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    /**
     * MAX PATHS.
     * <p>最大路径集合。
     */
    private static final int MAX_PATHS = 4_096;

    /**
     * Encodes one complete canonical data-path list, including an explicitly empty list. / 编码一个完整规范数据路径列表，包括显式空列表。
     *
     * @param paths paths / 路径集合
     * @return one complete canonical data-path list, including an explicitly empty list / 一个完整规范数据路径列表，包括显式空列表
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public byte[] write(List<ComponentDataPath> paths) throws IOException {
        List<ComponentDataPath> canonical = canonical(paths);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeInt(canonical.size());
            for (ComponentDataPath path : canonical) {
                output.writeUTF(path.path());
                output.writeByte(path.access() == ComponentDataPath.AccessMode.READ_ONLY ? 1 : 2);
                output.writeUTF(path.schemaId());
                output.writeByte(path.reversible() ? 1 : 0);
            }
        }
        byte[] document = bytes.toByteArray();
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("reviewed data-path document exceeds the storage limit");
        }
        return document;
    }

    /**
     * Decodes one exact canonical data-path list and rejects partial or extended documents. / 解码一个精确规范数据路径列表并拒绝部分或扩展文档。
     *
     * @param document document / 文档
     * @return one exact canonical data-path list and rejects partial or extended documents / 一个精确规范数据路径列表并拒绝部分或扩展文档
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public List<ComponentDataPath> read(byte[] document) throws IOException {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("reviewed data-path document size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("reviewed data-path document header is unsupported");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_PATHS) {
                throw new IOException("reviewed data-path count is invalid");
            }
            List<ComponentDataPath> paths = new ArrayList<>(count);
            while (count-- > 0) {
                String path = input.readUTF();
                ComponentDataPath.AccessMode access = switch (input.readUnsignedByte()) {
                    case 1 -> ComponentDataPath.AccessMode.READ_ONLY;
                    case 2 -> ComponentDataPath.AccessMode.READ_WRITE;
                    default -> throw new IOException("reviewed data-path access mode is unsupported");
                };
                String schemaId = input.readUTF();
                boolean reversible = switch (input.readUnsignedByte()) {
                    case 0 -> false;
                    case 1 -> true;
                    default -> throw new IOException("reviewed data-path boolean value is invalid");
                };
                paths.add(new ComponentDataPath(path, access, schemaId, reversible));
            }
            if (input.read() != -1) {
                throw new IOException("reviewed data-path document contains trailing data");
            }
            List<ComponentDataPath> canonical = canonical(paths);
            if (!canonical.equals(paths)) {
                throw new IOException("reviewed data-path document is not in canonical order");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new IOException("reviewed data-path document violates the typed model", exception);
        }
    }

    /**
     * Validates and canonicalizes the supplied identity or path representation.
     * <p>校验并规范化提供的身份或路径表示。
     *
     * @param paths paths / 路径集合
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<ComponentDataPath> canonical(List<ComponentDataPath> paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        if (paths.size() > MAX_PATHS) {
            throw new IOException("reviewed data-path collection exceeds the storage limit");
        }
        List<ComponentDataPath> values = paths.stream().map(path -> Objects.requireNonNull(path, "path"))
                .sorted(Comparator.comparing(ComponentDataPath::path)).toList();
        if (values.stream().map(ComponentDataPath::path).distinct().count() != values.size()) {
            throw new IOException("reviewed data paths contain duplicate paths");
        }
        return List.copyOf(values);
    }
}
