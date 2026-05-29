/**
 * 前端登录态轻量管理
 *
 * <p>真实登录态在后端 HttpSession（JSESSIONID Cookie）里；这里 localStorage 只存"用户名"用于：
 * <ul>
 *   <li>UI 显示当前登录的人（右上角）</li>
 *   <li>未登录时直接跳 /login，避免每页先打一发 API 再跳</li>
 * </ul>
 *
 * <p>真正的鉴权由后端 + axios 401 拦截器兜底；localStorage 被人手改也无所谓——
 * 没有 cookie，业务接口照样 401，照样会被赶去登录。
 *
 * gz
 */
const KEY = 'am.auth.username';

export function getCurrentUser(): string | null {
  return localStorage.getItem(KEY);
}

export function setCurrentUser(username: string) {
  localStorage.setItem(KEY, username);
}

export function clearCurrentUser() {
  localStorage.removeItem(KEY);
}
