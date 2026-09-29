# 語音指令偵測率測試（TASKS A0-21）
#
# 眼鏡上沒有畫面也沒有鍵盤，唯一能測語音的方式就是「人對著它講、看 logcat」。
# 這個腳本把那件事變成照著唸就好：逐條提示要說什麼、收集偵測結果、
# 最後列出每一句的命中率、誤判成什麼、以及誤觸次數。
#
# 用法（要用 pwsh 7，Windows PowerShell 5.1 會把中文顯示成亂碼）：
#   cd guide-glasses\tools
#   pwsh .\test_commands.ps1
#
# 只測某幾條、每條多說幾次：
#   pwsh .\test_commands.ps1 -Only "這是誰","唸給我聽" -Reps 5
#
# ⚠️ 這個腳本量的是**關鍵詞偵測**（常駐監聽，聽到指令直接做），
# 不是語音辨識。舊版腳本測的是已經被取代的「廣播 LISTEN → 全辨識 →
# 比對文字」那條路，而且清單裡有五句根本不在 keywords.txt 裡，
# 跑出來的 0% 是測錯東西，不是偵測率差。

param(
    [string[]]$Only = @(),

    # 每句說幾次。偵測率要有意義就不能只說一次；3 次是速度與可信度的折衷。
    [int]$Reps = 3,

    # 說完之後給多久偵測。KWS 是串流的，講完幾乎立刻就出結果，
    # 這個窗口主要是留給「使用者還沒開口」的反應時間。
    [int]$DetectSeconds = 6,

    # 靜默對照組要聽多久（量環境誤觸）。0 = 不做。
    [int]$SilenceSeconds = 60,

    [string]$CsvPath = "command_detection_results.csv"
)

# PowerShell 主控台預設用 cp950 解碼子行程輸出，logcat 的 UTF-8 會變亂碼。
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 前置檢查

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "找不到 adb，請先把 platform-tools 加進 PATH" -ForegroundColor Red
    exit 1
}
if (-not (adb devices | Select-String '\sdevice$')) {
    Write-Host "眼鏡沒有連上，請接上 USB" -ForegroundColor Red
    exit 1
}

# 背景限制沒解除的話 App 退到背景 2.4 秒就被殺，測到一半會整個消失。
# 這是靜默失敗——不查就只會看到「後面幾句都沒反應」。
$appop = (adb shell cmd appops get com.guideglasses RUN_ANY_IN_BACKGROUND) -join ''
if ($appop -notmatch 'allow') {
    Write-Host "🔴 背景限制沒解除，先跑：" -ForegroundColor Red
    Write-Host "   adb shell cmd appops set com.guideglasses RUN_ANY_IN_BACKGROUND allow" -ForegroundColor Yellow
    exit 1
}

# 指令清單一律從 App 實際載入的那份 keywords.txt 讀出來。
# 寫死在腳本裡的清單一定會跟程式碼脫節——舊版就是這樣測了五句
# 根本不存在的關鍵詞。
$keywordsFile = Join-Path $PSScriptRoot '..\ai\ai-asr-offline\src\main\assets\kws\keywords.txt'
if (-not (Test-Path $keywordsFile)) {
    Write-Host "找不到 keywords.txt：$keywordsFile" -ForegroundColor Red
    exit 1
}

$Commands = Get-Content -LiteralPath $keywordsFile -Encoding utf8 |
    Where-Object { $_ -match '@(.+)$' } |
    ForEach-Object { $Matches[1].Trim() }

if ($Only.Count -gt 0) {
    # 從別的 shell 用 `pwsh -File` 呼叫時，多個值會黏成一串連引號一起進來。
    $Only = @($Only |
        ForEach-Object { $_ -split ',' } |
        ForEach-Object { $_.Trim().Trim('"').Trim("'") } |
        Where-Object { $_ })

    $unknown = $Only | Where-Object { $Commands -notcontains $_ }
    if ($unknown) {
        Write-Host ("這幾句不在 keywords.txt 裡，偵測不到是必然的：{0}" -f ($unknown -join '、')) -ForegroundColor Red
        exit 1
    }
    $Commands = $Commands | Where-Object { $Only -contains $_ }
}

Write-Host ("將測試 {0} 句 × {1} 次 = {2} 輪" -f $Commands.Count, $Reps, ($Commands.Count * $Reps)) -ForegroundColor Cyan
Write-Host ("關鍵詞來源：{0}" -f (Resolve-Path $keywordsFile)) -ForegroundColor DarkGray

