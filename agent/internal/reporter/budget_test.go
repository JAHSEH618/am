package reporter

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// blockingProvider 的 Snapshot 一直阻塞直到 ctx 取消——用于验证"采集预算"的契约：
// 一个会响应 ctx 的慢 provider 在预算超时后会被及时取消（tickOnce 据此本轮略过它）。
type blockingProvider struct{}

func (blockingProvider) Type() string          { return "blocking" }
func (blockingProvider) TargetVersion() string { return "test" }
func (blockingProvider) IsInstalled() bool     { return true }
func (blockingProvider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	<-ctx.Done()
	return monitor.Snapshot{}, ctx.Err()
}

func TestProviderCollectBudgetSane(t *testing.T) {
	if providerCollectBudget <= 0 || providerCollectBudget > 45*time.Second {
		t.Fatalf("providerCollectBudget 应在 (0, 45s] 内，实际 %v", providerCollectBudget)
	}
}

// 验证：套上超时 ctx 后，卡住的 provider 在预算内被取消并返回 DeadlineExceeded。
// 这是 tickOnce 采集预算（context.WithTimeout(ctx, providerCollectBudget) → p.Snapshot(pctx)）依赖的契约。
func TestSlowProviderCancelledByBudget(t *testing.T) {
	const testBudget = 80 * time.Millisecond
	pctx, cancel := context.WithTimeout(context.Background(), testBudget)
	defer cancel()

	start := time.Now()
	_, err := blockingProvider{}.Snapshot(pctx)
	elapsed := time.Since(start)

	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected DeadlineExceeded, got %v", err)
	}
	if elapsed > 2*testBudget {
		t.Fatalf("provider 未被及时取消：耗时 %v（预算 %v）", elapsed, testBudget)
	}
}
