package gold.debug.windowstolinux.shared.model.recovery;

/**
 * Opaque terminal selection; URLs exclude credentials, queries and fragments. / 不透明终端选择，地址不含凭据、查询和片段。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param description description / 说明
 */
public record TerminalTarget(String id, String description) {
    /**
     * Returns description.
     * <p>返回说明。
     *
     * @return description / 说明
     */
    @Override
    public String toString() {
        return description;
    }
}
