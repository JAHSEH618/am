/**
 * 还原 Git core.quotepath 输出的 C 风格路径（\345\221… 八进制转义）。
 * 兼容历史库中已存的带引号 / 转义路径。
 * gz
 */
export function decodeGitQuotedPath(path: string): string {
  let s = path.trim();
  if (!s) {
    return s;
  }

  // 去掉外层引号（可能来自 git 或 JSON 双重转义后的字面量）
  for (let i = 0; i < 2; i++) {
    if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
      s = s.slice(1, -1);
    } else {
      break;
    }
  }

  // Git 八进制转义：\345 → 字节
  const decoded = s.replace(/\\([0-7]{1,3})/g, (_, oct: string) =>
    String.fromCharCode(parseInt(oct, 8)),
  );

  return decoded.replace(/\\"/g, '"').replace(/\\\\/g, '\\');
}
