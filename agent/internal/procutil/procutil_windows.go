//go:build windows

package procutil

import (
	"os/exec"
	"syscall"
)

// createNoWindow == CREATE_NO_WINDOW (0x08000000)：子进程是控制台程序但不为其分配控制台窗口。
// 标准库 syscall 未导出此常量，这里本地定义。
const createNoWindow = 0x08000000

// applyHidden 合并进已有 SysProcAttr（保留如 CREATE_NEW_PROCESS_GROUP 等既有标志）。
func applyHidden(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.HideWindow = true
	cmd.SysProcAttr.CreationFlags |= createNoWindow
}
