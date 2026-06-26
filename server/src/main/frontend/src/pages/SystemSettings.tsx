import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  Alert,
  App,
  Badge,
  Button,
  Card,
  Col,
  Divider,
  Empty,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Skeleton,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleFilled,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  KeyOutlined,
  PauseCircleFilled,
  PlayCircleFilled,
  ReloadOutlined,
  RobotOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  fetchAuthConfig,
  fetchCaptureConfig,
  fetchInsightConfig,
  fetchMonitorTargetsAdmin,
  fetchScheduledTasks,
  fetchSysConfigAudit,
  rotateAdminToken,
  saveAuthConfig,
  saveCaptureConfig,
  saveInsightConfig,
  setMonitorTargetEnabled,
  testJudgeConnection,
  triggerScheduledTask,
  updateScheduledTask,
  type SysConfigAuditRow,
} from '../api/client';
import type {
  JudgeTestResult,
  MonitorTargetAdmin,
  ScheduledTaskStatus,
} from '../api/types';
import { CollectorMark } from '../components/brand/CollectorMark';
import { HeroCard } from '../components/HeroCard';
import { EMPTY_DASH, NUM_STYLE } from '../utils/table';

const { Text, Paragraph } = Typography;

/**
 * 系统设置 - v2.10 一期。
 *
 * <p>设计原则：
 * <ul>
 *   <li>客户端 <strong>始终全量采集</strong> 6 种 agent；该页只控制"哪些 agent 进入展示与聚合"。</li>
 *   <li>关闭某 agent 后，<em>新数据</em>立刻不会再进入分析报告 / 员工数据（daily_summary 重算后回填）；
 *       历史 daily_summary 需触发"历史重算"才会按新口径回填（P1.e，本期未上线占位）。</li>
 *   <li>切换是热生效的，无需重启服务。</li>
 * </ul>
 */
export default function SystemSettings() {
  const [activeTab, setActiveTab] = useState<string>('active-agents');

  return (
    <div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        size="large"
        tabBarStyle={{ marginBottom: 16 }}
        items={[
          {
            key: 'active-agents',
            label: (
              <span>
                <RobotOutlined /> 活跃 Agent
              </span>
            ),
            children: <ActiveAgentsPanel />,
          },
          {
            key: 'schedules',
            label: (
              <span>
                <ClockCircleOutlined /> 定时任务
              </span>
            ),
            children: <SchedulesPanel />,
          },
          {
            key: 'judge',
            label: (
              <span>
                <ExperimentOutlined /> Judge 模型
              </span>
            ),
            children: <JudgeConfigPanel />,
          },
          {
            key: 'capture',
            label: (
              <span>
                <FileTextOutlined /> 内容采集
              </span>
            ),
            children: <CaptureConfigPanel />,
          },
          {
            key: 'auth',
            label: (
              <span>
                <KeyOutlined /> 鉴权与安全
              </span>
            ),
            children: <AuthPanel />,
          },
          {
            key: 'audit',
            label: (
              <span>
                <InfoCircleOutlined /> 操作日志
              </span>
            ),
            children: <AuditPanel />,
          },
        ]}
      />
    </div>
  );
}

// ============================================================
// Tab 1：活跃 Agent
// ============================================================

function ActiveAgentsPanel() {
  const { message, modal } = App.useApp();
  const [data, setData] = useState<MonitorTargetAdmin[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null); // 正在切换的 typeCode

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const arr = await fetchMonitorTargetsAdmin();
      setData(arr);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const counters = useMemo(() => {
    if (!data) return null;
    const total = data.length;
    const enabled = data.filter((d) => d.enabled === 1).length;
    const recentTotal = data.reduce((acc, d) => acc + d.recent7d_session_count, 0);
    const enabledRecent = data
      .filter((d) => d.enabled === 1)
      .reduce((acc, d) => acc + d.recent7d_session_count, 0);
    return { total, enabled, disabled: total - enabled, recentTotal, enabledRecent };
  }, [data]);

  const handleToggle = useCallback(
    async (target: MonitorTargetAdmin, next: boolean) => {
      const turningOff = !next;
      // 关闭时弹二次确认，避免误关——尤其是当前贡献会话数 > 0 时。
      const proceed = await new Promise<boolean>((resolve) => {
        if (!turningOff) return resolve(true);
        modal.confirm({
          title: `禁用 ${target.type_name}？`,
          icon: <ExclamationCircleOutlined style={{ color: 'var(--am-warning)' }} />,
          content: (
            <div style={{ lineHeight: 1.8 }}>
              <div>
                禁用后，所有展示页（员工数据、看板、分析报告）将<strong>立即不再统计</strong> {target.type_name} 的数据。
              </div>
              <div style={{ color: 'var(--am-ink-3)', marginTop: 8 }}>
                · 客户端将在下次同步策略后停止采集并上报<br />
                · 之后如重新启用，采集与展示都会恢复<br />
                · 最近 14 天 daily_summary 会自动按新口径重算（员工数据 / 分析报告立即生效）<br />
                · 更早的历史数据如需对齐，可走"历史重算"批量回填
              </div>
              {target.recent7d_session_count > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginTop: 10 }}
                  message={
                    <span>
                      注意：最近 7 天该 agent 贡献了 <strong>{target.recent7d_session_count}</strong> 个会话，禁用后这部分将退出团队聚合口径。
                    </span>
                  }
                />
              )}
            </div>
          ),
          okText: '确认禁用',
          okButtonProps: { danger: true },
          cancelText: '再想想',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      });
      if (!proceed) return;

      setBusy(target.type_code);
      try {
        await setMonitorTargetEnabled(target.type_code, next ? 1 : 0);
        message.success(`${target.type_name} 已${next ? '启用' : '禁用'}`);
        await load();
      } finally {
        setBusy(null);
      }
    },
    [load, message, modal],
  );

  if (loading && !data) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 6 }} />
      </Card>
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* 顶部说明条 —— 让用户一眼看懂"这页改了会发生什么" */}
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        message="活跃 Agent 白名单 —— 控制哪些 Agent 进入展示与聚合"
        description={
          <div style={{ lineHeight: 1.8 }}>
            <div>
              • <strong>启用</strong>：该 Agent 的会话进入员工数据、分析报告、每日聚合，参与团队口径。
            </div>
            <div>
              • <strong>禁用</strong>：客户端停止采集并上报该 Agent，展示页与聚合任务也不再纳入（策略随下次心跳同步，通常数秒内生效）。
            </div>
            <div>
              • 切换<strong>实时生效</strong>，无需重启服务。
            </div>
          </div>
        }
      />

      {/* 总览数字 —— 三张 Hero 卡（与 Dashboard 金标准一致：白底 + 近黑数字，语义色只在图标 chip） */}
      {counters && (
        <Row gutter={[16, 16]} className="am-dashboard-hero-row">
          <Col xs={24} sm={8}>
            <HeroCard
              label="启用中 Agent"
              value={counters.enabled}
              suffix={`/ 全部 ${counters.total}`}
              icon={<CheckCircleFilled />}
              tone="emerald"
            />
          </Col>
          <Col xs={24} sm={8}>
            <HeroCard
              label="禁用中 Agent"
              value={counters.disabled}
              icon={<PauseCircleFilled />}
              tone="amber"
            />
          </Col>
          <Col xs={24} sm={8}>
            <HeroCard
              label="近 7 天会话"
              value={counters.enabledRecent}
              suffix={`/ ${counters.recentTotal}`}
              subnote="白名单内 / 全部上报会话"
              icon={<ThunderboltOutlined />}
              tone="indigo"
              extra={
                <Tooltip title="过去 7 天落入白名单的会话数 / 全部上报会话数。差值就是被白名单过滤掉的部分。">
                  <InfoCircleOutlined style={{ color: 'var(--am-ink-4)' }} />
                </Tooltip>
              }
            />
          </Col>
        </Row>
      )}

      {/* Agent 卡片网格 */}
      <Card
        title={
          <Space>
            <span style={{ fontWeight: 600 }}>Agent 列表</span>
            <Text type="secondary" style={{ fontSize: 12 }}>
              开关即时生效；禁用后客户端停止采集上报，展示与聚合也不再纳入
            </Text>
          </Space>
        }
        extra={
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
        }
      >
        {!data || data.length === 0 ? (
          <Empty description="还没有任何监控目标。请先初始化 monitor_target 字典。" />
        ) : (
          <Row gutter={[16, 16]}>
            {data.map((t) => (
              <Col key={t.type_code} xs={24} sm={12} md={12} lg={8} xl={8}>
                <AgentCard
                  target={t}
                  loading={busy === t.type_code}
                  onToggle={(next) => handleToggle(t, next)}
                />
              </Col>
            ))}
          </Row>
        )}
      </Card>
    </Space>
  );
}

