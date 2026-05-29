// Package logger 提供 aiwatchd 的本地滚动日志能力。
//
// 对应 AIWatch 设计文档 §18.3，日志只记录运行态信息，禁止记录：
//
//	prompt、源码、文件内容、密钥明文
//
// v2.9 起强制开启"文件日志"：
//
//	macOS    ~/Library/Logs/aiwatchd/aiwatchd.log
//	Windows  %LOCALAPPDATA%\aiwatchd\logs\aiwatchd.log
//	Linux    ${XDG_STATE_HOME:-~/.local/state}/aiwatchd/aiwatchd.log
//
// 设计取舍：
//   - 旧版 logger 只 dump 到 os.Stderr，mac/Linux 走 launchd / systemd 兜底没问题，
//     但 Windows 通过 ScheduledTask / HKCU Run 启动的进程没人接 stderr，员工掉线后
//     运维只能靠"问员工要 status 输出"瞎猜，根因往往是启动崩溃。
//   - 只写文件，不写 stderr（避免 launchd 把 stderr 重定向到同一文件导致重复行；
//     查看日志请 tail 默认路径或 `aiwatchd status` 提示的路径）。
//   - 简单 size rotate：单文件 5MB 触发轮转，保留 1 个 .old，总占用 ≤ 10MB。
//     不引入 lumberjack 等第三方依赖，避免依赖复杂化（aiwatchd 体积敏感）。
//   - Init 失败时（磁盘满 / 权限禁止）静默退化为"只写 stderr"，绝不阻断 daemon 启动。
// gz
package logger

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// std 是默认 logger。Init 之前只写 stderr（启动极早阶段）；Init 之后只写文件。
var std = log.New(os.Stderr, "[aiwatchd] ", log.LstdFlags|log.Lmicroseconds)

// debugEnabled 是否打印 DEBUG 日志。默认关闭，避免 idle 心跳刷屏。
// 通过环境变量 AM_LOG_LEVEL=debug 打开（运维 / 开发排查用）。
var debugEnabled = strings.EqualFold(os.Getenv("AM_LOG_LEVEL"), "debug")

const (
	// maxLogBytes 单个日志文件触发轮转的字节数。
	// 5MB 是经验值：一次 tick 在 idle 下产 ~0 字节、busy 下 ~1KB，
	// 即使 24/7 上报 + 异常 spam，5MB 也能撑 ~30 天，足够诊断窗口。
	maxLogBytes = 5 * 1024 * 1024

	// retainOldFiles 保留几个 .old.N 滚动文件。1 即当前 + 1 个备份，总占用 ≤ 10MB。
	retainOldFiles = 1
)

// fileWriter 是封装好的"按 size rotate 的文件 writer"。
// 持有自己的 mu，可被并发 Write 调用（log 包内部已经有自己的锁，这里再加一层是为了
// rotate 期间的原子性 —— 切文件瞬间不能让别的 goroutine 写半截）。
type fileWriter struct {
	mu   sync.Mutex
	path string
	f    *os.File
	size int64
}

// init 打开文件、stat 一下当前 size，失败返回 err（由 Init 决定是否降级）。
func newFileWriter(path string) (*fileWriter, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("mkdir log dir: %w", err)
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, fmt.Errorf("open log file: %w", err)
	}
	st, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("stat log file: %w", err)
	}
	return &fileWriter{path: path, f: f, size: st.Size()}, nil
}

func (w *fileWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		return len(p), nil
	}
	if w.size+int64(len(p)) > maxLogBytes {
		w.rotateLocked()
	}
	n, err := w.f.Write(p)
	w.size += int64(n)
	return n, err
}

// rotateLocked 把当前文件 rename 为 .old.1，再开新文件。失败不致命，
// 失败时退化为"继续往老文件写"，下次再试。
func (w *fileWriter) rotateLocked() {
	if w.f == nil {
		return
	}
	_ = w.f.Close()
	w.f = nil

	for i := retainOldFiles; i >= 1; i-- {
		src := w.path + fmt.Sprintf(".%d", i)
		dst := w.path + fmt.Sprintf(".%d", i+1)
		if i == retainOldFiles {
			_ = os.Remove(dst)
		}
		_ = os.Rename(src, dst)
	}
	_ = os.Rename(w.path, w.path+".1")

	f, err := os.OpenFile(w.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		f, err = os.OpenFile(w.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			return
		}
	}
	w.f = f
	w.size = 0
}

// DefaultLogPath 返回当前平台的默认日志路径。
//
// macOS：与 install.sh 中 launchd 的 StandardOutPath / StandardErrorPath 相同（均为本文件），
// 避免再单独产生 out.log / err.log。
func DefaultLogPath() (string, error) {
	switch runtime.GOOS {
	case "darwin":
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, "Library", "Logs", "aiwatchd", "aiwatchd.log"), nil
	case "windows":
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			if home, err := os.UserHomeDir(); err == nil {
				base = filepath.Join(home, "AppData", "Local")
			} else {
				base = `C:\ProgramData`
			}
		}
		return filepath.Join(base, "aiwatchd", "logs", "aiwatchd.log"), nil
	default:
		if state := os.Getenv("XDG_STATE_HOME"); state != "" {
			return filepath.Join(state, "aiwatchd", "aiwatchd.log"), nil
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, ".local", "state", "aiwatchd", "aiwatchd.log"), nil
	}
}

// Init 把 std logger 切换到仅写文件。
//
// 调用时机：在 main 入口、所有业务代码之前调用一次。
//
// 行为：
//   - 成功打开文件 → 后续日志只写入该文件。
//   - 任何环节失败（路径解析 / mkdir / open）→ 静默回退到仅 stderr 模式，
//     返回 err 让 main 自己决定是否打印一行 warn（一般不打印，避免脏化前台输出）。
//   - 返回的 path 是实际写入的文件路径（失败时为空串），便于 main 在启动横幅里告诉员工
//     "本进程日志在 X"，运维报修时按这个路径捞。
func Init() (path string, err error) {
	p, err := DefaultLogPath()
	if err != nil {
		return "", err
	}
	w, err := newFileWriter(p)
	if err != nil {
		return "", err
	}
	std.SetOutput(w)
	return p, nil
}

// Debugf 输出 DEBUG 级别日志（默认静默，AM_LOG_LEVEL=debug 时打印）。
//
// 用于"无业务变化"的成功心跳：
//   - reporter idle 周期上报（events=0 messages=0）
//   - gitlog 周期扫描没有新提交
//
// 这样员工/运维默认只会看到真正有意义的 INFO/WARN/ERROR，避免 10s 一条 INFO 误以为出错。
func Debugf(format string, args ...any) {
	if !debugEnabled {
		return
	}
	std.Printf("DEBUG "+format, args...)
}

// Infof 输出 INFO 级别日志。
func Infof(format string, args ...any) {
	std.Printf("INFO  "+format, args...)
}

// Warnf 输出 WARN 级别日志。
func Warnf(format string, args ...any) {
	std.Printf("WARN  "+format, args...)
}

// Errorf 输出 ERROR 级别日志。
func Errorf(format string, args ...any) {
	std.Printf("ERROR "+format, args...)
}
