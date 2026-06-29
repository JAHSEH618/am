//go:build darwin || linux

package procutil

import "os/exec"

// 非 Windows：控制台型子进程不会弹窗，无需处理。
func applyHidden(cmd *exec.Cmd) {}
