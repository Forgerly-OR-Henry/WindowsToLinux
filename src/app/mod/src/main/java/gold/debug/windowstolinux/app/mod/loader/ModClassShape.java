package gold.debug.windowstolinux.app.mod.loader;

import java.io.IOException;
import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.*;

/** Reads declaration metadata without defining classes or visiting method bodies. / 不定义类、不访问方法体地读取声明元数据。 */
final class ModClassShape extends ClassVisitor {
    /** Exact internal class name. / 精确内部类名。 */
    String name;

    /** Class or nested declaration access flags. / 类或嵌套声明访问标志。 */
    int access;

    /** Direct superclass and interfaces. / 直接父类及接口。 */
    final List<String> parents = new ArrayList<>();

    /** Referenced types in the externally visible declaration. / 对外可见声明引用的类型。 */
    final Set<String> references = new HashSet<>();

    /** Enclosing member class, absent for top-level declarations. / 成员类的外层类，顶层声明为空。 */
    String outer;

    /** Whether this is an anonymous or local class. / 是否为匿名或局部类。 */
    boolean local;

    /** Whether a public zero-argument constructor is present. / 是否有公开无参构造器。 */
    boolean constructor;

    /** Original class-file major version. / 原类文件主版本。 */
    int major;

    /** Creates a bounded declaration visitor. / 创建有界声明访问器。 */
    private ModClassShape() {
        super(Opcodes.ASM9);
    }

    /** Parses the original bytes and checks the resource's declared identity. / 解析原字节并核对资源声明身份。
     * @param bytes bounded original bytes / 有界原字节
     * @param expected exact internal resource name / 精确内部资源名
     * @return inspected metadata / 已检验元数据
     * @throws IOException if metadata is invalid or unsupported / 元数据无效或不受支持时 */
    static ModClassShape read(byte[] bytes, String expected) throws IOException {
        try {
            if (bytes.length < 24 || java.nio.ByteBuffer.wrap(bytes).getInt() != 0xcafebabe)
                throw new IllegalArgumentException("Invalid class header");
            var result = new ModClassShape();
            var reader = new ClassReader(bytes);
            framing(reader, bytes.length);
            reader.accept(result, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!expected.equals(result.name) || result.major < 45)
                throw new IllegalArgumentException("Class resource identity differs");
            return result;
        } catch (RuntimeException | StackOverflowError invalid) {
            throw new IOException("Invalid or unsupported class metadata: " + expected, invalid);
        }
    }

    /** Checks complete member and attribute framing while leaving code bodies to the JVM. / 检查完整成员及属性边界，方法体留给 JVM 验证。
     * @param reader original class reader / 原类读取器
     * @param length exact original length / 精确原长度 */
    private static void framing(ClassReader reader, int length) {
        int offset = reader.header + 8 + 2 * reader.readUnsignedShort(reader.header + 6);
        for (int section = 0; section < 2; section++) {
            int count = reader.readUnsignedShort(offset);
            offset += 2;
            for (int member = 0; member < count; member++)
                offset = attributes(reader, offset + 6, length);
        }
        if (attributes(reader, offset, length) != length)
            throw new IllegalArgumentException("Trailing class bytes");
    }

    /** Skips bounded opaque attributes after their count field. / 在数量字段后跳过有界不透明属性。
     * @param reader original reader / 原读取器
     * @param offset count field offset / 数量字段偏移
     * @param length original byte length / 原字节长度
     * @return next structural offset / 下一结构偏移 */
    private static int attributes(ClassReader reader, int offset, int length) {
        int count = reader.readUnsignedShort(offset);
        offset += 2;
        for (int attribute = 0; attribute < count; attribute++) {
            long next = offset + 6L + Integer.toUnsignedLong(reader.readInt(offset + 2));
            if (next > length)
                throw new IllegalArgumentException("Truncated class attribute");
            offset = (int) next;
        }
        return offset;
    }

    /** Captures the class header and generic hierarchy. / 捕获类头和泛型继承关系。
     * @param version class version / 类版本
     * @param flags access flags / 访问标志
     * @param internal internal name / 内部名称
     * @param signature generic signature / 泛型签名
     * @param parent superclass / 父类
     * @param interfaces direct interfaces / 直接接口 */
    @Override
    public void visit(int version, int flags, String internal, String signature, String parent, String[] interfaces) {
        name = internal;
        access = flags;
        major = version & 0xffff;
        if (parent != null)
            parents.add(parent);
        parents.addAll(Arrays.asList(interfaces));
        references.addAll(parents);
        signature(signature, false);
    }

