package gold.debug.windowstolinux.mod.deployment.api;

import java.util.Arrays;

/** Opaque versioned runtime bytes for archival transfer. / 可供归档转交的不透明版本化运行字节。
 * @param formatVersion original format version / 原格式版本
 * @param payload exact original bytes / 精确原字节 */
public record VersionedRuntime(int formatVersion, byte[] payload) {
    /** Copies bounded runtime bytes. / 复制有界运行字节。
     * @param formatVersion original format version / 原格式版本
     * @param payload exact original bytes / 精确原字节 */
    public VersionedRuntime {
        if (formatVersion < 1 || payload == null || payload.length > 16 * 1_048_576)
            throw new IllegalArgumentException("Invalid versioned runtime");
        payload = payload.clone();
    }

    /** Returns an independent byte copy. / 返回独立字节副本。
     * @return original runtime bytes / 原运行字节 */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Compares the actual byte payload. / 比较实际字节载荷。
     * @param other comparison object / 比较对象
     * @return whether format and bytes match / 格式与字节是否相同 */
    @Override
    public boolean equals(Object other) {
        return other instanceof VersionedRuntime value && formatVersion == value.formatVersion
            && Arrays.equals(payload, value.payload);
    }

    /** Hashes the actual byte payload. / 对实际字节载荷求哈希。
     * @return value hash / 值哈希 */
    @Override
    public int hashCode() {
        return 31 * Integer.hashCode(formatVersion) + Arrays.hashCode(payload);
    }
}
