package com.am.server.config;

import com.am.server.web.security.AdminTokenInterceptor;
import com.am.server.web.security.InstallTokenInterceptor;
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
    private final InstallTokenInterceptor installTokenInterceptor;
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
        // 安装端点预共享令牌（install.token 非空时生效）：只挡 /install/** 实体文件
        // （脚本 / 二进制 / manifest）——这是外人拖整包反推后端的入口。
        // /api/v1/install/status 不挡：登录页未登录态要拉它渲染安装面板。
        registry.addInterceptor(installTokenInterceptor)
                .addPathPatterns("/install/**");
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

        // 管理控制台 SPA 搬到隐藏路径 /console（与公开落地页 / 分离）。
        // 资源由 vite base=/console/ 生成，引用形如 /console/assets/xxx.js。

        // /console/assets/** 带 hash 的构建产物 → 长缓存 1 年 + immutable。
        // 必须先于 /console/** 注册，保证 assets 命中专用 handler。
        registry.addResourceHandler("/console/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .resourceChain(cacheChain);

        // /console 与 /console/** 走 SPA fallback：命中文件返回文件，否则回退 index.html（no-cache）。
        registry.addResourceHandler("/console", "/console/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(cacheChain)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location) throws java.io.IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        Resource indexHtml = location.createRelative("index.html");
                        return indexHtml.exists() ? indexHtml : null;
                    }
                });
        // 根 / 与未知公开路径不再服务 admin SPA —— 由 LandingController 提供极简安装落地页。
    }
}
