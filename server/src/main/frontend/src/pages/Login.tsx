import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  AppleOutlined,
  CheckCircleTwoTone,
  CopyOutlined,
  LinuxOutlined,
  LockOutlined,
  UserOutlined,
  WindowsOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { fetchInstallStatus, login } from '../api/client';
import type { InstallStatus } from '../api/types';
import { setCurrentUser } from '../auth';
import { BrandIcon } from '../components/brand/BrandIcon';
import { purgeStrayPortals } from '../utils/dom';

/**
 * 登录页 + 客户端自助安装入口
 *
 * <p>布局：左卡客户端自助安装（OS Tab + 三段表单 + 实时拼装命令 + 复制按钮），
 * 右卡管理员登录。两侧均为浅色卡片，配色基于 indigo / slate，避免大块深色背景导致
 * Tabs 等浅色组件可读性下降。
 *
 * gz
 */

type OS = 'darwin' | 'windows' | 'linux';

function detectOS(): OS {
  const ua = navigator.userAgent.toLowerCase();
  if (ua.includes('mac')) return 'darwin';
  if (ua.includes('win')) return 'windows';
  return 'linux';
}

// 公司邮箱（如 xxxx@hisuntech.com），录入框不做强制大写
const USER_CODE_RE = /^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,63}$/;

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();

  const [submitting, setSubmitting] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);

  // 兜底：进入登录页时把上一次会话残留的 antd Modal / Drawer mask 清掉。
  useEffect(() => {
    purgeStrayPortals();
  }, []);

  const onFinish = async ({ username, password }: { username: string; password: string }) => {
    setSubmitting(true);
    setAuthError(null);
    try {
      const me = await login(username.trim(), password);
      setCurrentUser(me.username);
      const from = new URLSearchParams(location.search).get('from');
      const safeFrom = from && from.startsWith('/') && !from.startsWith('//') ? from : '/dashboard';
      navigate(safeFrom, { replace: true });
    } catch {
      setAuthError('账号或密码错误');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        background:
          'radial-gradient(1200px 600px at 10% 0%, #ede9fe 0%, transparent 60%),' +
          'radial-gradient(900px 500px at 100% 100%, #dbeafe 0%, transparent 55%),' +
          'linear-gradient(180deg, #f8fafc 0%, var(--am-brand-bg) 100%)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '32px 24px',
      }}
    >
      <div
        style={{
          display: 'flex',
          gap: 24,
          width: '100%',
          maxWidth: 1180,
          alignItems: 'stretch',
        }}
      >
        <InstallPanel />
        <LoginPanel
          submitting={submitting}
          authError={authError}
          onFinish={onFinish}
        />
      </div>
    </div>
  );
}

// ===================== 左卡：客户端自助安装 =====================

