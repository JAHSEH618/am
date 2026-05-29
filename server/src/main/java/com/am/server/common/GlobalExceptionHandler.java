package com.am.server.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("unexpected error", e);
        return R.fail(ErrorCode.INTERNAL_ERROR, "internal server error");
    }
}