function AgentCard({
  target,
  loading,
  onToggle,
}: {
  target: MonitorTargetAdmin;
  loading: boolean;
  onToggle: (next: boolean) => void;
}) {
  const isEnabled = target.enabled === 1;
  return (
    <Card
      size="small"
      // 切换卡统一形态：1px 发丝边（全局 .ant-card 提供）+ 启用态用 3px 左侧品牌色导轨做强调；
      // 左边框恒为 3px（启用品牌色 / 禁用发丝色）避免启停切换时内容水平抖动。
      style={{
        borderLeftWidth: 3,
        borderLeftStyle: 'solid',
        borderLeftColor: isEnabled ? 'var(--am-brand)' : 'var(--am-border-subtle)',
        background: isEnabled ? 'var(--am-bg-card)' : 'var(--am-surface-sunken)',
        transition: 'border-color var(--am-dur) var(--am-ease), background var(--am-dur) var(--am-ease)',
      }}
      styles={{ body: { padding: 16 } }}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {/* 头部：色块 + 名称 + 状态 Badge */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <CollectorMark
            code={target.type_code}
            name={target.type_name}
            color={target.display_color}
            enabled={isEnabled}
          />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div
              style={{
                fontWeight: 600,
                color: isEnabled ? 'var(--am-ink)' : 'var(--am-ink-3)',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {target.type_name}
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              <code>{target.type_code}</code>
            </Text>
          </div>
          <Badge
            status={isEnabled ? 'success' : 'default'}
            text={
              <span style={{ fontSize: 12, color: isEnabled ? 'var(--am-success-fg)' : 'var(--am-ink-3)' }}>
                {isEnabled ? '已启用' : '已禁用'}
              </span>
            }
          />
        </div>

        {/* 描述（来自 monitor_target.description） */}
        {target.description && (
          <Paragraph
            type="secondary"
            ellipsis={{ rows: 2 }}
            style={{ fontSize: 12, margin: 0, color: 'var(--am-ink-3)' }}
          >
            {target.description}
          </Paragraph>
        )}

        {/* 近 7 天会话量 + 开关 */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            paddingTop: 8,
            borderTop: '1px solid var(--am-border-subtle)',
          }}
        >
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              近 7 天会话
            </Text>
            <div
              style={{
                fontSize: 'var(--am-fs-lg)',
                fontWeight: 600,
                color: target.recent7d_session_count > 0 ? 'var(--am-ink)' : 'var(--am-ink-5)',
                fontVariantNumeric: 'tabular-nums',
                lineHeight: 1.2,
              }}
            >
              {target.recent7d_session_count.toLocaleString()}
            </div>
          </div>
          <Tooltip title={isEnabled ? '点击禁用：停止采集上报，并从展示与聚合排除' : '点击启用：恢复采集上报，并进入展示与聚合白名单'}>
            <Switch
              checked={isEnabled}
              loading={loading}
              onChange={onToggle}
              checkedChildren="启用"
              unCheckedChildren="禁用"
            />
          </Tooltip>
        </div>
      </Space>
    </Card>
  );
}

// ============================================================
// Tab 2：定时任务
// ============================================================

function SchedulesPanel() {
  const { message } = App.useApp();
  const [tasks, setTasks] = useState<ScheduledTaskStatus[] | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const arr = await fetchScheduledTasks();
      setTasks(arr);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    // 任务运行中（running=true）轮询频率高一点；这里偷懒固定 5s 轮询。
    // 页面不可见（切到后台标签页 / 最小化）时跳过这一拍，避免空转请求；卸载时清理。
    const id = window.setInterval(() => {
      if (document.hidden) return;
      load();
    }, 5000);
    return () => window.clearInterval(id);
  }, [load]);

  const businessTasks = useMemo(
    () => (tasks ?? []).filter((t) => t.category === 'business'),
    [tasks],
  );
  const infraTasks = useMemo(
    () => (tasks ?? []).filter((t) => t.category !== 'business'),
    [tasks],
  );

  if (loading && !tasks) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 6 }} />
      </Card>
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="定时任务在线管理 —— cron 改完即时生效，无需重启"
        description={
          <div style={{ lineHeight: 1.8 }}>
            • <strong>修改 cron</strong>：保存后下一拍按新表达式触发；任务正在跑则等当前一轮完成。<br />
            • <strong>启停</strong>：禁用后任务不再被调度，可随时再启用。<br />
            • <strong>手动触发</strong>：立即异步执行一次（同一任务不允许并发，正在跑时会被跳过）。
          </div>
        }
      />

      <Card
        title={<span style={{ fontWeight: 600 }}>业务任务</span>}
        extra={
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
        }
      >
        {businessTasks.length === 0 ? (
          <Empty description="尚未注册业务任务" />
        ) : (
          <Row gutter={[16, 16]}>
            {businessTasks.map((t) => (
              <Col key={t.task_code} xs={24} lg={12}>
                <ScheduledTaskCard task={t} onReload={load} message={message} />
              </Col>
            ))}
          </Row>
        )}
      </Card>

      {infraTasks.length > 0 && (
        <Card title={<span style={{ fontWeight: 600 }}>基础设施任务</span>}>
          <Row gutter={[16, 16]}>
            {infraTasks.map((t) => (
              <Col key={t.task_code} xs={24} lg={12}>
                <ScheduledTaskCard task={t} onReload={load} message={message} />
              </Col>
            ))}
          </Row>
        </Card>
      )}
    </Space>
  );
}

