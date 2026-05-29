/** 横条图 category 轴左侧留白：按最长标签估算，避免 y 轴命令名被截断。 */
export function categoryAxisGridLeft(
  labels: string[],
  opts?: { min?: number; max?: number; charWidth?: number },
): number {
  const min = opts?.min ?? 100;
  const max = opts?.max ?? 320;
  const charWidth = opts?.charWidth ?? 6.5;
  const longest = labels.reduce((m, s) => Math.max(m, s.length), 0);
  return Math.min(max, Math.max(min, longest * charWidth + 24));
}
