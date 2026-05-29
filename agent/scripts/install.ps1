# AIWatch 一键安装脚本（Windows PowerShell 5+）
#
# 流程：
#   1. 校验入参（优先命令行参数，缺省回退环境变量 AM_SERVER_URL / AM_USER_CODE）
#   2. 下载 ${ServerUrl}/install/aiwatchd-windows-amd64.exe
#   3. 安装到 %LOCALAPPDATA%\aiwatchd\aiwatchd.exe
#   4. 跑 aiwatchd init（写 config + 调 /api/v1/agent/register）
#   5. 注册 ScheduledTask 开机自启（AtLogon），失败回退 HKCU\...\Run
#   6. 启动 + PID 跟踪自检；失败时把 aiwatchd.log 末尾 30 行原样回显给员工
#
# v2.9 修复点（基于生产巡检）：
#   - ScheduledTask 全员"参数错误"：
#       根因  New-ScheduledTaskPrincipal -UserId / New-ScheduledTaskTrigger -User 在 Win10/11 上
#             短账号名（如 'lizixuan'）解析非确定性，多数企业域 / Microsoft Account 机器报
#             0x80070057 = "参数错误"，并被 try/catch 捕获，全员回退到 HKCU\...\Run。
#       修法  统一用当前进程的 SID 作为 -UserId（一定能解析），用 [WindowsIdentity]::GetCurrent().Name
#             即 'DOMAIN\user' / 'COMPUTER\user' 作为 trigger -User，跨域 / 本地账号 / MS 账号都通。
#   - 安装显示"It's OK!" 但员工后续掉线：
#       根因 1  Get-Process -Name 'aiwatchd' 命中的可能是上一次安装残留进程；本次拉起的
#               进程崩了 / 没拉起来都不影响自检通过。
#       修法 1  Start-Process -PassThru 拿到 PID，sleep 后按 PID 校验是否还活着。
#       根因 2  Windows 上 stderr 没人接，aiwatchd 启动崩溃完全无痕。
#       修法 2  agent 端 v2.9 起强制写文件日志到 %LOCALAPPDATA%\aiwatchd\logs\aiwatchd.log；
#               install 自检失败时自动 tail 该文件回显，让员工把错误一次性贴给运维。
#   - 安装过程报错"看起来成功"：
#       修法 全程 Start-Transcript 写到 %LOCALAPPDATA%\aiwatchd\logs\install-<时间戳>.log，
#            员工把这一份文件发给运维即可定位（不需要再让他们截图）。
#
# 由 server 通过 GET /install/aiwatchd.ps1 分发。
# 推荐调用形式（前端登录页生成）：
#   & ([scriptblock]::Create((irm http://aiwatch.example.com/install/aiwatchd.ps1))) `
#       -Clean `
#       -UserCode 'alice' ...
#
# -Clean  安装前卸载旧版（停任务、删 ProgramData / LocalAppData 数据与 gitlog 游标等），等同完全重装。
#
# -UserName / -Department 用于"员工自助注册"：服务端 employee 表无此 UserCode 时
# 按这两个字段创建一条 ACTIVE 记录；员工已存在则忽略，不会覆盖 HR 已录入的姓名 / 部门。

#Requires -Version 5
[CmdletBinding()]
param(
    [switch]$Clean,
    [string]$UserCode   = $env:AM_USER_CODE,
    [string]$UserName   = $env:AM_USER_NAME,
    [string]$Department = $env:AM_DEPARTMENT,
    [string]$ServerUrl  = $env:AM_SERVER_URL
)
$ErrorActionPreference = 'Stop'

function Write-AwInfo($msg) { Write-Host "[aiwatchd-install] $msg" -ForegroundColor Cyan }
function Write-AwWarn($msg) { Write-Host "[aiwatchd-install] $msg" -ForegroundColor Yellow }
function Write-AwErr($msg)  { Write-Host "[aiwatchd-install] $msg" -ForegroundColor Red }

# ---------- 0. 安装目录 + Transcript ----------
# 把整个安装过程的输出（含 PowerShell 抛错的 stack）落到一个时间戳文件里，员工反馈"装失败"时
# 直接让他把这一份发给运维即可定位，不再依赖截图。
$installDir   = Join-Path $env:LOCALAPPDATA 'aiwatchd'
$logDir       = Join-Path $installDir 'logs'
$installPath  = Join-Path $installDir 'aiwatchd.exe'
$daemonLog    = Join-Path $logDir 'aiwatchd.log'   # 与 agent logger.DefaultLogPath() 保持一致

