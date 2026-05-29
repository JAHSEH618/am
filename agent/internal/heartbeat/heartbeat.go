// Package heartbeat 负责与管理平台的通信：注册、心跳、下线。
//
// 对应设计文档 11.1 / 11.2 / 11.3 节 HTTP API。
// gz
package heartbeat

import "time"

// Payload 是单次心跳上报的负载，对应 POST /api/v1/agent/heartbeat 的 body。
type Payload struct {
	UserCode         string    `json:"userCode"`
	HostHash         string    `json:"hostHash"`
	CursorRunning    bool      `json:"cursorRunning"`
	CursorForeground bool      `json:"cursorForeground"`
	WindowTitle      string    `json:"windowTitle,omitempty"`
	ProjectName      string    `json:"projectName,omitempty"`
	ProjectPathHash  string    `json:"projectPathHash,omitempty"`
	RepoURL          string    `json:"repoUrl,omitempty"`
	BranchName       string    `json:"branchName,omitempty"`
	AgentVersion     string    `json:"agentVersion"`
	BinaryHash       string    `json:"binaryHash,omitempty"`
	EventTime        time.Time `json:"eventTime"`
}

// RegisterRequest 对应 POST /api/v1/agent/register 的 body。
type RegisterRequest struct {
	UserCode     string `json:"userCode"`
	Hostname     string `json:"hostname"`
	OSType       string `json:"osType"`
	AgentVersion string `json:"agentVersion"`
	MachineHash  string `json:"machineHash"`
	BinaryHash   string `json:"binaryHash,omitempty"`
}

// RegisterResponse 对应 POST /api/v1/agent/register 的返回值。
type RegisterResponse struct {
	AgentID                  string `json:"agentId"`
	AgentSecret              string `json:"agentSecret"`
	HeartbeatIntervalSeconds int    `json:"heartbeatIntervalSeconds"`
	MaxHeartbeatGapSeconds   int    `json:"maxHeartbeatGapSeconds"`
	ServerTime               string `json:"serverTime"`
}
