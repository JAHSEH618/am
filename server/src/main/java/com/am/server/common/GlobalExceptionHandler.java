package com.am.server.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器，把异常统一转换为 R 响应体
 * gz
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("biz exception: code={} message={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public R<Void> handleParam(Exception e) {
        log.warn("param invalid: {}", e.getMessage());
        return R.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public R<Void> handleNotFound(NoHandlerFoundException e) {
        return R.fail(ErrorCode.RESOURCE_NOT_FOUND, e.getMessage());
    }

    /**
     * Spring 6 / Boot 3：访问不存在的 classpath 静态文件时抛此异常（含 SPA 入口 index.html 缺失时整站 fallback 失败）。
     * 勿归类为 50000，否则开发者误以为服务端代码崩溃；常见原因是 {@code clean} 删空了 {@code src/main/resources/static}
     * 却又带 {@code -x frontendBuild} 启动。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("no static resource: path={}", e.getResourcePath());
        String hint = "静态资源未找到。若刚执行过 ./gradlew clean 且启动时跳过 frontendBuild，请先运行 ./gradlew frontendBuild 再重启。";
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ErrorCode.RESOURCE_NOT_FOUND, hint));
    }

    /**
     * 兜底处理器。<b>agent 通道</b>（{@code /api/v1/agent/**}）与控制台的行为刻意不同：
     * <ul>
     *   <li>控制台：沿用 HTTP 200 + {@code R.fail}（前端 axios 按业务码处理；查询超时给 {@link ErrorCode#QUERY_TIMEOUT}）。</li>
     *   <li>agent：客户端只有收到 HTTP 503 / 业务码 {@link ErrorCode#SERVER_BUSY} 才会「不落 outbox、退让重报」，
     *       其余失败一律整包落 outbox 且保持快节奏——所以「过载 / DB 不可用」类异常
     *       （{@link OverloadFailures#isOverload}：连接池取连接超时、开不了事务、锁等待超时 / 死锁、
     *       事务或语句超时、乐观锁冲突，沿 cause 链判断）必须回 <b>503 + 50301 + Retry-After</b>；
     *       Spring MVC 自带状态码的异常（405 / 415 …）按原状态码回；其它未识别异常回 <b>500</b>（不再是 200）
     *       并 {@code log.error}——200 会让客户端把失败当成功推进游标。</li>
     * </ul>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e, HttpServletRequest request) {
        if (isAgentPath(request)) {
            return handleAgentFailure(e, request);
        }
        if (isQueryTimeout(e)) {
            log.warn("query aborted by max execution time: {}", e.getMessage());
            return ResponseEntity.ok(R.fail(ErrorCode.QUERY_TIMEOUT, "查询超时，请缩小时间窗口后重试"));
        }
        log.error("unexpected error", e);
        return ResponseEntity.ok(R.fail(ErrorCode.INTERNAL_ERROR, "internal server error"));
    }

    private static final String AGENT_PATH_PREFIX = "/api/v1/agent/";

    /** 过载类 503 的 WARN 日志节流：连接池打满时每个请求都会走到这里，逐条打日志只会雪上加霜。 */
    private static final LogThrottle OVERLOAD_LOG_THROTTLE = new LogThrottle(10_000L);

    private static boolean isAgentPath(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        return uri != null && uri.startsWith(AGENT_PATH_PREFIX);
    }

    private ResponseEntity<R<Void>> handleAgentFailure(Exception e, HttpServletRequest request) {
        if (OverloadFailures.isOverload(e)) {
            long n = OVERLOAD_LOG_THROTTLE.tryEmit();
            if (n > 0) {
                log.warn("agent request rejected with 503 (database overloaded/unavailable): {} {} — {} occurrence(s)"
                                + " since last log; cause: {}",
                        request.getMethod(), request.getRequestURI(), n, rootMessage(e));
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", OverloadFailures.RETRY_AFTER_SECONDS)
                    .body(R.fail(ErrorCode.SERVER_BUSY, "server busy, retry next tick"));
        }
        if (e instanceof ErrorResponse er) {
            // Spring MVC 自带状态码的异常（405 / 415 / 406 …）：保持其原状态码，不当作 500
            log.warn("agent request rejected: {} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                    er.getStatusCode().value(), e.getMessage());
            return ResponseEntity.status(er.getStatusCode())
                    .body(R.fail(ErrorCode.PARAM_INVALID, "request rejected: " + er.getStatusCode()));
        }
        log.error("unexpected error on agent path {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.INTERNAL_ERROR, "internal server error"));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        for (int i = 0; i < 16 && t.getCause() != null && t.getCause() != t; i++) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** MySQL ER_QUERY_TIMEOUT(3024)：SELECT 超过 MAX_EXECUTION_TIME 被服务端中止。 */
    private static final int MYSQL_ER_QUERY_TIMEOUT = 3024;

    static boolean isQueryTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLTimeoutException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && sql.getErrorCode() == MYSQL_ER_QUERY_TIMEOUT) {
                return true;
            }
        }
        return false;
    }
}
