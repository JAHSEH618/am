// Package foreground 负责前台窗口检测与窗口标题获取。
//
// 对应设计文档 7.2 / 7.3 节。不同操作系统由 build tag 提供平台实现，
// 通用结果见 WindowInfo。
// gz
package foreground

// WindowInfo 描述当前前台窗口的信息。
type WindowInfo struct {
	// IsCursor 为 true 表示前台应用是 Cursor。
	IsCursor bool
	// Title 是当前前台窗口的原始标题，用于辅助识别项目。
	Title string
	// Degraded 表示因平台限制（如 Wayland）无法准确获取前台窗口。
	Degraded bool
}

// Detector 抽象前台窗口检测行为。
type Detector interface {
	Detect() (WindowInfo, error)
}
