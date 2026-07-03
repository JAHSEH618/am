# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`aiwatch-web` — the admin console SPA. React 18 + Vite 5 + TypeScript 5 (strict) + Ant Design 5,
ECharts for charts, axios for HTTP. Package manager is **pnpm 9** (`packageManager` in `package.json`).

## How it's built and served

This SPA is **not deployed standalone** — it is bundled into the Spring jar. `vite.config.ts` sets
`build.outDir` to `../resources/static/`, and `server/build.gradle`'s `frontendBuild` task (which
`processResources` depends on) runs `pnpm run build` during the backend build using a Gradle-managed
Node/pnpm. So `gradle bootJar` always ships a fresh bundle; you rarely build the frontend by hand.

At runtime Spring serves `static/index.html` as the SPA fallback for **`/console/**`** (the console is mounted
under `/console`, not root — see `com.am.server.config.WebConfig`), and forces `no-cache` on `index.html` so
clients don't pin a stale bundle after an upgrade. Root `/` is the public install landing
(`LandingController` → `resources/landing/install.html`), deliberately not the SPA.

## Local development

```bash
pnpm install
pnpm dev        # vite dev server on :5173, proxies /api -> http://127.0.0.1:8080
pnpm build      # tsc -b && vite build  -> ../resources/static/
pnpm lint       # eslint . --ext ts,tsx
```

Note the dev proxy targets **:8080**, but `gradle bootRun` (dev profile) listens on **:8081** — point
the proxy at whichever port your backend is actually on, or run the backend on 8080.

## Architecture

- **The whole SPA is mounted under `/console`**, not root: `vite.config.ts` sets `base: '/console/'` and
  `main.tsx` uses `<BrowserRouter basename="/console">`. Root `/` is a standalone public install landing
  (served by the backend `LandingController`, **not** this SPA) so employees fetching the installer never see
  the admin console. All in-app routes below are relative to that basename (the browser URL is `/console/...`);
  use react-router `navigate()`/`<Navigate>` (basename-aware), and for any hard `window.location` redirect
  prepend `/console` (see the 401 handler in `client.ts`).
- **Routing** (`src/App.tsx`, React Router v6): every route except `/login` is wrapped by `RequireAuth` +
  `MainLayout` (sidebar + header). Heavy pages (`Analysis`, `Projects`, `ModelsTools`, `SystemSettings`,
  `SessionDetail`) are `lazy()`-loaded. Main routes: `/dashboard`, `/realtime`, `/sessions[/:id]`,
  `/people[/:userCode]`, `/analysis`, `/projects`, `/models-tools`, `/alerts`, `/system`; several legacy
  paths (`/cost`, `/tools`, `/reports`, `/me`) redirect.
- **API layer** (`src/api/client.ts`): one axios instance, `baseURL: /api/v1`, `withCredentials: true`
  (session-cookie auth — there is no token in the browser). Every backend response is the envelope
  `R<T> = { code, data, message }`; use the `unwrap<T>()` helper, which throws on non-zero `code` and
  surfaces a toast. A response interceptor redirects to `/console/login?from=<path>` on HTTP 401 (note the
  `/console` prefix — it's a hard `window.location` redirect so it must include the basename). `src/api/types.ts`
  holds the DTO interfaces. All endpoint wrappers live here — add new calls to this file, not ad-hoc in pages.
- **Auth state** (`src/auth.ts` + `RequireAuth`): `localStorage` holds only the username for fast UI gating;
  the cookie is the real session, re-validated async via `GET /auth/me`.
- **Realtime** (`src/hooks/useSse.ts`): wraps `EventSource` on `/api/v1/dashboard/stream` for live dashboard
  updates; auto-reconnects.
- **Styling / design system** — authoritative spec in [`DESIGN.md`](DESIGN.md). Tokens are a single source:
  `src/styles/tokens.ts` (JS, for ECharts/canvas + `color` props) mirrored in `src/styles/global.css :root`
  (CSS vars, for DOM inline styles), wired into the AntD `ConfigProvider` theme in `src/main.tsx`. The one
  committed accent is **brand indigo `#4f46e5`** (the old `#2563eb` is gone). DOM inline styles use
  `var(--am-*)`; ECharts uses the JS tokens; status dots use `components/StatusDot`. Never scatter raw hex —
  add/borrow a token. 8pt spacing, `tnum` tabular numbers, sticky table headers as before.

## Gotchas

- **BFCache**: `src/main.tsx` listens for `pageshow`/`persisted` and reloads, because back/forward cache
  restores stale DOM that React won't re-init.
- `src/utils/dom.ts purgeStrayPortals()` removes orphaned AntD Modal/Drawer masks across route transitions —
  call it when a modal-heavy page can leave masks behind.
- TS is strict with `noUnusedLocals`/`noUnusedParameters`; the `@/*` path alias maps to `src/*`.
- ECharts / xlsx / antd are split into separate Vite chunks (see `vite.config.ts`) — keep those imports lazy.
- **PDF 导出**：与 xlsx 同模式——点击时才 `import('./Analysis/exportAnalysisPdf')`（团队报告）/
  `import('./exportUserPdf')`（UserDetail 单人）懒加载；pdfmake 是独立 Vite chunk，中文字体
  （Noto Sans SC，OFL）放 `public/fonts/` 仅导出时 fetch。公共件在 `src/lib/pdf/`
  （`pdfCore.ts` 字体加载/文档骨架/页脚页码，`chartImage.ts` ECharts 离屏 2x 截图）。