function InstallPanel() {
  const [activeOS, setActiveOS] = useState<OS>(detectOS());
  const [status, setStatus] = useState<InstallStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(true);

  const [userCode, setUserCode] = useState('');
  const [userName, setUserName] = useState('');
  const [department, setDepartment] = useState('');

  useEffect(() => {
    fetchInstallStatus()
      .then(setStatus)
      .catch(() => setStatus(null))
      .finally(() => setStatusLoading(false));
  }, []);

  const trimmedCode = userCode.trim();
  const trimmedName = userName.trim();
  const trimmedDept = department.trim();
  const userCodeValid = USER_CODE_RE.test(trimmedCode);
  const formValid = userCodeValid && trimmedName.length > 0 && trimmedDept.length > 0;

  const baseUrl = useMemo(() => {
    const path = status?.base_url_path || '/install';
    return `${window.location.origin}${path}`;
  }, [status]);

  const origin = window.location.origin;
  // 安全提示：控制台经明文 HTTP 访问时，安装命令、上报与登录都可能被中间人窥探/篡改。
  // localhost / 127.0.0.1 属本地调试，不告警。
  const isInsecureOrigin =
    origin.startsWith('http://') &&
    !origin.includes('localhost') &&
    !origin.includes('127.0.0.1');

  // 三项必填校验：未通过时只渲染"模板",但 CommandBox 会阻止复制 / 选中,
  // 所以下面的占位符只是 UI 引导,实际不会变成可复制的"半成品命令"。
  const cmdShell = useMemo(() => {
    const code = trimmedCode || '<公司邮箱>';
    const name = trimmedName || '<姓名>';
    const dept = trimmedDept || '<部门>';
    return [
      `curl -fsSL ${baseUrl}/aiwatchd.sh | bash -s --`,
      `--clean`,
      `--user-code ${shellQuote(code)}`,
      `--user-name ${shellQuote(name)}`,
      `--department ${shellQuote(dept)}`,
      `--server-url ${origin}`,
    ].join(' \\\n  ');
  }, [trimmedCode, trimmedName, trimmedDept, baseUrl, origin]);

  const cmdPwsh = useMemo(() => {
    const code = trimmedCode || '<公司邮箱>';
    const name = trimmedName || '<姓名>';
    const dept = trimmedDept || '<部门>';
    return [
      `& ([scriptblock]::Create((irm ${baseUrl}/aiwatchd.ps1)))`,
      `-Clean`,
      `-UserCode ${psQuote(code)}`,
      `-UserName ${psQuote(name)}`,
      `-Department ${psQuote(dept)}`,
      `-ServerUrl ${psQuote(origin)}`,
    ].join(' `\n  ');
  }, [trimmedCode, trimmedName, trimmedDept, baseUrl, origin]);

  const cmdByOS: Record<OS, string> = {
    darwin: cmdShell,
    windows: cmdPwsh,
    linux: cmdShell,
  };

  // Tab 顺序：用户要求 macOS → Windows → Linux
  const tabItems = [
    {
      key: 'darwin',
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <AppleOutlined /> macOS
        </span>
      ),
    },
    {
      key: 'windows',
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <WindowsOutlined /> Windows
        </span>
      ),
    },
    {
      key: 'linux',
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <LinuxOutlined /> Linux
        </span>
      ),
    },
  ];

  const tipByOS: Record<OS, string> = {
    darwin: '在「终端」(Terminal.app) 中粘贴并回车',
    windows: '在 PowerShell 中粘贴并回车',
    linux: '在终端中粘贴并回车',
  };

  return (
    <section
      style={{
        flex: '1.6 1 0',
        minWidth: 0,
        background: '#ffffff',
        borderRadius: 16,
        boxShadow: '0 12px 40px rgba(15,23,42,.06), 0 2px 6px rgba(15,23,42,.04)',
        border: '1px solid rgba(15,23,42,.05)',
        padding: '32px 36px 28px',
        display: 'flex',
        flexDirection: 'column',
        gap: 20,
      }}
    >
      <header style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <BrandIcon variant="monogram" size={30} style={{ boxShadow: '0 4px 10px rgba(99,102,241,.35)' }} />
          <Typography.Title level={3} style={{ margin: 0, color: 'var(--am-ink)', fontWeight: 700 }}>
            AIWatch
          </Typography.Title>
          <Tag
            color="geekblue"
            style={{
              marginLeft: 4,
              borderRadius: 999,
              fontSize: 11,
              padding: '0 10px',
              border: 'none',
            }}
          >
            员工 AI 工时统计
          </Tag>
        </div>
        <Typography.Text style={{ color: 'var(--am-ink-3)', fontSize: 13 }}>
          自助安装客户端 — 统计你日常使用 AI 编码的协作时长与产出
        </Typography.Text>
      </header>

      {/* 状态 Alerts（仅未配置 / 缺文件时显示） */}
      {!statusLoading && status && !status.configured && (
        <Alert
          type="warning"
          showIcon
          message="运维尚未配置客户端分发目录"
          description={
            <span>
              请管理员设置 <Typography.Text code>aiwatch.install.dir</Typography.Text>{' '}
              并把 aiwatchd 二进制 + 安装脚本放进去后重启服务。
            </span>
          }
        />
      )}
      {!statusLoading && status?.configured && status.missing_files.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`分发目录缺少 ${status.missing_files.length} 个文件`}
          description={
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {status.missing_files.map((f) => (
                <Tag key={f} color="orange">
                  {f}
                </Tag>
              ))}
            </div>
          }
        />
      )}

      {/* Step 1：表单 */}
      <div>
        <SectionLabel index={1} title="填写你的信息" />
        <Form layout="vertical" component="div" style={{ marginTop: 8 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
            <Form.Item
              label="公司邮箱"
              required
              validateStatus={trimmedCode && !userCodeValid ? 'error' : ''}
              help={
                trimmedCode && !userCodeValid
                  ? '请输入有效的公司邮箱'
                  : undefined
              }
              style={{ marginBottom: 0 }}
            >
              <Input
                placeholder="如 xxxx@hisuntech.com"
                value={userCode}
                onChange={(e) => setUserCode(e.target.value)}
                onBlur={(e) => setUserCode(e.target.value.trim())}
                maxLength={64}
                allowClear
                style={{ letterSpacing: 0.5, fontFamily: 'SFMono-Regular, Consolas, Menlo, monospace' }}
              />
            </Form.Item>
            <Form.Item label="姓名" required style={{ marginBottom: 0 }}>
              <Input
                placeholder="如 张三"
                value={userName}
                onChange={(e) => setUserName(e.target.value)}
                maxLength={32}
                allowClear
              />
            </Form.Item>
            <Form.Item label="部门" required style={{ marginBottom: 0 }}>
              <Input
                placeholder="如 研发部"
                value={department}
                onChange={(e) => setDepartment(e.target.value)}
                maxLength={64}
                allowClear
              />
            </Form.Item>
          </div>
        </Form>
      </div>

      {/* Step 2：OS Tab + 命令 */}
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <SectionLabel index={2} title="选择系统并复制命令" />
        {isInsecureOrigin && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 8 }}
            message="当前以明文 HTTP 访问，存在中间人风险"
            description="安装命令、上报与登录在 HTTP 下可能被窃听/篡改。强烈建议为本平台配置 HTTPS（反向代理终止 TLS）后再分发安装命令。"
          />
        )}
        <Tabs
          activeKey={activeOS}
          onChange={(k) => setActiveOS(k as OS)}
          items={tabItems}
          size="middle"
          style={{ marginTop: 4, marginBottom: 8 }}
        />

        <Typography.Text style={{ color: 'var(--am-ink-3)', fontSize: 12, marginBottom: 8 }}>
          {tipByOS[activeOS]}
        </Typography.Text>

        <CommandBox cmd={cmdByOS[activeOS]} disabled={!formValid} />
      </div>
    </section>
  );
}

