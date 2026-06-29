// Package procutil 提供跨平台子进程辅助：在 Windows 上隐藏子进程的控制台窗口。
//
// 背景（v1.0.19）：daemon 自 1.0.18 起 FreeConsole 脱离控制台以求常驻；没有控制台后，
// 它再 exec 出来的控制台型子进程（git / powershell）会被 Windows 各分配一个新控制台窗口，
// 表现为"时不时弹一个 cmd 窗口又自动关闭"。给这些子进程加 CREATE_NO_WINDOW 即可彻底消除。
// 其它平台无此问题，applyHidden 为 no-op。
//
// gz
package procutil

import "os/exec"

// Hidden 让 cmd 在 Windows 上以无窗口方式启动（CREATE_NO_WINDOW + HideWindow），其它平台为 no-op。
// 返回同一个 *exec.Cmd，便于链式调用：
//
//	out, err := procutil.Hidden(exec.Command("git", "-C", dir, "status")).Output()
func Hidden(cmd *exec.Cmd) *exec.Cmd {
	applyHidden(cmd)
	return cmd
}