function ScheduledTaskCard({
  task,
  onReload,
  message,
}: {
  task: ScheduledTaskStatus;
  onReload: () => Promise<void>;
  message: ReturnType<typeof App.useApp>['message'];
}) {
  const [cronDraft, setCronDraft] = useState(task.cron);
  const [savingCron, setSavingCron] = useState(false);
  const [toggling, setToggling] = useState(false);
  const [triggering, setTriggering] = useState(false);

  // 父刷新拉来新值时同步草稿（除非用户正在编辑）
  useEffect(() => {
    setCronDraft(task.cron);
  }, [task.cron]);

  const dirty = cronDraft.trim() !== task.cron.trim();

  const saveCron = async () => {
    if (!dirty) return;
    // 护栏：空表达式或非 6 段（秒 分 时 日 月 周）直接拦下；@hourly 等宏放行交服务端解析。
    const expr = cronDraft.trim();
    if (!expr || (!expr.startsWith('@') && expr.split(/\s+/).filter(Boolean).length !== 6)) {
      message.error('Cron 表达式需为 6 段（秒 分 时 日 月 周），请检查');
      return;
    }
    setSavingCron(true);
    try {
      await updateScheduledTask(task.task_code, { cron: cronDraft.trim() });
      message.success('cron 已更新，下次触发按新表达式');
      await onReload();
    } finally {
      setSavingCron(false);
    }
  };

  const toggleEnabled = async (next: boolean) => {
    setToggling(true);
    try {
      await updateScheduledTask(task.task_code, { enabled: next });
      message.success(`任务已${next ? '启用' : '禁用'}`);
      await onReload();
    } finally {
      setToggling(false);
    }
  };

  const triggerNow = async () => {
    setTriggering(true);
    try {
      await triggerScheduledTask(task.task_code);
      message.success('已触发，结果将在 5 秒内刷新');
      await onReload();
    } finally {
      setTriggering(false);
    }
  };

  const statusTag = (() => {
    if (task.running) return <Tag color="cyan">运行中</Tag>;
    if (!task.enabled) return <Tag>已禁用</Tag>;
    if (task.last_status === 'FAILED') return <Tag color="error">上次失败</Tag>;
    if (task.last_status === 'SUCCESS') return <Tag color="success">就绪</Tag>;
    return <Tag color="default">待首次执行</Tag>;
  })();

  return (
    <Card
      size="small"
      // 切换卡统一形态：与活跃 Agent 卡一致 —— 1px 发丝边 + 启用态 3px 左侧品牌色导轨，左边框恒为 3px 防抖动。
      style={{
        borderLeftWidth: 3,
        borderLeftStyle: 'solid',
        borderLeftColor: task.enabled ? 'var(--am-brand)' : 'var(--am-border-subtle)',
        background: task.enabled ? 'var(--am-bg-card)' : 'var(--am-surface-sunken)',
        transition: 'border-color var(--am-dur) var(--am-ease), background var(--am-dur) var(--am-ease)',
      }}
      styles={{ body: { padding: 16 } }}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {/* 头部 */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 600, color: 'var(--am-ink)' }}>
              {task.display_name}
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              <code>{task.task_code}</code>
              {task.zone_id && <span style={{ marginLeft: 8 }}>· 时区 {task.zone_id}</span>}
            </Text>
          </div>
          <Space size={6}>
            {statusTag}
            <Tooltip title={task.enabled ? '点击禁用' : '点击启用'}>
              <Switch
                checked={task.enabled}
                loading={toggling}
                onChange={toggleEnabled}
                checkedChildren="启用"
                unCheckedChildren="禁用"
              />
            </Tooltip>
          </Space>
        </div>

        {task.description && (
          <Paragraph
            type="secondary"
            ellipsis={{ rows: 3 }}
            style={{ fontSize: 12, margin: 0, color: 'var(--am-ink-3)' }}
          >
            {task.description}
          </Paragraph>
        )}

        {/* cron 编辑 */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
            <Text style={{ fontSize: 12, color: 'var(--am-ink-3)', fontWeight: 500 }}>Cron 表达式</Text>
            {!task.cron_editable && (
              <Tag color="default" style={{ marginLeft: 4 }}>
                只读
              </Tag>
            )}
            {task.cron !== task.default_cron && (
              <Tooltip title={`默认 ${task.default_cron}`}>
                <Tag color="warning" style={{ fontSize: 11 }}>
                  已自定义
                </Tag>
              </Tooltip>
            )}
          </div>
          <Space.Compact style={{ width: '100%' }}>
            <Input
              value={cronDraft}
              onChange={(e) => setCronDraft(e.target.value)}
              disabled={!task.cron_editable}
              placeholder="秒 分 时 日 月 周（Spring 6 字段）"
              style={{ fontFamily: 'var(--am-font-mono)', fontSize: 13 }}
              onPressEnter={saveCron}
            />
            <Button
              type={dirty ? 'primary' : 'default'}
              loading={savingCron}
              disabled={!task.cron_editable || !dirty}
              onClick={saveCron}
            >
              保存
            </Button>
          </Space.Compact>
        </div>

        {/* 运行状态 */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(2, 1fr)',
            gap: 12,
            paddingTop: 8,
            borderTop: '1px solid var(--am-border-subtle)',
            fontSize: 12,
          }}
        >
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              上次执行
            </Text>
            <div style={{ marginTop: 2 }}>
              {task.last_start_time ? (
                <Tooltip title={task.last_start_time}>
                  <span style={{ color: 'var(--am-ink)' }}>
                    {dayjs(task.last_start_time).format('MM-DD HH:mm:ss')}
                  </span>
                </Tooltip>
              ) : (
                <Text type="secondary">—</Text>
              )}
              {task.last_duration_ms >= 0 && (
                <Text type="secondary" style={{ marginLeft: 6 }}>
                  ({formatDurationMs(task.last_duration_ms)})
                </Text>
              )}
            </div>
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              下次执行
            </Text>
            <div style={{ marginTop: 2 }}>
              {task.enabled && task.next_run_time ? (
                <Tooltip title={task.next_run_time}>
                  <span style={{ color: 'var(--am-ink)' }}>
                    {dayjs(task.next_run_time).format('MM-DD HH:mm:ss')}
                  </span>
                </Tooltip>
              ) : (
                <Text type="secondary">{task.enabled ? '计算中…' : '已禁用'}</Text>
              )}
            </div>
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              累计成功
            </Text>
            <div style={{ color: 'var(--am-success-fg)', fontWeight: 600 }}>
              <CheckCircleOutlined style={{ marginRight: 4 }} />
              {task.success_count.toLocaleString()}
            </div>
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              累计失败
            </Text>
            <div
              style={{
                color: task.failure_count > 0 ? 'var(--am-error-fg)' : 'var(--am-ink-3)',
                fontWeight: task.failure_count > 0 ? 600 : 400,
              }}
            >
              <CloseCircleOutlined style={{ marginRight: 4 }} />
              {task.failure_count.toLocaleString()}
            </div>
          </div>
        </div>

        {task.last_error_text && (
          <Alert
            type="error"
            showIcon
            message="最近一次错误"
            description={
              <pre
                style={{
                  fontSize: 12,
                  margin: 0,
                  maxHeight: 100,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                }}
              >
                {task.last_error_text}
              </pre>
            }
          />
        )}

        {/* 手动触发 */}
        {task.manual_triggerable && (
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button
              icon={task.running ? <PlayCircleFilled /> : <ThunderboltOutlined />}
              onClick={triggerNow}
              loading={triggering}
              disabled={task.running}
              type="default"
            >
              {task.running ? '正在跑…' : '立即执行'}
            </Button>
          </div>
        )}
      </Space>
    </Card>
  );
}

