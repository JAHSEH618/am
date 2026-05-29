package com.am.server.web;

import com.am.server.common.R;
import com.am.server.config.InstallProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端分发元信息：
 *
 * <p>前端"安装客户端"弹框拉这一份，知道：
 * <ul>
 *   <li>{@code configured}：运维有没有配 {@code aiwatch.install.dir} 且目录存在；
 *       前端 false 时弹框走"未配置"分支，告诉员工"联系运维"。</li>
 *   <li>{@code baseUrlPath}：固定 {@code /install}；前端拼成 {@code window.location.origin + baseUrlPath}
 *       作为脚本 / 二进制的下载根 URL（公司域名变化无感）。</li>
 *   <li>{@code platforms}：硬编码 4 平台 + 文件名，与 install.sh / install.ps1 的探测逻辑保持一一对应；
 *       前端按浏览器 OS 默认选中，员工也可手动切 Tab。</li>
 *   <li>{@code missingFiles}：扫一遍 dir，列出缺失的脚本 / 二进制，让运维一眼看出哪些没传。</li>
 * </ul>
 *
 * <p>本端点是公开的（不需要 admin token / 身份），员工还没安装 aiwatchd 时就要能访问。
 * gz
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/install")
public class InstallController {

    private static final String BASE_URL_PATH = "/install";

    /** 与 agent/build-dist.sh / install.sh 的探测分支一一对应；改这里时三处一起改。 */
    private static final List<Platform> PLATFORMS = List.of(
            new Platform("darwin", "arm64", "aiwatchd-darwin-arm64"),
            new Platform("darwin", "amd64", "aiwatchd-darwin-amd64"),
            new Platform("linux", "amd64", "aiwatchd-linux-amd64"),
            new Platform("windows", "amd64", "aiwatchd-windows-amd64.exe")
    );

    private static final List<String> SCRIPTS = List.of("aiwatchd.sh", "aiwatchd.ps1");

    private final InstallProperties installProperties;

    @GetMapping("/status")
    public R<StatusDto> status() {
        StatusDto out = new StatusDto();
        out.setBaseUrlPath(BASE_URL_PATH);
        out.setPlatforms(PLATFORMS);

        String dir = installProperties.getDir();
        if (dir == null || dir.isBlank()) {
            out.setConfigured(false);
            return R.ok(out);
        }
        File f = new File(dir).getAbsoluteFile();
        if (!f.isDirectory()) {
            out.setConfigured(false);
            out.setDir(f.getAbsolutePath());
            return R.ok(out);
        }
        out.setConfigured(true);
        out.setDir(f.getAbsolutePath());

        List<String> missing = new ArrayList<>();
        for (String s : SCRIPTS) {
            if (!new File(f, s).isFile()) missing.add(s);
        }
        for (Platform p : PLATFORMS) {
            if (!new File(f, p.getFilename()).isFile()) missing.add(p.getFilename());
        }
        out.setMissingFiles(missing);
        return R.ok(out);
    }

    @Data
    public static class StatusDto {
        private boolean configured;
        private String dir;
        private String baseUrlPath;
        private List<Platform> platforms;
        private List<String> missingFiles = List.of();
    }

    @Data
    public static class Platform {
        private final String os;
        private final String arch;
        private final String filename;
    }
}
