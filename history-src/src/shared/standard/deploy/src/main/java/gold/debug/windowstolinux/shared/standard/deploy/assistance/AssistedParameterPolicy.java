package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.Set;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;

/** Restricts advice to existing nonsecret technical inputs. / 将建议限制为既有非秘密技术输入。 */
public final class AssistedParameterPolicy {
    /** Fields whose semantics are checked again by the standard planner. / 标准规划器再次检查语义的字段。 */
    private static final Set<String> ALLOWED = Set.of("type", "primary", "secondary", "version", "jvmTarget",
            "dependencies", "healthOwner", "port", "healthMode", "healthEndpoint", "expectedStatus");

    /** Prevents construction. / 禁止构造。 */
    private AssistedParameterPolicy() {
    }

    /** Checks whether a field can be offered to the model. / 检查字段能否提供给模型。
     * @param id component-qualified field / 带组件前缀的字段
     * @return whether the field is supported / 字段是否受支持
     */
    public static boolean allows(String id) {
        return ALLOWED.contains(id.substring(id.lastIndexOf('/') + 1));
    }

    /** Validates the shape without replacing domain validation. / 验证形状，不代替领域校验。
     * @param field offered field / 提供的字段
     * @param value proposed technical value / 建议的技术值
     * @return whether the value may enter standard validation / 值是否可进入标准校验
     */
    public static boolean accepts(DeploymentInputField field, String value) {
        if (!allows(field.id()) || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl))
            return false;
        if (!field.choices().isEmpty() && !field.choices().contains(value))
            return false;
        String key = field.id().substring(field.id().lastIndexOf('/') + 1);
        if (key.equals("healthMode"))
            return Set.of("HTTP", "TCP", "UDP", "PROCESS").contains(value);
        if (key.equals("port")) {
            try {
                int port = Integer.parseInt(value);
                return port > 0 && port <= 65535;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        if (key.equals("expectedStatus"))
            return value.matches("[1-5][0-9]{2}");
        if (key.equals("dependencies"))
            return value.isBlank() || value.matches("[a-zA-Z0-9._,; -]+");
        if (key.equals("healthEndpoint"))
            return value.startsWith("http://127.0.0.1:") || value.startsWith("http://localhost:");
        return !value.isBlank() && !value.matches(".*[;$`|&<>\\r\\n].*") && !value.contains("..")
                && !value.startsWith("/") && !value.contains("\\") && !value.contains(":");
    }
}