function SectionLabel({ index, title }: { index: number; title: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span
        style={{
          width: 22,
          height: 22,
          borderRadius: 999,
          background: 'var(--am-brand-bg)',
          color: 'var(--am-brand)',
          fontWeight: 700,
          fontSize: 12,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {index}
      </span>
      <Typography.Text strong style={{ color: 'var(--am-ink)', fontSize: 14 }}>
        {title}
      </Typography.Text>
    </div>
  );
}

// 把单个 token 包成 POSIX 单引号字符串；包含 ' 时拆开转义。
// 避免姓名 / 部门里带空格或中文产生奇怪的 shell 解析问题。
function shellQuote(s: string): string {
  if (s === '' || /^[A-Za-z0-9._/:@+-]+$/.test(s)) return s;
  return `'${s.replace(/'/g, "'\\''")}'`;
}

// PowerShell 单引号转义：' 变成 ''；所有值都用单引号包，避免 $env: 等扩展。
function psQuote(s: string): string {
  return `'${s.replace(/'/g, "''")}'`;
}

// 通用文本复制 —— 优先 Clipboard API，失败回退到 execCommand('copy')。
//
// 背景：navigator.clipboard 只在 secure context（https / localhost）下可用，
// 公司内网 http 部署时直接 throw NotAllowedError，按钮看起来像"坏了"。
// fallback 路径：在 body 里挂一个 readOnly textarea -> select -> execCommand('copy') -> remove。
// execCommand 虽然在标准里被标记为 deprecated，但所有主流浏览器仍保留兼容实现。
async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 走 fallback
    }
  }

  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.left = '-1000px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, text.length);
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

