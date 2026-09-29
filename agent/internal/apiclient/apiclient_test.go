package apiclient

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestIsServerBusy(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{&HTTPError{StatusCode: http.StatusServiceUnavailable}, true},
		{&HTTPError{StatusCode: http.StatusTooManyRequests}, true},
		{fmt.Errorf("wrapped: %w", &HTTPError{StatusCode: http.StatusServiceUnavailable}), true},
		{&ServerError{Code: CodeServerBusy}, true},
		{&HTTPError{StatusCode: http.StatusInternalServerError}, false},
		{&ServerError{Code: CodeAgentNotFound}, false},
		{fmt.Errorf("dial tcp: connection refused"), false},
		{nil, false},
	}
	for _, c := range cases {
		if got := IsServerBusy(c.err); got != c.want {
			t.Errorf("IsServerBusy(%v) = %v want %v", c.err, got, c.want)
		}
	}
}

// report-commits 与 /report 同一签名 / 压缩约定：HMAC 对线上字节（gzip 后）计算，Content-Encoding 随行；
// 模拟服务端 AgentSignatureFilter + CachedBodyHttpServletRequest 的校验与解压顺序。
func TestSignedEndpoints_GzipBodySignedOverWireBytes(t *testing.T) {
	const secret = "s3cret"
	plain := []byte(`{"agent_id":"a1","commits":[{"commit_hash":"abc"}]}`)
	var gz bytes.Buffer
	zw := gzip.NewWriter(&gz)
	_, _ = zw.Write(plain)
	_ = zw.Close()

	for _, tc := range []struct {
		name     string
		endpoint string
		body     []byte
		encoding string
	}{
		{"report-commits raw", "/api/v1/agent/report-commits", plain, ""},
		{"report-commits gzip", "/api/v1/agent/report-commits", gz.Bytes(), "gzip"},
		{"report gzip", "/api/v1/agent/report", gz.Bytes(), "gzip"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				wire, _ := io.ReadAll(r.Body)
				mac := hmac.New(sha256.New, []byte(secret))
				mac.Write(wire)
				mac.Write([]byte(r.Header.Get("X-Agent-Ts")))
				mac.Write([]byte(r.Header.Get("X-Agent-Nonce")))
				if r.URL.Path != tc.endpoint || hex.EncodeToString(mac.Sum(nil)) != r.Header.Get("X-Agent-Sign") {
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				if got := r.Header.Get("Content-Encoding"); got != tc.encoding {
					t.Errorf("Content-Encoding=%q want %q", got, tc.encoding)
				}
				decoded := wire
				if strings.EqualFold(r.Header.Get("Content-Encoding"), "gzip") {
					zr, err := gzip.NewReader(bytes.NewReader(wire))
					if err != nil {
						w.WriteHeader(http.StatusBadRequest)
						return
					}
					decoded, _ = io.ReadAll(zr)
				}
				if !bytes.Equal(decoded, plain) {
					t.Errorf("decoded body=%q want %q", decoded, plain)
				}
				_, _ = w.Write([]byte(`{"code":0,"message":"ok","data":{}}`))
			}))
			defer srv.Close()

			c := New(srv.URL, 5*time.Second)
			var err error
			if tc.endpoint == "/api/v1/agent/report" {
				_, err = c.Report(context.Background(), "a1", secret, tc.body, tc.encoding)
			} else {
				_, err = c.ReportCommits(context.Background(), "a1", secret, tc.body, tc.encoding)
			}
			if err != nil {
				t.Fatalf("request failed: %v", err)
			}
		})
	}
}

