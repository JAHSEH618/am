// JSONL 通用读取与行遍历。
//
// gz
package common

import (
	"bufio"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

// FileCache 维护单个 JSONL 文件的"已解析到第几字节 + 上次 mtime + 缓存好的会话快照"。
//
// Provider 的典型用法：
//
//	cache := common.NewFileCache()
//	for each .jsonl:
//	    cached, offset, mtime := cache.GetIncremental(path)
//	    if cached != nil && offset == 0 { 直接复用 cached }
//	    else 增量从 offset 读到 EOF，得到新 session
//	    cache.Put(path, mtime, newOffset, session)
//	cache.Prune(seenSet)  // 清理已删除的文件
//
// 实现参考 lazyagent model.SessionCache（MIT），但泛化为存任意 V，避免与 monitor.Session 强耦合。
type FileCache[V any] struct {
	mu      sync.Mutex
	entries map[string]fileCacheEntry[V]
}

type fileCacheEntry[V any] struct {
	mtime time.Time
	size  int64 // 上次解析消耗到的字节数（=下次 seek 起点）
	value V
}

// NewFileCache 创建空缓存。
func NewFileCache[V any]() *FileCache[V] {
	return &FileCache[V]{entries: make(map[string]fileCacheEntry[V])}
}

// GetIncremental 给出文件 path 的增量起点。
//
// 返回 (value, offset, mtime)：
//
//	value != nil && offset == 0  ：mtime 没变 → 完全命中，直接复用上次的 value。
//	value != nil && offset > 0   ：文件长大了 → 从 offset 继续读，把新内容 merge 进 value。
//	value == nil && offset == 0  ：文件不在缓存 / 文件被截断 → 全量重新解析。
//
// 由调用方保证拷贝 value 时不会被外部 mutate（要么不可变，要么自行 Clone）。
func (c *FileCache[V]) GetIncremental(path string) (val V, offset int64, mtime time.Time) {
	var zero V
	info, err := os.Stat(path)
	if err != nil {
		return zero, 0, time.Time{}
	}
	mtime = info.ModTime()
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[path]
	if !ok {
		return zero, 0, mtime
	}
	if e.mtime.Equal(mtime) {
		return e.value, 0, mtime
	}
	if info.Size() <= e.size || e.size == 0 {
		return zero, 0, mtime
	}
	return e.value, e.size, mtime
}

// Put 写回缓存。size 是解析后消耗到的字节数。
func (c *FileCache[V]) Put(path string, mtime time.Time, size int64, value V) {
	c.mu.Lock()
	c.entries[path] = fileCacheEntry[V]{mtime: mtime, size: size, value: value}
	c.mu.Unlock()
}

// Prune 删除不在 seen 集合中的缓存项（适用于文件被用户手动删除的场景）。
func (c *FileCache[V]) Prune(seen map[string]struct{}) {
	c.mu.Lock()
	for k := range c.entries {
		if _, ok := seen[k]; !ok {
			delete(c.entries, k)
		}
	}
	c.mu.Unlock()
}

// ScanLine 是流式 JSONL 行扫描的回调签名。返回 false 终止扫描。
//
//	line  当前行的字节切片（仅在回调内有效，调用方需要持久化时必须 copy）
//	bytes 截止目前累计消耗到的字节数（含末尾换行）
type ScanLine func(line []byte, totalBytes int64) bool

// ScanJSONL 从 path 的 startOffset 字节处开始按行扫描 JSONL。
//
//	maxLine  单行最大字节数（Cursor / Claude assistant 大段输出可能超 1 MB），建议 4 << 20
//	cb       每读到一行调用一次，返回 false 立刻退出
//
// 返回:
//
//	consumed  此次扫描结束时的字节位置（== 下次增量解析的 startOffset）
//	parsed    是否真的扫描到至少一行（用于"空文件不要清空缓存"的判断）
//	err       打开 / 读取错误（os.IsNotExist 视为非致命由调用方决定）
//
// Provider 实现可以把 line 反序列化为自己的协议结构，再决定如何 merge 到 Session 上。
func ScanJSONL(path string, startOffset int64, maxLine int, cb ScanLine) (consumed int64, parsed bool, err error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, false, err
	}
	defer func() { _ = f.Close() }()

	if startOffset > 0 {
		if _, err := f.Seek(startOffset, 0); err != nil {
			return 0, false, err
		}
	}

	scanner := bufio.NewScanner(f)
	if maxLine <= 0 {
		maxLine = 4 << 20
	}
	scanner.Buffer(make([]byte, 0, 64<<10), maxLine)

	consumed = startOffset
	for scanner.Scan() {
		line := scanner.Bytes()
		consumed += int64(len(line)) + 1 // +1 for \n stripped by Scanner
		parsed = true
		if !cb(line, consumed) {
			break
		}
	}
	if err := scanner.Err(); err != nil && !errors.Is(err, bufio.ErrTooLong) {
		return consumed, parsed, err
	}

	if fi, err := f.Stat(); err == nil && consumed > fi.Size() {
		consumed = fi.Size()
	}
	return consumed, parsed, nil
}

// ParseJob / ParseResult 是 Provider 在"扫描目录 → 收集要解析的文件 → 并行解析 → 回写缓存"
// 流程中常用的中间结构。本包不强制使用，仅提供给 Provider 复用。
type ParseJob[V any] struct {
	Path   string
	Cached V
	Offset int64
	MTime  time.Time
}

type ParseResult[V any] struct {
	Path      string
	MTime     time.Time
	NewOffset int64
	Value     V
}

// ParseFn 是 Provider 自己的"单文件解析函数"。给到 (path, offset, cached) 返回 (value, newOffset, error)。
type ParseFn[V any] func(path string, offset int64, cached V) (V, int64, error)

// RunParallelParse 把 jobs 用 GOMAXPROCS 个 worker 并发执行 fn，返回所有非错误结果（保留输入顺序）。
//
// 用于 Provider 扫描出 N 个 JSONL 文件后并行重解析；与 lazyagent 的实现思路一致。
func RunParallelParse[V any](jobs []ParseJob[V], fn ParseFn[V]) []ParseResult[V] {
	if len(jobs) == 0 {
		return nil
	}
	workers := runtime.GOMAXPROCS(0)
	if workers > len(jobs) {
		workers = len(jobs)
	}

	results := make([]ParseResult[V], len(jobs))
	jobCh := make(chan int, len(jobs))
	for i := range jobs {
		jobCh <- i
	}
	close(jobCh)

	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for idx := range jobCh {
				j := &jobs[idx]
				value, newOffset, err := fn(j.Path, j.Offset, j.Cached)
				if err != nil {
					continue
				}
				results[idx] = ParseResult[V]{
					Path:      j.Path,
					MTime:     j.MTime,
					NewOffset: newOffset,
					Value:     value,
				}
			}
		}()
	}
	wg.Wait()
	return results
}

// WalkJSONL 递归扫描 root 下所有 .jsonl 文件并把路径塞进 seen，回调 onFile。
//
// 用于 codex 这种"按日期/UUID 分子目录"的存储格式；也适用于 Claude 的 projects/<encoded>/*.jsonl。
//
// onFile 返回 false 时跳过该文件（不入 seen），避免缓存里残留无效记录。
func WalkJSONL(root string, onFile func(path string, info fs.DirEntry) bool) error {
	if root == "" {
		return os.ErrNotExist
	}
	return filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if os.IsNotExist(err) {
				return nil
			}
			return nil
		}
		if d.IsDir() || filepath.Ext(path) != ".jsonl" {
			return nil
		}
		_ = onFile(path, d)
		return nil
	})
}
