// Package security 负责 Agent 端的 HMAC-SHA256 签名与 nonce 生成。
//
// 与服务端 com.am.server.agent.security.HmacUtil 保持一致：
//
//	canonical = body || ts || nonce
//	sign      = HMAC-SHA256(agent_secret, canonical) → 小写 hex
//
// 头：
//
//	X-Agent-Id    agent_id
//	X-Agent-Ts    Unix 毫秒时间戳
//	X-Agent-Nonce 16 字节随机 hex
//	X-Agent-Sign  上面的 sign
// gz
package security

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"time"
)

// SignatureHeaders 描述签名需要在 HTTP header 中携带的四个字段。
type SignatureHeaders struct {
	AgentID   string // X-Agent-Id
	Timestamp string // X-Agent-Ts (Unix milliseconds)
	Nonce     string // X-Agent-Nonce
	Signature string // X-Agent-Sign
}

// Sign 计算签名头。secret 为 /register 返回的 agent_secret。
func Sign(agentID, secret string, body []byte) (SignatureHeaders, error) {
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce, err := newNonce()
	if err != nil {
		return SignatureHeaders{}, err
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	mac.Write([]byte(ts))
	mac.Write([]byte(nonce))
	return SignatureHeaders{
		AgentID:   agentID,
		Timestamp: ts,
		Nonce:     nonce,
		Signature: hex.EncodeToString(mac.Sum(nil)),
	}, nil
}

func newNonce() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}
