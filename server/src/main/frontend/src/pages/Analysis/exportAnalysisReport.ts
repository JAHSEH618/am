import * as XLSX from 'xlsx';
import type {
  AnalysisReportDetail,
  DifficultyDist,
  ModeDist,
  PercentileBundle,
  TeamPercentiles,
} from '@/api/types';
import {
  BUCKET_META,
  CAPABILITY_DIMENSIONS,
  MODE_META,
  WATCHLIST_META,
} from './constants';

function pctStr(v: number | null | undefined, digits = 1): string {
  if (v == null || Number.isNaN(v)) return '';
  return `${(v * 100).toFixed(digits)}%`;
}

function fmtNum(v: number | null | undefined, digits?: number): string | number {
  if (v == null || Number.isNaN(v)) return '';
  if (digits != null) return Number(v.toFixed(digits));
  return v;
}

function formatDifficultyDist(d: DifficultyDist | null): string {
  if (!d) return '';
  const keys = ['1', '2', '3', '4', '5'] as const;
  return keys.map((k) => `难度${k}:${d[k] ?? 0}`).join('；');
}

function formatModeDist(d: ModeDist | null): string {
  if (!d) return '';
  return (Object.entries(d) as [string, number][])
    .filter(([, v]) => v != null && !Number.isNaN(v))
    .map(([k, v]) => `${MODE_META[k]?.label ?? k}:${pctStr(v)}`)
    .join('；');
}

function watchlistLabels(flags: string[] | null): string {
  if (!flags?.length) return '';
  return flags.map((f) => WATCHLIST_META[f]?.label ?? f).join('；');
}

function appendPercentileRows(rows: (string | number | null)[][], tp: TeamPercentiles | null) {
  if (!tp) return;
  const bundles: [string, PercentileBundle | undefined][] = [
    ['会话数', tp.session_count],
    ['协作时长(h)', tp.ai_active_hours],
    ['每小时 commit', tp.ai_commits_per_active_hour],
  ];
  for (const [label, b] of bundles) {
    if (!b) continue;
    rows.push([
      label,
      `P10:${b.p10 ?? ''}`,
      `P25:${b.p25 ?? ''}`,
      `P50:${b.p50 ?? ''}`,
      `P75:${b.p75 ?? ''}`,
      `P90:${b.p90 ?? ''}`,
    ]);
  }
}

function buildSummarySheet(detail: AnalysisReportDetail): XLSX.WorkSheet {
  const rows: (string | number | null)[][] = [];
  rows.push(['分析报告概要']);
  rows.push([]);
  rows.push(['报告 ID', detail.id]);
  rows.push(['时间窗口', `${detail.window_from} ~ ${detail.window_to}`]);
  rows.push(['状态', detail.status]);
  rows.push(['创建时间', detail.created_time]);
  rows.push(['完成时间', detail.completed_time ?? '']);
  rows.push(['活跃员工数', detail.active_user_count ?? '']);
  rows.push(['总会话数', detail.total_session_count ?? '']);
  rows.push(['协作总时长（小时）', detail.total_active_hours ?? '']);
  rows.push(['Git 提交数', detail.total_ai_commit ?? '']);
  rows.push(['审计进度', `${detail.audited_count}/${detail.total_count}`]);
  rows.push([
    '双 judge 不一致率',
    detail.judge_disagreement_ratio == null ? '' : pctStr(detail.judge_disagreement_ratio),
  ]);
  rows.push([]);
  rows.push(['团队难度分布', formatDifficultyDist(detail.team_difficulty_dist)]);
  rows.push(['团队协作模式占比', formatModeDist(detail.team_mode_dist)]);
  rows.push([]);
  rows.push(['团队百分位基线']);
  appendPercentileRows(rows, detail.team_percentiles);
  rows.push([]);
  rows.push(['Watchlist 汇总']);
  const ws = detail.watchlist_summary;
  if (ws && Object.keys(ws).length) {
    for (const [flag, codes] of Object.entries(ws)) {
      rows.push([WATCHLIST_META[flag]?.label ?? flag, (codes || []).join('、')]);
    }
  } else {
    rows.push(['（无触发）']);
  }

  const sheet = XLSX.utils.aoa_to_sheet(rows);
  sheet['!cols'] = [{ wch: 22 }, { wch: 52 }];
  return sheet;
}

function buildUsersSheet(detail: AnalysisReportDetail): XLSX.WorkSheet {
  const headers = [
    '展示名',
    '工号',
    '样本不足',
    '会话数',
    '协作时长(h)',
    'Token 总量',
    'Git 提交数',
    '新增行数',
    '难度分布(会话数)',
    '平均难度',
    '高难度占比',
    ...CAPABILITY_DIMENSIONS.map((c) => c.label),
    '协作模式占比',
    '每小时 commit',
    '每千 token 行数',
    '回滚率',
    '高难度 commit 占比',
    '综合分',
    '综合分位',
    '分位段',
    'Watchlist',
    '典型会话 ID',
  ];

  const body = detail.users.map((u) => [
    u.user_display,
    u.user_code,
    u.insufficient_data ? '是' : '否',
    u.session_count,
    fmtNum(u.ai_active_hours, 2),
    u.total_tokens,
    u.ai_commit_count,
    u.ai_lines_added,
    formatDifficultyDist(u.difficulty_dist),
    fmtNum(u.avg_difficulty, 2),
    pctStr(u.high_difficulty_ratio),
    ...CAPABILITY_DIMENSIONS.map((c) => {
      const v = u[c.key];
      return v == null ? '' : fmtNum(v, 2);
    }),
    formatModeDist(u.mode_dist),
    u.ai_commits_per_active_hour == null ? '' : fmtNum(u.ai_commits_per_active_hour, 3),
    u.ai_lines_per_1k_token == null ? '' : fmtNum(u.ai_lines_per_1k_token, 3),
    pctStr(u.commit_revert_rate),
    pctStr(u.high_difficulty_commit_ratio),
    u.composite_score == null ? '' : fmtNum(u.composite_score, 1),
    u.composite_percentile == null ? '' : fmtNum(u.composite_percentile, 1),
    u.composite_bucket ? BUCKET_META[u.composite_bucket].label : '',
    watchlistLabels(u.watchlist_flags),
    (u.highlight_session_ids ?? []).join(','),
  ]);

  const sheet = XLSX.utils.aoa_to_sheet([headers, ...body]);
  sheet['!cols'] = headers.map((_, i) => ({ wch: i === 0 ? 18 : i === 8 || i === 20 ? 36 : 14 }));
  return sheet;
}

export function exportAnalysisReportExcel(detail: AnalysisReportDetail): void {
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, buildSummarySheet(detail), '报告概要');
  XLSX.utils.book_append_sheet(wb, buildUsersSheet(detail), '员工明细');
  const fname = `分析报告_${detail.window_from}_${detail.window_to}.xlsx`;
  XLSX.writeFile(wb, fname);
}