function formatDurationMs(ms: number): string {
  if (ms < 0) return '—';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`;
  const min = Math.floor(ms / 60_000);
  const sec = ((ms % 60_000) / 1000).toFixed(0);
  return `${min}m ${sec}s`;
}

// ============================================================
// Tab 3：内容采集限额
// ============================================================

const CAPTURE_KEYS = {
  MAX_TEXT: 'capture.max_text_bytes_per_part',
  MAX_BLOB: 'capture.max_blob_bytes_per_part',
  MAX_BLOBS: 'capture.max_blobs_per_message',
  MAX_PARTS: 'capture.max_parts_per_message',
  INLINE_BLOB: 'capture.inline_blob_max_bytes',
  AUDIT_CHARS: 'capture.audit_message_max_chars',
} as const;

const CAPTURE_BYTE_FIELD_KEYS = new Set<string>([
  CAPTURE_KEYS.MAX_TEXT,
  CAPTURE_KEYS.MAX_BLOB,
  CAPTURE_KEYS.INLINE_BLOB,
]);

const BYTES_PER_KB = 1024;

function captureBytesToKb(bytes: string): string {
  const n = Number(bytes);
  if (!bytes || Number.isNaN(n)) return '';
  return String(n / BYTES_PER_KB);
}

function captureKbToBytes(kb: string): string {
  const n = Number(kb);
  if (!kb || Number.isNaN(n)) return '';
  return String(Math.round(n * BYTES_PER_KB));
}

function captureConfigToDraft(config: Record<string, string>): Record<string, string> {
  const draft = { ...config };
  CAPTURE_BYTE_FIELD_KEYS.forEach((key) => {
    if (draft[key]) draft[key] = captureBytesToKb(draft[key]);
  });
  return draft;
}

function captureDraftDisplayValue(config: Record<string, string>, key: string): string {
  const raw = config[key] ?? '';
  return CAPTURE_BYTE_FIELD_KEYS.has(key) ? captureBytesToKb(raw) : raw;
}

function CaptureFieldTooltip({ lines }: { lines: string[] }) {
  return (
    <div style={{ maxWidth: 400, lineHeight: 1.65 }}>
      {lines.map((line, i) => (
        <div key={i} style={i > 0 ? { marginTop: 6 } : undefined}>
          {line}
        </div>
      ))}
    </div>
  );
}

const CAPTURE_FIELD_DEFS: {
  key: string;
  label: string;
  tooltip: ReactNode;
  unitKb?: boolean;
}[] = [
  {
    key: CAPTURE_KEYS.MAX_TEXT,
    label: '单 part 文本上限（KB）',
    unitKb: true,
    tooltip: (
      <CaptureFieldTooltip
        lines={[
          '限制单个 text part 按 UTF-8 编码的最大体积；超出部分截断并标记 truncated。',
          '例：设为 512 时，一段约 600 KB 的 read 工具输出入库时会被截到 512 KB。',
          '默认 512（即 524288 字节）。',
        ]}
      />
    ),
  },
  {
    key: CAPTURE_KEYS.MAX_BLOB,
    label: '单 blob 上限（KB）',
    unitKb: true,
    tooltip: (
      <CaptureFieldTooltip
        lines={[
          '限制单张图片/附件 gzip 解压后的原始体积；超出则丢弃 blob 并标记 blob_too_large。',
          '例：设为 1024 时，一张解压后 1.5 MB 的截图不会保存二进制，仅保留占位。',
          '默认 1024（即 1048576 字节，约 1 MB）。',
        ]}
      />
    ),
  },
  {
    key: CAPTURE_KEYS.MAX_BLOBS,
    label: '单消息最多 blob 数',
    tooltip: (
      <CaptureFieldTooltip
        lines={[
          '一条消息里最多保留几个带 blob 的 part（图片/附件）；超出后去掉 blob 并标记 blob_limit。',
          '例：设为 8 时，同一条消息里第 9 张及之后的图片不会入库二进制。',
          '默认 8。',
        ]}
      />
    ),
  },
  {
    key: CAPTURE_KEYS.MAX_PARTS,
    label: '单消息最多 part 数',
    tooltip: (
      <CaptureFieldTooltip
        lines={[
          '限制一条消息拆分后的 part 总数（文本、工具结果、图片等）；超出部分直接丢弃。',
          '例：设为 64 时，Agent 上报 80 段内容时仅前 64 段入库。',
          '默认 64。',
        ]}
      />
    ),
  },
  {
    key: CAPTURE_KEYS.INLINE_BLOB,
    label: '内联 blob 上限（KB）',
    unitKb: true,
    tooltip: (
      <CaptureFieldTooltip
        lines={[
          'Agent 上报 JSON 时，小附件以 gzip+base64 内联在 part 里；此值为 Agent 侧内联阈值参考。',
          '例：设为 32 时，大于 32 KB 的图片应由 Agent 预处理，避免单条上报 JSON 过大。',
          '默认 32（即 32768 字节）。服务端 ingest 仍按「单 blob 上限」校验解压后体积。',
        ]}
      />
    ),
  },
  {
    key: CAPTURE_KEYS.AUDIT_CHARS,
    label: '审计单条消息字符上限',
    tooltip: (
      <CaptureFieldTooltip
        lines={[
          '洞察审计拼 LLM prompt 时，每条历史消息扁平化后的字符数上限（非字节），超出截断以控制 token。',
          '例：设为 4000 时，一条含大量工具输出的消息在审计 prompt 中最多展示约 4000 字符。',
          '默认 4000。',
        ]}
      />
    ),
  },
];

function CaptureConfigPanel() {
  const { message } = App.useApp();
  const [config, setConfig] = useState<Record<string, string> | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchCaptureConfig();
      setConfig(data);
      setDraft(captureConfigToDraft(data));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const dirty = useMemo(() => {
    if (!config) return false;
    return Object.values(CAPTURE_KEYS).some(
      (k) => (draft[k] ?? '') !== captureDraftDisplayValue(config, k),
    );
  }, [draft, config]);

  const handleSave = async () => {
    setSaving(true);
    try {
      const updates: Record<string, string> = {};
      Object.values(CAPTURE_KEYS).forEach((k) => {
        const draftVal = draft[k] ?? '';
        const configVal = captureDraftDisplayValue(config ?? {}, k);
        if (draftVal !== configVal) {
          updates[k] = CAPTURE_BYTE_FIELD_KEYS.has(k) ? captureKbToBytes(draftVal) : draftVal;
        }
      });
      if (Object.keys(updates).length === 0) {
        message.info('没有变更');
        return;
      }
      const next = await saveCaptureConfig(updates);
      setConfig(next);
      setDraft(captureConfigToDraft(next));
      message.success('采集限额已保存，新上报消息立即生效');
    } finally {
      setSaving(false);
    }
  };

  if (loading && !config) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 6 }} />
      </Card>
    );
  }

  const renderField = (def: (typeof CAPTURE_FIELD_DEFS)[number]) => (
    <Form.Item
      key={def.key}
      label={
        <Space size={4}>
          <span>{def.label}</span>
          <Tooltip title={def.tooltip}>
            <InfoCircleOutlined style={{ color: 'var(--am-ink-3)', fontSize: 13, cursor: 'help' }} />
          </Tooltip>
        </Space>
      }
      style={{ marginBottom: 16 }}
    >
      <InputNumber
        style={{ width: '100%' }}
        min={1}
        addonAfter={def.unitKb ? 'KB' : undefined}
        value={draft[def.key] ? Number(draft[def.key]) : undefined}
        onChange={(v) => setDraft((prev) => ({ ...prev, [def.key]: v == null ? '' : String(v) }))}
      />
    </Form.Item>
  );

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="会话内容采集限额 —— 服务端兜底"
        description={
          <div style={{ lineHeight: 1.8 }}>
            <div>限制 ingest 入库与洞察审计 prompt 的单条消息体积；Agent 端另有本地限额。</div>
            <div>历史消息（ingest_version=0）仅保留 content_text，不会回填结构化 parts。</div>
          </div>
        }
      />
      <Card
        title="采集与审计限额"
        extra={
          <Space>
            <Button onClick={() => config && setDraft(captureConfigToDraft(config))} disabled={!dirty}>
              撤销
            </Button>
            <Button type="primary" onClick={handleSave} loading={saving} disabled={!dirty}>
              保存
            </Button>
          </Space>
        }
      >
        <Form layout="vertical" size="middle" colon={false}>
          <Row gutter={24}>
            <Col xs={24} md={12}>
              {CAPTURE_FIELD_DEFS.slice(0, 3).map(renderField)}
            </Col>
            <Col xs={24} md={12}>
              {CAPTURE_FIELD_DEFS.slice(3).map(renderField)}
            </Col>
          </Row>
        </Form>
      </Card>
    </Space>
  );
}

// ============================================================
// Tab 4：Judge 模型 + 评判参数
// ============================================================

/** 服务端 sys_config 的 key 常量；与 backend SystemConfigKeys 对齐。 */
const KEYS = {
  JUDGE_A_PROVIDER: 'judge.a.provider',
  JUDGE_A_ENDPOINT: 'judge.a.endpoint',
  JUDGE_A_API_KEY: 'judge.a.api_key',
  JUDGE_A_MODEL: 'judge.a.model',
  JUDGE_A_TIMEOUT: 'judge.a.timeout_ms',
  JUDGE_B_PROVIDER: 'judge.b.provider',
  JUDGE_B_ENDPOINT: 'judge.b.endpoint',
  JUDGE_B_API_KEY: 'judge.b.api_key',
  JUDGE_B_MODEL: 'judge.b.model',
  JUDGE_B_TIMEOUT: 'judge.b.timeout_ms',
  INSIGHT_MAX_LLM: 'insight.max_llm_calls_per_report',
  INSIGHT_REAUDIT: 'insight.reaudit_message_threshold',
  INSIGHT_CONCURRENCY: 'insight.audit_concurrency',
  INSIGHT_AUDIT_SCAN: 'insight.audit_scan_enabled',
  INSIGHT_REDACT: 'insight.redact_enabled',
  INSIGHT_RUBRIC: 'insight.rubric_version',
  INSIGHT_AUDIT_VERSION: 'insight.audit_version',
  INSIGHT_RUBRIC_YAML: 'insight.rubric_yaml',
} as const;

const PROVIDER_OPTIONS = [
  { value: 'mock', label: 'mock (本地模拟，零外部依赖)' },
  { value: 'openai-compatible', label: 'openai-compatible (OpenAI/DeepSeek/Qwen 等)' },
];

const SECRET_MASK = '********';

/** 校验是否为合法 http(s) URL —— 用于 provider 非 mock 时的 endpoint 护栏。 */
function isHttpUrl(s: string): boolean {
  try {
    const u = new URL(s);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

function JudgeConfigPanel() {
  const { message } = App.useApp();
  const [config, setConfig] = useState<Record<string, string> | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<'a' | 'b' | null>(null);
  const [testResults, setTestResults] = useState<Record<'a' | 'b', JudgeTestResult | null>>({
    a: null,
    b: null,
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchInsightConfig();
      setConfig(data);
      setDraft({ ...data });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const dirty = useMemo(() => {
    if (!config) return false;
    return Object.keys(KEYS).some((k) => {
      const key = KEYS[k as keyof typeof KEYS];
      return (draft[key] ?? '') !== (config[key] ?? '');
    });
  }, [draft, config]);

  const setField = (key: string, val: string) => {
    setDraft((prev) => ({ ...prev, [key]: val }));
  };

  const handleSave = async () => {
    // 护栏：provider 非 mock 时 endpoint 必须是合法 http(s) URL，避免存下明显跑不通的配置。
    for (const slot of ['a', 'b'] as const) {
      const providerKey = slot === 'a' ? KEYS.JUDGE_A_PROVIDER : KEYS.JUDGE_B_PROVIDER;
      const endpointKey = slot === 'a' ? KEYS.JUDGE_A_ENDPOINT : KEYS.JUDGE_B_ENDPOINT;
      const slotTouched =
        (draft[providerKey] ?? '') !== (config?.[providerKey] ?? '') ||
        (draft[endpointKey] ?? '') !== (config?.[endpointKey] ?? '');
      const provider = (draft[providerKey] ?? '').toLowerCase();
      if (slotTouched && provider && provider !== 'mock' && !isHttpUrl((draft[endpointKey] ?? '').trim())) {
        message.error(`Judge ${slot.toUpperCase()} 的 Endpoint 需填写合法的 http(s) URL`);
        return;
      }
    }
    setSaving(true);
    try {
      // 只发出本次有变化的字段；API Key 现在是明文回显，没改就不会进 updates
      const updates: Record<string, string> = {};
      Object.values(KEYS).forEach((k) => {
        if ((draft[k] ?? '') !== (config?.[k] ?? '')) {
          updates[k] = draft[k] ?? '';
        }
      });
      if (Object.keys(updates).length === 0) {
        message.info('没有变更');
        return;
      }
      const next = await saveInsightConfig(updates);
      setConfig(next);
      setDraft({ ...next });
      message.success(`已保存 ${Object.keys(updates).length} 项，立即生效`);
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    if (config) setDraft({ ...config });
  };

  const handleTest = async (slot: 'a' | 'b') => {
    // 测试前提示：如果有未保存变更，先警告——后端用的是当前生效配置（即 sys_config 已保存的值）
    if (dirty) {
      message.warning('当前有未保存变更，测试只会用"已保存"配置；建议先保存再测试');
    }
    setTesting(slot);
    try {
      const r = await testJudgeConnection(slot);
      setTestResults((prev) => ({ ...prev, [slot]: r }));
      if (r.success) {
        message.success(`Judge ${slot.toUpperCase()} 连通正常 (${r.latency_ms} ms)`);
      } else {
        message.error(`Judge ${slot.toUpperCase()} 连通失败：${r.message ?? '未知'}`);
      }
    } finally {
      setTesting(null);
    }
  };

  if (loading && !config) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 8 }} />
      </Card>
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="Judge 模型 + 评判参数 —— 改完即时生效，不重启"
        description={
          <div style={{ lineHeight: 1.8 }}>
            • <strong>API Key</strong> 字段显示 <code>{SECRET_MASK}</code> 占位；未触碰则保留原值，
            想替换直接覆盖输入框内容即可。<br />
            • <strong>测试连通性</strong> 使用<em>已保存</em>的配置发一次极短 prompt；建议先保存再测试。<br />
            • <strong>provider=mock</strong> 是本地模拟，零外部依赖；<strong>openai-compatible</strong>
            覆盖 OpenAI / DeepSeek / Qwen / 自建 vLLM 等所有 chat/completions 协议。
          </div>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <JudgeCard
            slot="a"
            draft={draft}
            setField={setField}
            testing={testing === 'a'}
            testResult={testResults.a}
            onTest={() => handleTest('a')}
          />
        </Col>
        <Col xs={24} xl={12}>
          <JudgeCard
            slot="b"
            draft={draft}
            setField={setField}
            testing={testing === 'b'}
            testResult={testResults.b}
            onTest={() => handleTest('b')}
          />
        </Col>
      </Row>

      <Card
        title={<span style={{ fontWeight: 600 }}>评判参数</span>}
        size="small"
      >
        <Form layout="vertical" size="middle">
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item
                label={
                  <Tooltip title="单次报告调用 LLM 的硬上限（双 judge 计 2 次），超出后报告停审计但仍出 partial 结果">
                    单报告 LLM 调用上限
                    <InfoCircleOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
                  </Tooltip>
                }
              >
                <InputNumber
                  min={0}
                  value={Number(draft[KEYS.INSIGHT_MAX_LLM] ?? 0)}
                  onChange={(v) => setField(KEYS.INSIGHT_MAX_LLM, String(v ?? 0))}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                label={
                  <Tooltip title="已审计会话再增长 N 条消息后会进入重审队列；调小 = 重审更频繁，调大 = 节省 token">
                    重审消息阈值
                    <InfoCircleOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
                  </Tooltip>
                }
              >
                <InputNumber
                  min={0}
                  value={Number(draft[KEYS.INSIGHT_REAUDIT] ?? 0)}
                  onChange={(v) => setField(KEYS.INSIGHT_REAUDIT, String(v ?? 0))}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                label={
                  <Tooltip title="审计 worker 并发数；外网网关建议 ≤ 4 防止速率限制，自建本地模型可以拉到 16+">
                    审计并发数
                    <InfoCircleOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
                  </Tooltip>
                }
              >
                <InputNumber
                  min={1}
                  max={64}
                  value={Number(draft[KEYS.INSIGHT_CONCURRENCY] ?? 1)}
                  onChange={(v) => setField(KEYS.INSIGHT_CONCURRENCY, String(v ?? 1))}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                label={
                  <Tooltip title="关闭则后台不自动审计会话、仅生成报告时按需审；打开后台扫描器开始清积压。改后热生效，无需重启。">
                    后台审计扫描
                    <InfoCircleOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
                  </Tooltip>
                }
              >
                <Switch
                  checked={draft[KEYS.INSIGHT_AUDIT_SCAN] === 'true'}
                  onChange={(v) => setField(KEYS.INSIGHT_AUDIT_SCAN, v ? 'true' : 'false')}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                label={
                  <Tooltip title="拼 LLM 审计 prompt 前对密钥/令牌/私钥打码，降低把会话原文里的密钥发给第三方模型网关的泄露风险。">
                    LLM 外发脱敏
                    <InfoCircleOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
                  </Tooltip>
                }
              >
                <Switch
                  checked={draft[KEYS.INSIGHT_REDACT] === 'true'}
                  onChange={(v) => setField(KEYS.INSIGHT_REDACT, v ? 'true' : 'false')}
                />
              </Form.Item>
            </Col>
          </Row>

          <Divider style={{ margin: '4px 0 16px' }} />

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                label={
                  <Tooltip title="Rubric YAML 版本号，会写入 ai_session_audit.audit_version；改 rubric 内容时 bump 这里会让历史会话进入重审">
                    Rubric 版本
                    <InfoCircleOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
                  </Tooltip>
                }
              >
                <Input
                  value={draft[KEYS.INSIGHT_RUBRIC] ?? ''}
                  onChange={(e) => setField(KEYS.INSIGHT_RUBRIC, e.target.value)}
                  placeholder="如 v3.0"
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="流水线版本">
                <Input
                  value={draft[KEYS.INSIGHT_AUDIT_VERSION] ?? ''}
                  onChange={(e) => setField(KEYS.INSIGHT_AUDIT_VERSION, e.target.value)}
                  placeholder="如 v3.0"
                />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Card>

      <RubricEditorCard
        value={draft[KEYS.INSIGHT_RUBRIC_YAML] ?? ''}
        originalValue={config?.[KEYS.INSIGHT_RUBRIC_YAML] ?? ''}
        onChange={(v) => setField(KEYS.INSIGHT_RUBRIC_YAML, v)}
        rubricVersion={draft[KEYS.INSIGHT_RUBRIC] ?? ''}
      />

      {/* 全局保存条（与「鉴权与安全」页保存条同款：不透明卡面 + shadow-2 + r-md + Badge 脏标） */}
      <div
        style={{
          position: 'sticky',
          bottom: 0,
          background: 'var(--am-bg-card)',
          padding: '12px 16px',
          border: '1px solid var(--am-border-subtle)',
          borderRadius: 'var(--am-r-md)',
          boxShadow: 'var(--am-shadow-2)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 12,
          zIndex: 10,
        }}
      >
        <Space>
          {dirty && (
            <>
              <Badge status="warning" />
              <Text type="warning">有未保存变更</Text>
            </>
          )}
        </Space>
        <Space>
          <Button onClick={load} disabled={saving}>
            重新拉取
          </Button>
          <Button onClick={handleReset} disabled={!dirty || saving}>
            撤销修改
          </Button>
          <Button type="primary" onClick={handleSave} loading={saving} disabled={!dirty}>
            保存全部
          </Button>
        </Space>
      </div>
    </Space>
  );
}

// ============================================================
// Rubric 模板编辑卡片
// ============================================================

interface RubricEditorCardProps {
  value: string;
  originalValue: string;
  onChange: (v: string) => void;
  rubricVersion: string;
}

function RubricEditorCard({ value, originalValue, onChange, rubricVersion }: RubricEditorCardProps) {
  const dirty = value !== originalValue;
  const bytes = useMemo(() => new Blob([value]).size, [value]);
  const lines = useMemo(() => (value ? value.split('\n').length : 0), [value]);

  return (
    <Card
      title={
        <Space size={8}>
          <FileTextOutlined style={{ color: 'var(--am-violet)' }} />
          <span style={{ fontWeight: 600 }}>Rubric 模板</span>
          {dirty && (
            <Tag color="orange" style={{ marginLeft: 4 }}>
              未保存
            </Tag>
          )}
          {!dirty && rubricVersion && (
            <Tag color="cyan" style={{ marginLeft: 4 }}>
              {rubricVersion}
            </Tag>
          )}
        </Space>
      }
      size="small"
      extra={
        <Space size={4}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {lines} 行 · {bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`}
          </Text>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="这是给 LLM 看的评分指令"
        description={
          <div style={{ fontSize: 12, lineHeight: '20px' }}>
            <div>
              这段 YAML 会作为 <Text code>prompt 头部</Text> 拼接到每段 AI 会话原文前送给 Judge A / B 评分。
              保存后<Text strong>立即对下一次报告生效</Text>；正在进行的报告任务不会被打断。
            </div>
            <div style={{ marginTop: 6 }}>
              改 rubric 实质内容（评分维度 / 分档标准 / 边界条件）时，建议同步在上方
              <Text code>Rubric 版本</Text> bump 一档（如 v3.0 → v3.1），旧 audit 行会自动进入重审队列。
              只调措辞 / 修字打错时可以不 bump。
            </div>
          </div>
        }
      />
      <Input.TextArea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoSize={{ minRows: 14, maxRows: 36 }}
        spellCheck={false}
        style={{
          fontFamily: 'var(--am-font-mono)',
          fontSize: 13,
          lineHeight: '20px',
          background: 'var(--am-surface-sunken)',
        }}
        placeholder={'# Rubric YAML 示例\nsystem: |\n  你是一位资深技术经理...\n\ncapabilities:\n  problem_decomposition: ...\n'}
      />
      <div style={{ marginTop: 10, fontSize: 12, color: 'var(--am-ink-3)' }}>
        <InfoCircleOutlined style={{ marginRight: 4 }} />
        sys_config 字段：<Text code style={{ fontSize: 11 }}>insight.rubric_yaml</Text>
        ；变更会写入操作日志，可在 <Text strong>操作日志</Text> Tab 回溯。
      </div>
    </Card>
  );
}

