package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/** Checks the Agent invocation's exact input binding before model review. / 模型审批前核验Agent调用的精确输入绑定。 */
public final class DeploymentAgentCommandInputBinding {
    private static final Pattern FRAME = Pattern.compile(Pattern.quote("'" + DeploymentManagedHelperBundle.PATH + "' 'agent-run' ")
        + "'[A-Za-z0-9._-]+' '[A-Za-z0-9._-]+' '[A-Za-z0-9._-]+' '[0-9a-f]{64}' '[0-9]+' '[0-9]+' "
        + "'(?:ordinary|docker|podman)' '([0-9a-f]{64})' '(?:-|[A-Za-z0-9+/=]+)'");

    private DeploymentAgentCommandInputBinding() {
    }

    /** Compares the literal digest argument and displayed body with original stdin bytes; it grants no permission. / 比较字面摘要参数、展示正文与原stdin字节，不产生许可。
     * @param invocation complete unwrapped product invocation / 完整解封产品调用
     * @param input original executable stdin bytes / 原可执行stdin字节
     * @param executable complete displayed executable body / 完整展示的可执行正文
     * @return product-measured byte consistency facts only / 仅产品实测的字节一致性事实 */
    public static Map<String, Object> facts(String invocation, byte[] input, String executable) {
        var frame = FRAME.matcher(invocation);
        if (!frame.matches())
            throw new IllegalArgumentException("Agent command must use the exact literal input protocol");
        byte[] displayed = executable.getBytes(StandardCharsets.UTF_8);
        try {
            if (!Arrays.equals(input, displayed))
                throw new IllegalArgumentException("Agent executable evidence does not match original stdin bytes");
            String measured;
            try {
                measured = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 unavailable", unavailable);
            }
            if (!measured.equals(frame.group(1)))
                throw new IllegalArgumentException("Agent digest argument does not match original stdin bytes");
            return Map.of("schema", "agent-executable-input-binding.v1", "helperVerb", "agent-run",
                "digestArgumentIndex", 8, "stdinBytes", input.length, "stdinSha256", measured,
                "argumentSha256", frame.group(1), "argumentDigestMatchesInput", true, "displayedUtf8MatchesInput", true);
        } finally {
            Arrays.fill(displayed, (byte) 0);
        }
    }
}
