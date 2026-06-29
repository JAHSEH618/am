//go:build darwin || linux

// 非 Windows 平台无需脱离控制台：launchd / systemd --user 通过 SIGTERM 管理 daemon
// 生命周期，不存在"控制台窗口关闭误杀后台进程"的问题，且 SIGTERM 正是要响应的停止信号。
//
// gz
package main

func detachConsole() {}