function JudgeCard({
  slot,
  draft,
  setField,
  testing,
  testResult,
  onTest,
}: {
  slot: 'a' | 'b';
  draft: Record<string, string>;
  setField: (key: string, val: string) => void;
  testing: boolean;
  testResult: JudgeTestResult | null;
  onTest: () => void;
}) {
  const upper = slot.toUpperCase();
  const k = slot === 'a'
    ? {
        provider: KEYS.JUDGE_A_PROVIDER,
        endpoint: KEYS.JUDGE_A_ENDPOINT,
        apiKey: KEYS.JUDGE_A_API_KEY,
        model: KEYS.JUDGE_A_MODEL,
        timeout: KEYS.JUDGE_A_TIMEOUT,
      }
    : {
        provider: KEYS.JUDGE_B_PROVIDER,
        endpoint: KEYS.JUDGE_B_ENDPOINT,
        apiKey: KEYS.JUDGE_B_API_KEY,
        model: KEYS.JUDGE_B_MODEL,
        timeout: KEYS.JUDGE_B_TIMEOUT,
      };

  const subtitle = slot === 'a' ? '主审 —— 建议自建本地大模型' : '互审 —— 建议外部 API，与 A 差异化';

  const isMock = (draft[k.provider] ?? '').toLowerCase() === 'mock';

  return (
    <Card
      size="small"
      title={
        <Space>
          <Tag color={slot === 'a' ? 'cyan' : 'purple'} style={{ fontWeight: 600 }}>
            Judge {upper}
          </Tag>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {subtitle}
          </Text>
        </Space>
      }
      extra={
        <Button
          icon={<ThunderboltOutlined />}
          loading={testing}
          onClick={onTest}
          size="small"
        >
          测试连通性
        </Button>
      }
    >
      <Form layout="vertical" size="middle">
        <Form.Item label="Provider">
          <Select
            value={draft[k.provider] ?? 'mock'}
            options={PROVIDER_OPTIONS}
            onChange={(v) => setField(k.provider, v)}
          />
        </Form.Item>
        <Form.Item
          label="Endpoint"
          help={
            isMock ? (
              <Text type="secondary" style={{ fontSize: 12 }}>
                provider=mock 时此项无效
              </Text>
            ) : null
          }
        >
          <Input
            value={draft[k.endpoint] ?? ''}
            onChange={(e) => setField(k.endpoint, e.target.value)}
            placeholder="https://api.example.com/v1"
            disabled={isMock}
          />
        </Form.Item>
        <Form.Item
          label={
            <span>
              API Key <KeyOutlined style={{ marginLeft: 4, color: 'var(--am-ink-3)' }} />
            </span>
          }
        >
          <Input.Password
            value={draft[k.apiKey] ?? ''}
            onChange={(e) => setField(k.apiKey, e.target.value)}
            placeholder={SECRET_MASK}
            disabled={isMock}
            visibilityToggle
          />
        </Form.Item>
        <Row gutter={12}>
          <Col span={16}>
            <Form.Item label="Model">
              <Input
                value={draft[k.model] ?? ''}
                onChange={(e) => setField(k.model, e.target.value)}
                placeholder="deepseek/deepseek-v4-pro"
                disabled={isMock}
              />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item label="Timeout (ms)">
              <InputNumber
                min={1000}
                step={1000}
                value={Number(draft[k.timeout] ?? 60000)}
                onChange={(v) => setField(k.timeout, String(v ?? 60000))}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
        </Row>
      </Form>

      {/* 测试连通性结果 */}
      {testResult && (
        <Alert
          type={testResult.success ? 'success' : 'error'}
          showIcon
          message={
            <Space size={8}>
              <span>{testResult.success ? '连通正常' : '连通失败'}</span>
              <Tag color="default">{testResult.latency_ms} ms</Tag>
              {testResult.sample_difficulty != null && (
                <Tag color="cyan">样本 difficulty = {testResult.sample_difficulty}</Tag>
              )}
            </Space>
          }
          description={
            testResult.message ? (
              <pre
                style={{
                  fontSize: 12,
                  margin: 0,
                  maxHeight: 100,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  color: testResult.success ? 'var(--am-ink-3)' : 'var(--am-error-fg)',
                }}
              >
                {testResult.message}
              </pre>
            ) : null
          }
          style={{ marginTop: 8 }}
        />
      )}
    </Card>
  );
}

