package gold.debug.windowstolinux.app.mod.installation;

import java.io.*;

/** Reads entry declarations without loading or initializing potentially newer bytecode. / 不加载或初始化潜在新版字节码地读取入口声明。 */
final class ModEntryShape {
    /** Prevents construction of the structural reader. / 禁止构造结构读取器。 */
    private ModEntryShape() {
    }

    /** Checks the declared class identity, public concrete shape and public no-argument constructor. / 检查声明类身份、公开具体形状及公开无参构造器。
     * Interface linking remains the actual enable operation's responsibility. / 接口链接仍由实际启用操作负责。
     * @param source bounded original class stream / 有界原类流
     * @param size declared class length / 声明类长度
     * @param path original logical class path / 原逻辑类路径
     * @throws IOException if byte structure or entry shape is invalid / 字节结构或入口形状无效时 */
    static void read(InputStream source, long size, String path) throws IOException {
        if (size < 24 || size > 16 * 1024 * 1024)
            throw new IOException("Invalid entry class size");
        byte[] bytes = source.readNBytes((int) size + 1);
        if (bytes.length != size)
            throw new IOException("Entry class length differs");
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != 0xcafebabe)
            throw new IOException("Invalid class magic");
        input.readUnsignedShort();
        if (input.readUnsignedShort() < 45)
            throw new IOException("Invalid class version");
        var pool = new Object[input.readUnsignedShort()];
        for (int i = 1; i < pool.length; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = input.readUTF();
                case 7 -> pool[i] = input.readUnsignedShort();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 5, 6 -> {
                    input.skipNBytes(8);
                    i++;
                }
                case 8, 16, 19, 20 -> input.skipNBytes(2);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unknown class constant tag");
            }
        }
        int access = input.readUnsignedShort();
        int self = input.readUnsignedShort();
        if ((access & 1) == 0 || (access & (0x200 | 0x400)) != 0 || self <= 0 || self >= pool.length
                || !(pool[self] instanceof Integer name)
                || !text(pool, name).equals(path.substring(0, path.length() - 6)))
            throw new IOException("Entry must be its declared public concrete class");
        input.readUnsignedShort();
        input.skipNBytes(2L * input.readUnsignedShort());
        members(input, pool, false);
        if (!members(input, pool, true))
            throw new IOException("Entry requires a public no-argument constructor");
        attributes(input);
        if (input.available() != 0)
            throw new IOException("Trailing entry class bytes");
    }

    /** Reads fields or methods while skipping bounded attributes. / 读取字段或方法并跳过有界属性。
     * @param input class bytes / 类字节
     * @param pool original constants / 原常量
     * @param methods whether scanning methods / 是否扫描方法
     * @return whether the required constructor was declared / 是否声明必需构造器
     * @throws IOException if malformed / 格式错误时 */
    private static boolean members(DataInputStream input, Object[] pool, boolean methods) throws IOException {
        int count = input.readUnsignedShort();
        boolean constructor = false;
        for (int i = 0; i < count; i++) {
            int access = input.readUnsignedShort();
            String name = text(pool, input.readUnsignedShort());
            String descriptor = text(pool, input.readUnsignedShort());
            if (methods && name.equals("<init>") && descriptor.equals("()V") && (access & 1) != 0)
                constructor = true;
            attributes(input);
        }
        return constructor;
    }

    /** Validates a required string constant reference. / 验证必需字符串常量引用。
     * @param pool constant table / 常量表
     * @param index original index / 原索引
     * @return original string / 原字符串
     * @throws IOException if the reference is invalid / 引用无效时 */
    private static String text(Object[] pool, int index) throws IOException {
        if (index <= 0 || index >= pool.length || !(pool[index] instanceof String value))
            throw new IOException("Invalid class constant reference");
        return value;
    }

    /** Skips exact attribute bytes without trusting their lengths beyond the held class. / 跳过精确属性字节，不信任超出持有类范围的长度。
     * @param input original class bytes / 原类字节
     * @throws IOException if truncated / 截断时 */
    private static void attributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            input.readUnsignedShort();
            input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
        }
    }
}
