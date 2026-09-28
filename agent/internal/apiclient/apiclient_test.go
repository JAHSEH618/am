package apiclient

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
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