# 螢幕逾時只有 5 秒，睡著之後 App 會被切到背景。
adb shell svc power stayon true | Out-Null

# ---------------------------------------------------------------- 工具函式

# 讀出目前 logcat 裡與語音有關的行。
function Get-VoiceLog {
    adb logcat -d -s WakeWord:I AssistantVM:I OfflineTts:I TtsAnnouncer:I 2>$null
}

# 從 log 抽出「偵測到的關鍵詞」。
function Get-Detections([string[]]$log) {
    $log | Select-String '偵測到語音指令：「(.+?)」' |
        ForEach-Object { $_.Matches[0].Groups[1].Value }
}

# 從 log 抽出「關鍵詞 → 哪個功能」。偵測到卻沒有對應功能時這裡會是空的，
# 那代表 keywords.txt 加了詞但 VoiceCommand.MAPPING 忘了加。
function Get-Dispatches([string[]]$log) {
    $log | Select-String '語音指令：「(.+?)」→ (\w+)' |
        ForEach-Object { $_.Matches[0].Groups[2].Value }
}

# 等到助理不再說話。
#
# 這一步是測量正確性的關鍵：播報期間講的話會被當成雜訊，
# 太早提示使用者開口，記到的「沒偵測到」其實是「根本沒被聽」。
#
# ⚠️ StartSeconds 不能省。實測「出門前檢查」從觸發到 log 出現要 4.4 秒
# （快取命中的那行是**播完才印**），只看「安靜了幾秒」會在助理還沒開口前
# 就判定講完了——這正是本專案反覆吃到的那種靜默失敗。
function Wait-Idle {
    param(
        [int]$MaxSeconds = 30,
        [double]$QuietSeconds = 2.5,
        # 預期接下來會有播報時，最多等多久等它「開始」。0 = 不預期。
        [double]$StartSeconds = 0
    )
    $enteredAt = Get-Date
    $deadline = $enteredAt.AddSeconds($MaxSeconds)
    $last = ((adb logcat -d -s OfflineTts:I TtsAnnouncer:I 2>$null) | Measure-Object).Count
    $started = $false
    $stableSince = $enteredAt

    while ((Get-Date) -lt $deadline) {
        $n = ((adb logcat -d -s OfflineTts:I TtsAnnouncer:I 2>$null) | Measure-Object).Count
        if ($n -ne $last) {
            $last = $n
            $started = $true
            $stableSince = Get-Date
        } elseif ($started) {
            if (((Get-Date) - $stableSince).TotalSeconds -ge $QuietSeconds) { return $true }
        } elseif (((Get-Date) - $enteredAt).TotalSeconds -ge $StartSeconds) {
            return $true   # 一直都沒有動靜，本來就是安靜的
        }
        Start-Sleep -Milliseconds 400
    }
    return $false   # 逾時：可能還在唸長文，也可能卡住了
}

# ---------------------------------------------------------------- 主迴圈

$results = @()
$selfTriggers = @()   # 播報期間被自己的聲音觸發的（助理會自問自答）
$round = 0
$total = $Commands.Count * $Reps