func TestReport_503IsServerBusy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"code":50301,"message":"agent ingest busy, retry next tick"}`))
	}))
	defer srv.Close()

	c := New(srv.URL, 5*time.Second)
	_, err := c.Report(context.Background(), "agent", "secret", []byte(`{}`), "")
	if !IsServerBusy(err) {
		t.Fatalf("503 response must be classified as busy, got %v", err)
	}
}

func TestParseRetryAfter(t *testing.T) {
	now := time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)
	cases := []struct {
		name string
		in   string
		want time.Duration
	}{
		{"empty", "", 0},
		{"seconds", "30", 30 * time.Second},
		{"seconds with spaces", "  120 ", 120 * time.Second},
		{"zero", "0", 0},
		{"negative", "-5", 0},
		{"garbage", "soon", 0},
		{"http date in future", now.Add(90 * time.Second).UTC().Format(http.TimeFormat), 90 * time.Second},
		{"http date in past", now.Add(-time.Minute).UTC().Format(http.TimeFormat), 0},
		{"absurdly large seconds are clamped, not overflowed", "99999999999999", 365 * 24 * time.Hour},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseRetryAfter(tc.in, now); got != tc.want {
				t.Errorf("parseRetryAfter(%q) = %s, want %s", tc.in, got, tc.want)
			}
		})
	}
}

// 各种"服务端没处理成功"的响应：Report 必须返回可被 reporter 正确分类的错误，且带上 Retry-After。
func TestReport_FailureClassification(t *testing.T) {
	cases := []struct {
		name         string
		status       int
		retryAfter   string
		body         string
		wantBusy     bool
		wantTooLarge bool
		wantRetry    time.Duration
	}{
		{"503 busy", 503, "7", `{"code":50301}`, true, false, 7 * time.Second},
		{"429", 429, "15", ``, true, false, 15 * time.Second},
		{"500 not busy", 500, "", `oops`, false, false, 0},
		{"502 from nginx", 502, "", `<html>bad gateway</html>`, false, false, 0},
		{"504 from nginx with retry-after", 504, "20", `<html>timeout</html>`, false, false, 20 * time.Second},
		{"413 too large (nginx page)", 413, "", `<html>too large</html>`, false, true, 0},
		{"413 + business code 41301 (server guard)", 413, "", `{"code":41301,"message":"payload too large"}`, false, true, 0},
		{"non-413 status carrying business code 41301", 400, "", `{"code":41301,"message":"payload too large"}`, false, true, 0},
		{"HTTP 200 + business code 41301", 200, "", `{"code":41301,"message":"payload too large"}`, false, true, 0},
		{"503 + 50301 + Retry-After 30 (readiness gate / bulkhead)", 503, "30", `{"code":50301,"message":"busy"}`, true, false, 30 * time.Second},
		{"non-503 status carrying business code 50301", 500, "", `{"code":50301}`, true, false, 0},
		{"401", 401, "", `{"code":10001}`, false, false, 0},
		{"200 + business code 50000 (legacy pool timeout)", 200, "", `{"code":50000,"message":"pool"}`, false, false, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if tc.retryAfter != "" {
					w.Header().Set("Retry-After", tc.retryAfter)
				}
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			_, err := New(srv.URL, 5*time.Second).Report(context.Background(), "a", "s", []byte(`{}`), "")
			if err == nil {
				t.Fatal("expected an error")
			}
			if got := IsServerBusy(err); got != tc.wantBusy {
				t.Errorf("IsServerBusy = %v, want %v (err=%v)", got, tc.wantBusy, err)
			}
			if got := IsPayloadTooLarge(err); got != tc.wantTooLarge {
				t.Errorf("IsPayloadTooLarge = %v, want %v", got, tc.wantTooLarge)
			}
			if got := RetryAfter(err); got != tc.wantRetry {
				t.Errorf("RetryAfter = %s, want %s", got, tc.wantRetry)
			}
		})
	}
}

func TestRetryAfter_NonHTTPErrors(t *testing.T) {
	for _, err := range []error{nil, fmt.Errorf("dial tcp: refused"), &ServerError{Code: CodeServerBusy}} {
		if got := RetryAfter(err); got != 0 {
			t.Errorf("RetryAfter(%v) = %s, want 0", err, got)
		}
	}
	if IsPayloadTooLarge(&HTTPError{StatusCode: 500}) || IsPayloadTooLarge(nil) {
		t.Error("only HTTP 413 is payload-too-large")
	}
}

func TestNew_DefaultTimeoutIsNinetySeconds(t *testing.T) {
	if got := New("http://x", 0).http.Timeout; got != 90*time.Second {
		t.Errorf("default timeout = %s, want 90s (was 15m before the 2026-09 incident fix)", got)
	}
	if got := New("http://x", 7*time.Second).http.Timeout; got != 7*time.Second {
		t.Errorf("explicit timeout = %s, want 7s", got)
	}
}

// 请求超时也是"服务端没处理成功"：返回 error（不是 HTTPError / ServerError），调用方按通用失败走退避。
func TestReport_TimeoutIsPlainError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(300 * time.Millisecond):
		}
	}))
	defer srv.Close()
	_, err := New(srv.URL, 50*time.Millisecond).Report(context.Background(), "a", "s", []byte(`{}`), "")
	if err == nil {
		t.Fatal("expected timeout error")
	}
	if IsServerBusy(err) || IsPayloadTooLarge(err) || IsAgentNotFound(err) {
		t.Errorf("timeout must not be classified as busy/too-large/agent-not-found: %v", err)
	}
}
