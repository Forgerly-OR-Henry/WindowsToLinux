package gold.debug.windowstolinux.app.service.setting;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.setting.NavigationPreferenceStore;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Security-scoped entrance preference operations. / 安全作用域内的入口偏好操作。 */
public final class NavigationOrderService {
    /** Transactional platform entrance preference storage. / 平台入口偏好的事务存储。 */
    private final NavigationPreferenceStore repository;

    /** Current user privacy admission boundary. / 当前用户隐私准入边界。 */
    private final DesktopSecurityService security;

    /** Binds existing storage and privacy admission. / 绑定已有存储和隐私准入。
     * @param repository actual preference owner / 实际偏好所有者
     * @param security privacy boundary / 隐私边界 */
    public NavigationOrderService(NavigationPreferenceStore repository, DesktopSecurityService security) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.security = java.util.Objects.requireNonNull(security);
    }

    /** Loads confirmed order. / 加载已确认顺序。
     * @param defaults current available defaults / 当前可用默认入口
     * @return complete order / 完整顺序
     * @throws ApiException if access or storage fails / 访问或存储失败时 */
    public List<String> load(List<String> defaults) throws ApiException {
        try (var operation = security.enterOperation()) {
            return repository.read(defaults);
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    /** Saves before updating the visible shell. / 先保存再更新可见外壳。
     * @param defaults default visible identities / 默认可见身份
     * @param expected confirmed complete order / 已确认完整顺序
     * @param visible requested permutation / 请求排列
     * @return committed order / 已提交顺序
     * @throws ApiException if access, snapshot or persistence fails / 访问、快照或持久化失败时 */
    public List<String> save(List<String> defaults, List<String> expected, List<String> visible) throws ApiException {
        try (var operation = security.enterOperation()) {
            return repository.reorder(defaults, expected, visible);
        } catch (SQLException failure) {
            throw failure(failure);
        }
    }

    /** Reports unconfirmed storage without exposing SQL details or replacing preferences. / 报告未确认存储，不暴露 SQL 细节或替换偏好。
     * @param cause original storage failure / 原存储失败
     * @return public unavailable result / 公开不可用结果 */
    private static ApiException failure(SQLException cause) {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "NAVIGATION_PREFERENCE_UNCONFIRMED",
            "Navigation preference could not be confirmed; original value retained", Map.of(), Optional.empty()));
    }
}
