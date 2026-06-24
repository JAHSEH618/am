// Kimi Code wire.jsonl 扫描与解析。
//
// 信任度：token_usage / 时间戳 = 高（来自官方 wire 协议 + ccusage 实测字段）；
// user/assistant/tool 文本提取 = 启发式，待真实 wire/context 样本校准（见 handleHeuristicMessage）。
// gz
package kimicode

import (
	"bufio"
	"encoding/json"
	"os"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// wireEnvelope 兼容两类行：首行 metadata（顶层 type）与普通行（timestamp + message{type,payload}）。
type wireEnvelope struct {
	Type            string          `json:"type"`             // metadata 行
	ProtocolVersion json.RawMessage `json:"protocol_version"` // metadata 行
	Timestamp       float64         `json:"timestamp"`        // 普通行，unix float 秒
	Message         *wireMessage    `json:"message"`
}

type wireMessage struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

// tokenUsage 对应 StatusUpdate.payload.token_usage（累计值）。
type tokenUsage struct {
	InputOther         int64 `json:"input_other"`
	Output             int64 `json:"output"`
	InputCacheRead     int64 `json:"input_cache_read"`
	InputCacheCreation int64 `json:"input_cache_creation"`
}

func parseFile(path string) (*parsedSession, int64, error) {
	sid, agent := deriveSessionID(path)
	ps := &parsedSession{SessionID: sid, AgentName: agent, WirePath: path}
	consumed, _, err := common.ScanJSONL(path, 0, 4<<20, makeLineHandler(ps))
	if err != nil {
		return nil, 0, err
	}
	return ps, consumed, nil
}

func parseFileIncremental(path string, offset int64, base *parsedSession) (*parsedSession, int64, error) {
	ps := base.clone()
	consumed, _, err := common.ScanJSONL(path, offset, 4<<20, makeLineHandler(ps))
	if err != nil {
		return nil, 0, err
	}
	return ps, consumed, nil
}

func makeLineHandler(ps *parsedSession) common.ScanLine {
	return func(line []byte, _ int64) bool {
		var env wireEnvelope
		if err := json.Unmarshal(line, &env); err != nil {
			return true
		}
		// metadata 行：取协议版本，无 message。
		if env.Message == nil {
			if env.Type == "metadata" && len(env.ProtocolVersion) > 0 {
				ps.Version = "wire/" + strings.Trim(string(env.ProtocolVersion), `"`)
			}
			return true
		}

		ts := fromUnixFloat(env.Timestamp)
		if !ts.IsZero() {
			if ps.StartedAt.IsZero() {
				ps.StartedAt = ts
			}
			ps.LastActivity = ts
		}
		handleWireMessage(ps, env.Message.Type, env.Message.Payload, ts)
		return true
	}
}

// handleWireMessage 处理单条 wire 消息；SubagentEvent 递归其嵌套 message。
func handleWireMessage(ps *parsedSession, msgType string, payload json.RawMessage, ts time.Time) {
	switch msgType {
	case "StatusUpdate":
		var sp struct {
			TokenUsage *tokenUsage `json:"token_usage"`
		}
		if json.Unmarshal(payload, &sp) == nil && sp.TokenUsage != nil {
			applyTokenUsage(ps, sp.TokenUsage, ts)
		}
	case "SubagentEvent":
		// 子智能体事件：尝试取出嵌套的 message{type,payload} 递归，token 仍归并到本会话。
		var nested struct {
			Message *wireMessage `json:"message"`
			Event   *wireMessage `json:"event"`
		}
		if json.Unmarshal(payload, &nested) == nil {
			if nested.Message != nil {
				handleWireMessage(ps, nested.Message.Type, nested.Message.Payload, ts)
			} else if nested.Event != nil {
				handleWireMessage(ps, nested.Event.Type, nested.Event.Payload, ts)
			}
		}
	default:
		handleHeuristicMessage(ps, msgType, payload, ts)
	}
}

func applyTokenUsage(ps *parsedSession, tu *tokenUsage, ts time.Time) {
	totalIn := tu.InputOther + tu.InputCacheRead + tu.InputCacheCreation
	totalOut := tu.Output
	dIn := totalIn - ps.prevIn
	if dIn < 0 {
		dIn = 0
	}
	dOut := totalOut - ps.prevOut
	if dOut < 0 {
		dOut = 0
	}
	if (dIn != 0 || dOut != 0) && !ts.IsZero() {
		ref := "token_usage:" + ts.Format(time.RFC3339Nano)
		common.AppendActivityDelta(&ps.ActivityDeltas, ts, ref, common.ActivitySourceTokenCount, dIn, dOut, 0)
	}
	ps.prevIn = totalIn
	ps.prevOut = totalOut
	ps.InputTokens = totalIn
	ps.OutputTokens = totalOut
	ps.CacheRead = tu.InputCacheRead + tu.InputCacheCreation
}

// handleHeuristicMessage 对非 token 的 wire 消息做"常见形态"启发式提取。
//
// ⚠️ 待校准：kimi-code 的 wire/context 消息字段官方文档未枚举（MEDIUM 置信）。这里按
// role/content/text/name/tool 等通用字段尽力提取，拿到真实样本后据实收敛——拿不到字段时
// 安全跳过，绝不影响 token / 活跃度 这些高置信信号。
func handleHeuristicMessage(ps *parsedSession, msgType string, payload json.RawMessage, ts time.Time) {
	var m struct {
		Role    string `json:"role"`
		Content string `json:"content"`
		Text    string `json:"text"`
		Name    string `json:"name"`
		Tool    string `json:"tool"`
		Model   string `json:"model"`
	}
	_ = json.Unmarshal(payload, &m)
	if m.Model != "" && ps.Model == "" {
		ps.Model = m.Model
	}
	text := strings.TrimSpace(firstNonEmpty(m.Content, m.Text))
	lower := strings.ToLower(msgType + " " + m.Role)

	switch {
	case strings.Contains(lower, "tool"):
		name := common.NormalizeToolName(firstNonEmpty(m.Tool, m.Name))
		if name != "" {
			ps.appendTool(name, ts)
			ps.BubbleStatus = common.BubbleExecTool
			ps.CurrentTool = name
		}
	case strings.Contains(lower, "user"):
		ps.UserMessages++
		ps.BubbleStatus = common.BubbleThinking
		ps.CurrentTool = ""
		if text != "" {
			ps.appendMessage("user", text, "", ts)
			common.AppendActivityDelta(&ps.ActivityDeltas, ts, "user:"+ts.Format(time.RFC3339Nano),
				common.ActivitySourceUserTurn, 0, 0, 1)
		}
	case strings.Contains(lower, "assistant"), strings.Contains(lower, "agent"),
		strings.Contains(lower, "model"), strings.Contains(lower, "response"),
		strings.Contains(lower, "completion"):
		ps.AssistantMessages++
		ps.BubbleStatus = common.BubbleWaitingUser
		ps.CurrentTool = ""
		if text != "" {
			ps.appendMessage("assistant", text, "", ts)
		}
	}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func fromUnixFloat(sec float64) time.Time {
	if sec <= 0 {
		return time.Time{}
	}
	s := int64(sec)
	ns := int64((sec - float64(s)) * 1e9)
	return time.Unix(s, ns)
}

// indexEntry 对应 session_index.jsonl 每行。
type indexEntry struct {
	SessionID string `json:"sessionId"`
	WorkDir   string `json:"workDir"`
}

// loadWorkdirs 读所有 session_index.jsonl，得到 sessionId → workDir 映射。
func loadWorkdirs(paths []string) map[string]string {
	out := make(map[string]string)
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 0, 64<<10), 1<<20)
		for sc.Scan() {
			var e indexEntry
			if json.Unmarshal(sc.Bytes(), &e) == nil && e.SessionID != "" && e.WorkDir != "" {
				out[e.SessionID] = e.WorkDir
			}
		}
		_ = f.Close()
	}
	return out
}

// loadTitle 读 state.json 的 title 字段（best-effort）。
func loadTitle(statePath string) string {
	data, err := os.ReadFile(statePath)
	if err != nil {
		return ""
	}
	var st struct {
		Title string `json:"title"`
	}
	if json.Unmarshal(data, &st) != nil {
		return ""
	}
	return strings.TrimSpace(st.Title)
}
