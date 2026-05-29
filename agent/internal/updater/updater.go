// Package updater 实现 `aiwatchd update` 子命令的全部业务：
//
//  1. 从 ${server_url}/install/manifest.json 拉服务端最新版本号 + 当前平台 sha256
//  2. 与本地 main.Version 比对，相同则直接退出（"已是最新"）
//  3. 不同则下载对应平台二进制到临时目录，做 sha256 校验
//  4. 通过 svcctl 停掉运行中的 service（这一步会让正在跑的 aiwatchd 退出，从而释放二进制文件锁）
//  5. 把当前可执行文件原子替换为新二进制（os.Rename；Windows 上需要先把老文件 rename 走）
//  6. 启动 service（service 起来之后会跑新版本）
//
// 设计取舍：
//   - 不复用 install.sh / install.ps1：避免 update 路径再走 init / 服务注册（员工已注册，多走只会引入失败面）。
//   - manifest.json 走 /install/** 静态映射，不需要后端写新的 controller。
//   - sha256 校验只在客户端做；服务端假设运维 rsync 上来的文件就是真相。
//
// 错误处理：任意一步失败都直接 return 错误，不尝试自动回滚——下载失败 / 校验失败时
// 二进制还没替换，service 也还没动，是安全状态。stop 之后到替换之间窗口里失败会让
// service 处于停止状态，updater 会显式打印"请手动执行 launchctl/systemctl/Start-ScheduledTask"。
//
// gz
package updater

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/svcctl"
)

const (
	manifestPath = "/install/manifest.json"
	httpTimeout  = 60 * time.Second
)

// Manifest 是 build-dist.sh 生成的产物清单。
//
// {
//   "version": "2.0.1",
//   "generated_at": "2026-05-08T12:00:00Z",
//   "artifacts": {
//     "darwin-arm64": {"filename": "aiwatchd-darwin-arm64", "sha256": "...", "size": 12345},
//     ...
//   }
// }
type Manifest struct {
	Version     string                       `json:"version"`
	GeneratedAt string                       `json:"generated_at"`
	Artifacts   map[string]ManifestArtifact  `json:"artifacts"`
}

// ManifestArtifact 描述单个平台的产物。
type ManifestArtifact struct {
	Filename string `json:"filename"`
	SHA256   string `json:"sha256"`
	Size     int64  `json:"size"`
}

// Options 控制 Run 的行为细节。零值表示"标准升级"——比对版本，必要时下载、替换、重启。
type Options struct {
	// Force = true 时即使版本号一致也走完下载 + 替换 + 重启流程。
	// 开发期 / 修复二进制损坏 / 修复签名（macOS）时用。
	Force bool

	// CheckOnly = true 时只打印服务端版本号，不下载、不替换、不动 service。
	// 由 cmdUpdate 在解析到 --check-only 时短路调用 CheckOnly()，这个字段当前
	// 不被 Run 自身用到，保留是为了把 CLI 参数语义集中放在 Options 结构里，便于后续
	// （比如直接在 Run 里实现 check-only 逻辑替换 CheckOnly 函数）。
	CheckOnly bool

	// NoRestart = true 时只下载 + 替换二进制，不停启 service。
	// 适合运维批量升级 + 自己控制重启窗口的场景；员工常规升级不要用。
	// 注意：mac 上不重启 service 意味着新二进制要等下次 launchd 重启 service 才会生效。
	NoRestart bool
}

// CheckOnly 只查询服务端最新版本，不下载也不重启。
// 用于运维 / 员工"看看有没有新版"的场景。
func CheckOnly(currentVersion string) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("read config: %w", err)
	}
	if cfg.ServerURL == "" {
		return fmt.Errorf("server_url empty in config")
	}
	manifest, err := fetchManifest(cfg.ServerURL)
	if err != nil {
		return fmt.Errorf("fetch manifest: %w", err)
	}
	fmt.Printf("当前版本: %s\n服务端版本: %s\n生成时间: %s\n",
		currentVersion, manifest.Version, manifest.GeneratedAt)
	if manifest.Version == currentVersion {
		fmt.Println("状态: 已是最新")
	} else {
		fmt.Println("状态: 有新版本可升级，运行 `aiwatchd update` 一键升级")
	}
	return nil
}

