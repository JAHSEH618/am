// Package kimicode 实现 Moonshot Kimi Code CLI（MoonshotAI/kimi-code，TS/Node；非 Claude/Codex fork，
// 自有格式）的本地会话采集。
//
// 数据源（best token source = wire.jsonl）：
//
//	~/.kimi-code/                         （$KIMI_CODE_HOME 覆盖；当前世代）
//	├── session_index.jsonl               每行 {sessionId, sessionDir, workDir}
//	└── sessions/<workDirKey>/<sessionId>/
//	    ├── state.json                    title / approval / plan_mode / subagent_instances ...
//	    ├── context.jsonl                 会话正文（字段名待真实样本校准）
//	    └── agents/main/wire.jsonl        主线 wire 协议事件流（+ agents/<subagentId>/wire.jsonl）
//
//	~/.kimi/                              （$KIMI_SHARE_DIR 覆盖；legacy kimi-cli，扁平布局）
//	└── sessions/<md5(workdir)>/<sessionId>/{state.json, context.jsonl, wire.jsonl}
//
// wire.jsonl 每行：{"timestamp": <unix float 秒>, "message":{"type":..., "payload":{...}}}
// （首行为 {"type":"metadata","protocol_version":...}）。token 在 type=StatusUpdate 的
// payload.token_usage.{input_other,output,input_cache_read,input_cache_creation}，并需递归 SubagentEvent。
//
// 跨平台：三平台都在用户主目录下（~/.kimi-code、~/.kimi），不区分 GOOS。
//
// 实现思路与 codex 一致：FileCache + ScanJSONL 增量 + 并行解析；按 <sessionId> 目录把
// main + 各 subagent 的 wire.jsonl 归并为一个会话。
// gz
package kimicode

import (
	"os"
	"path/filepath"
)

// TypeCode 必须与服务端 monitor_target.type_code 对齐。
const TypeCode = "kimicode"

// sessionRoots 返回所有要扫描 wire.jsonl 的会话根目录（当前世代优先，附带 legacy）。
//
// 优先级：$AM_KIMICODE_DIR（开发/测试覆盖，直接当作数据根）> $KIMI_CODE_HOME / ~/.kimi-code
// + legacy $KIMI_SHARE_DIR / ~/.kimi。去重后返回存在的目录。
func sessionRoots() []string {
	var roots []string
	add := func(base string) {
		if base == "" {
			return
		}
		roots = append(roots, filepath.Join(base, "sessions"))
	}

	if d := os.Getenv("AM_KIMICODE_DIR"); d != "" {
		add(d)
		return roots
	}

	home, _ := os.UserHomeDir()
	if d := os.Getenv("KIMI_CODE_HOME"); d != "" {
		add(d)
	} else if home != "" {
		add(filepath.Join(home, ".kimi-code"))
	}
	if d := os.Getenv("KIMI_SHARE_DIR"); d != "" {
		add(d)
	} else if home != "" {
		add(filepath.Join(home, ".kimi"))
	}
	return roots
}

// anyRootExists 用于 IsInstalled：任一会话根存在即视为已安装。
func anyRootExists() bool {
	for _, r := range sessionRoots() {
		if info, err := os.Stat(r); err == nil && info.IsDir() {
			return true
		}
	}
	return false
}

// sessionIndexPaths 返回各数据根下的 session_index.jsonl（与 sessionRoots 一一对应的上级目录）。
func sessionIndexPaths() []string {
	var out []string
	for _, r := range sessionRoots() {
		out = append(out, filepath.Join(filepath.Dir(r), "session_index.jsonl"))
	}
	return out
}

// deriveSessionID 从 wire.jsonl 路径推出 (sessionID, agentName)。
//
//	新布局：.../<sessionId>/agents/<agentName>/wire.jsonl
//	legacy：.../<sessionId>/wire.jsonl                      （agentName 记为 main）
func deriveSessionID(path string) (sessionID, agentName string) {
	dir := filepath.Dir(path) // .../agents/<agentName>  或  .../<sessionId>(legacy)
	base := filepath.Base(dir)
	parent := filepath.Dir(dir)
	if filepath.Base(parent) == "agents" {
		return filepath.Base(filepath.Dir(parent)), base
	}
	return base, "main"
}

// stateJSONPath 返回某 session 目录下的 state.json（用于取 title）。
// 由 wire.jsonl 路径反推 <sessionId> 目录：新布局为 agents 的祖父目录，legacy 为 wire.jsonl 的父目录。
func stateJSONPath(wirePath string) string {
	dir := filepath.Dir(wirePath)
	parent := filepath.Dir(dir)
	if filepath.Base(parent) == "agents" {
		return filepath.Join(filepath.Dir(parent), "state.json")
	}
	return filepath.Join(dir, "state.json")
}
