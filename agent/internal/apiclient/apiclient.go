// Package apiclient 是 aiwatchd 与 AIWatch 服务端的 HTTP 客户端。
//
// 它对应设计文档 §4 接口表：
//
//	POST /api/v1/agent/register   注册（无 HMAC）
//	POST /api/v1/agent/report     通用上报（带 HMAC）
//
// 上报响应统一遵循 R<T> 三段式：{ code, message, data }。
// gz
package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/security"
)

// 业务错误码（与 server com.am.server.common.ErrorCode 对齐）。
//
// <p>仅列出 client 需要识别并触发自愈逻辑的码；其他错误码统一视为"通用失败"，
// 由 reporter / outbox 走重试路径处理。
const (
	// CodeInvalidSignature 10001：HMAC 不匹配 / 头缺失 / body 损坏。
	CodeInvalidSignature = 10001
	// CodeNonceReplay 10002：nonce 已被使用。理论不会发生，发生通常是时钟漂移引起的"伪重放"。
	CodeNonceReplay = 10002
	// CodeTimestampOutOfWindow 10003：±300s 时间窗外。
	CodeTimestampOutOfWindow = 10003
	// CodeAgentNotFound 10004：server 端找不到此 agent_id 或 status != ACTIVE。
	// 触发条件示例：DBA 误删 / 开发期重置数据库 / 设备被人工标 INACTIVE。
	// client 收到这个码必须**清掉本地 agent_id+secret 并重新 register**，否则永远 fail。
	CodeAgentNotFound = 10004
)

// ServerError 是 server 在 R 三段式 envelope 中返回的业务错误（HTTP 200 但 code != 0）。
//
// <p>reporter / registrar 用 errors.As 提取它来判断"是否需要触发重新注册"等自愈动作。
// HTTP 层面的失败（拨号失败 / 5xx / 4xx）走另一条路径，不会被包成 ServerError。
type ServerError struct {
	Code    int
	Message string
}

func (e *ServerError) Error() string {
	return fmt.Sprintf("server returned code=%d message=%s", e.Code, e.Message)
}

// IsAgentNotFound 是 reporter 自愈时常用的快捷判断：err 链中是否存在 CodeAgentNotFound。
func IsAgentNotFound(err error) bool {
	var se *ServerError
	return errors.As(err, &se) && se.Code == CodeAgentNotFound
}

// Client 是带签名能力的 Agent HTTP 客户端。
type Client struct {
	baseURL string
	http    *http.Client
}

// New 创建一个 Client。baseURL 末尾不带斜杠。
//
// timeout 由 config.json report_timeout_ms 控制，默认 15 分钟（见 config.DefaultReportTimeoutMs）。
// bootstrap 首次 tick 可能 collect 大量 session/message，server ingest 耗时可达数分钟；
// 普通增量 tick 仍用同一超时，实际 RT 通常远小于上限。
func New(baseURL string, timeout time.Duration) *Client {
	if timeout <= 0 {
		timeout = 15 * time.Minute
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    &http.Client{Timeout: timeout},
	}
}

// RegisterRequest 与服务端 AgentRegisterRequest 一一对应（snake_case JSON）。
type RegisterRequest struct {
	UserCode string `json:"user_code"`
	// v2.3 员工自助注册：employee 表无此 user_code 时由服务端按这两个字段创建一条 ACTIVE 记录；
	// 已存在员工不会被覆盖。
	UserName     string `json:"user_name,omitempty"`
	Department   string `json:"department,omitempty"`
	HostHash     string `json:"host_hash"`
	Hostname     string `json:"hostname,omitempty"`
	OSType       string `json:"os_type,omitempty"`
	AgentVersion string `json:"agent_version,omitempty"`
	BinaryHash   string `json:"binary_hash,omitempty"`
	LocalIP      string `json:"local_ip,omitempty"`
	GitUserName  string `json:"git_user_name,omitempty"`
	GitUserEmail string `json:"git_user_email,omitempty"`
}

// RegisterResponse 与服务端 AgentRegisterResponse 一一对应。
type RegisterResponse struct {
	AgentID           string                `json:"agent_id"`
	AgentSecret       string                `json:"agent_secret"`
	ReportIntervalMs  int64                 `json:"report_interval_ms"`
	TimestampWindowMs int64                 `json:"timestamp_window_ms"`
	MonitorPolicy     *config.MonitorPolicy `json:"monitor_policy,omitempty"`
}

