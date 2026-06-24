import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Card, DatePicker, Descriptions, Input, Modal, Select, Space, Spin, Switch, Table, Tag, Tooltip } from 'antd';
import { ClearOutlined, InfoCircleOutlined, RobotOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { fetchMonitorTargets, fetchSessionAuditDetail, fetchSessions } from '../api/client';
import type { AiSession, AiSessionAuditDetail, MonitorTarget, PageDto } from '../api/types';
import SourceFilter from '../components/SourceFilter';
import { useSse } from '../hooks/useSse';
import {
  formatTime,
  formatTokens,
  statusColor,
  statusLabel,
  targetTypeColor,
  targetTypeLabel,
  type TargetTypeMap,
} from '../utils/format';
import { MODE_META, OUTCOME_META, CAPABILITY_DIMENSIONS } from './Analysis/constants';

// 页面 URL 参数键。集中在这里，避免 Sessions / SessionDetail 各写一份字符串字面量。
// user_code：项目透视页跳转链使用（精确）
// user_name：列表页过滤框使用（按姓名 / 工号模糊）
const QP_USER = 'user_code';
const QP_USER_NAME = 'user_name';
const QP_DAYS = 'days';
const QP_FROM = 'from';
const QP_TO = 'to';
const QP_TARGET = 'target_type';
const QP_PROJECT = 'project_name';
const QP_ACTIVE = 'active_only';
// v2.11：是否显示无效会话（user>0、assist=0、token=0；典型场景是 Claude Code 的本地命令空跑）。
// 默认隐藏；运维查脏数据时勾上。
const QP_INCLUDE_INVALID = 'include_invalid';

const { RangePicker } = DatePicker;

const SESSION_PAGE_SIZE_DEFAULT = 100;
const SESSION_PAGE_SIZE_OPTIONS = ['100', '300', '500'] as const;

dayjs.extend(isoWeek);

/**
 * SSE session_changed 快照与列表 HTTP 口径不完全一致：ingest 推送的 AiSessionDto 没带
 * 「窗内 slash 合并」「窗内消息数 / token」，Jackson 仍会输出 slash_invocations: null，
 * 若直接 spread 会把列表刚拿到的字段冲掉（先显示再闪成「—」）。
 */
function mergeSessionListPatch(prev: AiSession, incoming: AiSession): AiSession {
  const next = { ...prev, ...incoming };
  if (incoming.slash_invocations == null) {
    next.slash_invocations = prev.slash_invocations;
  }
  if (incoming.window_message_count == null && prev.window_message_count != null) {
    next.window_message_count = prev.window_message_count;
  }
  if (incoming.window_tokens == null && prev.window_tokens != null) {
    next.window_tokens = prev.window_tokens;
  }
  return next;
}

function defaultRange(): [Dayjs, Dayjs] {
  // v2.10：默认窗口统一改为自然周（周一 ~ 周日），与员工数据 / 项目透视 / 模型与工具 / 分析报告页一致。
  const monday = dayjs().isoWeekday(1).startOf('day');
  const sunday = monday.add(6, 'day');
  return [monday, sunday];
}

function isDefaultRange(r: [Dayjs | null, Dayjs | null] | null): boolean {
  if (!r || !r[0] || !r[1]) return false;
  const def = defaultRange();
  return r[0].isSame(def[0], 'day') && r[1].isSame(def[1], 'day');
}

type TargetFilter = string;

export default function Sessions() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // user_code：精确（来自项目透视页跳转链）；进入页面后只读
  const initialUserCode = searchParams.get(QP_USER) || '';
  // user_name：模糊（页面顶部搜索框）
  const initialUserName = searchParams.get(QP_USER_NAME) || '';
  const initialDays = Number(searchParams.get(QP_DAYS) || 7);
  const initialTarget = (searchParams.get(QP_TARGET) as TargetFilter) || 'all';
  const initialProject = searchParams.get(QP_PROJECT) || '';
  const initialFrom = searchParams.get(QP_FROM);
  const initialTo = searchParams.get(QP_TO);
  const initialActiveOnly = (() => {
    const v = (searchParams.get(QP_ACTIVE) || '').toLowerCase();
    return v === '1' || v === 'true' || v === 'yes';
  })();
  const initialIncludeInvalid = (() => {
    const v = (searchParams.get(QP_INCLUDE_INVALID) || '').toLowerCase();
    return v === '1' || v === 'true' || v === 'yes';
  })();

  const [nameInput, setNameInput] = useState(initialUserName);
  const [userName, setUserName] = useState(initialUserName);
  const [userCode, setUserCode] = useState(initialUserCode);
  const [days, setDays] = useState(initialDays);
  const [target, setTarget] = useState<TargetFilter>(initialTarget);
  const [projectName, setProjectName] = useState(initialProject);
  const [activeOnly, setActiveOnly] = useState(initialActiveOnly);
  const [includeInvalid, setIncludeInvalid] = useState(initialIncludeInvalid);
  // 精确时间窗口：from/to 一旦设置就优先于 days，并且让窗内 token / 消息只算这段时间。
  // v2.10：未传 from/to 时默认初始化为本自然周（周一 ~ 周日），与其它页统一。
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(
    initialFrom && initialTo ? [dayjs(initialFrom), dayjs(initialTo)] : defaultRange(),
  );
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(SESSION_PAGE_SIZE_DEFAULT);

  const fromStr = range?.[0]?.format('YYYY-MM-DD');
  const toStr = range?.[1]?.format('YYYY-MM-DD');
  const hasWindow = !!(fromStr && toStr);

  const [data, setData] = useState<PageDto<AiSession> | null>(null);
  const [loading, setLoading] = useState(false);

  const [auditModalOpen, setAuditModalOpen] = useState(false);
  const [auditModalSessionId, setAuditModalSessionId] = useState<number | null>(null);
  const [auditDetail, setAuditDetail] = useState<AiSessionAuditDetail | null>(null);
  const [auditDetailLoading, setAuditDetailLoading] = useState(false);

  // 字典：开屏拉一次 monitor_target（接口只返回 enabled=1 的活跃 agent），
  // 既是 SourceFilter 选项来源，也是 Tag 颜色映射来源。
  // 用户从老链接进入但 target_type=已禁用的 agent 时，会自动回退到 "all" 并轻提示。
  const [targets, setTargets] = useState<MonitorTarget[]>([]);
  useEffect(() => {
    fetchMonitorTargets()
      .then((arr) => {
        setTargets(arr);
        // 用户当前选中的 target 不在 active 列表（例如被管理员在系统设置里禁用了）→ 自动回退
        if (target !== 'all' && !arr.some((t) => t.type_code === target)) {
          setTarget('all');
        }
      })
      .catch(() => setTargets([]));
    // 仅在挂载 + target 变化时校验（target 由 URL 初始化，无需把 setTarget 列入依赖）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const targetMap = useMemo<TargetTypeMap>(() => {
    const m: TargetTypeMap = {};
    for (const t of targets) m[t.type_code] = { label: t.type_name, color: t.display_color };
    return m;
  }, [targets]);

  const targetParam = useMemo(() => (target === 'all' ? undefined : target), [target]);

  useEffect(() => {
    setLoading(true);
    fetchSessions({
      user_code: userCode || undefined,
      user_name: userName || undefined,
      days: hasWindow ? undefined : days,
      from: fromStr,
      to: toStr,
      target_type: targetParam,
      project_name: projectName || undefined,
      active_only: activeOnly ? true : undefined,
      include_invalid: includeInvalid ? true : undefined,
      page,
      size,
    })
      .then(setData)
      .finally(() => setLoading(false));
  }, [userCode, userName, days, fromStr, toStr, hasWindow, targetParam, projectName, activeOnly, includeInvalid, page, size]);

  // SSE：服务端 ingest 完成 / 卡僵自愈定时任务都会推 session_changed（含整个 AiSession 快照）。
  // 我们用它把当前列表里命中的那行 status / current_tool / model / last_activity 实时 patch 上，
  // 与 Dashboard 共享同一条 SSE 流，实现"客户端打字 → 列表状态变化"的低延迟联动。
  // 列表本身分页：只 patch 当前页里已有的 row（新增/删除走 polling 兜底，避免乱序）。
  const onSessionChanged = useCallback((raw: string) => {
    try {
      const s = JSON.parse(raw) as AiSession;
      setData((prev) => {
        if (!prev) return prev;
        const idx = prev.items.findIndex((it) => it.id === s.id);
        if (idx < 0) return prev;
        const next = prev.items.slice();
        next[idx] = mergeSessionListPatch(prev.items[idx], s);
        return { ...prev, items: next };
      });
    } catch {
      // best-effort：解析失败就等下次 polling
    }
  }, []);
  const sseHandlers = useMemo(
    () => ({ session_changed: onSessionChanged }),
    [onSessionChanged],
  );
  useSse('/api/v1/dashboard/stream', sseHandlers);

  const openAuditDetail = useCallback((sessionId: number) => {
    setAuditModalSessionId(sessionId);
    setAuditModalOpen(true);
    setAuditDetail(null);
    setAuditDetailLoading(true);
    fetchSessionAuditDetail(sessionId)
      .then(setAuditDetail)
      .finally(() => setAuditDetailLoading(false));
  }, []);

  const closeAuditModal = useCallback(() => {
    setAuditModalOpen(false);
    setAuditModalSessionId(null);
    setAuditDetail(null);
    setAuditDetailLoading(false);
  }, []);

  // 同步 URL 参数：所有筛选变更都立即写回 URL，刷新 / 分享链接都能保留筛选态。
  const writeQuery = (next: {
    name?: string;
    d?: number;
    t?: TargetFilter;
    from?: string | null;
    to?: string | null;
    active?: boolean;
    invalid?: boolean;
  }) => {
    const sp = new URLSearchParams(searchParams);
    const n = next.name ?? userName;
    const d = next.d ?? days;
    const t = next.t ?? target;
    const a = next.active !== undefined ? next.active : activeOnly;
    const inv = next.invalid !== undefined ? next.invalid : includeInvalid;
    if (n) sp.set(QP_USER_NAME, n);
    else sp.delete(QP_USER_NAME);
    sp.set(QP_DAYS, String(d));
    if (t === 'all') sp.delete(QP_TARGET);
    else sp.set(QP_TARGET, t);
    if (a) sp.set(QP_ACTIVE, '1');
    else sp.delete(QP_ACTIVE);
    if (inv) sp.set(QP_INCLUDE_INVALID, '1');
    else sp.delete(QP_INCLUDE_INVALID);
    // from/to：next 显式传入时按新值写；undefined 时维持 URL 现状
    if (next.from !== undefined) {
      if (next.from) sp.set(QP_FROM, next.from);
      else sp.delete(QP_FROM);
    }
    if (next.to !== undefined) {
      if (next.to) sp.set(QP_TO, next.to);
      else sp.delete(QP_TO);
    }
    setSearchParams(sp);
  };

  const onNameSubmit = () => {
    const v = nameInput.trim();
    setUserName(v);
    setPage(0);
    writeQuery({ name: v });
  };

  const onTargetChange = (v: TargetFilter) => {
    setTarget(v);
    setPage(0);
    writeQuery({ t: v });
  };

  const onDaysChange = (v: number) => {
    setDays(v);
    setPage(0);
    writeQuery({ d: v });
  };

  const onRangeChange = (v: [Dayjs | null, Dayjs | null] | null) => {
    setRange(v);
    setPage(0);
    const f = v?.[0]?.format('YYYY-MM-DD') || null;
    const t = v?.[1]?.format('YYYY-MM-DD') || null;
    writeQuery({ from: f, to: t });
  };

  const onReset = () => {
    setNameInput('');
    setUserName('');
    setUserCode('');
    setDays(7);
    setTarget('all');
    setProjectName('');
    setRange(defaultRange());
    setActiveOnly(false);
    setIncludeInvalid(false);
    setPage(0);
    setSearchParams(new URLSearchParams());
  };

  const onIncludeInvalidChange = (v: boolean) => {
    setIncludeInvalid(v);
    setPage(0);
    writeQuery({ invalid: v });
  };

  const clearActiveOnly = () => {
    setActiveOnly(false);
    setPage(0);
    const sp = new URLSearchParams(searchParams);
    sp.delete(QP_ACTIVE);
    setSearchParams(sp);
  };

  /** 清掉项目过滤（保留其它筛选） */
  const clearProject = () => {
    setProjectName('');
    setPage(0);
    const sp = new URLSearchParams(searchParams);
    sp.delete(QP_PROJECT);
    setSearchParams(sp);
  };

  /** 清掉项目透视带过来的 user_code（保留其它筛选） */
  const clearUserCode = () => {
    setUserCode('');
    setPage(0);
    const sp = new URLSearchParams(searchParams);
    sp.delete(QP_USER);
    setSearchParams(sp);
  };

  // v2.10：默认 range 是本自然周，不算"已筛选"——仅当用户真的改成其它窗口时才高亮"重置筛选"。
  const hasFilter =
    !!userCode
    || !!userName
    || target !== 'all'
    || days !== 7
    || !!projectName
    || (hasWindow && !isDefaultRange(range))
    || activeOnly
    || includeInvalid;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* 项目 / user_code 过滤来自项目透视页跳转，作为只读 banner 让用户能看到当前过滤态并一键清除 */}
      {(projectName || userCode) && (
        <Card size="small" styles={{ body: { padding: '8px 16px' } }}>
          <Space size={12} wrap>
            {projectName && (
              <Space size={6}>
                <span style={{ color: '#64748b', fontSize: 13 }}>项目：</span>
                <Tag color="blue" style={{ fontSize: 13 }}>{projectName}</Tag>
                <Button size="small" type="link" onClick={clearProject}>清除</Button>
              </Space>
            )}
            {userCode && (
              <Space size={6}>
                <span style={{ color: '#64748b', fontSize: 13 }}>员工工号：</span>
                <Tag color="geekblue" style={{ fontSize: 13 }}>{userCode}</Tag>
                <Button size="small" type="link" onClick={clearUserCode}>清除</Button>
              </Space>
            )}
          </Space>
        </Card>
      )}
      {/* 工具栏卡片：所有筛选条件归一在这一行，宽度不会被 agent 类型数撑爆 */}
      <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
        <div className="am-toolbar">
          <SourceFilter value={target} onChange={onTargetChange} targets={targets} width={200} />
          <Input.Search
            placeholder="按姓名 / 工号搜索"
            value={nameInput}
            onChange={(e) => setNameInput(e.target.value)}
            onSearch={onNameSubmit}
            style={{ width: 240 }}
            allowClear
          />
          <Select
            value={days}
            onChange={onDaysChange}
            style={{ width: 120 }}
            disabled={hasWindow}
            options={[
              { label: '近 1 天', value: 1 },
              { label: '近 3 天', value: 3 },
              { label: '近 7 天', value: 7 },
              { label: '近 14 天', value: 14 },
              { label: '近 30 天', value: 30 },
            ]}
          />
          <RangePicker
            allowClear
            value={range as [Dayjs, Dayjs] | null}
            onChange={(v) => onRangeChange(v as [Dayjs | null, Dayjs | null] | null)}
            placeholder={['起始日', '截止日']}
            style={{ width: 240 }}
          />
          <Tooltip
            title={
              activeOnly
                ? '当前仅展示 status≠空闲 的会话，与观测大盘「活跃 AI 会话」计数口径一致。下方时间窗仍影响「消息 / Token」列的窗内统计。'
                : '选择精确日期区间后，列表会展示在该区间内有过对话的会话；其消息数 / Token 列也会按区间内消息重算（而不是会话生命周期累计）。'
            }
          >
            <InfoCircleOutlined style={{ color: '#94a3b8' }} />
          </Tooltip>
          {activeOnly && (
            <Tag closable onClose={clearActiveOnly} color="green" style={{ marginInlineEnd: 0 }}>
              仅非空闲会话
            </Tag>
          )}
          {/* v2.11：无效会话 = 用户输入过、但模型一次都没回应；典型场景是 Claude Code
              用户敲了 /usage、/exit 等本地命令但没真的对话。默认隐藏，开关后能查到。 */}
          <Tooltip title="无效会话：用户在客户端输入过、但模型一次都没回应（典型是 Claude Code 的 /usage 等本地命令）。默认不显示。">
            <Space size={6}>
              <Switch
                size="small"
                checked={includeInvalid}
                onChange={onIncludeInvalidChange}
              />
              <span style={{ color: '#64748b', fontSize: 13 }}>显示无效会话</span>
            </Space>
          </Tooltip>
          <span className="am-toolbar-spacer" />
          <Button
            icon={<ClearOutlined />}
            onClick={onReset}
            disabled={!hasFilter}
            type="text"
          >
            重置筛选
          </Button>
        </div>
      </Card>

      {/* 列表卡片：表头 sticky，title 区只放计数（来源筛选已在工具栏，不再重复） */}
      <Card
        size="small"
        title={
          <Space size={8}>
            <RobotOutlined style={{ color: '#2563eb' }} />
            <span style={{ fontWeight: 600 }}>AI 会话列表</span>
            <span style={{ color: '#94a3b8', fontSize: 13, fontWeight: 400 }}>
              共 {data?.total ?? 0} 条
              {activeOnly && ' · 仅非空闲'}
              {includeInvalid && ' · 含无效会话'}
            </span>
          </Space>
        }
        styles={{ body: { padding: 0 } }}
      >
        <Table<AiSession>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={data?.items ?? []}
          scroll={{ x: 1780 }}
          className="am-sticky-table"
          pagination={{
            current: page + 1,
            pageSize: size,
            total: data?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [...SESSION_PAGE_SIZE_OPTIONS],
            size: 'small',
            showTotal: (t, [a, b]) => `第 ${a}–${b} / 共 ${t}`,
            onChange: (p, s) => {
              setPage(p - 1);
              setSize(s);
            },
          }}
          onRow={(r) => ({
            onClick: () => {
              // 把当前时间窗带进详情页，让详情页的消息 / 事件也按这段窗口截断
              const sp = new URLSearchParams();
              if (fromStr) sp.set(QP_FROM, fromStr);
              if (toStr) sp.set(QP_TO, toStr);
              const qs = sp.toString();
              navigate(`/sessions/${r.id}${qs ? `?${qs}` : ''}`);
            },
            style: { cursor: 'pointer' },
          })}
          columns={[
            {
              title: '状态',
              dataIndex: 'status',
              width: 140,
              fixed: 'left',
              // 仅展示 ai_session.status 本身（idle / writing / running / ...）。
              // v2.8 起去掉前端"离线"灰化降级：旧逻辑用 last_activity > 3 分钟兜底打"离线"标，
              // 但与"几十天未动的 idle session 不会标离线"口径不统一，反而误导。
              // 真正的"会话陈旧"由列表的 last_activity 列直接呈现，无需再叠一个降级标签。
              // v2.11：当包含无效会话时，状态后面加一个"无效"小 Tag，避免运维误把它当正常会话排查。
              render: (v, r) => (
                <Space size={6}>
                  <span
                    style={{
                      display: 'inline-block',
                      width: 8,
                      height: 8,
                      borderRadius: 4,
                      background: statusColor(v),
                    }}
                  />
                  <span>{statusLabel(v)}</span>
                  {r.invalid_reason && (
                    <Tooltip title={
                      r.invalid_reason === 'merged_subagent'
                        ? '已归并到父 chat 的 Task 子 composer，不在列表单独展示'
                        : `无效会话（${r.invalid_reason}）：模型从未真正回应过这个会话`
                    }>
                      <Tag color="default" style={{ marginInlineEnd: 0, fontSize: 11 }}>无效</Tag>
                    </Tooltip>
                  )}
                </Space>
              ),
            },
            { title: '员工', dataIndex: 'user_display', width: 140, fixed: 'left', ellipsis: true },
            {
              title: '来源',
              dataIndex: 'target_type',
              width: 110,
              render: (v) => (
                <Tag color={targetTypeColor(v, targetMap)} style={{ marginInlineEnd: 0 }}>
                  {targetTypeLabel(v, targetMap)}
                </Tag>
              ),
              filters: targets.map((t) => ({ text: t.type_name, value: t.type_code })),
              onFilter: (val, record) => record.target_type === val,
            },
            {
              title: '项目 / 分支',
              dataIndex: 'project_name',
              width: 280,
              ellipsis: true,
              render: (v, row) => {
                let sub: React.ReactNode = (
                  <span style={{ color: '#cbd5e1' }}>非 git 仓库</span>
                );
                if (row.git_branch) sub = <span style={{ color: '#94a3b8' }}>@{row.git_branch}</span>;
                else if (row.repo_url)
                  sub = (
                    <span style={{ color: '#94a3b8' }} title={row.repo_url}>
                      {row.repo_url}
                    </span>
                  );
                return (
                  <Space
                    direction="vertical"
                    size={0}
                    style={{ lineHeight: 1.4 }}
                    title={row.cwd || undefined}
                  >
                    <Space size={6} wrap>
                      <span style={{ fontWeight: 500 }}>{v || '(未识别)'}</span>
                      {row.worktree && <Tag color="purple">worktree</Tag>}
                    </Space>
                    <span style={{ fontSize: 12 }}>{sub}</span>
                  </Space>
                );
              },
            },
            {
              title: (
                <Space size={4}>
                  Slash commands
                  <Tooltip
                    title={
                      '当前筛选时间窗内，本会话 user 消息里合并去重后的「快捷调用」展示名。'
                      + ' 含 Cursor 显式 /…、Codex $技能，以及 NL 已执行 skill（Read SKILL.md 后有 Edit/Write/Bash 等落地工具；仅 Read 或 Grep 浏览不计）。'
                      + ' Claude Code 等其它来源当前无此项。'
                      + ' 与详情页消息字段同源。'
                      + ' 标签颜色区分 command / skill / nl_skill / noise。'
                    }
                  >
                    <InfoCircleOutlined style={{ color: '#94a3b8' }} />
                  </Tooltip>
                </Space>
              ),
              width: 260,
              render: (_, r) => {
                const hits = r.slash_invocations;
                if (!hits || hits.length === 0) {
                  return <span style={{ color: '#94a3b8' }}>—</span>;
                }
                return (
                  <Space size={[4, 4]} wrap>
                    {hits.map((h, i) => {
                      const k = (h.kind || '').toLowerCase();
                      let color: string | undefined = 'blue';
                      if (k === 'skill') color = 'cyan';
                      else if (k === 'noise') color = 'default';
                      return (
                        <Tag key={`${h.token}-${h.kind}-${i}`} color={color} style={{ marginInlineEnd: 0, fontSize: 11 }}>
                          {h.token}
                        </Tag>
                      );
                    })}
                  </Space>
                );
              },
            },
            { title: '模型', dataIndex: 'model', width: 180, ellipsis: true },
            {
              title: (
                <Space size={4}>
                  审计
                  <Tooltip
                    title={
                      'Insight 会话级评判（ai_session_audit）：跑过分析报告或命中缓存时出现；'
                      + '难度 1–5、完成度、协作模式均为双 judge 合成结果，与「分析报告」口径一致。'
                    }
                  >
                    <InfoCircleOutlined style={{ color: '#94a3b8' }} />
                  </Tooltip>
                </Space>
              ),
              width: 244,
              render: (_, r) => {
                const a = r.audit;
                if (!a) {
                  return <span style={{ color: '#94a3b8' }}>未审计</span>;
                }
                const outcomeLabel = OUTCOME_META[a.outcome]?.label ?? a.outcome;
                const modeLabel = MODE_META[a.mode]?.label ?? a.mode;
                const modeColor = MODE_META[a.mode]?.color ?? '#94a3b8';
                const tip = (
                  <Space direction="vertical" size={0}>
                    <span>难度（合成）：{a.difficulty}</span>
                    <span>审计时间：{formatTime(a.audited_at)}</span>
                    <span>Rubric / 流水线版本：{a.audit_version}</span>
                  </Space>
                );
                return (
                  <div onClick={(e) => e.stopPropagation()} role="presentation">
                    <Space size={[4, 4]} style={{ lineHeight: 1.35 }} align="center" wrap={false}>
                      <Tooltip title={tip}>
                        <Space size={[4, 4]} wrap>
                          <Tag color="blue" style={{ marginInlineEnd: 0, fontSize: 11 }}>
                            难度 {a.difficulty}
                          </Tag>
                          <Tag
                            style={{
                              marginInlineEnd: 0,
                              fontSize: 11,
                              borderColor: OUTCOME_META[a.outcome]?.color,
                              color: OUTCOME_META[a.outcome]?.color,
                              background: '#fff',
                            }}
                          >
                            {outcomeLabel}
                          </Tag>
                          <Tag
                            style={{
                              marginInlineEnd: 0,
                              fontSize: 11,
                              borderColor: modeColor,
                              color: modeColor,
                              background: '#fff',
                            }}
                          >
                            {modeLabel}
                          </Tag>
                        </Space>
                      </Tooltip>
                      <Button type="link" size="small" style={{ padding: 0, height: 'auto' }} onClick={() => openAuditDetail(r.id)}>
                        详情
                      </Button>
                    </Space>
                  </div>
                );
              },
            },
            {
              title: (
                <Space size={4}>
                  消息
                  <Tooltip
                    title={
                      hasWindow
                        ? '当前显示筛选区间内本会话的消息数（按 message_time 切片）；括号内为会话生命周期累计'
                        : '会话生命周期累计的用户/助手消息数；选定时间区间后会切换为窗内值'
                    }
                  >
                    <InfoCircleOutlined style={{ color: '#94a3b8' }} />
                  </Tooltip>
                </Space>
              ),
              width: 120,
              render: (_, r) => {
                const win = r.window_message_count;
                if (hasWindow && win != null) {
                  return (
                    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                      <strong>{win}</strong>
                      <span style={{ color: '#94a3b8', fontSize: 12 }}>
                        {' '}
                        / 累计 {r.user_messages + r.assistant_messages}
                      </span>
                    </span>
                  );
                }
                return (
                  <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {r.user_messages} / {r.assistant_messages}
                  </span>
                );
              },
            },
            {
              title: (
                <Space size={4}>
                  Token
                  <Tooltip
                    title={
                      hasWindow
                        ? '筛选区间内 input + output token；括号内为会话生命周期累计 in/out'
                        : '会话生命周期累计的 input / output token；选定时间区间后会切换为窗内总和'
                    }
                  >
                    <InfoCircleOutlined style={{ color: '#94a3b8' }} />
                  </Tooltip>
                </Space>
              ),
              width: 180,
              render: (_, r) => {
                const winTok = r.window_tokens;
                if (hasWindow && winTok != null) {
                  return (
                    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                      <strong>{formatTokens(winTok)}</strong>
                      <span style={{ color: '#94a3b8', fontSize: 12 }}>
                        {' '}
                        / 累计 {formatTokens(r.input_tokens + r.output_tokens)}
                      </span>
                    </span>
                  );
                }
                return (
                  <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {formatTokens(r.input_tokens)} / {formatTokens(r.output_tokens)}
                  </span>
                );
              },
            },
            {
              title: '最近活动',
              dataIndex: 'last_activity',
              width: 150,
              render: (v) => formatTime(v),
            },
            {
              title: '开始',
              dataIndex: 'started_at',
              width: 150,
              render: (v) => formatTime(v),
            },
          ]}
        />
      </Card>

      <Modal
        title={auditModalSessionId != null ? `会话 #${auditModalSessionId} · Insight 评判详情` : 'Insight 评判详情'}
        open={auditModalOpen}
        onCancel={closeAuditModal}
        footer={null}
        width={760}
        destroyOnClose
      >
        {auditDetailLoading ? (
          <div style={{ textAlign: 'center', padding: 48 }}>
            <Spin />
          </div>
        ) : auditDetail ? (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="审计时间">{formatTime(auditDetail.audited_at)}</Descriptions.Item>
              <Descriptions.Item label="Rubric / 流水线版本">{auditDetail.audit_version}</Descriptions.Item>
              <Descriptions.Item label="Judge A 模型">
                {auditDetail.judge_a_model?.trim() ? auditDetail.judge_a_model.trim() : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="Judge B 模型">
                {auditDetail.judge_b_model?.trim() ? auditDetail.judge_b_model.trim() : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="难度（合成 / A / B）">
                {auditDetail.difficulty} / {auditDetail.difficulty_a} / {auditDetail.difficulty_b}
              </Descriptions.Item>
              <Descriptions.Item label="完成度">
                {OUTCOME_META[auditDetail.outcome]?.label ?? auditDetail.outcome}
              </Descriptions.Item>
              <Descriptions.Item label="协作模式">
                {MODE_META[auditDetail.mode]?.label ?? auditDetail.mode}
              </Descriptions.Item>
              <Descriptions.Item label="双 judge 不一致">
                {auditDetail.judge_disagreement === 1 ? '是' : '否'}
              </Descriptions.Item>
              <Descriptions.Item label="审计时消息条数">{auditDetail.message_count_at_audit}</Descriptions.Item>
              {CAPABILITY_DIMENSIONS.map(({ key, label }) => (
                <Descriptions.Item key={key} label={`能力 · ${label}`}>
                  {auditDetail[key]}
                </Descriptions.Item>
              ))}
            </Descriptions>
            <div>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>判定说明（judge_reason）</div>
              <pre
                style={{
                  margin: 0,
                  padding: 12,
                  borderRadius: 8,
                  background: '#f8fafc',
                  border: '1px solid #e2e8f0',
                  maxHeight: 360,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  fontSize: 13,
                  lineHeight: 1.5,
                }}
              >
                {auditDetail.judge_reason_text ?? '—'}
              </pre>
            </div>
          </Space>
        ) : (
          <div style={{ color: '#94a3b8', textAlign: 'center', padding: 24 }}>
            未能加载评判详情（可能没有审计记录）。
          </div>
        )}
      </Modal>
    </Space>
  );
}
