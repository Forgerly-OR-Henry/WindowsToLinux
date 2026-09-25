package gold.debug.windowstolinux.web.db.entity;

import java.util.Objects;

/**
 * Carries server-assigned ownership that is never inferred from a browser-supplied identifier.
 * <p>携带服务器分配的归属，绝不从浏览器提供的标识推导。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param userId user id / 用户标识
 */
public record ResourceScope(String workspaceId, String userId) {
    /**
     * Binds the supplied dependencies and state for resource scope.
     * <p>为资源作用域绑定传入的依赖及状态。
     *
     * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
     * @param userId user id / 用户标识
     */
    public ResourceScope {
        identifier(workspaceId);
        identifier(userId);
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String identifier(String value) {
        if (!Objects.requireNonNull(value).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Invalid resource identifier");
        return value;
    }
}
