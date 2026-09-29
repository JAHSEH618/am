import dayjs, { type Dayjs } from 'dayjs';
import type { PeopleDailyPoint } from '../../api/types';

/** 报告窗口最多补多少天：整自然周报告是 7 天，留出自定义窗口余量，同时防异常入参画出海量空格子。 */
const MAX_FILL_DAYS = 92;

export interface WindowHours {
  dates: string[];
  hours: number[];
  /** 服务端确实返回了该窗口内的日汇总行（区别于"全是补出来的 0"）。 */
  hasRows: boolean;
}

/**
 * 把员工日汇总（daily_timeline，后端只返回**有 daily_summary 行**的日子，且已按日期升序）
 * 装配成报告窗口 [windowFrom, min(windowTo, today)] 的逐日序列，缺失日补 0。
 *
 * 为什么要按窗口补：分析报告是「某个自然周」的报告，图应当铺满这个时间范围；此前直接拿返回行画图，
 * 窗口内没有行（日汇总还在后台重算 / 该员工当周没有协作）就整块退化成"暂无 timeline"，
 * 而报告上方的 KPI 明明是有窗口数据的，读起来像数据丢了。
 * 终点不越过今天（未来日没有数据，只会画一排空格子——见 utils/timeWindow.ts）。
 */
export function fillWindowHours(
  windowFrom: string,
  windowTo: string,
  points: PeopleDailyPoint[] | null | undefined,
  today: Dayjs = dayjs(),
): WindowHours {
  const byDate = new Map<string, number>();
  for (const p of points ?? []) {
    byDate.set(p.date, Math.round(((p.ai_active_seconds_union ?? 0) / 3600) * 10) / 10);
  }

  const from = dayjs(windowFrom).startOf('day');
  const to = dayjs(windowTo).startOf('day');
  if (!from.isValid() || !to.isValid()) {
    // 窗口串解析不了时退回"有什么画什么"，别因为一个坏日期把图整个吞掉。
    const dates = [...byDate.keys()].sort();
    return { dates, hours: dates.map((d) => byDate.get(d) ?? 0), hasRows: dates.length > 0 };
  }
  const end = to.isAfter(today, 'day') ? today.startOf('day') : to;

  const dates: string[] = [];
  const hours: number[] = [];
  let hasRows = false;
  for (let d = from, n = 0; !d.isAfter(end, 'day') && n < MAX_FILL_DAYS; d = d.add(1, 'day'), n++) {
    const key = d.format('YYYY-MM-DD');
    if (byDate.has(key)) hasRows = true;
    dates.push(key);
    hours.push(byDate.get(key) ?? 0);
  }
  return { dates, hours, hasRows };
}
