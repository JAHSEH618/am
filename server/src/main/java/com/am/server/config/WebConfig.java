package com.am.server.config;

import com.am.server.web.security.AdminTokenInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Web MVC 配置：
 * <ul>
 *   <li>{@code /install/**} 映射到 {@link InstallProperties#getDir()}（如果配置且目录存在），
 *       用于分发 aiwatchd 客户端二进制 + 一键安装脚本。设计文档 §22。</li>
 *   <li>{@code /assets/**} 映射 vite 构建产物（带内容哈希）→ 1 年 immutable 长缓存。</li>
 *   <li>{@code /**} 映射到 classpath:/static/ 并做 SPA fallback：除 /api 与 /actuator 外，
 *       任意未命中的路径都回退到 index.html，让前端 React Router 处理。
 *       <b>SPA fallback 上的所有响应都强制 no-cache，避免 index.html 被浏览器缓存
 *       后引用到老的 hash bundle，导致升级后旧脚本残留 DOM（蒙层 / 错误状态）。</b></li>
 *   <li>{@code /api/v1/admin/**} 注册 X-Admin-Token 拦截器。</li>
 * </ul>
 * gz
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminTokenInterceptor;
    private final InstallProperties installProperties;
    private final Environment environment;

    /** dev profile 下禁用 resource chain 的元数据缓存：前端 npm run build 后端无需重启就能拿到新文件 */
    private boolean isDevProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor)
                .addPathPatterns("/api/v1/admin/**");
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        // dev: 不缓存 resource chain 解析结果（前端 build 后端无需重启就能拿到新 hash 文件）
        // prod: 缓存解析结果（性能更好，发版时 Spring Boot jar 包整个替换天然失效）
        boolean cacheChain = !isDevProfile();

        // /install/** 必须先注册，否则会被下面的 /** SPA fallback 吞掉
        String dir = installProperties.getDir();
        if (dir != null && !dir.isBlank()) {
            File f = new File(dir).getAbsoluteFile();
            if (f.isDirectory()) {
                // ResourceLocations 末尾必须加 /，否则 Spring 会按相对路径处理
                String location = f.toURI().toString();
                if (!location.endsWith("/")) location = location + "/";
                registry.addResourceHandler("/install/**")
                        .addResourceLocations(location)
                        .resourceChain(cacheChain);
                log.info("install dir mounted: /install/** -> {}", f.getAbsolutePath());
            } else {
                log.warn("aiwatch.install.dir = {} not a directory; /install/** will 404", f.getAbsolutePath());
            }
        } else {
            log.info("aiwatch.install.dir not set; /install/** disabled (frontend will guide ops to configure)");
        }

        // /assets/** 是 vite 构建出来的带 hash 的文件（如 index-CgaWtEoG.js），
        // 内容变了 hash 就变 → 直接长缓存 1 年 + immutable，命中浏览器本地最快
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .resourceChain(cacheChain);

        // 其他所有路径走 SPA fallback：命中文件就返回文件、没命中就返回 index.html
        // 关键：CacheControl=no-cache 让浏览器每次都来询问；
        //   - 如果 index.html 没变（ETag 相同）→ 304 极快；
        //   - 如果 index.html 变了（发版）→ 立刻拿到新 index.html，跟着拉新的 hash bundle
        // 这样彻底杜绝"浏览器抱着老 index.html + 引用了被删的 hash 文件 / 残留旧 DOM"的场景
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(cacheChain)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location) throws java.io.IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                                || resourcePath.startsWith("install/")) {
                            return null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        Resource indexHtml = location.createRelative("index.html");
                        return indexHtml.exists() ? indexHtml : null;
                    }
                });
    }
}