// Run 是 `aiwatchd update` 的入口。
//
// currentVersion 由 main.Version 注入；opts 见 Options 注释。
func Run(currentVersion string, opts Options) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("read config: %w (have you run install once?)", err)
	}
	if cfg.ServerURL == "" {
		return fmt.Errorf("server_url empty in config; reinstall first")
	}

	platformKey, filenameDefault, err := resolvePlatform()
	if err != nil {
		return err
	}

	// 1. 拉 manifest
	manifest, err := fetchManifest(cfg.ServerURL)
	if err != nil {
		return fmt.Errorf("fetch manifest: %w", err)
	}
	logger.Infof("update: server version=%s (generated %s) current=%s",
		manifest.Version, manifest.GeneratedAt, currentVersion)

	if !opts.Force && manifest.Version == currentVersion {
		logger.Infof("update: already at latest version %s", currentVersion)
		fmt.Printf("aiwatchd 已是最新版本 (%s)\n", currentVersion)
		return nil
	}

	art, ok := manifest.Artifacts[platformKey]
	if !ok || art.Filename == "" {
		// fallback：服务端 manifest 没有当前平台条目时按惯例文件名兜底（不做 sha256 校验）。
		logger.Warnf("update: manifest has no entry for %s, falling back to %s without sha256 verify",
			platformKey, filenameDefault)
		art = ManifestArtifact{Filename: filenameDefault}
	}

	// 2. 下载到临时文件
	tmpPath, err := download(cfg.ServerURL, art.Filename)
	if err != nil {
		return fmt.Errorf("download %s: %w", art.Filename, err)
	}
	defer func() {
		_ = os.Remove(tmpPath)
	}()
	logger.Infof("update: downloaded %s (%d bytes) -> %s",
		art.Filename, fileSize(tmpPath), tmpPath)

	// 3. sha256 校验（manifest 里有 sha256 才校验）
	if art.SHA256 != "" {
		got, err := sha256OfFile(tmpPath)
		if err != nil {
			return fmt.Errorf("sha256 compute: %w", err)
		}
		if !strings.EqualFold(got, art.SHA256) {
			return fmt.Errorf("sha256 mismatch: expected %s got %s (refuse to install)",
				art.SHA256, got)
		}
		logger.Infof("update: sha256 verified %s", got)
	}

	// 4. 找到自己的可执行文件路径
	selfPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("locate self exe: %w", err)
	}
	selfPath, _ = filepath.EvalSymlinks(selfPath)

	// 5. 停 service（释放文件锁；这一步会让运行中的 aiwatchd 主进程退出）。
	//    update 命令本身是手动启的前台进程，与 service 进程是两个独立进程，不会被 stop 影响。
	//    opts.NoRestart = true 时跳过 stop —— 调用方自己控制重启窗口。
	if !opts.NoRestart {
		if err := svcctl.Stop(); err != nil {
			logger.Warnf("update: stop service failed (continuing): %v", err)
		} else {
			logger.Infof("update: service stopped")
		}
	}

	// 6. 替换二进制
	if err := replaceBinary(selfPath, tmpPath); err != nil {
		return fmt.Errorf("replace binary at %s: %w", selfPath, err)
	}
	logger.Infof("update: binary replaced %s", selfPath)

	// 6.5. macOS ad-hoc codesign。
	// install.sh 在初次安装时做过这一步，避免 arm64 mac 因未签名被 kernel "killed: 9"。
	// update 路径替换的二进制是从服务端拉的"裸 go build 产物"，必须重做一次签名。
	// codesign 不存在 / 失败时只 warn 不阻断（部分 mac 没装 Xcode CLI）。
	if runtime.GOOS == "darwin" {
		if _, err := exec.LookPath("codesign"); err == nil {
			if out, cErr := exec.Command("codesign", "--force", "--sign", "-", selfPath).CombinedOutput(); cErr != nil {
				logger.Warnf("update: codesign ad-hoc sign failed (continuing): %v: %s", cErr, string(out))
			} else {
				logger.Infof("update: codesign ad-hoc signed %s", selfPath)
			}
		}
	}

	// 7. 启 service（NoRestart 时跳过；员工要手动重启）
	if opts.NoRestart {
		logger.Infof("update: --no-restart specified, binary replaced but service not restarted")
		fmt.Printf("aiwatchd 二进制已替换 %s -> %s（service 未重启，请手动 launchctl/systemctl restart）\n",
			currentVersion, manifest.Version)
		return nil
	}
	if err := svcctl.Start(); err != nil {
		return fmt.Errorf("start service after upgrade (binary already replaced): %w", err)
	}
	logger.Infof("update: service started, now running %s", manifest.Version)
	fmt.Printf("aiwatchd 已升级 %s -> %s\n", currentVersion, manifest.Version)
	return nil
}

