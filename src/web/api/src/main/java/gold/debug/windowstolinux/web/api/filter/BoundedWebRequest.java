package gold.debug.windowstolinux.web.api.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Bounds unknown-length JSON while Spring's message converter reads it.
 * <p>在 Spring 消息转换器读取时限制未知长度 JSON。
 */
final class BoundedWebRequest extends HttpServletRequestWrapper {
    /**
     * Source content consumed by this operation.
     * <p>当前操作消费的源内容。
     */
    private final ServletInputStream input;
    /**
     * Binds the supplied dependencies and state for bounded web request.
     * <p>为有界Web请求绑定传入的依赖及状态。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param limit limit / 限制
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    BoundedWebRequest(HttpServletRequest request, int limit) throws IOException {
        super(request);
        ServletInputStream original = request.getInputStream();
        input = new ServletInputStream() {
            /**
             * Count.
             * <p>数量。
             */
            private long count;
            /**
             * Checks the item or byte count against the explicit bound before accepting more content.
             * <p>在接受更多内容前按显式边界检查条目数或字节数。
             *
             * @param amount amount / 数量
             */
            private void count(int amount) {
                if (amount > 0 && (count += amount) > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            }
            /**
             * Reads anonymous.
             * <p>读取匿名。
             *
             * @return anonymous / 匿名
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override public int read() throws IOException { int result = original.read(); count(result < 0 ? 0 : 1); return result; }
            /**
             * Reads anonymous.
             * <p>读取匿名。
             *
             * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
             * @param offset offset / 偏移量
             * @param length length / 长度
             * @return anonymous / 匿名
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = original.read(bytes, offset, (int) Math.min(length, limit - count + 1)); count(read); return read;
            }
            /**
             * Reports whether the finished condition holds for this contract.
             * <p>判断当前契约是否满足已完成条件。
             *
             * @return true when finished condition holds for this contract, false otherwise / 当前契约是否满足已完成条件时为 true，否则为 false
             */
            @Override public boolean isFinished() { return original.isFinished(); }
            /**
             * Reports whether the ready condition holds for this contract.
             * <p>判断当前契约是否满足就绪条件。
             *
             * @return true when ready condition holds for this contract, false otherwise / 当前契约是否满足就绪条件时为 true，否则为 false
             */
            @Override public boolean isReady() { return original.isReady(); }
            /**
             * Updates read listener.
             * <p>更新读取监听器。
             *
             * @param listener listener / 监听器
             */
            @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
            /**
             * Closes the resources owned by this instance and completes its cleanup boundary.
             * <p>关闭当前实例持有的资源并完成其清理边界。
             *
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override public void close() throws IOException { original.close(); }
        };
    }
    /**
     * Returns source content consumed by this operation.
     * <p>返回当前操作消费的源内容。
     *
     * @return source content consumed by this operation / 当前操作消费的源内容
     */
    @Override public ServletInputStream getInputStream() { return input; }
    /**
     * Returns reader.
     * <p>返回读取器。
     *
     * @return reader / 读取器
     */
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); }
}
