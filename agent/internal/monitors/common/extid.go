// external_message_id 拼接与规范化。
//
// gz
package common

import (
	"crypto/sha1"
	"encoding/hex"
	"strconv"
	"strings"
	"time"
)

// SyntheticMessageID 为没有原生 message id 的 Provider（hermes / openharness 等）合成稳定的
// ExternalMessageID，避免服务端 ai_session_message 因为 external_message_id 全是 NULL 导致
// (ai_session_id, external_message_id) 唯一索引在 NULL 上退化，每个 reporter tick 都把同一批
// "最近消息"重复 INSERT —— 这个问题已经在 v1.6 之前的 hermes / openharness 上被坐实
// （hermes 实测 726 行只有 2 条不同内容；openharness 实测 1196 行只有 4 条不同内容）。
//
// 形态：<sessionID>:<discriminator>:<role>:<contentHash8>
//
//   - sessionID      会话主键，确保跨 session 不冲突
//   - discriminator  调用方提供的稳定区分量；hermes 推荐用 microsecond 级 timestamp，
//     openharness 推荐用 raw.messages 数组下标
//   - role           user / assistant / tool / thinking ...，同 ts 多角色兜底
//   - contentHash8   原文 SHA-1 的前 4 字节（8 hex 字符），同 ts 同 role 不同内容兜底
//
// 长度 ≤ 128（符合 ai_session_message.external_message_id 列定义）。
func SyntheticMessageID(sessionID, discriminator, role, content string) string {
	sum := sha1.Sum([]byte(content))
	tail := hex.EncodeToString(sum[:4])
	var b strings.Builder
	b.Grow(len(sessionID) + len(discriminator) + len(role) + len(tail) + 3)
	b.WriteString(sessionID)
	b.WriteByte(':')
	b.WriteString(discriminator)
	b.WriteByte(':')
	b.WriteString(role)
	b.WriteByte(':')
	b.WriteString(tail)
	return b.String()
}

// SyntheticMessageIDByTime 用 ts 的微秒 epoch 作为 discriminator 的便捷封装，
// 适用于 hermes.messages 这种每条 message 都自带稳定 timestamp、却没有原生 id 的场景。
func SyntheticMessageIDByTime(sessionID string, ts time.Time, role, content string) string {
	return SyntheticMessageID(sessionID, strconv.FormatInt(ts.UnixMicro(), 10), role, content)
}
