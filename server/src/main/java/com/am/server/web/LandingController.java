package com.am.server.web;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 公开「安装落地页」。
 *
 * <p>根路径 {@code /} 不再服务管理控制台 SPA（已搬到隐藏路径 {@code /console}），改为返回一个
 * 极简、自包含的安装说明页：只生成 aiwatchd 安装命令，<b>不暴露任何后台路由 / 接口 / SPA bundle</b>。
 * 这样员工（或其使用的 AI）访问服务器地址只看到装机引导，看不到后台页面信息。
 * gz
 */
@RestController
public class LandingController {

    private String html = "";

    @PostConstruct
    public void load() throws IOException {
        ClassPathResource res = new ClassPathResource("landing/install.html");
        try (var in = res.getInputStream()) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> landing() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
