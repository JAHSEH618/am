// sentstate.go：把"最近一次成功发送的空闲会话指纹"落盘（state/sent_sessions.json），进程重启后加载。
//
// <p>背景：unchanged.go 的指纹表原先只在内存里，进程一重启第一个 tick 就把 48 小时窗口内的全部会话重发一遍。
// 发版自更新、崩溃拉起、服务端事故后一批 agent 一起重启时，这正是叠在恢复洪峰上的额外重负载
// （每个会话服务端一个事务 + 5~7 条查询）。
//
// <p>只落盘、只加载能真正起作用的那部分：
//   - 只存 status==idle 的条目：非 idle 会话每个 tick 都必须发，它的指纹重启后没有用；
//   - 只存 15 分钟（unchangedResyncInterval）内的条目：更老的重启后本来就会被强制重发，存了也白存；
//     sentAt 原样落盘，所以"15 分钟强制重发"的语义重启前后不变；
//   - 条目数有上限（sentStateMaxEntries，取最近发送的），文件因此天然有界，过期条目每次写盘都被清掉；
//   - 带 schema 版本：sessionFingerprint 覆盖的字段变了（新字段要让所有会话重发一次）就必须把它加一；
//     文件损坏 / 版本不符一律当作"没有这个文件"，退化为旧行为（首 tick 全量上报），从不报错。
//
// <p>写入是原子的（tmp + rename），且只在"落盘视图"（key→指纹）真的变化时才写，稳态下（会话都已 idle、
// 指纹不变）不产生磁盘写入。
//
// gz
package reporter

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"time"

	"github.com/am/aiwatch-agent/internal/logger"
)

const (
	sentStateFileName = "sent_sessions.json"
	// sentStateSchema 与 sessionFingerprint 覆盖的字段绑定：改了指纹的字段集合，就把它加一，
	// 让旧文件失效（否则新字段的变化会被旧指纹"掩盖"至多一个 resync 周期）。
	sentStateSchema = 1
	// sentStateMaxEntries 落盘 / 加载的条目上限（重度用户 48h 内几十个会话，留足余量）。
	sentStateMaxEntries = 4000
)

type sentStateFile struct {
	SchemaVersion int                       `json:"schema_version"`
	Sessions      map[string]sentStateEntry `json:"sessions"`
}

type sentStateEntry struct {
	// Fingerprint 用十六进制字符串存 uint64：避免 JSON 数字在别的工具里被当作 float64 丢精度。
	Fingerprint string    `json:"fp"`
	SentAt      time.Time `json:"sent_at"`
}

// persistableSent 从内存指纹表里挑出值得落盘的条目：idle、且仍在强制重发周期内；超过上限只留最近发送的。
func persistableSent(sent map[string]sentSession, now time.Time) map[string]sentSession {
	out := make(map[string]sentSession, len(sent))
	for k, v := range sent {
		if v.idle && now.Sub(v.sentAt) < unchangedResyncInterval {
			out[k] = v
		}
	}
	if len(out) <= sentStateMaxEntries {
		return out
	}
	keys := make([]string, 0, len(out))
	for k := range out {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return out[keys[i]].sentAt.After(out[keys[j]].sentAt) })
	for _, k := range keys[sentStateMaxEntries:] {
		delete(out, k)
	}
	return out
}

// loadSentState 读取落盘的指纹表。任何异常（文件不存在 / 损坏 / 版本不符 / 条目非法）都返回空表——
// 这只会让首个 tick 多发一些会话，与没有本功能时的行为一致。已过期的条目在这里就被丢掉。
func loadSentState(path string, now time.Time) map[string]sentSession {
	out := make(map[string]sentSession)
	if path == "" {
		return out
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 {
		return out
	}
	var f sentStateFile
	if err := json.Unmarshal(data, &f); err != nil || f.SchemaVersion != sentStateSchema {
		return out
	}
	for k, e := range f.Sessions {
		fp, err := strconv.ParseUint(e.Fingerprint, 16, 64)
		if err != nil || e.SentAt.IsZero() || e.SentAt.After(now.Add(time.Minute)) {
			continue // 非法 / 来自"未来"（时钟回拨过）的条目不可信
		}
		if now.Sub(e.SentAt) >= unchangedResyncInterval {
			continue
		}
		out[k] = sentSession{fingerprint: fp, sentAt: e.SentAt, idle: true}
	}
	if len(out) > sentStateMaxEntries {
		out = persistableSent(out, now)
	}
	return out
}

// saveSentState 原子写盘（tmp + rename）。
func saveSentState(path string, view map[string]sentSession) error {
	if path == "" {
		return nil
	}
	f := sentStateFile{SchemaVersion: sentStateSchema, Sessions: make(map[string]sentStateEntry, len(view))}
	for k, v := range view {
		f.Sessions[k] = sentStateEntry{Fingerprint: strconv.FormatUint(v.fingerprint, 16), SentAt: v.sentAt}
	}
	data, err := json.Marshal(f)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename sent state: %w", err)
	}
	return nil
}

// persistSentIfChanged 在指纹表提交之后调用：落盘视图（key→指纹）与上次落盘的不同才写盘。
// 只比较 key 与指纹、不比较 sentAt：sentAt 只影响"重启后是否强制重发"，落后一点只会更早重发，无害；
// 若把它算进去，每 15 分钟的强制重发都会触发一次写盘。
func (r *Reporter) persistSentIfChanged() {
	if r.sentPath == "" {
		return
	}
	view := persistableSent(r.sent, r.clock())
	if sameSentView(view, r.sentSaved) {
		return
	}
	if err := saveSentState(r.sentPath, view); err != nil {
		// 落盘只是重启后的优化，失败不影响上报；不更新 sentSaved，下次提交时重试。
		logger.Warnf("persist sent-session fingerprints failed (only affects post-restart dedupe): %v", err)
		return
	}
	r.sentSaved = make(map[string]uint64, len(view))
	for k, v := range view {
		r.sentSaved[k] = v.fingerprint
	}
}

func sameSentView(view map[string]sentSession, saved map[string]uint64) bool {
	if len(view) != len(saved) {
		return false
	}
	for k, v := range view {
		if fp, ok := saved[k]; !ok || fp != v.fingerprint {
			return false
		}
	}
	return true
}

// initSentState 在 New 里调用：确定落盘路径并加载上次的指纹表。
func (r *Reporter) initSentState(dir string, now time.Time) {
	r.sentPath = filepath.Join(dir, sentStateFileName)
	r.sent = loadSentState(r.sentPath, now)
	r.sentSaved = make(map[string]uint64, len(r.sent))
	for k, v := range r.sent {
		r.sentSaved[k] = v.fingerprint
	}
}