if ($Clean) {
    Write-AwInfo 'clean: removing prior install (task, Run key, ProgramData, LocalAppData)...'
    $pref = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        Stop-ScheduledTask -TaskName 'aiwatchd' -ErrorAction SilentlyContinue
        Get-Process -Name 'aiwatchd' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 800
        if (Test-Path $installPath) {
            try { & $installPath uninstall --yes 2>$null } catch {}
        }
        Unregister-ScheduledTask -TaskName 'aiwatchd' -Confirm:$false -ErrorAction SilentlyContinue
        Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'aiwatchd' -ErrorAction SilentlyContinue
        $progDataDir = Join-Path $env:ProgramData 'aiwatchd'
        $legacyProg   = Join-Path $env:ProgramData 'ai-work-agent'
        if (Test-Path $progDataDir) { Remove-Item -Recurse -Force $progDataDir }
        if (Test-Path $legacyProg) { Remove-Item -Recurse -Force $legacyProg }
        if (Test-Path $installDir) { Remove-Item -Recurse -Force $installDir }
    } finally {
        $ErrorActionPreference = $pref
    }
}

New-Item -ItemType Directory -Force -Path $installDir, $logDir | Out-Null

$installLog = Join-Path $logDir ("install-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
try {
    Start-Transcript -Path $installLog -Append -Force | Out-Null
    $transcriptStarted = $true
} catch {
    # 极少数 ConstrainedLanguage / 受限策略下 Start-Transcript 不可用，不致命
    $transcriptStarted = $false
    Write-AwWarn "transcript disabled (continue without log file): $($_.Exception.Message)"
}

# ---------- 1. 入参 ----------
if (-not $ServerUrl) { Write-AwErr 'server URL not provided. Pass -ServerUrl or set $env:AM_SERVER_URL'; if ($transcriptStarted) { Stop-Transcript | Out-Null }; exit 1 }
if (-not $UserCode)  { Write-AwErr 'user code not provided. Pass -UserCode or set $env:AM_USER_CODE';   if ($transcriptStarted) { Stop-Transcript | Out-Null }; exit 1 }
$serverUrl  = $ServerUrl.TrimEnd('/')
$userCode   = $UserCode
$userName   = $UserName
$department = $Department

Write-AwInfo "install log: $installLog"
Write-AwInfo "daemon log : $daemonLog"

# ---------- 2. ARCH ----------
# Windows on ARM 也回退用 amd64（aiwatchd 暂未提供 windows/arm64）
$arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'AMD64') { 'amd64' } else { 'amd64' }
$binaryName = "aiwatchd-windows-$arch.exe"
$downloadUrl = "$serverUrl/install/$binaryName"

# ---------- 3. 下载 ----------
Write-AwInfo "downloading $downloadUrl"
$tmp = New-TemporaryFile
try {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $tmp -UseBasicParsing -TimeoutSec 30
} catch {
    Write-AwErr "download failed: $_"
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
    exit 1
}

$size = (Get-Item $tmp).Length
if ($size -lt 1048576) {
    Write-AwErr "downloaded file too small ($size bytes); likely 404 / proxy error"
    Get-Content $tmp -TotalCount 5 | Write-Host
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
    exit 1
}

# 强制覆盖；如果 ScheduledTask / 老进程占着文件锁，先停掉。
try { Stop-ScheduledTask -TaskName 'aiwatchd' -ErrorAction SilentlyContinue } catch {}
try {
    Get-Process -Name 'aiwatchd' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
} catch {}
Start-Sleep -Milliseconds 500
Move-Item -Path $tmp -Destination $installPath -Force
Write-AwInfo "installed: $installPath"

# ---------- 4. PATH ----------
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*$installDir*") {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$installDir", 'User')
    $env:Path = "$env:Path;$installDir"
    Write-AwInfo "added $installDir to user PATH (new shells will see it)"
}

# ---------- 5. init + 注册 ----------
$env:AM_SERVER_URL  = $serverUrl
$env:AM_USER_CODE   = $userCode
$env:AM_USER_NAME   = $userName
$env:AM_DEPARTMENT  = $department
if ($userName -or $department) {
    Write-AwInfo "registering as user_code=$userCode user_name=$userName department=$department"
} else {
    Write-AwInfo "registering as user_code=$userCode"
}
& $installPath init
if ($LASTEXITCODE -ne 0) {
    Write-AwErr "aiwatchd init failed (exit $LASTEXITCODE)"
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
    exit $LASTEXITCODE
}