// ============================================================
// Tab 4：鉴权与安全（账号 / 密码 / admin token）
// ============================================================

const AUTH_KEYS = {
  USERNAME: 'auth.username',
  PASSWORD: 'auth.password',
  ADMIN_TOKEN: 'auth.admin_token',
} as const;

function AuthPanel() {
  const { message, modal } = App.useApp();
  const [config, setConfig] = useState<Record<string, string> | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [rotating, setRotating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchAuthConfig();
      setConfig(data);
      setDraft({ ...data });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const setField = (k: string, v: string) => setDraft((p) => ({ ...p, [k]: v }));

  const dirty = useMemo(() => {
    if (!config) return false;
    return Object.values(AUTH_KEYS).some(
      (k) => (draft[k] ?? '') !== (config[k] ?? ''),
    );
  }, [draft, config]);

  const handleSave = async () => {
    if (!dirty) {
      message.info('没有变更');
      return;
    }
    const updates: Record<string, string> = {};
    Object.values(AUTH_KEYS).forEach((k) => {
      if ((draft[k] ?? '') !== (config?.[k] ?? '')) updates[k] = draft[k] ?? '';
    });

    // 护栏：拦下明显非法/危险的凭证写入（仅前置校验，不改提交的数据结构）
    if (updates[AUTH_KEYS.USERNAME] !== undefined && updates[AUTH_KEYS.USERNAME].trim() === '') {
      message.error('用户名不能为空');
      return;
    }
    if (updates[AUTH_KEYS.PASSWORD] !== undefined && updates[AUTH_KEYS.PASSWORD].length < 6) {
      message.error('密码至少 6 位');
      return;
    }
    const nextToken = updates[AUTH_KEYS.ADMIN_TOKEN];
    if (nextToken !== undefined && nextToken.length > 0 && nextToken.length < 8) {
      message.error('admin token 至少 8 字符（生产建议 32+）');
      return;
    }

    // 清空 admin token = 静默关闭 X-Admin-Token 鉴权通道——单独二次确认（与改用户名/密码的确认相互独立）
    const willClearToken =
      nextToken !== undefined && nextToken === '' && (config?.[AUTH_KEYS.ADMIN_TOKEN] ?? '') !== '';
    if (willClearToken) {
      const okClear = await new Promise<boolean>((resolve) => {
        modal.confirm({
          title: '清空 admin token？',
          icon: <ExclamationCircleOutlined style={{ color: 'var(--am-warning)' }} />,
          content: (
            <div style={{ lineHeight: 1.8 }}>
              <div>
                置空后 <strong>X-Admin-Token 鉴权通道将被关闭</strong>，所有依赖该 token 的自动化脚本 / curl 调用会立即失去访问权限。
              </div>
              <div style={{ color: 'var(--am-ink-3)', marginTop: 6 }}>
                · 浏览器登录态不受影响（走 Session，不依赖 token）<br />
                · 如需恢复，重新填写 token 或点「生成新 token」
              </div>
            </div>
          ),
          okText: '确认清空',
          okButtonProps: { danger: true },
          cancelText: '再想想',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      });
      if (!okClear) return;
    }

    // 改密码 / 用户名前再确认一次——避免误操作把自己锁在外面
    const willChangeCreds =
      updates[AUTH_KEYS.USERNAME] !== undefined ||
      updates[AUTH_KEYS.PASSWORD] !== undefined;

    const proceed = await new Promise<boolean>((resolve) => {
      if (!willChangeCreds) return resolve(true);
      modal.confirm({
        title: '确认修改登录凭证？',
        icon: <ExclamationCircleOutlined style={{ color: 'var(--am-warning)' }} />,
        content: (
          <div style={{ lineHeight: 1.8 }}>
            <div>修改后<strong>下一次登录</strong>立即生效。</div>
            <div style={{ color: 'var(--am-ink-3)', marginTop: 6 }}>
              · 当前已登录的会话不会被踢出（避免改错把自己锁死）<br />
              · 新密码请妥善保存，如忘记需到数据库 sys_config 表手工修改
            </div>
          </div>
        ),
        okText: '确认修改',
        okButtonProps: { danger: true },
        cancelText: '再想想',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!proceed) return;

    setSaving(true);
    try {
      const next = await saveAuthConfig(updates);
      setConfig(next);
      setDraft({ ...next });
      message.success(`已保存 ${Object.keys(updates).length} 项，立即生效`);
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    if (config) setDraft({ ...config });
  };

  const handleRotateToken = async () => {
    const ok = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '轮换 admin token？',
        icon: <ExclamationCircleOutlined style={{ color: 'var(--am-warning)' }} />,
        content: (
          <div style={{ lineHeight: 1.8 }}>
            <div>服务器将生成一个 32 字符随机 token，<strong>立即生效</strong>。</div>
            <div style={{ color: 'var(--am-ink-3)', marginTop: 6 }}>
              · 旧 token 立即失效；所有自动化脚本 / curl 命令需同步更新<br />
              · 浏览器登录态不受影响（走 Session，不依赖 token）
            </div>
          </div>
        ),
        okText: '生成新 token',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!ok) return;

    setRotating(true);
    try {
      const r = await rotateAdminToken();
      // 直接刷新展示 + 弹一个含复制按钮的提示框
      await load();
      modal.success({
        title: '新 admin token 已生成',
        icon: <CheckCircleFilled style={{ color: 'var(--am-success)' }} />,
        content: (
          <div>
            <Paragraph type="secondary" style={{ marginTop: 0 }}>
              已自动保存并立即生效。请复制保存——再次进入页面也能看到。
            </Paragraph>
            <Input.TextArea
              value={r.admin_token}
              autoSize
              readOnly
              onClick={(e) => (e.target as HTMLTextAreaElement).select()}
              style={{ fontFamily: 'var(--am-font-mono)' }}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>{r.note}</Text>
          </div>
        ),
        okText: '我已保存',
        width: 540,
      });
    } finally {
      setRotating(false);
    }
  };

  if (loading && !config) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 6 }} />
      </Card>
    );
  }

  return (
    <div style={{ paddingBottom: 80 }}>
      <Alert
        type="warning"
        showIcon
        icon={<KeyOutlined />}
        message="登录凭证 + 自动化通道 token"
        description={
          <div style={{ lineHeight: 1.9, fontSize: 13 }}>
            <div>· 这里所有变更<strong>立即生效</strong>，无需重启服务。</div>
            <div>· 当前已登录的会话<strong>不会被踢出</strong>——避免误操作把自己锁死；新密码仅作用于下一次登录。</div>
            <div>· <code>admin token</code> 用于 <code>X-Admin-Token</code> 请求头通道（自动化脚本 / curl），轮换会即时影响所有外部脚本。</div>
            <div style={{ color: 'var(--am-error-fg)' }}>
              · 修改前请确认有另一种回滚路径（数据库直接改 <code>sys_config</code> 表）。
            </div>
          </div>
        }
        style={{ marginBottom: 16 }}
      />

      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <Card
            title={
              <span>
                <KeyOutlined style={{ color: 'var(--am-brand)', marginRight: 6 }} />
                后台登录账号
              </span>
            }
          >
            <Form layout="vertical" size="middle">
              <Form.Item
                label="用户名"
                htmlFor="auth-username"
                help={<Text type="secondary" style={{ fontSize: 12 }}>登录后台用</Text>}
              >
                <Input
                  id="auth-username"
                  value={draft[AUTH_KEYS.USERNAME] ?? ''}
                  onChange={(e) => setField(AUTH_KEYS.USERNAME, e.target.value)}
                  placeholder="admin"
                  autoComplete="off"
                />
              </Form.Item>
              <Form.Item
                label="密码"
                htmlFor="auth-password"
                help={
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    至少 6 位；点眼睛切换显示
                  </Text>
                }
              >
                <Input.Password
                  id="auth-password"
                  value={draft[AUTH_KEYS.PASSWORD] ?? ''}
                  onChange={(e) => setField(AUTH_KEYS.PASSWORD, e.target.value)}
                  placeholder="留空将无法登录"
                  autoComplete="new-password"
                  visibilityToggle
                />
              </Form.Item>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card
            title={
              <span>
                <ThunderboltOutlined style={{ color: 'var(--am-purple)', marginRight: 6 }} />
                X-Admin-Token（自动化通道）
              </span>
            }
            extra={
              <Button
                size="small"
                icon={<ReloadOutlined />}
                loading={rotating}
                onClick={handleRotateToken}
              >
                生成新 token
              </Button>
            }
          >
            <Form layout="vertical" size="middle">
              <Form.Item
                label="当前 token"
                htmlFor="auth-admin-token"
                help={
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    用作请求头 <code>X-Admin-Token</code>；至少 8 字符（生产建议 32+），置空将关闭该通道鉴权
                  </Text>
                }
              >
                <Input.Password
                  id="auth-admin-token"
                  value={draft[AUTH_KEYS.ADMIN_TOKEN] ?? ''}
                  onChange={(e) => setField(AUTH_KEYS.ADMIN_TOKEN, e.target.value)}
                  placeholder="32 字符随机串"
                  autoComplete="off"
                  visibilityToggle
                />
              </Form.Item>
              <Alert
                type="info"
                showIcon
                icon={<InfoCircleOutlined />}
                message={
                  <span style={{ fontSize: 13 }}>
                    浏览器登录态不依赖此 token；它只影响 curl / shell 脚本调用 <code>/api/v1/admin/**</code>。
                  </span>
                }
              />
            </Form>
          </Card>
        </Col>
      </Row>

      {/* 底栏：保存 / 重置（与 Judge 页保存条同款：不透明卡面 + shadow-2 + r-md + Badge 脏标） */}
      {dirty && (
        <div
          style={{
            position: 'sticky',
            bottom: 0,
            marginTop: 16,
            padding: '12px 16px',
            background: 'var(--am-bg-card)',
            border: '1px solid var(--am-border-subtle)',
            borderRadius: 'var(--am-r-md)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            boxShadow: 'var(--am-shadow-2)',
            zIndex: 10,
          }}
        >
          <Space>
            <Badge status="warning" />
            <Text type="warning">有未保存的鉴权变更</Text>
          </Space>
          <Space>
            <Button onClick={handleReset} disabled={saving}>
              重置
            </Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存并立即生效
            </Button>
          </Space>
        </div>
      )}
    </div>
  );
}

// ============================================================
// Tab 5：操作日志（sys_config 变更流水）
// ============================================================

const AUDIT_CATEGORY_OPTIONS = [
  { value: '', label: '全部分类' },
  { value: 'agents', label: '活跃 Agent' },
  { value: 'scheduling', label: '定时任务' },
  { value: 'judge', label: 'Judge 模型' },
  { value: 'insight', label: '评判参数' },
  { value: 'auth', label: '鉴权与安全' },
];

const CATEGORY_TAG_COLOR: Record<string, string> = {
  agents: 'blue',
  scheduling: 'cyan',
  judge: 'purple',
  insight: 'magenta',
  auth: 'red',
};

function AuditPanel() {
  const [data, setData] = useState<{ items: SysConfigAuditRow[]; total: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [category, setCategory] = useState<string>('');
  const [keyDraft, setKeyDraft] = useState<string>('');
  const [keyQuery, setKeyQuery] = useState<string>('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  // 记录哪些 secret 行被用户点开过明文（默认掩码，点击展开）
  const [revealed, setRevealed] = useState<Set<number>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await fetchSysConfigAudit({
        category: category || undefined,
        key: keyQuery || undefined,
        page,
        size,
      });
      setData({ items: r.items, total: r.total });
    } finally {
      setLoading(false);
    }
  }, [category, keyQuery, page, size]);

  useEffect(() => {
    load();
  }, [load]);

  const toggleReveal = (id: number) => {
    setRevealed((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const renderValue = (val: string | null, isSecret: boolean, rowId: number, kind: 'old' | 'new') => {
    if (val === null || val === '') {
      return <Text type="secondary" style={{ fontSize: 12 }}>{kind === 'old' ? '(空)' : '(已清空)'}</Text>;
    }
    if (isSecret && !revealed.has(rowId)) {
      return <Text code style={{ fontSize: 12 }}>••••••••</Text>;
    }
    // 超长截断（如 JSON），点击展开整行查看
    const tooLong = val.length > 80;
    return (
      <Tooltip title={tooLong ? val : undefined} mouseEnterDelay={0.3}>
        <Text
          code
          style={{
            fontSize: 12,
            display: 'inline-block',
            maxWidth: 260,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            verticalAlign: 'bottom',
          }}
        >
          {val}
        </Text>
      </Tooltip>
    );
  };

  const columns: ColumnsType<SysConfigAuditRow> = [
    {
      title: '时间',
      dataIndex: 'changed_time',
      width: 165,
      render: (v: string) => (
        <Tooltip title={dayjs(v).format('YYYY-MM-DD HH:mm:ss')}>
          <Text style={{ fontSize: 13, ...NUM_STYLE }}>{dayjs(v).format('MM-DD HH:mm:ss')}</Text>
        </Tooltip>
      ),
    },
    {
      title: '分类',
      dataIndex: 'category',
      width: 100,
      render: (v: string) => <Tag color={CATEGORY_TAG_COLOR[v] || 'default'}>{v}</Tag>,
    },
    {
      title: '配置项',
      dataIndex: 'config_key',
      width: 280,
      render: (v: string, row) => (
        <Space size={6}>
          <Text style={{ fontSize: 13, fontFamily: 'var(--am-font-mono)' }}>
            {v}
          </Text>
          {row.is_secret === 1 && (
            <Tooltip title={revealed.has(row.id) ? '点击隐藏明文' : '点击查看明文'}>
              <Button
                type="text"
                size="small"
                icon={<KeyOutlined />}
                onClick={() => toggleReveal(row.id)}
                aria-label={revealed.has(row.id) ? '隐藏明文' : '查看明文'}
                style={{
                  color: revealed.has(row.id) ? 'var(--am-brand)' : 'var(--am-ink-3)',
                  padding: '0 4px',
                }}
              />
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '旧值',
      dataIndex: 'old_value',
      width: 240,
      ellipsis: true,
      render: (v: string | null, row) => renderValue(v, row.is_secret === 1, row.id, 'old'),
    },
    {
      title: '新值',
      dataIndex: 'new_value',
      width: 240,
      ellipsis: true,
      render: (v: string | null, row) => renderValue(v, row.is_secret === 1, row.id, 'new'),
    },
    {
      title: '操作人',
      dataIndex: 'operator',
      width: 120,
      render: (v: string | null) =>
        v ? (
          <Tag
            className="am-break"
            style={{
              background: 'var(--am-surface-sunken)',
              border: 'none',
              color: 'var(--am-ink-3)',
              maxWidth: '100%',
              whiteSpace: 'normal',
            }}
          >
            {v}
          </Tag>
        ) : (
          EMPTY_DASH
        ),
    },
  ];

  return (
    <div>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        message="变更流水"
        description="所有系统设置的修改都会留痕（含旧值 / 新值 / 修改人 / 时间）。出问题时可以一眼回溯——「昨天谁把 Judge 模型改了」「上周改的并发数是多少」。敏感字段默认掩码，点钥匙图标可临时展开。"
        style={{ marginBottom: 16 }}
      />

      {/* 过滤工具栏 */}
      <Card
        size="small"
        style={{ marginBottom: 12 }}
        styles={{ body: { padding: '12px 16px' } }}
      >
        <Space wrap>
          <Select
            value={category}
            options={AUDIT_CATEGORY_OPTIONS}
            onChange={(v) => {
              setCategory(v);
              setPage(0);
            }}
            style={{ width: 160 }}
            size="middle"
          />
          <Input.Search
            placeholder="按 key 模糊搜索（如 judge.a.model）"
            value={keyDraft}
            onChange={(e) => setKeyDraft(e.target.value)}
            onSearch={(v) => {
              setKeyQuery(v.trim());
              setPage(0);
            }}
            allowClear
            style={{ width: 280 }}
            size="middle"
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={load}
            loading={loading}
            size="middle"
          >
            刷新
          </Button>
        </Space>
      </Card>

      <Table<SysConfigAuditRow>
        rowKey="id"
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        size="middle"
        scroll={{ x: 1150 }}
        className="am-sticky-table"
        pagination={{
          current: page + 1,
          pageSize: size,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (t) => `共 ${t} 条变更记录`,
          onChange: (p, s) => {
            setPage(p - 1);
            setSize(s);
          },
        }}
        locale={{
          emptyText: (
            <div style={{ padding: '32px 0' }}>
              <Empty
                description={
                  <Text type="secondary">
                    暂无变更记录
                    {category || keyQuery ? '（试试清空过滤条件）' : ''}
                  </Text>
                }
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            </div>
          ),
        }}
      />
    </div>
  );
}

