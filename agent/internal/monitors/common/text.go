// 文本截断与空白处理。
//
// gz
package common

import "unicode/utf8"

// Truncate 把字符串截到 n 个 rune，超出添加省略号。
//
// 比 string[:n] 安全：不会切断 utf-8 字符（中文/emoji），符合"消息原文不被破坏"的诉求。
func Truncate(s string, n int) string {
	if n <= 0 || s == "" {
		return s
	}
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	cnt := 0
	for i := range s {
		cnt++
		if cnt > n {
			return s[:i] + "…"
		}
	}
	return s
}

// EstimateTokens 用 chars/4 启发式估算 LLM token 数（与主流 BPE 编码近似）。
//
// 适用于 Provider 没有原生 token 计数的场景（Cursor / Claude assistant text-only bubble 等）。
func EstimateTokens(chars int64) int64 {
	if chars <= 0 {
		return 0
	}
	return chars / 4
}
