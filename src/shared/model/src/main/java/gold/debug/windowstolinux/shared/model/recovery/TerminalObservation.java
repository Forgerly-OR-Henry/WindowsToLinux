package gold.debug.windowstolinux.shared.model.recovery;

/**
 * Ephemeral terminal evidence; image bytes never belong in a journal. / 临时终端证据，图片不得进入记录。
 *
 * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
 * @param image image / 镜像
 * @param generation generation / 代次
 */
public record TerminalObservation(String text, byte[] image, long generation) {
    /**
     * Validates and binds the inputs required by terminal observation.
     * <p>校验并绑定终端观测所需输入。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param image image / 镜像
     * @param generation generation / 代次
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public TerminalObservation {
        text = java.util.Objects.requireNonNull(text);
        image = java.util.Objects.requireNonNull(image).clone();
        if (text.length() > 32000 || image.length > 4 * 1024 * 1024)
            throw new IllegalArgumentException("observation exceeds limit");
    }

    /**
     * Returns image.
     * <p>返回镜像。
     *
     * @return image / 镜像
     */
    @Override
    public byte[] image() {
        return image.clone();
    }

    /**
     * Returns the diagnostic text representation of this object.
     * <p>返回当前对象的诊断文本表示。
     *
     * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
     */
    @Override
    public String toString() {
        return "TerminalObservation[redacted,generation=" + generation + "]";
    }
}
