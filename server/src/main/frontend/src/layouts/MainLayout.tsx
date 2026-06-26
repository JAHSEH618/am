import { useEffect, useMemo, useState } from 'react';
import { Button, Dropdown, Layout, Menu, Space, Tooltip } from 'antd';
import {
  DashboardOutlined,
  ThunderboltOutlined,
  RobotOutlined,
  ApartmentOutlined,
  ProjectOutlined,
  ExperimentOutlined,
  FileSearchOutlined,
  AlertOutlined,
  SettingOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { fetchMonitorTargets, logout } from '../api/client';
import type { MonitorTarget } from '../api/types';
import { clearCurrentUser, getCurrentUser } from '../auth';
import { BrandIcon } from '../components/brand/BrandIcon';

const { Header, Sider, Content } = Layout;

const COLLAPSE_KEY = 'am.layout.siderCollapsed';

// 每个路由的页面元数据：左侧菜单与右侧 Header 文案共享同一份配置，
// 增加新路由时改这里一处即可。
interface NavItem {
  key: string;
  icon: React.ReactNode;
  label: string; // 菜单文案
  title: string; // 页面 H1 文案（与 menu label 保持一致，但单独留出灵活性）
  subtitle?: string; // 副标题（描述这个页面解决什么问题）
}

const NAV_ITEMS: NavItem[] = [
  { key: '/dashboard',    icon: <DashboardOutlined />,   label: '观测大盘',  title: '观测大盘',  subtitle: 'AI 渗透率、Token 消耗、活跃团队 - 团队级 AI 使用全景' },
  { key: '/realtime',     icon: <ThunderboltOutlined />, label: '实时活跃',  title: '实时活跃',  subtitle: '当前所有 Agent 进行中的会话状态流' },
  { key: '/sessions',     icon: <RobotOutlined />,       label: 'AI 会话',   title: 'AI 会话',   subtitle: '近期所有 AI 编码会话列表与详情' },
  { key: '/people',       icon: <ApartmentOutlined />,   label: '员工数据',  title: '员工数据',  subtitle: '每位员工的 AI 渗透率 / 协作时长 / 行为标签' },
  { key: '/analysis',     icon: <FileSearchOutlined />,  label: '分析报告',  title: '分析报告',  subtitle: '基于全量 LLM 审计的能力洞察 + 产出验证（按时间窗 · 管理员视图）' },
  { key: '/projects',     icon: <ProjectOutlined />,     label: '项目透视',  title: '项目透视',  subtitle: '以仓库为锚的 AI 协助率与提交关联' },
  { key: '/models-tools', icon: <ExperimentOutlined />,  label: '模型与工具', title: '模型与工具', subtitle: '模型 Token 占比 + Slash Commands Top' },
  { key: '/alerts',       icon: <AlertOutlined />,       label: '异常告警',  title: '异常告警',  subtitle: 'Agent 离线 / 签名异常 / 重放等运行时告警' },
  { key: '/system',       icon: <SettingOutlined />,     label: '系统设置',  title: '系统设置',  subtitle: '活跃 Agent / 定时任务 / Judge 模型 / 鉴权 —— 改完即时生效，不重启' },
];

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState<boolean>(
    () => localStorage.getItem(COLLAPSE_KEY) === '1',
  );
  // Agent 字典只为右上角 badge 用：拉一次即可，下次进入直接复用浏览器内存。
  const [targetCount, setTargetCount] = useState<number>(0);
  useEffect(() => {
    fetchMonitorTargets()
      .then((arr: MonitorTarget[]) => setTargetCount(arr.filter((t) => t.enabled).length))
      .catch(() => setTargetCount(0));
  }, []);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0');
      return next;
    });
  };

  // 高亮当前菜单：/sessions/:id 也应高亮 /sessions
  const selected = useMemo(() => {
    return NAV_ITEMS.map((i) => i.key).filter(
      (k) => location.pathname === k || location.pathname.startsWith(k + '/'),
    );
  }, [location.pathname]);

  // 当前页面 meta（命中不到时退化成 brand 名）
  const currentPage = useMemo(() => {
    return (
      NAV_ITEMS.find(
        (i) => location.pathname === i.key || location.pathname.startsWith(i.key + '/'),
      ) || null
    );
  }, [location.pathname]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        className="am-app-sider"
        theme="light"
        collapsible
        collapsed={collapsed}
        onCollapse={(c) => {
          setCollapsed(c);
          localStorage.setItem(COLLAPSE_KEY, c ? '1' : '0');
        }}
        trigger={null}
        width={216}
        collapsedWidth={64}
      >
        {/* Logo 区：折叠态显示观测眼标，展开态 AW 字标 + 产品名 */}
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            padding: collapsed ? 0 : '0 20px',
            borderBottom: '1px solid var(--am-border-subtle)',
            fontWeight: 600,
            fontSize: collapsed ? 14 : 15,
            letterSpacing: collapsed ? 0 : 0.2,
            color: 'var(--am-ink)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
          }}
        >
          {collapsed ? (
            <Tooltip title="AIWatch — AI 使用观测与洞察" placement="right">
              <BrandIcon variant="eye" size={28} />
            </Tooltip>
          ) : (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <BrandIcon variant="monogram" size={26} />
              <span className="am-wordmark">AIWatch</span>
            </span>
          )}
        </div>

        <Menu
          mode="inline"
          inlineCollapsed={collapsed}
          selectedKeys={selected}
          items={NAV_ITEMS.map(({ key, icon, label }) => ({ key, icon, label }))}
          onClick={(e) => navigate(e.key)}
          style={{ borderInlineEnd: 0, paddingTop: 8 }}
        />
      </Sider>

      {/* 右侧主列是外层 Layout(row) 的 flex 子项；必须允许其收缩到 0，
          否则任意超宽子内容（宽表格 / 长不可断字符串 / pre 块）会把它撑出视口，
          形成整页横向滚动（即"越界"）。min-width:0 是消除该问题的根因修复。 */}
      <Layout style={{ minWidth: 0 }}>
        <Header
          className="am-app-header"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 12,
          }}
        >
          {/* 折叠按钮：用 hitarea 包一下，避免 16px 图标点不准 */}
          <span
            onClick={toggleCollapsed}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                toggleCollapsed();
              }
            }}
            role="button"
            tabIndex={0}
            aria-label={collapsed ? '展开菜单' : '收起菜单'}
            style={{
              cursor: 'pointer',
              fontSize: 18,
              padding: 6,
              color: 'var(--am-ink-3)',
              borderRadius: 'var(--am-r-sm)',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>

          {/* 动态页名：跟随路由变化，不再写死 "Cursor · Claude Code · Codex CLI"。
              min-width:0 + 单行省略：副标题再长也只在自身宽度内截断，绝不挤飞右侧操作区。 */}
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              lineHeight: 1.2,
              minWidth: 0,
            }}
          >
            <span
              style={{
                fontSize: 16,
                fontWeight: 600,
                color: 'var(--am-ink)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {currentPage?.title || 'AIWatch'}
            </span>
            {currentPage?.subtitle && (
              <span
                style={{
                  fontSize: 12,
                  color: 'var(--am-ink-3)',
                  marginTop: 2,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
                title={currentPage.subtitle}
              >
                {currentPage.subtitle}
              </span>
            )}
          </div>

          <span style={{ flex: 1, minWidth: 16 }} />

          {/* 右上角操作区：
              - 「支持 N 种 Agent」chip：信息展示（动态来自 monitor_target）
              - 「安装客户端」入口已下沉到登录页（左侧 Tab）：员工拿到命令的链路是
                "找运维 → 拿登录页 URL → 自助粘贴命令"，管理员后台不再放显眼的安装按钮。 */}
          <Space size={8} style={{ flexShrink: 0 }}>
            {targetCount > 0 && (
              <Tooltip title="支持监控的 Agent 类型数（来源：monitor_target 字典）">
                <span
                  style={{
                    fontSize: 12,
                    color: 'var(--am-ink-3)',
                    background: 'var(--am-surface-sunken)',
                    padding: '4px 10px',
                    borderRadius: 999,
                    fontVariantNumeric: 'tabular-nums',
                  }}
                >
                  支持 {targetCount} 种 Agent
                </span>
              </Tooltip>
            )}

            {/* 当前管理员 + 退出登录：用 Dropdown 收纳，避免抢占主操作 */}
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    onClick: async () => {
                      try {
                        await logout();
                      } finally {
                        // 不管后端 200 还是 401，前端都清掉本地状态并跳登录
                        clearCurrentUser();
                        navigate('/login', { replace: true });
                      }
                    },
                  },
                ],
              }}
              placement="bottomRight"
            >
              <Button
                size="small"
                icon={<UserOutlined />}
                type="text"
                style={{ color: 'var(--am-ink-3)' }}
              >
                {getCurrentUser() || '未登录'}
              </Button>
            </Dropdown>
          </Space>
        </Header>

        {/* min-width:0 让内容区可随主列收缩；overflow-x:clip 作为最后兜底，
            把任何漏网的横向溢出限制在内容区内，绝不冒泡成整页横向滚动条。
            （clip 不像 hidden 那样会牵连纵向轴，页面纵向滚动仍由 body 承担；
            宽表格的横向滚动发生在 .ant-table-body 自身，不受这里影响。） */}
        <Content style={{ padding: 24, minHeight: 0, minWidth: 0, overflowX: 'clip' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
