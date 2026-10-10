package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;

/** Explicit database observation scope; it confers no execution authority. / 明确数据库观测范围，不产生执行权限。 */
public enum DeploymentAgentDatabaseScope {
    SOURCE, REMOTE;

    /** Requires the caller's exact explicit scope; missing and unknown values are rejected. / 要求调用方明确范围，拒绝缺省或未知值。
     * @param arguments actual capability arguments / 实际能力参数
     * @return explicit observation scope / 明确观测范围 */
    public static DeploymentAgentDatabaseScope parse(JsonNode arguments) {
        return valueOf(DeploymentJsonFields.text(arguments, "scope", 6));
    }
}