    /** Captures effective nested visibility and its declaring type. / 捕获嵌套可见性及声明类型。
     * @param internal nested name / 嵌套名称
     * @param enclosing enclosing member owner / 外层成员所有者
     * @param simple simple name / 简名
     * @param flags nested flags / 嵌套标志 */
    @Override
    public void visitInnerClass(String internal, String enclosing, String simple, int flags) {
        if (internal.equals(name)) {
            access = flags;
            outer = enclosing;
            local |= simple == null;
        }
    }

    /** Marks local declarations as outside the exported surface. / 将局部声明标记为不属于导出表面。
     * @param owner enclosing class / 外层类
     * @param method enclosing method / 外层方法
     * @param descriptor method descriptor / 方法描述符 */
    @Override
    public void visitOuterClass(String owner, String method, String descriptor) {
        local = true;
    }

    /** Includes sealed hierarchy declarations in the public surface. / 将密封继承声明纳入公开表面。
     * @param subclass permitted subclass / 允许子类 */
    @Override
    public void visitPermittedSubclass(String subclass) {
        references.add(subclass);
    }

    /** Reads public and protected field types and annotations. / 读取公开及受保护字段类型与注解。
     * @param flags field flags / 字段标志
     * @param field field name / 字段名
     * @param descriptor erased type / 擦除类型
     * @param generic generic signature / 泛型签名
     * @param value constant value / 常量值
     * @return surface visitor or no visitor / 表面访问器或无访问器 */
    @Override
    public FieldVisitor visitField(int flags, String field, String descriptor, String generic, Object value) {
        if (!visible(flags))
            return null;
        descriptor(descriptor);
        signature(generic, true);
        return new FieldVisitor(Opcodes.ASM9) {
            /** Reads declaration annotations. / 读取声明注解。
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitAnnotation(String type, boolean runtime) {
                return annotation(type);
            }

            /** Reads type-use annotations. / 读取类型使用注解。
             * @param ref target reference / 目标引用
             * @param path target path / 目标路径
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String type, boolean runtime) {
                return annotation(type);
            }
        };
    }

    /** Reads public and protected methods, including generic throws and annotation defaults. / 读取公开及受保护方法，包括泛型异常及注解默认值。
     * @param flags method flags / 方法标志
     * @param method method name / 方法名
     * @param descriptor erased signature / 擦除签名
     * @param generic generic signature / 泛型签名
     * @param exceptions declared exceptions / 声明异常
     * @return surface visitor or no visitor / 表面访问器或无访问器 */
    @Override
    public MethodVisitor visitMethod(int flags, String method, String descriptor, String generic, String[] exceptions) {
        constructor |= method.equals("<init>") && descriptor.equals("()V") && (flags & Opcodes.ACC_PUBLIC) != 0;
        if (!visible(flags))
            return null;
        descriptor(descriptor);
        signature(generic, false);
        if (exceptions != null)
            references.addAll(Arrays.asList(exceptions));
        return new MethodVisitor(Opcodes.ASM9) {
            /** Reads declaration annotations. / 读取声明注解。
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitAnnotation(String type, boolean runtime) {
                return annotation(type);
            }

            /** Reads type-use annotations. / 读取类型使用注解。
             * @param ref target reference / 目标引用
             * @param path target path / 目标路径
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String type, boolean runtime) {
                return annotation(type);
            }

            /** Reads parameter annotations. / 读取参数注解。
             * @param parameter parameter index / 参数索引
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String type, boolean runtime) {
                return annotation(type);
            }

            /** Reads annotation defaults including class literals. / 读取包括类字面量的注解默认值。
             * @return recursive annotation reader / 递归注解读取器 */
            @Override
            public AnnotationVisitor visitAnnotationDefault() {
                return annotation(null);
            }
        };
    }

    /** Reads record component surface metadata. / 读取记录组件表面元数据。
     * @param component component name / 组件名
     * @param descriptor erased type / 擦除类型
     * @param generic generic signature / 泛型签名
     * @return component visitor / 组件访问器 */
    @Override
    public RecordComponentVisitor visitRecordComponent(String component, String descriptor, String generic) {
        descriptor(descriptor);
        signature(generic, true);
        return new RecordComponentVisitor(Opcodes.ASM9) {
            /** Reads component annotations. / 读取组件注解。
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitAnnotation(String type, boolean runtime) {
                return annotation(type);
            }

            /** Reads component type annotations. / 读取组件类型注解。
             * @param ref target reference / 目标引用
             * @param path target path / 目标路径
             * @param type annotation type / 注解类型
             * @param runtime runtime visibility / 运行时可见性
             * @return annotation reader / 注解读取器 */
            @Override
            public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String type, boolean runtime) {
                return annotation(type);
            }
        };
    }

    /** Reads class annotations. / 读取类注解。
     * @param type annotation type / 注解类型
     * @param runtime runtime visibility / 运行时可见性
     * @return annotation reader / 注解读取器 */
    @Override
    public AnnotationVisitor visitAnnotation(String type, boolean runtime) {
        return annotation(type);
    }

    /** Reads class type annotations. / 读取类类型注解。
     * @param ref target reference / 目标引用
     * @param path target path / 目标路径
     * @param type annotation type / 注解类型
     * @param runtime runtime visibility / 运行时可见性
     * @return annotation reader / 注解读取器 */
    @Override
    public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String type, boolean runtime) {
        return annotation(type);
    }

