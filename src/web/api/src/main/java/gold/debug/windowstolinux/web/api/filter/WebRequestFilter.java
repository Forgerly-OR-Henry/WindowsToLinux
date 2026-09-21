package gold.debug.windowstolinux.web.api.filter;

import gold.debug.windowstolinux.web.api.config.WebHttpPolicy;
import gold.debug.windowstolinux.web.api.error.WebErrorResponse;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Enforces request size, origin and maintenance boundaries before controller dispatch.
 * <p>在分派到控制器前执行请求大小、来源及维护边界检查。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class WebRequestFilter extends OncePerRequestFilter {
    /**
     * Bound web http policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的WebHTTP策略协作对象。
     */
    private final WebHttpPolicy policy;
    /**
     * Requests.
     * <p>请求集合。
     * <p>streams:
     * Streams.
     * <p>流集合。
     * <p>uploads:
     * Uploads.
     * <p>上传集合。
     */
    private final Semaphore requests, streams, uploads;
    /**
     * Binds the supplied dependencies and state for web request filter.
     * <p>为Web请求筛选绑定传入的依赖及状态。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     */
    public WebRequestFilter(WebHttpPolicy policy) {
        this.policy = policy; requests = new Semaphore(policy.requests()); streams = new Semaphore(policy.streams()); uploads = new Semaphore(policy.uploads());
    }

    /**
     * Checks do filter internal syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查do筛选内部语法及边界。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param response response / 响应
     * @param chain chain / 调用链
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        headers(response);
        boolean acquired = requests.tryAcquire(); Semaphore operation = null;
        try {
            if (!acquired) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            guard(request); query(request);
            String path = request.getRequestURI(); boolean upload = upload(path);
            Semaphore candidate = upload ? uploads : path.matches("/api/v1/tasks/[^/]+/events") ? streams : null;
            if (candidate != null) {
                if (!candidate.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
                operation = candidate;
            }
            boolean json = !upload && Set.of("POST", "PUT", "DELETE").contains(request.getMethod());
            if (json && request.getContentLengthLong() > policy.jsonBytes()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            chain.doFilter(json ? new BoundedWebRequest(request, policy.jsonBytes()) : request, response);
        } catch (Exception failure) {
            if (!response.isCommitted()) {
                var error = WebErrorResponse.from(failure); response.setStatus(error.status());
                response.setContentType("application/json;charset=UTF-8"); response.getWriter().write(WebJsonCodec.write(error));
            }
        } finally { if (operation != null) operation.release(); if (acquired) requests.release(); }
    }

    /**
     * Checks guard syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查guard语法及边界。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void guard(HttpServletRequest request) throws IOException {
        String host = "127.0.0.1:" + request.getLocalPort(), origin = "http://" + host;
        if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                || Collections.list(request.getHeaders("Host")).size() != 1 || !host.equals(request.getHeader("Host"))) throw new SecurityException();
        String supplied = request.getHeader("Origin"), fetch = request.getHeader("Sec-Fetch-Site");
        if (supplied != null && !origin.equals(supplied) || fetch != null && !Set.of("same-origin", "none").contains(fetch)) throw new SecurityException();
        String method = request.getMethod(), path = request.getRequestURI();
        if (!Set.of("GET", "HEAD", "POST", "PUT", "DELETE").contains(method)
                || method.equals("HEAD") && path.startsWith("/api/")) throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);
        if (!path.startsWith("/api/v1/") && !path.equals("/") && !path.equals("/index.html")
                && !path.matches("/assets/[A-Za-z0-9_.-]+\\.(js|css|svg|woff2|png)")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!Set.of("GET", "HEAD").contains(method)) {
            if (!origin.equals(supplied) || !"web".equals(request.getHeader("X-W2L-Client"))) throw new SecurityException();
            String type = request.getContentType();
            if (type == null || !(upload(path) ? type.equals("application/octet-stream") : type.matches("application/json(?:;\\s*charset=[Uu][Tt][Ff]-8)?")))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    /**
     * Checks query syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查查询语法及边界。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void query(HttpServletRequest request) {
        String query = request.getQueryString(); if (query == null) return;
        if (query.length() > policy.queryCharacters()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        var names = new HashSet<String>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (!names.add(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) throw new IllegalArgumentException("Duplicate query parameter");
        }
    }
    /**
     * Uploads web request filter.
     * <p>上传Web请求筛选。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return true when uploads web request filter, false otherwise / 上传Web请求筛选时为 true，否则为 false
     */
    private static boolean upload(String path) {
        return path.matches("/api/v1/sources/[a-f0-9-]{36}/(files|archive)") || path.equals("/api/v1/backups/upload");
    }
    /**
     * Adds the fixed no-cache, content-type, framing, referrer and content-security response headers.
     * <p>添加固定的禁用缓存、内容类型、框架、来源及内容安全响应头。
     *
     * @param response response / 响应
     */
    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer"); response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    }
}
