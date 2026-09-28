import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';

dayjs.extend(isoWeek);

/**
 * 控制台时间窗的公共件：默认窗口、快捷预设、禁选未来日期。
 *
 * 规则：窗口终点不越过今天。未来日期没有数据，只会在热力图 / 趋势图里画出一排空格子，
 * 表格日均也要额外解释"不含未到日期"。此前本周窗口取周一 ~ 周日，周一打开页面就显示 6 天空白。
 * 分析报告页生成的是整自然周报告，不走这里。
 */

/** 本自然周至今：周一 ~ 今天。 */
export function weekToDate(): [Dayjs, Dayjs] {
  return [dayjs().isoWeekday(1).startOf('day'), dayjs().startOf('day')];
}

/** 最近 n 天（含今天）。 */
export function lastNDays(n: number): [Dayjs, Dayjs] {
  return [dayjs().subtract(n - 1, 'day').startOf('day'), dayjs().startOf('day')];
}

/** RangePicker 快捷预设。按调用时刻计算，跨天打开的页面也不会拿到昨天的"今天"。 */
export function rangePresets(): { label: string; value: [Dayjs, Dayjs] }[] {
  return [
    { label: '今天', value: lastNDays(1) },
    { label: '本周', value: weekToDate() },
    { label: '近 7 天', value: lastNDays(7) },
    { label: '近 30 天', value: lastNDays(30) },
    { label: '本月', value: [dayjs().startOf('month'), dayjs().startOf('day')] },
  ];
}

/** RangePicker disabledDate：今天之后不可选。 */
export function disableFutureDate(d: Dayjs): boolean {
  return d.isAfter(dayjs(), 'day');
}
