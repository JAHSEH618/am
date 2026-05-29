import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
// 设计文档 20.2 节：前端构建产物输出到 Spring Boot 的 static 目录
// outDir 相对 vite 项目根（即 src/main/frontend），向上一级是 src/main，再进 resources/static
export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, 'src'),
        },
    },
    build: {
        outDir: path.resolve(__dirname, '../resources/static'),
        emptyOutDir: true,
        sourcemap: false,
        rollupOptions: {
            output: {
                manualChunks: function (id) {
                    if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) {
                        return 'echarts';
                    }
                    if (id.includes('node_modules/xlsx')) {
                        return 'xlsx';
                    }
                    if (id.includes('node_modules/antd') || id.includes('node_modules/@ant-design')) {
                        return 'antd';
                    }
                },
            },
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
            },
        },
    },
    // SSE 长连接需要禁用响应压缩以及保持 Connection: keep-alive，由 Spring 自身处理
    // 这里仅保证开发代理端口正确
});
