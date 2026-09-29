// 自动升级调度：长期运行的 daemon 周期性比对 manifest，必要时分离启动 `aiwatchd update`。
//
// <p>灰度发布（manifest.json 的可选字段 rollout_percent，0–100，缺省 = 100）：agent 用自己 agent_id 的
// 稳定哈希（sha256 前 4 字节 mod 100）得到一个固定的"桶号"，只有桶号 < rollout_percent 才自动升级。
//   - 同一台机器的桶号永远不变，比例只增不减时升级的机器集合单调扩大（10% → 50% → 100% 不会有人来回）；
//   - 运维把 manifest 里的比例调大即可放量，不需要重新构建二进制（见 build-dist.sh 的 ROLLOUT_PERCENT）；
//   - 只约束"daemon 自动升级"。手动执行 `aiwatchd update`（含 --force）是运维的明确意图，不受灰度限制；
//     daemon 拉起的 update 子进程同理——是否升级在 daemon 里已经决定过了；
//   - 没有 agent_id（尚未注册）且比例 < 100 时不自动升级：灰度期宁可保守；注册后即按桶号参与。
//
// <p>检查更新的轮询带 ±25% 抖动（autoUpdateJitter）：原先所有 daemon 都是"启动后每整点一小时"，
// 一发版、或一批机器同时重启，就会在同一时刻涌向 /install 下载并重启。
//
// gz
package updater

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"math/rand/v2"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
)

var autoUpdateSpawnMu sync.Mutex

const (
	// autoUpdateInterval 检查 manifest 的基准间隔。
	autoUpdateInterval = time.Hour
	// autoUpdateJitter 每次等待在基准间隔上的抖动幅度（±25% → 45~75 分钟）。
	autoUpdateJitter = 0.25
)

// NextAutoUpdateDelay 返回下一次检查更新前的等待时长：autoUpdateInterval ±autoUpdateJitter。rnd ∈ [0,1)。
func NextAutoUpdateDelay(rnd float64) time.Duration {
	return time.Duration(float64(autoUpdateInterval) * (1 - autoUpdateJitter + 2*autoUpdateJitter*rnd))
}

// RolloutBucket 返回 agentID 的灰度桶号 [0,100)：sha256 前 4 字节（大端）mod 100。稳定、与版本无关。
func RolloutBucket(agentID string) int {
	sum := sha256.Sum256([]byte(agentID))
	return int(binary.BigEndian.Uint32(sum[:4]) % 100)
}

// InRollout 判断 agentID 是否落在 percent% 的灰度范围内。
// percent ≥ 100 恒为 true（缺省 / 全量）；≤ 0 恒为 false；没有 agentID 时（percent<100）保守地返回 false。
func InRollout(agentID string, percent int) bool {
	if percent >= 100 {
		return true
	}
	if percent <= 0 || strings.TrimSpace(agentID) == "" {
		return false
	}
	return RolloutBucket(agentID) < percent
}

// upgradeDecision 是"要不要拉起升级"的纯函数（不碰磁盘 / 网络，便于单测）。返回 (是否升级, 原因)。
func upgradeDecision(currentVersion string, m *Manifest, agentID string) (bool, string) {
	sv := strings.TrimSpace(m.Version)
	if sv == "" {
		return false, "manifest missing version"
	}
	if sv == strings.TrimSpace(currentVersion) {
		return false, "already at latest"
	}
	pct := m.EffectiveRolloutPercent()
	if !InRollout(agentID, pct) {
		return false, fmt.Sprintf("not in rollout (percent=%d bucket=%s)", pct, bucketLabel(agentID))
	}
	return true, ""
}

func bucketLabel(agentID string) string {
	if strings.TrimSpace(agentID) == "" {
		return "n/a(unregistered)"
	}
	return fmt.Sprint(RolloutBucket(agentID))
}

// MaybeScheduleDetachedUpgrade 比对服务端 manifest 与本机版本；若有差异且本机在灰度范围内，则分离启动
// `aiwatchd update` 子进程完成停服 → 换包 → 启服（与员工手动执行等价）。
//
// 不可在 `update` 子命令进程内调用（无意义）；应由长期运行的 `start` daemon 周期触发。
func MaybeScheduleDetachedUpgrade(currentVersion string) error {
	cv := strings.TrimSpace(currentVersion)
	if cv == "" || cv == "dev" {
		return nil
	}
	cfg, err := config.Load()
	if err != nil {
		logger.Warnf("auto-update: skip (config): %v", err)
		return nil
	}
	if strings.TrimSpace(cfg.ServerURL) == "" {
		return nil
	}

	manifest, err := fetchManifest(cfg.ServerURL)
	if err != nil {
		logger.Warnf("auto-update: manifest fetch failed: %v", err)
		return nil
	}
	ok, reason := upgradeDecision(cv, manifest, cfg.AgentID)
	if !ok {
		if reason != "already at latest" {
			logSkipOnce(manifest.Version, cv, reason)
		}
		return nil
	}

	autoUpdateSpawnMu.Lock()
	defer autoUpdateSpawnMu.Unlock()

	logger.Infof("auto-update: server=%s local=%s rollout=%d%%, spawning detached `aiwatchd update`",
		manifest.Version, cv, manifest.EffectiveRolloutPercent())
	return launchDetachedUpdateCLI()
}

var lastSkipLog string

// logSkipOnce 同一 (服务端版本, 本机版本, 原因) 只打一次 INFO——每小时重复一行没有信息量。
func logSkipOnce(serverVersion, localVersion, reason string) {
	key := serverVersion + "|" + localVersion + "|" + reason
	autoUpdateSpawnMu.Lock()
	defer autoUpdateSpawnMu.Unlock()
	if lastSkipLog == key {
		return
	}
	lastSkipLog = key
	logger.Infof("auto-update: server=%s local=%s, skip: %s", serverVersion, localVersion, reason)
}

// RunAutoUpdateLoop 阻塞运行自动升级轮询直到 ctx 结束：每次等待 NextAutoUpdateDelay（带抖动），
// 随后调用 MaybeScheduleDetachedUpgrade。
func RunAutoUpdateLoop(ctx context.Context, currentVersion string) {
	timer := time.NewTimer(NextAutoUpdateDelay(rand.Float64()))
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			if err := MaybeScheduleDetachedUpgrade(currentVersion); err != nil {
				logger.Warnf("auto-update: could not spawn updater: %v", err)
			}
			timer.Reset(NextAutoUpdateDelay(rand.Float64()))
		}
	}
}

func launchDetachedUpdateCLI() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	cmd := exec.Command(exe, "update")
	cmd.Stdin = nil
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if attr := sysProcAttrDetached(); attr != nil {
		cmd.SysProcAttr = attr
	}
	return cmd.Start()
}
