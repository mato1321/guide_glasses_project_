# 語音指令辨識率測試
#
# 眼鏡上沒有畫面也沒有鍵盤，唯一能測語音的方式就是「人對著它講、看 logcat」。
# 這個腳本把那件事變成照著唸就好：它會逐條提示你要說什麼、觸發聆聽、
# 收集結果，最後列出對照表。
#
# 用法：
#   cd guide-glasses\tools
#   .\test_commands.ps1
#
# 只測某幾條：
#   .\test_commands.ps1 -Only "這是誰","唸給我聽"

param(
    [string[]]$Only = @(),
    # 每條指令給多少秒說話。太短會來不及，太長則整輪測試很久。
    [int]$SpeakSeconds = 9
)

# PowerShell 主控台預設用 cp950 解碼子行程輸出，logcat 的 UTF-8 會變亂碼。
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$Commands = @(
    @{ Say = '前面有什麼';   Expect = 'DETECT_OBSTACLES' }
    @{ Say = '這是誰';       Expect = 'IDENTIFY_PERSON' }
    @{ Say = '唸給我聽';     Expect = 'READ_TEXT' }
    @{ Say = '這是哪裡';     Expect = 'READ_SIGN' }
    @{ Say = '翻成英文';     Expect = 'TRANSLATE' }
    @{ Say = '下一段';       Expect = 'READING_NEXT' }
    @{ Say = '上一段';       Expect = 'READING_PREVIOUS' }
    @{ Say = '再說一次';     Expect = 'REPEAT_LAST' }
    @{ Say = '測試相機';     Expect = 'CAMERA_TEST' }
    @{ Say = '測試感測器';   Expect = 'SENSOR_TEST' }
    @{ Say = '出門前檢查';   Expect = 'READINESS_CHECK' }
    @{ Say = '準備翻譯';     Expect = 'PREPARE_TRANSLATION' }
    @{ Say = '同步人臉';     Expect = 'SYNC_PEOPLE' }
    @{ Say = '停';           Expect = 'STOP' }
)

if ($Only.Count -gt 0) {
    $Commands = $Commands | Where-Object { $Only -contains $_.Say }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "找不到 adb，請先把 platform-tools 加進 PATH" -ForegroundColor Red
    exit 1
}
if (-not (adb devices | Select-String '\sdevice$')) {
    Write-Host "眼鏡沒有連上，請接上 USB" -ForegroundColor Red
    exit 1
}

# 螢幕逾時只有 5 秒，睡著之後 App 會被切到背景。
adb shell svc power stayon true | Out-Null

$results = @()
foreach ($cmd in $Commands) {
    # 每次都把 Activity 拉回前景 —— launcher 會把焦點搶走，
    # 而背景限制沒解除時 App 會被系統回收。
    adb shell am start -n com.guideglasses/.MainActivity 2>&1 | Out-Null
    Start-Sleep -Milliseconds 800
    adb logcat -c

    Write-Host ""
    Write-Host ("=" * 50)
    Write-Host ("  請說：  {0}" -f $cmd.Say) -ForegroundColor Cyan
    Write-Host ("=" * 50)
    Start-Sleep -Seconds 2

    adb shell am broadcast -a com.guideglasses.DEBUG --es cmd LISTEN 2>&1 | Out-Null
    Write-Host "  >>> 現在說 <<<" -ForegroundColor Yellow
    Start-Sleep -Seconds $SpeakSeconds

    $log = adb logcat -d | Select-String 'OfflineAsr: (辨識完成|逾時)'
    $heard = if ($log) { ($log | Select-Object -Last 1).ToString() } else { '(沒有結果)' }

    if ($heard -match '辨識完成：「(.+?)」') {
        $text = $Matches[1]
        Write-Host ("  聽到：{0}" -f $text) -ForegroundColor Green
    } else {
        $text = '(沒聽到)'
        Write-Host "  沒聽到" -ForegroundColor Red
    }

    $results += [PSCustomObject]@{
        該說的 = $cmd.Say
        聽到的 = $text
        預期意圖 = $cmd.Expect
    }

    # 等它把回應播完，否則下一輪會聽到自己講話。
    Start-Sleep -Seconds 6
}

Write-Host ""
Write-Host "=============== 結果 ===============" -ForegroundColor Cyan
$results | Format-Table -AutoSize

$ok = ($results | Where-Object { $_.聽到的 -ne '(沒聽到)' }).Count
Write-Host ("有聽到：{0} / {1}" -f $ok, $results.Count)
Write-Host ""
Write-Host "「聽到的」是簡體字是正常的 —— 模型是 zh-CN 訓練的，" -ForegroundColor DarkGray
Write-Host "比對前會做繁簡摺疊（SpokenText.forMatching）。" -ForegroundColor DarkGray
Write-Host "要確認有沒有真的執行對的功能，看播報內容或："  -ForegroundColor DarkGray
Write-Host "  adb logcat -d | Select-String 'AssistantVM|OfflineTts'" -ForegroundColor DarkGray
