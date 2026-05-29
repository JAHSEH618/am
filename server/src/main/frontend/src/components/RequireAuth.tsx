import { useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { fetchMe } from '../api/client';
import { clearCurrentUser, getCurrentUser, setCurrentUser } from '../auth';
import { purgeStrayPortals } from '../utils/dom';

/**
 * 路由守卫：未登录直接跳 /login，并把当前路径写到 ?from=
 *
 * <p>双重检查：
 * <ol>
 *   <li>本地 localStorage 有 username → 立刻渲染子组件（最优体验，避免每次都白屏一下）</li>
 *   <li>同时后台异步 fetchMe 校验 cookie 是否仍有效；过期就清 localStorage + 由 axios 拦截器跳登录</li>
 *   <li>本地无 username → 直接跳登录页（不打多余请求）</li>
 * </ol>
 *
 * gz
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  const localUser = getCurrentUser();
  const [verified, setVerified] = useState<boolean>(!!localUser);

  // 兜底：进入受保护路由时清掉上一次会话残留的 antd Modal/Drawer mask（BFCache、孤儿 portal 等）
  useEffect(() => {
    purgeStrayPortals();
  }, []);

  useEffect(() => {
    // 本地有用户名时异步对账：cookie 是否还在
    if (!localUser) return;
    let alive = true;
    fetchMe()
      .then((me) => {
        if (!alive) return;
        setCurrentUser(me.username);
        setVerified(true);
      })
      .catch(() => {
        if (!alive) return;
        clearCurrentUser();
        // axios 拦截器已经处理 401 跳转，这里不重复跳
      });
    return () => { alive = false; };
  }, [localUser]);

  if (!localUser) {
    const from = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?from=${from}`} replace />;
  }

  if (!verified) {
    // 这条分支理论上很少命中（localUser 时 verified 初始就是 true）
    return (
      <div style={{ minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Spin />
      </div>
    );
  }

  return <>{children}</>;
}
