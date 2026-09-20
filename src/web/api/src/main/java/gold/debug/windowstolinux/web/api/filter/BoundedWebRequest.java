package gold.debug.windowstolinux.web.api.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounds unknown-length JSON as it is read by Spring's message converter. */
final class BoundedWebRequest extends HttpServletRequestWrapper {
    private final ServletInputStream input;
    BoundedWebRequest(HttpServletRequest request, int limit) throws IOException {
        super(request);
        ServletInputStream original = request.getInputStream();
        input = new ServletInputStream() {
            private long count;
            private void count(int amount) {
                if (amount > 0 && (count += amount) > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            }
            @Override public int read() throws IOException { int result = original.read(); count(result < 0 ? 0 : 1); return result; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = original.read(bytes, offset, (int) Math.min(length, limit - count + 1)); count(read); return read;
            }
            @Override public boolean isFinished() { return original.isFinished(); }
            @Override public boolean isReady() { return original.isReady(); }
            @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
            @Override public void close() throws IOException { original.close(); }
        };
    }
    @Override public ServletInputStream getInputStream() { return input; }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); }
}