# ---------- 6. 自启：首选 ScheduledTask（多策略），失败回退 HKCU Run ----------
# 设计取舍：
#   - 首选 ScheduledTask：开机/登录都能起 + 进程崩了 RestartCount 自愈，是首选方案；
#   - New-ScheduledTaskPrincipal -UserId / Trigger -User 在不同 Windows 配置下接受的
#     格式差别极大；我们按"成功率高 → 低"顺序依次尝试，任一通过即完成：
#       策略 A  -UserId 'DOMAIN\user' (NT4 名)  ：标准做法，covers 99% 机器；
#                Microsoft 官方示例就是这个，schema 校验最宽松。
#       策略 B  -UserId <SID>                  ：FQDN 解析不通的奇葩机器兜底；
#                注意：LogonType Interactive 下 schema 会拒 SID，所以同时把 LogonType
#                降到 S4U（无需密码、无需 token），换取"能注册成功"。
#       策略 C  无 Principal，仅 trigger -User ：让 Task Scheduler 从 trigger 自动推断
#                principal，跳过 schema 严格校验路径。
#   - 三条都炸才回退到 HKCU\...\Run —— 纯用户级、零特权；
#     代价是只在 logon 时启动 + 没有进程崩溃自动重启。
#   - 旧脚本踩坑：Register-ScheduledTask 报红 / status 命令通过 / 输出 "It's OK!"，
#     员工以为装好了实际上根本没自启进程——后台只能看到一个永远 inactive 的"幽灵 agent"。
#
# v1.0.2 修复：上一版用 -UserId <SID> + -LogonType Interactive 在 schema 校验阶段被
# Task Scheduler 拒绝，错误 `(17,8):UserId:` 来自生成 XML 第 17 行第 8 列的 Principal/UserId
# —— XSD 规定 Interactive 必须用 NT4 名而非 SID。改回 NT4 名作为首选。
$taskName = 'aiwatchd'
$autostartMode = ''

# 用当前进程的真实身份算出 SID + DOMAIN\user 全名，绕开 $env:USERNAME 短名解析坑。
$currentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$userSid         = $currentIdentity.User.Value     # e.g. S-1-5-21-xxx
$fullUserName    = $currentIdentity.Name           # e.g. CONTOSO\lizixuan 或 DESKTOP-XXX\lizixuan
if (-not $fullUserName) { $fullUserName = "$env:USERDOMAIN\$env:USERNAME" }
Write-AwInfo "principal: name=$fullUserName sid=$userSid"

# 三条策略共享的 action / trigger / settings。
$action   = New-ScheduledTaskAction -Execute $installPath -Argument 'start' -WorkingDirectory $env:USERPROFILE
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $fullUserName

