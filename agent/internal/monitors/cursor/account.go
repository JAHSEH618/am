// Cursor 账户与订阅者元数据。
//
// gz
package cursor

import (
	"database/sql"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// readAccount 从 globalStorage/state.vscdb 的 ItemTable 读取 Cursor 客户端的登录态。
//
// 关键 key（采样自真实 Cursor，2026-05）：
//
//	cursorAuth/cachedEmail              用户邮箱（Cursor 账号）
//	cursorAuth/cachedSignUpType         注册渠道：Auth_0 / Google / GitHub / 邮箱
//	cursorAuth/stripeMembershipType     付费档：free / pro / pro_plus / business / ultra / enterprise
//	cursorAuth/stripeSubscriptionStatus 订阅状态：active / canceled / past_due / trialing / 空(免费)
//
// 三平台 (mac / windows / linux) 都是同一组 key，无需平台分支。
//
// 不读 cursorAuth/accessToken / refreshToken：那是凭证，外泄风险高，平台层面用不到。
func readAccount(dbPath string) monitor.Account {
	if dbPath == "" {
		return monitor.Account{}
	}
	db, err := sql.Open("sqlite", dbPath+"?mode=ro")
	if err != nil {
		return monitor.Account{}
	}
	defer func() { _ = db.Close() }()

	return monitor.Account{
		Provider:           TypeCode,
		Email:              readItemTable(db, "cursorAuth/cachedEmail"),
		MembershipType:     readItemTable(db, "cursorAuth/stripeMembershipType"),
		SubscriptionStatus: readItemTable(db, "cursorAuth/stripeSubscriptionStatus"),
		SignUpType:         readItemTable(db, "cursorAuth/cachedSignUpType"),
	}
}

// readItemTable 安全读单个 ItemTable 字符串值，未找到 / 异常都返回空串。
func readItemTable(db *sql.DB, key string) string {
	var v string
	err := db.QueryRow("SELECT value FROM ItemTable WHERE key=? LIMIT 1", key).Scan(&v)
	if err != nil {
		return ""
	}
	return v
}

// Account 实现 monitor.AccountProvider 接口（reporter 通过 type assertion 调用）。
//
// 这是 Provider 抽象的可选扩展：监控目标如果有"账号"概念，就实现这个方法，否则返回零值即可。
func (p *Provider) Account() monitor.Account {
	return readAccount(stateDBPath())
}
