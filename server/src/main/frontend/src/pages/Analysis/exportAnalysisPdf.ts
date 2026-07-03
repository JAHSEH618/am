import type { Content, TDocumentDefinitions } from 'pdfmake/interfaces';
import type { AnalysisReportDetail, AnalysisReportUser, CompositeGrade } from '@/api/types';
import { indigo, semantic, accent } from '@/styles/tokens';
import { downloadPdf, docSkeleton, sectionTitle, kvTable, PDF_COLORS } from '@/lib/pdf/pdfCore';
import { renderChartToDataUrl } from '@/lib/pdf/chartImage';
import { BUCKET_META, GRADE_META, WATCHLIST_META, MODE_META } from './constants';
import { employeeName } from '@/utils/format';

const GRADES = ['S', 'A', 'B', 'C', 'D'] as const;

function pct(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? '—' : `${(v * 100).toFixed(1)}%`;
}

function num(v: number | null | undefined, digits = 1): string {
  return v == null || Number.isNaN(v) ? '—' : Number(v).toFixed(digits);
}

function gradeLabel(u: AnalysisReportUser): string {
  if (u.composite_grade) return GRADE_META[u.composite_grade].label;
  if (u.composite_bucket) return BUCKET_META[u.composite_bucket].label;
  return u.insufficient_data ? '样本不足' : '—';
}

function chartImages(detail: AnalysisReportDetail): Content {
  const gradeDist: Partial<Record<CompositeGrade, number>> = detail.team_grade_dist || {};
  const diffDist = detail.team_difficulty_dist || { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 };
  const modeDist: Partial<Record<string, number>> = detail.team_mode_dist || {};
  const gradeColors = [accent.purple.base, semantic.success.base, accent.blue.base, semantic.warning.base, semantic.error.base];
  const base = { grid: { left: 40, right: 12, top: 30, bottom: 28 }, yAxis: { type: 'value' as const } };
  const gradeImg = renderChartToDataUrl({
    ...base,
    title: { text: '等级分布', textStyle: { fontSize: 13 } },
    xAxis: { type: 'category' as const, data: [...GRADES] },
    series: [{ type: 'bar' as const, data: GRADES.map((g) => gradeDist[g] ?? 0), label: { show: true, position: 'top' as const }, itemStyle: { color: (p: { dataIndex: number }) => gradeColors[p.dataIndex] } }],
  }, 360, 240);
  const diffImg = renderChartToDataUrl({
    ...base,
    title: { text: '难度分布', textStyle: { fontSize: 13 } },
    xAxis: { type: 'category' as const, data: ['1', '2', '3', '4', '5'] },
    series: [{ type: 'bar' as const, data: ['1', '2', '3', '4', '5'].map((k) => diffDist[k as keyof typeof diffDist] ?? 0), label: { show: true, position: 'top' as const }, itemStyle: { color: (p: { dataIndex: number }) => [indigo[200], indigo[300], indigo[400], indigo[500], indigo[600]][p.dataIndex] } }],
  }, 360, 240);
  const modeKeys = Object.keys(modeDist);
  const modeImg = renderChartToDataUrl({
    title: { text: '协作模式占比', textStyle: { fontSize: 13 } },
    series: [{ type: 'pie' as const, radius: ['30%', '60%'], label: { formatter: '{b}: {d}%' }, data: modeKeys.map((k) => ({ name: MODE_META[k]?.label ?? k, value: modeDist[k] ?? 0 })) }],
  }, 360, 240);
  return {
    columns: [
      { image: gradeImg, width: 160 },
      { image: diffImg, width: 160 },
      { image: modeImg, width: 160 },
    ],
    columnGap: 8,
    margin: [0, 4, 0, 0],
  };
}

function narrativeSection(detail: AnalysisReportDetail): Content[] {
  const n = detail.team_narrative;
  if (!n) return [];
  const parts: [string, string][] = [
    ['总体', n.overview], ['亮点', n.highlights], ['风险', n.risks], ['建议', n.recommendations],
  ];
  return [
    sectionTitle('团队总评（AI 生成）'),
    ...parts.map(([t, v]): Content => ({ text: [{ text: `${t}：`, bold: true }, v], margin: [0, 2, 0, 4] })),
  ];
}

function usersTable(detail: AnalysisReportDetail): Content {
  const header = ['员工', '等级', '综合分', '会话', '协作(h)', '完成率', '高难完成', '回滚率', 'commit/h', 'Watchlist'];
  const body = detail.users.map((u) => [
    employeeName(u.user_display, u.user_code),
    gradeLabel(u),
    num(u.composite_score),
    String(u.session_count),
    num(u.ai_active_hours),
    pct(u.completion_rate ?? null),
    pct(u.high_difficulty_ratio),
    pct(u.commit_revert_rate),
    u.ai_commits_per_active_hour == null ? '—' : num(u.ai_commits_per_active_hour, 2),
    (u.watchlist_flags ?? []).map((f) => WATCHLIST_META[f]?.label ?? f).join('、') || '—',
  ]);
  return {
    table: {
      headerRows: 1,
      widths: [78, 52, 36, 30, 40, 40, 44, 40, 46, '*'],
      body: [header.map((h) => ({ text: h, style: 'th' })), ...body],
    },
    layout: {
      hLineWidth: (i: number) => (i <= 1 ? 0.8 : 0.4),
      vLineWidth: () => 0,
      hLineColor: () => PDF_COLORS.border,
      fillColor: (rowIndex: number) => (rowIndex > 0 && rowIndex % 2 === 0 ? '#fafbfd' : null),
      paddingTop: () => 4,
      paddingBottom: () => 4,
    },
    fontSize: 8,
  };
}

export async function exportAnalysisReportPdf(detail: AnalysisReportDetail): Promise<void> {
  const subtitle = `${detail.window_from} ~ ${detail.window_to}`;
  const watchRows: [string, string][] = Object.entries(detail.watchlist_summary ?? {}).map(
    ([flag, codes]) => [WATCHLIST_META[flag]?.label ?? flag, (codes || []).map((c) => employeeName(c, c)).join('、')],
  );
  const def: TDocumentDefinitions = {
    ...docSkeleton('AIWatch 分析报告', subtitle),
    info: { title: `AIWatch 分析报告 ${subtitle}` },
    content: [
      // 封面
      { text: 'AIWatch 团队分析报告', style: 'cover', margin: [0, 140, 0, 0] },
      { text: subtitle, style: 'coverSub' },
      { text: `生成于 ${detail.completed_time ?? detail.created_time}`, style: 'coverSub' },
      kvTable([
        ['活跃员工数', String(detail.active_user_count ?? '—')],
        ['总会话数', String(detail.total_session_count ?? '—')],
        ['协作总时长（小时）', String(detail.total_active_hours ?? '—')],
        ['Git 提交数', String(detail.total_ai_commit ?? '—')],
        ['审计覆盖', `${detail.audited_count}/${detail.total_count}`],
        ['双 judge 不一致率', pct(detail.judge_disagreement_ratio)],
      ]),
      { text: '', pageBreak: 'after' },
      // 正文
      ...narrativeSection(detail),
      sectionTitle('团队分布'),
      chartImages(detail),
      sectionTitle('Watchlist'),
      watchRows.length ? kvTable(watchRows) : { text: '（无触发）', style: 'sub' },
      sectionTitle('全员明细'),
      usersTable(detail),
    ],
  };
  await downloadPdf(def, `分析报告_${detail.window_from}_${detail.window_to}.pdf`);
}
