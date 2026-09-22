package gold.debug.windowstolinux.shared.backup.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretDocument;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

/**
 * Strict canonical binary codec whose secret buffers remain clearable. / 秘密缓冲区始终可清零的严格规范二进制编解码器。
 */
final class BackupSecretDocumentCodec {
    /**
     * MAGIC.
     * <p>格式标记。
     */
    private static final byte[] MAGIC = {'W', 'T', 'L', 'S', 'C', 'R', 'T', '1'};

    /**
     * MAXIMUM DOCUMENT BYTES.
     * <p>最大文档字节。
     */
    private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024 * 1024;

    /**
     * MAXIMUM REVISIONS.
     * <p>最大修订集合。
     */
    private static final int MAXIMUM_REVISIONS = 64;

    /**
     * ORDER.
     * <p>顺序。
     */
    private static final Comparator<ResolvedSecretRevision> ORDER = Comparator
            .comparing((ResolvedSecretRevision revision) -> revision.reference().identifier())
            .thenComparingLong(revision -> revision.reference().revision());

    /**
     * Encodes a complete canonical revision set; the caller must clear the returned bytes. / 编码完整规范修订集；调用方必须清零返回字节。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return a complete canonical revision set; the caller must clear the returned bytes / 完整规范修订集；调用方必须清零返回字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    byte[] write(List<ResolvedSecretRevision> source) throws IOException {
        List<ResolvedSecretRevision> revisions = validated(source);
        int size = Math.addExact(MAGIC.length, Integer.BYTES);
        for (ResolvedSecretRevision revision : revisions) {
            byte[] identifier = revision.reference().identifier().getBytes(StandardCharsets.UTF_8);
            byte[] value = revision.copyValue();
            try {
                size = Math.addExact(size, Integer.BYTES + identifier.length + Long.BYTES + Integer.BYTES);
                size = Math.addExact(size, value.length);
                if (size > MAXIMUM_DOCUMENT_BYTES)
                    throw new IOException("backup secret document exceeds policy");
            } catch (ArithmeticException exception) {
                throw new IOException("backup secret document size overflow", exception);
            } finally {
                Arrays.fill(value, (byte) 0);
            }
        }
        ByteBuffer output = ByteBuffer.allocate(size);
        output.put(MAGIC).putInt(revisions.size());
        for (ResolvedSecretRevision revision : revisions) {
            byte[] identifier = revision.reference().identifier().getBytes(StandardCharsets.UTF_8);
            byte[] value = revision.copyValue();
            try {
                output.putInt(identifier.length).put(identifier);
                output.putLong(revision.reference().revision()).putInt(value.length).put(value);
            } finally {
                Arrays.fill(value, (byte) 0);
            }
        }
        return output.array();
    }

    /**
     * Decodes all revisions or clears every partial result before failing. / 解码全部修订，失败前清零每个部分结果。
     *
     * @param document document / 文档
     * @return all revisions or clears every partial result before failing / 全部修订，失败前清零每个部分结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    BackupSecretDocument read(byte[] document) throws IOException {
        Objects.requireNonNull(document, "document");
        if (document.length < MAGIC.length + Integer.BYTES || document.length > MAXIMUM_DOCUMENT_BYTES) {
            throw new IOException("backup secret document length is invalid");
        }
        ByteBuffer input = ByteBuffer.wrap(document);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        if (!Arrays.equals(magic, MAGIC))
            throw new IOException("backup secret document format is unsupported");
        int count = input.getInt();
        if (count < 1 || count > MAXIMUM_REVISIONS) {
            throw new IOException("backup secret revision count is invalid");
        }
        List<ResolvedSecretRevision> revisions = new ArrayList<>(count);
        SecretReference previous = null;
        try {
            for (int index = 0; index < count; index++) {
                String identifier = identifier(readBytes(input, 1, 64, "identifier"));
                requireRemaining(input, Long.BYTES + Integer.BYTES);
                SecretReference reference = new SecretReference(identifier, input.getLong());
                if (previous != null && compare(previous, reference) >= 0) {
                    throw new IOException("backup secret revisions are not canonical and unique");
                }
                previous = reference;
                int valueLength = input.getInt();
                if (valueLength < 1 || valueLength > ResolvedSecretRevision.MAX_VALUE_BYTES) {
                    throw new IOException("backup secret value length is invalid");
                }
                byte[] value = readBytes(input, valueLength, valueLength, "value");
                char[] characters = null;
                try {
                    characters = characters(value);
                    revisions.add(new ResolvedSecretRevision(reference, characters));
                } finally {
                    Arrays.fill(value, (byte) 0);
                    if (characters != null)
                        Arrays.fill(characters, '\0');
                }
            }
            if (input.hasRemaining())
                throw new IOException("backup secret document has trailing bytes");
            return new BackupSecretDocument(revisions);
        } catch (IOException | RuntimeException exception) {
            revisions.forEach(ResolvedSecretRevision::close);
            if (exception instanceof IOException ioException)
                throw ioException;
            throw new IOException("backup secret document is invalid", exception);
        }
    }

    /**
     * Constructs the contract only after its declared constraints are checked.
     * <p>仅在检查已声明约束后构造契约。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return the contract only after its declared constraints are checked / 仅在检查已声明约束后构造契约
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<ResolvedSecretRevision> validated(List<ResolvedSecretRevision> source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty() || source.size() > MAXIMUM_REVISIONS) {
            throw new IllegalArgumentException("backup secret revision count is invalid");
        }
        List<ResolvedSecretRevision> revisions = source.stream()
                .map(revision -> Objects.requireNonNull(revision, "revision")).sorted(ORDER).toList();
        if (revisions.stream().map(ResolvedSecretRevision::reference).distinct().count() != revisions.size()) {
            throw new IllegalArgumentException("backup secret revisions must be unique by exact reference");
        }
        return revisions;
    }

    /**
     * Reads content buffer processed by the current codec or stream.
     * <p>读取当前编解码器或流处理的内容缓冲区。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param minimum minimum / 最小
     * @param maximum maximum / 最大
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static byte[] readBytes(ByteBuffer input, int minimum, int maximum, String field) throws IOException {
        if (minimum == maximum) {
            requireRemaining(input, minimum);
            byte[] value = new byte[minimum];
            input.get(value);
            return value;
        }
        requireRemaining(input, Integer.BYTES);
        int length = input.getInt();
        if (length < minimum || length > maximum)
            throw new IOException(field + " length is invalid");
        requireRemaining(input, length);
        byte[] value = new byte[length];
        input.get(value);
        return value;
    }

    /**
     * Decodes an identifier as strict UTF-8 while clearing temporary character storage after conversion.
     * <p>按严格 UTF-8 解码标识，并在转换后清空临时字符存储。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return an identifier as strict UTF-8 while clearing temporary character storage after conversion / 按严格 UTF-8 解码标识，并在转换后清空临时字符存储
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static String identifier(byte[] bytes) throws IOException {
        char[] characters = null;
        try {
            characters = characters(bytes);
            return new String(characters);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (characters != null)
                Arrays.fill(characters, '\0');
        }
    }

    /**
     * Decodes secret bytes as strict UTF-8 and rejects malformed or unmappable input.
     * <p>按严格 UTF-8 解码秘密字节，并拒绝格式无效或不可映射的输入。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return secret bytes as strict UTF-8 and rejects malformed or unmappable input / 按严格 UTF-8 解码秘密字节，并拒绝格式无效或不可映射的输入
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static char[] characters(byte[] bytes) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            for (int index = 0; index < decoded.limit(); index++)
                decoded.put(index, '\0');
            return result;
        } catch (CharacterCodingException exception) {
            throw new IOException("backup secret text is not valid UTF-8", exception);
        }
    }

    /**
     * Compares backup secret document.
     * <p>比较备份秘密文档。
     *
     * @param left left / 左侧
     * @param right right / 右侧
     * @return compare as a numeric result / 比较的数值结果
     */
    private static int compare(SecretReference left, SecretReference right) {
        int identifier = left.identifier().compareTo(right.identifier());
        return identifier != 0 ? identifier : Long.compare(left.revision(), right.revision());
    }

    /**
     * Requires remaining and rejects inputs outside the declared constraints.
     * <p>要求剩余并拒绝超出已声明约束的输入。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void requireRemaining(ByteBuffer input, int bytes) throws IOException {
        if (bytes < 0 || input.remaining() < bytes)
            throw new IOException("backup secret document is truncated");
    }
}
