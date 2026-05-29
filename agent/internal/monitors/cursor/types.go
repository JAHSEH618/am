// Package cursor 的 JSON 结构体，对应 state.vscdb 中 cursorDiskKV 存储格式。
// bubbleData 结构参考 lazyagent（MIT）对 Cursor 的逆向结论。
// gz
package cursor

// bubbleData 对应键 bubbleId:<sessionId>:<bubbleId> 的 value JSON。
type bubbleData struct {
	Type                int                 `json:"type"` // 1 = user, 2 = assistant / tool
	Text                string              `json:"text"`
	Thinking            thinkingData        `json:"thinking"`
	CreatedAt           string              `json:"createdAt"`
	WorkspaceUris       []string            `json:"workspaceUris"`
	TokenCount          bubbleTokens        `json:"tokenCount"`
	ToolFormerData      toolFormer          `json:"toolFormerData"`
	Images              []bubbleImage       `json:"images"`
	Context             bubbleContext       `json:"context"`
	AttachedCodeChunks  []attachedCodeChunk `json:"attachedCodeChunks"`
}

// bubbleContext 气泡附带的上下文（截图常在 selectedImages，顶层 images 可能为空）。
type bubbleContext struct {
	SelectedImages []bubbleImage `json:"selectedImages"`
}

// bubbleImage 用户粘贴/上传的截图元数据。
// 字节位置：优先 path（Cursor 常给绝对路径）；否则 workspaceStorage/<hash>/images/<uuid>-*.png。
type bubbleImage struct {
	UUID      string `json:"uuid"`
	Path      string `json:"path"`
	Dimension struct {
		Width  int `json:"width"`
		Height int `json:"height"`
	} `json:"dimension"`
}

// attachedCodeChunk @ 文件 / 选区附带的代码片段。
type attachedCodeChunk struct {
	RelativeWorkspacePath string   `json:"relativeWorkspacePath"`
	StartLineNumber       int      `json:"startLineNumber"`
	Lines                 []string `json:"lines"`
}

type bubbleTokens struct {
	InputTokens  int `json:"inputTokens"`
	OutputTokens int `json:"outputTokens"`
}

// toolFormer 工具调用相关数据：
//
//	rawArgs / params 是工具入参（assistant 视角的输出）
//	result 是工具响应（assistant 视角的输入）
//	内容直接来自 Cursor，路径常带绝对路径，用于工程根推断
type toolFormer struct {
	Name    string `json:"name"`
	Status  string `json:"status"`
	RawArgs string `json:"rawArgs"`
	Params  string `json:"params"`
	Result  string `json:"result"`
}

// thinkingData Cursor assistant bubble 的"思考"区块，按需取 text 估算 output token。
type thinkingData struct {
	Text string `json:"text"`
}