# ExecutionTimeLimit 取很大值代替"无限"（[TimeSpan]::Zero / 'PT0S' 在部分 Win10 build
# 上被 CIM 层判定为 invalid arg，触发 0x80070057；9999 天 ≈ 27 年，等价 forever
# 且任何 Win10/11 版本都接受）。RestartCount/Interval 保留：进程崩溃自愈。
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Days 9999) `
    -Hidden

$strategies = @(
    @{ Label = 'fqdn+interactive'; Principal = (New-ScheduledTaskPrincipal -UserId $fullUserName -LogonType Interactive -RunLevel Limited) }
    @{ Label = 'sid+s4u';          Principal = (New-ScheduledTaskPrincipal -UserId $userSid      -LogonType S4U         -RunLevel Limited) }
    @{ Label = 'no-principal';     Principal = $null }
)

$registerErr = $null
foreach ($strat in $strategies) {
    try {
        # 幂等：每次尝试前先删，避免上一次 half-registered 的脏 task 卡住下一次。
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue

        $regArgs = @{
            TaskName    = $taskName
            Action      = $action
            Trigger     = $trigger
            Settings    = $settings
            Description = 'AIWatch agent (aiwatchd) — AI 使用观测'
            ErrorAction = 'Stop'
        }
        if ($strat.Principal) { $regArgs.Principal = $strat.Principal }

        Register-ScheduledTask @regArgs | Out-Null
        Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
        $autostartMode = "task ($($strat.Label))"
        Write-AwInfo "ScheduledTask registered & started via strategy '$($strat.Label)'"
        $registerErr = $null
        break
    } catch {
        $registerErr = $_
        Write-AwWarn "ScheduledTask strategy '$($strat.Label)' failed: $($_.Exception.Message)"
    }
}

if (-not $autostartMode) {
    $lastErrMsg = if ($registerErr) { $registerErr.Exception.Message } else { '(unknown)' }
    Write-AwWarn  "all ScheduledTask strategies failed; last error: $lastErrMsg"
    Write-AwInfo  "falling back to HKCU\...\Run autostart entry (user-level, no admin needed)..."
    try {
        $runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
        if (-not (Test-Path $runKey)) { New-Item -Path $runKey -Force | Out-Null }
        $runValue = "`"$installPath`" start"
        Set-ItemProperty -Path $runKey -Name 'aiwatchd' -Value $runValue -ErrorAction Stop

        # 回读校验，确认确实写进去了——某些 GPO / 受限策略下 Set-ItemProperty 不报错但不生效。
        $persisted = (Get-ItemProperty -Path $runKey -Name 'aiwatchd' -ErrorAction Stop).aiwatchd
        if ($persisted -ne $runValue) {
            throw "HKCU Run value verify mismatch: expected=$runValue actual=$persisted"
        }
        Write-AwInfo "HKCU Run entry installed: $persisted"

        # 立即拉起一份当前进程；用 -PassThru 拿到 PID 给后面的自检校验。
        $script:launchedProc = Start-Process -FilePath $installPath -ArgumentList 'start' `
            -WindowStyle Hidden -PassThru -ErrorAction Stop
        $autostartMode = 'run-key'
        Write-AwInfo "aiwatchd launched: pid=$($script:launchedProc.Id) (auto-launch on next logon)"
    } catch {
        Write-AwErr "HKCU Run fallback also failed: $($_.Exception.Message)"
        Write-AwErr "no autostart configured. To start manually: '$installPath start'"
        if ($transcriptStarted) { Stop-Transcript | Out-Null }
        exit 1
    }
}

# ---------- 7. 活体自检：PID 跟踪 + status 双 check ----------
# 旧脚本只跑 `Get-Process -Name aiwatchd`，命中的可能是上一次安装残留——本次拉起的进程
# 即使已经崩了也会"自检通过"。改成按 PID 校验：
#   - task 模式：扫一次 aiwatchd 进程列表，取最新（StartTime 最大）的那个
#   - run-key 模式：直接用 Start-Process -PassThru 拿到的 $launchedProc.Id
# 进程不在 / 已退出，立即把 daemon 日志末 30 行回显，便于一次性定位。
Start-Sleep -Seconds 3

function Tail-DaemonLog {
    if (Test-Path $daemonLog) {
        Write-AwWarn "---- tail of $daemonLog ----"
        try { Get-Content $daemonLog -Tail 30 -ErrorAction Stop | ForEach-Object { Write-Host $_ } } catch {
            Write-AwWarn "(failed to read daemon log: $($_.Exception.Message))"
        }
        Write-AwWarn "---- end of daemon log ----"
    } else {
        Write-AwWarn "daemon log not found at $daemonLog (process likely died before logger.Init())"
    }
}

$alive = $false
$isTaskMode = $autostartMode.StartsWith('task')
if ($isTaskMode) {
    $procs = Get-Process -Name 'aiwatchd' -ErrorAction SilentlyContinue | Sort-Object StartTime -Descending
    if ($procs) {
        $alive = $true
        $latest = $procs | Select-Object -First 1
        Write-AwInfo "process check: pid=$($latest.Id) start=$($latest.StartTime)"
    }
} else {
    if ($script:launchedProc -and -not $script:launchedProc.HasExited) {
        $alive = $true
        Write-AwInfo "process check: pid=$($script:launchedProc.Id) alive"
    } elseif ($script:launchedProc) {
        Write-AwErr "process check: pid=$($script:launchedProc.Id) exit_code=$($script:launchedProc.ExitCode) — crashed within 3s"
    }
}

if (-not $alive) {
    Write-AwErr "self-check FAILED: aiwatchd process not running (autostart=$autostartMode)"
    Tail-DaemonLog
    Write-AwErr "to inspect later: '$installPath status' or look at $daemonLog"
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
    exit 1
}

& $installPath status *> $null
if ($LASTEXITCODE -ne 0) {
    Write-AwErr "self-check FAILED: process up but '$installPath status' exit=$LASTEXITCODE"
    Tail-DaemonLog
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
    exit 1
}

if ($isTaskMode) {
    $strategyLabel = $autostartMode -replace '^task\s*\(|\)$', ''
    Write-AwInfo "It's OK! (autostart: ScheduledTask, strategy=$strategyLabel)"
} else {
    Write-AwInfo "It's OK! (autostart: HKCU Run, no admin)"
}
Write-AwInfo "tip: if agent goes offline later, run 'aiwatchd status' or check $daemonLog"

if ($transcriptStarted) { Stop-Transcript | Out-Null }
