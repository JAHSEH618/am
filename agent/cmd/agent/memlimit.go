package main

import (
	"os"
	"runtime/debug"

	"github.com/am/aiwatch-agent/internal/logger"
)

// defaultMemoryLimit 常驻 daemon 的 Go 堆软上限。
//
// 默认 GOGC=100 时堆会涨到存活数据的 2 倍才回收，bootstrap（30d 回填）或大会话解析的峰值因此被放大；
// 软上限让 GC 在逼近上限时提前回收并把空闲页还给系统。它是兜底而不是硬限：存活数据真超过上限时
// Go 把 GC CPU 限在 50% 以内，不会 OOM。员工机器可用环境变量 GOMEMLIMIT（如 GOMEMLIMIT=768MiB）覆盖。
const defaultMemoryLimit = 512 << 20

func applyMemoryLimit() {
	if os.Getenv("GOMEMLIMIT") != "" {
		return // runtime 启动时已按环境变量生效
	}
	debug.SetMemoryLimit(defaultMemoryLimit)
	logger.Debugf("go memory limit set to %d MiB (override with GOMEMLIMIT)", defaultMemoryLimit>>20)
}
