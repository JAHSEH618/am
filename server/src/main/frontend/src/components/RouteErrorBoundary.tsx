import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button, Result } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  isChunkError: boolean;
}

// 「旧 chunk 失效」的典型报错指纹：用户停留在旧 index.html，后端已发布新 bundle
// （chunk hash 变了），进入 lazy 路由时动态 import 会 404 / 加载失败。
const CHUNK_ERROR_RE =
  /ChunkLoadError|Loading chunk|dynamically imported module|Importing a module script failed/i;

/**
 * 路由级错误边界 —— 主要兜「发版后旧 chunk 失效导致的白屏」。
 *
 * React 的 lazy() 在 chunk 加载失败时会在渲染期抛错，默认行为是整棵子树卸载 → 白屏；
 * 这里捕获后给出「刷新页面」引导。为避免把真实渲染 bug 掩盖成「刷新就好」，会区分
 * chunk 失效与普通渲染错误并分别给文案，且始终 console.error 留痕（非静默自动刷新）。
 * 复位策略：父级以 location.pathname 作 key 重新挂载本组件即可。
 */
export default class RouteErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, isChunkError: false };

  static getDerivedStateFromError(error: unknown): State {
    const msg = error instanceof Error ? `${error.name} ${error.message}` : String(error);
    return { hasError: true, isChunkError: CHUNK_ERROR_RE.test(msg) };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 留痕到控制台，便于排查（尤其区分 chunk 失效与真实渲染错误）。
    console.error('[RouteErrorBoundary]', error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      const chunk = this.state.isChunkError;
      return (
        <div style={{ padding: '48px 24px', display: 'flex', justifyContent: 'center' }}>
          <Result
            status="warning"
            title={chunk ? '页面已更新' : '页面出错了'}
            subTitle={
              chunk
                ? '应用刚发布了新版本，当前页面资源已失效。点击刷新即可加载最新版本。'
                : '渲染时发生了意外错误，可尝试刷新页面恢复；若反复出现请联系运维。'
            }
            extra={
              <Button
                type="primary"
                icon={<ReloadOutlined />}
                onClick={() => window.location.reload()}
              >
                刷新页面
              </Button>
            }
          />
        </div>
      );
    }
    return this.props.children;
  }
}