// Register 调 /api/v1/agent/register。
func (c *Client) Register(ctx context.Context, req RegisterRequest) (*RegisterResponse, error) {
	var resp RegisterResponse
	if err := c.doJSON(ctx, http.MethodPost, "/api/v1/agent/register", req, nil, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ReportSummary 与服务端 AgentReportService.ReportSummary 对应。
type ReportSummary struct {
	Sessions      int                   `json:"sessions"`
	Events        int                   `json:"events"`
	Messages      int                   `json:"messages"`
	Active        bool                  `json:"active"`
	MonitorPolicy *config.MonitorPolicy `json:"monitor_policy,omitempty"`
}

// CommitReportSummary 与服务端 GitCommitIngestService 返回值对齐。
type CommitReportSummary struct {
	Inserted                int `json:"inserted"`
	Duplicates              int `json:"duplicates"`
	IgnoredIdentityMismatch int `json:"ignored_identity_mismatch"`
	DetailsUpdated          int `json:"details_updated"`
}

// ReportCommits 调 /api/v1/agent/report-commits（v2.2 Phase 3）。
// 与 Report 相同的 HMAC 签名链路，但走独立 endpoint 避免污染会话上报通道。
func (c *Client) ReportCommits(ctx context.Context, agentID, agentSecret string, body []byte) (*CommitReportSummary, error) {
	headers, err := security.Sign(agentID, agentSecret, body)
	if err != nil {
		return nil, fmt.Errorf("sign: %w", err)
	}
	hdr := map[string]string{
		"X-Agent-Id":    headers.AgentID,
		"X-Agent-Ts":    headers.Timestamp,
		"X-Agent-Nonce": headers.Nonce,
		"X-Agent-Sign":  headers.Signature,
	}
	var summary CommitReportSummary
	if err := c.doRaw(ctx, http.MethodPost, "/api/v1/agent/report-commits", body, hdr, &summary); err != nil {
		return nil, err
	}
	return &summary, nil
}

// Report 调 /api/v1/agent/report。
//
// body 必须是已经序列化好（必要时已经 gzip 压缩）的字节，因为 HMAC 签名需要对**线上字节**计算，
// 序列化 / 压缩在调用方完成可避免"序列化两次产生不同字节"的微妙问题。
//
// contentEncoding 留空时按原始 JSON 上送；传 "gzip" 时表示 body 已经是 gzip 字节，
// 会自动加 Content-Encoding 头让 server 端的 GzipDecodingFilter 解压。
func (c *Client) Report(ctx context.Context, agentID, agentSecret string, body []byte, contentEncoding string) (*ReportSummary, error) {
	headers, err := security.Sign(agentID, agentSecret, body)
	if err != nil {
		return nil, fmt.Errorf("sign: %w", err)
	}
	hdr := map[string]string{
		"X-Agent-Id":    headers.AgentID,
		"X-Agent-Ts":    headers.Timestamp,
		"X-Agent-Nonce": headers.Nonce,
		"X-Agent-Sign":  headers.Signature,
	}
	if contentEncoding != "" {
		hdr["Content-Encoding"] = contentEncoding
	}
	var summary ReportSummary
	if err := c.doRaw(ctx, http.MethodPost, "/api/v1/agent/report", body, hdr, &summary); err != nil {
		return nil, err
	}
	return &summary, nil
}

// envelope 是服务端 R<T> 三段式响应。
type envelope struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

func (c *Client) doJSON(ctx context.Context, method, path string, in any, headers map[string]string, out any) error {
	var body []byte
	if in != nil {
		buf, err := json.Marshal(in)
		if err != nil {
			return fmt.Errorf("marshal request: %w", err)
		}
		body = buf
	}
	return c.doRaw(ctx, method, path, body, headers, out)
}

func (c *Client) doRaw(ctx context.Context, method, path string, body []byte, headers map[string]string, out any) error {
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("http %d: %s", resp.StatusCode, truncate(string(respBody), 256))
	}
	var env envelope
	if err := json.Unmarshal(respBody, &env); err != nil {
		return fmt.Errorf("decode envelope: %w (body=%s)", err, truncate(string(respBody), 256))
	}
	if env.Code != 0 {
		return &ServerError{Code: env.Code, Message: env.Message}
	}
	if out != nil && len(env.Data) > 0 && string(env.Data) != "null" {
		if err := json.Unmarshal(env.Data, out); err != nil {
			return fmt.Errorf("decode data: %w", err)
		}
	}
	return nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "...(truncated)"
}
