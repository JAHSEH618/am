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
