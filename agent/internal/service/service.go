// Package service 负责把 aiwatchd 安装为后台服务。
//
// 对应 AIWatch 设计文档 §5.3：
//
//	macOS    LaunchAgent（~/Library/LaunchAgents/com.company.aiwatchd.plist）
//	Windows  Windows Service（第一版可先用开机启动项兜底）
//	Linux    systemd user service
// gz
package service

// Installer 抽象后台服务安装行为。
type Installer interface {
	Install() error
	Uninstall() error
	Status() (running bool, err error)
}