function CommandBox({ cmd, disabled }: { cmd: string; disabled: boolean }) {
  const [copied, setCopied] = useState(false);

  // 复制命令到剪贴板。
  //
  // 生产环境通常走 http（内网 IP），navigator.clipboard 仅在 secure context（https / localhost）
  // 下可用，否则会直接 throw NotAllowedError，看起来就像"按钮坏了"。
  // 兜底用经典的 document.execCommand('copy') 路径，挂一个 textarea 进 DOM、选中、execCommand,
  // 立即移除 —— 兼容所有现代浏览器（Chrome 仍保留这个 API 用于兼容场景）。
  const onCopy = async () => {
    if (disabled) return;

    const ok = await copyText(cmd);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } else {
      message.error('复制失败,请手动选中命令复制');
    }
  };

  // 三项填齐前禁止复制 + 禁止文本选中（防止"先复制半成品再手填"），
  // 同时阻断拖拽和右键菜单兜底；保留可视化提示由 Tooltip 给出。
  const guardHandlers = disabled
    ? {
        onCopy: (e: React.SyntheticEvent) => e.preventDefault(),
        onCut: (e: React.SyntheticEvent) => e.preventDefault(),
        onContextMenu: (e: React.SyntheticEvent) => e.preventDefault(),
        onDragStart: (e: React.SyntheticEvent) => e.preventDefault(),
      }
    : {};

  const codeBlock = (
    <div
      {...guardHandlers}
      style={{
        position: 'relative',
        background: '#0b1220',
        color: disabled ? '#475569' : '#e2e8f0',
        padding: '16px 64px 16px 18px',
        borderRadius: 10,
        fontFamily: 'SFMono-Regular, Consolas, Menlo, monospace',
        fontSize: 12.5,
        lineHeight: 1.75,
        whiteSpace: 'pre',
        overflowX: 'auto',
        border: '1px solid rgba(148,163,184,.18)',
        userSelect: disabled ? 'none' : 'text',
        WebkitUserSelect: disabled ? 'none' : 'text',
        cursor: disabled ? 'not-allowed' : 'text',
        opacity: disabled ? 0.6 : 1,
        transition: 'opacity .15s',
      }}
    >
      <Tooltip
        title={disabled ? '请先填写公司邮箱 / 姓名 / 部门' : copied ? '已复制' : '复制命令'}
        placement="left"
      >
        <Button
          type="text"
          size="small"
          disabled={disabled}
          onClick={onCopy}
          icon={copied ? <CheckCircleTwoTone twoToneColor="#22c55e" /> : <CopyOutlined />}
          style={{
            position: 'absolute',
            right: 8,
            top: 8,
            color: copied ? 'var(--am-success)' : '#94a3b8',
            background: 'rgba(15,23,42,.6)',
            border: '1px solid rgba(148,163,184,.2)',
          }}
        >
          {copied ? '已复制' : '复制'}
        </Button>
      </Tooltip>
      {cmd}
    </div>
  );

  return codeBlock;
}

// ===================== 右卡：管理员登录 =====================

interface LoginPanelProps {
  submitting: boolean;
  authError: string | null;
  onFinish: (v: { username: string; password: string }) => void;
}

function LoginPanel({ submitting, authError, onFinish }: LoginPanelProps) {
  return (
    <section
      style={{
        flex: '1 1 0',
        minWidth: 360,
        maxWidth: 420,
        background: '#ffffff',
        borderRadius: 16,
        boxShadow: '0 12px 40px rgba(15,23,42,.06), 0 2px 6px rgba(15,23,42,.04)',
        border: '1px solid rgba(15,23,42,.05)',
        padding: '40px 36px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
      }}
    >
      <div style={{ marginBottom: 28 }}>
        <Typography.Title level={3} style={{ margin: 0, color: 'var(--am-ink)', fontWeight: 700 }}>
          管理员登录
        </Typography.Title>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          仅授权管理员可登录后台 · 员工无需登录
        </Typography.Text>
      </div>

      <Form layout="vertical" onFinish={onFinish} autoComplete="off" requiredMark={false}>
        <Form.Item
          name="username"
          label="用户名"
          rules={[{ required: true, message: '请输入用户名' }]}
        >
          <Input prefix={<UserOutlined />} autoComplete="username" size="large" />
        </Form.Item>
        <Form.Item
          name="password"
          label="密码"
          rules={[{ required: true, message: '请输入密码' }]}
          style={{ marginBottom: authError ? 8 : 24 }}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="current-password" size="large" />
        </Form.Item>
        {authError && (
          <div style={{ color: 'var(--am-error-fg)', fontSize: 12, marginBottom: 16 }}>{authError}</div>
        )}
        <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
          登 录
        </Button>
      </Form>
    </section>
  );
}