foreach ($say in $Commands) {
    for ($rep = 1; $rep -le $Reps; $rep++) {
        $round++

        # 每輪都把 Activity 拉回前景——launcher 會自動輪播別的 App 搶走焦點。
        adb shell am start -n com.guideglasses/.MainActivity 2>&1 | Out-Null
        Start-Sleep -Milliseconds 600
        Wait-Idle | Out-Null
        adb logcat -c

        Write-Host ""
        Write-Host ("[{0}/{1}] " -f $round, $total) -NoNewline -ForegroundColor DarkGray
        Write-Host ("請說： {0}   （第 {1} 次）" -f $say, $rep) -ForegroundColor Cyan
        Start-Sleep -Seconds 2
        Write-Host "  >>> 現在說 <<<" -ForegroundColor Yellow

        Start-Sleep -Seconds $DetectSeconds

        $log = @(Get-VoiceLog)
        $heard = @(Get-Detections $log)
        $intents = @(Get-Dispatches $log)

        $detected = $heard -contains $say
        $status = if ($detected) { '命中' }
                  elseif ($heard.Count -gt 0) { '誤判' }
                  else { '沒偵測到' }

        $colour = switch ($status) { '命中' { 'Green' } '誤判' { 'Yellow' } default { 'Red' } }
        Write-Host ("  {0}｜聽到：{1}｜功能：{2}" -f
            $status,
            $(if ($heard.Count) { $heard -join '、' } else { '（無）' }),
            $(if ($intents.Count) { $intents -join '、' } else { '（無）' })) -ForegroundColor $colour

        if ($detected -and $intents.Count -eq 0) {
            # 偵測到了卻沒有功能——keywords.txt 與 VoiceCommand.MAPPING 不同步。
            Write-Host "  ⚠️ 偵測到但沒有對應功能：VoiceCommand.MAPPING 少了這一句" -ForegroundColor Magenta
        }

        $results += [PSCustomObject]@{
            該說的   = $say
            第幾次   = $rep
            結果     = $status
            偵測到的 = ($heard -join '、')
            觸發功能 = ($intents -join '、')
        }

        # 助理正在回應。這段期間再出現的偵測一定不是使用者說的，
        # 而是它聽到自己播報的內容——播報裡本來就含有指令詞。
        # 有命中才預期會有播報；沒命中就沒東西可等。
        $before = $heard.Count
        Wait-Idle -StartSeconds $(if ($detected) { 10 } else { 0 }) | Out-Null
        $after = @(Get-Detections @(Get-VoiceLog))
        if ($after.Count -gt $before) {
            $extra = $after[$before..($after.Count - 1)]
            $selfTriggers += $extra
            Write-Host ("  🔁 播報期間自我觸發：{0}" -f ($extra -join '、')) -ForegroundColor Magenta
        }
    }
}

# ---------------------------------------------------------------- 靜默對照組

$ambient = @()
if ($SilenceSeconds -gt 0) {
    Write-Host ""
    Write-Host ("靜默對照組：接下來 {0} 秒請不要說話（量環境誤觸）" -f $SilenceSeconds) -ForegroundColor Cyan
    adb shell am start -n com.guideglasses/.MainActivity 2>&1 | Out-Null
    Wait-Idle | Out-Null
    adb logcat -c
    Start-Sleep -Seconds $SilenceSeconds
    $ambient = @(Get-Detections @(Get-VoiceLog))
    if ($ambient.Count -eq 0) {
        Write-Host "  ✅ 沒有誤觸" -ForegroundColor Green
    } else {
        Write-Host ("  🔴 誤觸 {0} 次：{1}" -f $ambient.Count, ($ambient -join '、')) -ForegroundColor Red
    }
}

# ---------------------------------------------------------------- 結果

Write-Host ""
Write-Host "=============== 逐句偵測率 ===============" -ForegroundColor Cyan

$summary = $results | Group-Object 該說的 | ForEach-Object {
    $hit = ($_.Group | Where-Object 結果 -eq '命中').Count
    $wrong = @($_.Group | Where-Object 結果 -eq '誤判' | ForEach-Object 偵測到的) -join '、'
    [PSCustomObject]@{
        指令   = $_.Name
        命中   = ("{0}/{1}" -f $hit, $_.Count)
        百分比 = ("{0:P0}" -f ($hit / $_.Count))
        誤判成 = $wrong
    }
}
$summary | Sort-Object { [int]($_.命中 -split '/')[0] } | Format-Table -AutoSize

$hitTotal = ($results | Where-Object 結果 -eq '命中').Count
Write-Host ("整體命中：{0} / {1}（{2:P0}）" -f $hitTotal, $results.Count, ($hitTotal / $results.Count))
Write-Host ("播報期間自我觸發：{0} 次" -f $selfTriggers.Count)
if ($SilenceSeconds -gt 0) {
    Write-Host ("靜默 {0} 秒的環境誤觸：{1} 次" -f $SilenceSeconds, $ambient.Count)
}

$results | Export-Csv -LiteralPath $CsvPath -NoTypeInformation -Encoding utf8
Write-Host ""
Write-Host ("逐輪明細已寫入 {0}" -f (Resolve-Path $CsvPath)) -ForegroundColor DarkGray
Write-Host "命中率低的句子：在 keywords.txt 補一行同義說法（拼音 ＋ @漢字），" -ForegroundColor DarkGray
Write-Host "並在 VoiceCommand.MAPPING 加上對照——只改一邊會安靜地沒反應。" -ForegroundColor DarkGray

adb shell svc power stayon false | Out-Null