    /** Collects nested annotation and class-literal references. / 收集嵌套注解及类字面量引用。
     * @param descriptor annotation descriptor or absent / 注解描述符或缺省
     * @return recursive visitor / 递归访问器 */
    private AnnotationVisitor annotation(String descriptor) {
        if (descriptor != null)
            descriptor(descriptor);
        return new AnnotationVisitor(Opcodes.ASM9) {
            /** Collects class literals. / 收集类字面量。
             * @param key element name / 元素名
             * @param value element value / 元素值 */
            @Override
            public void visit(String key, Object value) {
                if (value instanceof Type type)
                    type(type);
            }

            /** Collects enum types. / 收集枚举类型。
             * @param key element name / 元素名
             * @param type enum descriptor / 枚举描述符
             * @param value enum value / 枚举值 */
            @Override
            public void visitEnum(String key, String type, String value) {
                descriptor(type);
            }

            /** Reads nested annotations. / 读取嵌套注解。
             * @param key element name / 元素名
             * @param type annotation descriptor / 注解描述符
             * @return nested reader / 嵌套读取器 */
            @Override
            public AnnotationVisitor visitAnnotation(String key, String type) {
                return annotation(type);
            }

            /** Reads array values. / 读取数组值。
             * @param key element name / 元素名
             * @return array reader / 数组读取器 */
            @Override
            public AnnotationVisitor visitArray(String key) {
                return this;
            }
        };
    }

    /** Checks externally visible member flags. / 检查对外可见成员标志。
     * @param flags original flags / 原标志
     * @return public or protected / 公开或受保护 */
    static boolean visible(int flags) {
        return (flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    /** Collects an erased field or method descriptor. / 收集擦除字段或方法描述符。
     * @param value original descriptor / 原描述符 */
    private void descriptor(String value) {
        type(Type.getType(value));
    }

    /** Collects object references through arrays and method types. / 通过数组及方法类型收集对象引用。
     * @param value parsed type / 已解析类型 */
    private void type(Type value) {
        switch (value.getSort()) {
            case Type.OBJECT -> references.add(value.getInternalName());
            case Type.ARRAY -> type(value.getElementType());
            case Type.METHOD -> {
                type(value.getReturnType());
                for (var argument : value.getArgumentTypes())
                    type(argument);
            }
            default -> {
            }
        }
    }

    /** Collects generic bounds, arguments and nested class names. / 收集泛型界限、实参及嵌套类名。
     * @param value optional signature / 可选签名
     * @param field field rather than class or method signature / 字段签名而非类或方法签名 */
    private void signature(String value, boolean field) {
        if (value == null)
            return;
        var reader = new SignatureReader(value);
        var roundtrip = new SignatureWriter();
        if (field)
            reader.acceptType(roundtrip);
        else
            reader.accept(roundtrip);
        if (!value.equals(roundtrip.toString()))
            throw new IllegalArgumentException("Incomplete generic signature");
        if (field)
            reader.acceptType(new TypeParameters());
        else
            reader.accept(new TypeParameters());
    }

    /** Tracks each generic type branch separately to preserve outer names. / 分别跟踪泛型类型分支以保留外层名称。 */
    private final class TypeParameters extends SignatureVisitor {
        /** Current nested class prefix. / 当前嵌套类前缀。 */
        private String current;
        /** Creates an independent branch. / 创建独立分支。 */
        TypeParameters() {
            super(Opcodes.ASM9);
        }

        /** Collects a class type. / 收集类类型。
         * @param value internal name / 内部名称 */
        @Override
        public void visitClassType(String value) {
            current = value;
            references.add(value);
        }

        /** Collects a member class. / 收集成员类。
         * @param value simple name / 简名 */
        @Override
        public void visitInnerClassType(String value) {
            current += "$" + value;
            references.add(current);
        }

        /** Visits type arguments independently. / 独立访问类型实参。
         * @param wildcard variance marker / 型变标记
         * @return independent branch / 独立分支 */
        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new TypeParameters();
        }
    }
}
