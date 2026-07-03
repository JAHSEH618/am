import type { Content, TDocumentDefinitions } from 'pdfmake/interfaces';
import type { AnalysisReportDetail, AnalysisReportUser } from '@/api/types';
import { indigo, ink } from '@/styles/tokens';
import { downloadPdf, docSkeleton, sectionTitle, kvTable } from '@/lib/pdf/pdfCore';
import { renderChartToDataUrl } from '@/lib/pdf/chartImage';
import { BUCKET_META, CAPABILITY_DIMENSIONS, GRADE_META, MODE_META, WATCHLIST_META, CAP_TEAM_KEY } from './constants';
import { employeeName } from '@/utils/format';

function pct(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? '—' : `${(v * 100).toFixed(1)}%`;
}

function radarImage(report: AnalysisReportDetail, user: AnalysisReportUser): string {
  const caps = report.team_capability_percentiles;
  const teamP50 = CAPABILITY_DIMENSIONS.map((d) => {
    const p50 = caps?.[CAP_TEAM_KEY[d.key]]?.p50;
    return p50 == null || Number.isNaN(p50) ? 3 : p50;
  });
  const userRadar = CAPABILITY_DIMENSIONS.map((d) => Number(user[d.key] ?? 0));
  return renderChartToDataUrl({
    legend: { data: ['该员工', '团队 P50'], top: 0 },
    radar: { indicator: CAPABILITY_DIMENSIONS.map((d) => ({ name: d.label, max: 5 })), radius: 90 },
    series: [{
      type: 'radar' as const,
      areaStyle: { opacity: 0.2 },
      data: [
        { name: '该员工', value: userRadar, itemStyle: { color: indigo[600] } },
        { name: '团队 P50', value: teamP50, itemStyle: { color: ink[3] } },
      ],
    }],
  }, 420, 300);
}

function breakdownContent(user: AnalysisReportUser): Content[] {
  const b = user.composite_breakdown;
  if (!b) return [];
  return [
    sectionTitle('得分构成'),
    kvTable(b.dimensions.map((d): [string, string] =>
      [`${d.label}（权重 ${(d.weight * 100).toFixed(0)}%）`, `${(d.score * 100).toFixed(1)} / 100`])),
    { text: `原始分 ${b.raw} → 收缩后 ${b.final}（团队均值 ${b.team_mean ?? '—'}，样本权重 ${b.shrink_weight}）`, style: 'sub', margin: [0, 6, 0, 0] },
  ];
}

function narrativeContent(user: AnalysisReportUser): Content[] {
  const n = user.narrative;
  if (!n) return [];
  const parts: [string, string][] = [
    ['水平定位', n.level_summary], ['典型表现', n.evidence],
    ['优势', n.strengths], ['短板', n.weaknesses], ['发展建议', n.suggestions],
  ];
  return [
    sectionTitle('AI 评语'),
    ...parts.map(([t, v]): Content => ({ text: [{ text: `${t}：`, bold: true }, v], margin: [0, 2, 0, 4] })),
  ];
}

export async function exportUserReportPdf(report: AnalysisReportDetail, user: AnalysisReportUser): Promise<void> {
  const name = employeeName(user.user_display, user.user_code);
  const subtitle = `${report.window_from} ~ ${report.window_to}`;
  const grade = user.composite_grade
    ? GRADE_META[user.composite_grade].label
    : user.composite_bucket ? BUCKET_META[user.composite_bucket].label : '—';
  const modeStr = Object.entries(user.mode_dist ?? {})
    .map(([k, v]) => `${MODE_META[k]?.label ?? k} ${pct(v as number)}`)
    .join('，') || '—';
  const highlights = (user.highlight_sessions ?? [])
    .map((h) => `#${h.session_id} 难度${h.difficulty}｜${MODE_META[h.mode]?.label ?? h.mode}｜${h.reason}`);
  const def: TDocumentDefinitions = {
    ...docSkeleton(`个人分析报告 · ${name}`, subtitle),
    info: { title: `个人分析报告 ${name} ${subtitle}` },
    content: [
      { text: `个人分析报告 · ${name}`, style: 'cover', fontSize: 20, margin: [0, 0, 0, 2] },
      { text: `${subtitle} · 等级 ${grade}${user.composite_confidence === 'low' ? '（低置信度）' : ''} · 综合分 ${user.composite_score ?? '—'}`, style: 'coverSub', margin: [0, 0, 0, 8] },
      ...narrativeContent(user),
      sectionTitle('能力雷达（vs 团队 P50）'),
      { image: radarImage(report, user), width: 300, alignment: 'center' },
      ...breakdownContent(user),
      sectionTitle('关键统计'),
      kvTable([
        ['会话数', String(user.session_count)],
        ['协作时长（小时）', String(user.ai_active_hours)],
        ['完成率', pct(user.completion_rate ?? null)],
        ['放弃率', pct(user.abandoned_rate ?? null)],
        ['平均难度', String(user.avg_difficulty ?? '—')],
        ['高难度占比', pct(user.high_difficulty_ratio)],
        ['每小时 commit', String(user.ai_commits_per_active_hour ?? '—')],
        ['回滚率', pct(user.commit_revert_rate)],
        ['重试次数', String(user.retry_count ?? '—')],
        ['工具调用次数', String(user.tool_call_count ?? '—')],
        ['协作模式', modeStr],
        ['Watchlist', (user.watchlist_flags ?? []).map((f) => WATCHLIST_META[f]?.label ?? f).join('、') || '—'],
      ]),
      ...(highlights.length ? [sectionTitle('典型会话'), { ul: highlights, fontSize: 9 } as Content] : []),
    ],
  };
  await downloadPdf(def, `个人报告_${name}_${report.window_from}_${report.window_to}.pdf`);
}
