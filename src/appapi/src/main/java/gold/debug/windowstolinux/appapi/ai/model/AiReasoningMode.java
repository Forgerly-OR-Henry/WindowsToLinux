package gold.debug.windowstolinux.appapi.ai.model;

/** A requested control, not a cross-model measure of intelligence. / 推理控制选项，不是跨模型能力标尺。
 */
public enum AiReasoningMode {
    /** Omits provider controls. / 省略提供方控制参数。
     */
    DEFAULT,
    /** Selects standard, non-thinking mode where supported. / 选择支持的标准模式。
     */
    STANDARD,
    /** Enables thinking using the provider's budget. / 以提供方预算开启思考。
     */
    THINKING,
    /** Minimal effort. / 最小强度。
     */
    MINIMAL,
    /** Low effort. / 低强度。
     */
    LOW,
    /** Medium effort. / 中等强度。
     */
    MEDIUM,
    /** High effort. / 高强度。
     */
    HIGH,
    /** Extra high effort. / 极高强度。
     */
    XHIGH,
    /** Maximum effort. / 最大强度。
     */
    MAX;

    /** Distinguishes levels from modes and omission. / 区分档位、模式及参数省略。
     * @return whether this is a discrete effort / 是否为离散强度 */

    public boolean level() {
        return this != DEFAULT && this != STANDARD && this != THINKING;
    }
}
