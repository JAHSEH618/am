//go:build windows

// Windows：让常驻 daemon 脱离启动它的控制台。
//
// 背景（v1.0.18 修复 "装完冒一次就永久离线"）：
//   cmdStart 用 signal.NotifyContext(os.Interrupt, syscall.SIGTERM) 监听停止信号，
//   而 Windows 把"关闭控制台窗口 / 注销 / 关机"统一映射成 SIGTERM。安装时计划任务把
//   `aiwatchd start` 拉起后，安装那个 PowerShell 窗口一关就发出 CTRL_CLOSE，被 daemon
//   当成 SIGTERM 优雅退出（exit 0）→ 计划任务把"干净退出"视为成功、不触发崩溃自重启
//   → 只能等下一次 AtLogon → 表现为"装完冒一次 active 就再不上报、永久离线"。
//
// FreeConsole 后本进程不再隶属任何控制台，上述控制台事件不再投递到本进程，daemon 得以
// 真正后台常驻。daemon 的日志走文件（internal/logger），不依赖控制台 stdout/stderr，
// 脱离后无影响；进程本就无控制台时 FreeConsole 返回错误，忽略即可。
//
// gz
package main

import "syscall"

func detachConsole() {
	_, _, _ = syscall.NewLazyDLL("kernel32.dll").NewProc("FreeConsole").Call()
}
