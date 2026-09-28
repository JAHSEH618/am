package gitlog

import (
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// gitRepoWithAuthors 建一个临时仓库，按顺序以 authors 里的邮箱各提交一次；repoEmail 非空时写入仓库级 user.email。
func gitRepoWithAuthors(t *testing.T, repoEmail string, authors []string) string {
	t.Helper()
	dir := t.TempDir()
	run := func(env []string, args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir}, args...)...)
		cmd.Env = append(os.Environ(), env...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	run(nil, "init", "-q")
	if repoEmail != "" {
		run(nil, "config", "user.email", repoEmail)
	}
	for i, a := range authors {
		name := filepath.Join(dir, "f"+strings.Repeat("x", i)+".txt")
		if err := os.WriteFile(name, []byte(a+"\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		run(nil, "add", ".")
		run([]string{
			"GIT_AUTHOR_NAME=a", "GIT_AUTHOR_EMAIL=" + a,
			"GIT_COMMITTER_NAME=c", "GIT_COMMITTER_EMAIL=" + a,
		}, "commit", "-q", "-m", "c"+strings.Repeat("x", i))
	}
	return dir
}

// 作者邮箱过滤必须发生在富化之前：只有本人的提交被 enrich（每条 4 次 git fork），过滤口径不变
// （trim + 忽略大小写；仓库 user.email ∪ git_author_emails；无任何身份时整库跳过、不推进游标）。
func TestScan_FiltersByEmailBeforeEnrich(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git not installed")
	}
	// 隔离 state/cache 目录与全局 git 配置，避免读到开发机真实的 session roots / 游标 / user.email。
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))
	t.Setenv("XDG_CACHE_HOME", filepath.Join(home, ".cache"))
	t.Setenv("GIT_CONFIG_NOSYSTEM", "1")
	t.Setenv("GIT_CONFIG_GLOBAL", filepath.Join(home, "gitconfig"))

	for _, tc := range []struct {
		name         string
		repoEmail    string
		aliases      []string
		authors      []string
		wantKept     []string // 保留（且仅这些被富化）的作者邮箱，排序后比较
		wantFiltered int
		wantNoIdent  int
		wantHead     bool
	}{
		{
			name:         "only own commits enriched, case-insensitive",
			repoEmail:    "me@corp.com",
			authors:      []string{"mate@corp.com", "Me@Corp.com", "other@corp.com", "me@corp.com"},
			wantKept:     []string{"Me@Corp.com", "me@corp.com"},
			wantFiltered: 2,
			wantHead:     true,
		},
		{
			name:         "config alias counts as own",
			repoEmail:    "me@corp.com",
			aliases:      []string{" ME@home.org "},
			authors:      []string{"me@home.org", "mate@corp.com"},
			wantKept:     []string{"me@home.org"},
			wantFiltered: 1,
			wantHead:     true,
		},
		{
			name:         "all teammates: nothing enriched, head still advances",
			repoEmail:    "me@corp.com",
			authors:      []string{"mate@corp.com", "other@corp.com"},
			wantFiltered: 2,
			wantHead:     true,
		},
		{
			name:        "no identity: repo skipped, nothing enriched, cursor held",
			authors:     []string{"me@corp.com", "mate@corp.com"},
			wantNoIdent: 1,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := gitRepoWithAuthors(t, tc.repoEmail, tc.authors)

			var enriched []string
			orig := enrich
			enrich = func(repoDir string, c *Commit, lim Limits) {
				enriched = append(enriched, c.AuthorEmail)
				orig(repoDir, c, lim)
			}
			t.Cleanup(func() { enrich = orig })

			p := &Provider{
				roots:           []string{dir},
				gitAuthorEmails: tc.aliases,
				limits:          DefaultLimits(),
				cursor:          &cursorState{HeadByRepo: map[string]string{}},
			}
			res := p.Scan()

			var kept []string
			for _, c := range res.Commits {
				kept = append(kept, c.AuthorEmail)
				if c.DetailStatus == "" {
					t.Errorf("reported commit %s was not enriched", c.CommitHash)
				}
			}
			sort.Strings(kept)
			sort.Strings(enriched)
			if strings.Join(kept, ",") != strings.Join(tc.wantKept, ",") {
				t.Fatalf("kept=%v want %v", kept, tc.wantKept)
			}
			if strings.Join(enriched, ",") != strings.Join(tc.wantKept, ",") {
				t.Fatalf("enriched=%v want only %v", enriched, tc.wantKept)
			}
			if res.CommitsFilteredByEmail != tc.wantFiltered || res.ReposSkippedNoIdentity != tc.wantNoIdent {
				t.Fatalf("filtered=%d noIdentity=%d, want %d/%d",
					res.CommitsFilteredByEmail, res.ReposSkippedNoIdentity, tc.wantFiltered, tc.wantNoIdent)
			}
			if got := len(res.HeadByRepo) == 1; got != tc.wantHead {
				t.Fatalf("head advanced=%v want %v (%v)", got, tc.wantHead, res.HeadByRepo)
			}
		})
	}
}