// resolvePlatform 把 runtime.GOOS/GOARCH 映射成 manifest key + 默认文件名（fallback 用）。
func resolvePlatform() (string, string, error) {
	switch runtime.GOOS {
	case "darwin":
		switch runtime.GOARCH {
		case "arm64":
			return "darwin-arm64", "aiwatchd-darwin-arm64", nil
		case "amd64":
			return "darwin-amd64", "aiwatchd-darwin-amd64", nil
		}
	case "linux":
		if runtime.GOARCH == "amd64" {
			return "linux-amd64", "aiwatchd-linux-amd64", nil
		}
	case "windows":
		if runtime.GOARCH == "amd64" {
			return "windows-amd64", "aiwatchd-windows-amd64.exe", nil
		}
	}
	return "", "", fmt.Errorf("unsupported platform %s/%s", runtime.GOOS, runtime.GOARCH)
}

// fetchManifest 从 server_url + /install/manifest.json 拉清单。
func fetchManifest(serverURL string) (*Manifest, error) {
	u, err := joinURL(serverURL, manifestPath)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Timeout: httpTimeout}
	resp, err := client.Get(u)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s -> HTTP %d", u, resp.StatusCode)
	}
	var m Manifest
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		return nil, fmt.Errorf("parse manifest: %w", err)
	}
	if m.Version == "" {
		return nil, fmt.Errorf("manifest missing version field")
	}
	return &m, nil
}

// download 把 ${serverURL}/install/${filename} 下载到临时目录，返回临时文件路径。
func download(serverURL, filename string) (string, error) {
	u, err := joinURL(serverURL, "/install/"+filename)
	if err != nil {
		return "", err
	}
	client := &http.Client{Timeout: 5 * time.Minute}
	resp, err := client.Get(u)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("GET %s -> HTTP %d", u, resp.StatusCode)
	}

	f, err := os.CreateTemp("", "aiwatchd-update-*")
	if err != nil {
		return "", err
	}
	tmpPath := f.Name()
	if _, err := io.Copy(f, resp.Body); err != nil {
		_ = f.Close()
		_ = os.Remove(tmpPath)
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	// 二进制需要可执行（macOS / Linux 上 update 之后 launchctl/systemd 起的就是这个文件）。
	if runtime.GOOS != "windows" {
		_ = os.Chmod(tmpPath, 0o755)
	}
	return tmpPath, nil
}

// replaceBinary 把 src 文件 move 到 dst，覆盖已存在文件。
//
// Windows 上不能直接覆盖正在运行的 .exe。
// service 进程已被 svcctl.Stop 关掉，但操作系统仍可能短暂持锁；
// 这里把现有文件先 rename 成 .old，再把新文件 rename 到原路径。.old 文件在下次成功
// 启动后由用户或下一轮 update 自行清理（保留一份回滚能力，反正只有 ~10MB）。
func replaceBinary(dst, src string) error {
	if runtime.GOOS == "windows" {
		oldPath := dst + ".old"
		_ = os.Remove(oldPath)
		if err := os.Rename(dst, oldPath); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("rename old binary aside: %w", err)
		}
		if err := copyAndRename(src, dst); err != nil {
			// 失败时尝试把 .old 还原回来，最大努力修复
			_ = os.Rename(oldPath, dst)
			return err
		}
		return nil
	}
	// macOS / Linux：直接 rename 即可（即便 src 与 dst 跨设备，os.Rename 失败时退化到 copy+remove）
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	return copyAndRename(src, dst)
}

// copyAndRename 跨设备 / Windows 版的覆盖：写到 dst.tmp，再 rename。
func copyAndRename(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, dst)
}

// ---------- helpers ----------

func joinURL(base, path string) (string, error) {
	u, err := url.Parse(strings.TrimRight(base, "/"))
	if err != nil {
		return "", fmt.Errorf("invalid server_url %q: %w", base, err)
	}
	u.Path = strings.TrimRight(u.Path, "/") + path
	return u.String(), nil
}

func sha256OfFile(p string) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func fileSize(p string) int64 {
	st, err := os.Stat(p)
	if err != nil {
		return -1
	}
	return st.Size()
}
