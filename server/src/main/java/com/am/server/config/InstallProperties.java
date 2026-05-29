package com.am.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * aiwatch.install.* —— 客户端二进制分发目录
 *
 * <p>设计取舍：
 * <ul>
 *   <li>不把 aiwatchd 二进制塞进 jar：4 平台 ~60MB 会让 jar 翻倍，而且二进制更新频率
 *       和服务端代码完全独立，每改一行 agent 就重打 jar 不合理。</li>
 *   <li>独立目录 + Spring `ResourceHandlerRegistry` 把 /install/** 映射到磁盘，
 *       运维 rsync 上传二进制即可，无需重启服务。</li>
 *   <li>不存在或未配置时不挂主服务，仅 /api/v1/install/status 返回 configured=false，
 *       前端弹框走"未配置"分支提示运维。</li>
 * </ul>
 *
 * <p>建议生产部署目录：
 * <pre>
 * /srv/aiwatch/
 *   ├── aiwatch-server-2.0.0.jar
 *   ├── application-prod.yml
 *   └── install/                   ← {@link #dir}
 *       ├── aiwatchd.sh
 *       ├── aiwatchd.ps1
 *       ├── aiwatchd-darwin-arm64
 *       ├── aiwatchd-darwin-amd64
 *       ├── aiwatchd-linux-amd64
 *       └── aiwatchd-windows-amd64.exe
 * </pre>
 *
 * gz
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiwatch.install")
public class InstallProperties {

    /**
     * 二进制 + 安装脚本所在的本地磁盘目录。
     * 相对路径相对 jar 启动 cwd 解析；空串等于"未配置"，所有 install 端点会优雅降级。
     */
    private String dir = "";
}
