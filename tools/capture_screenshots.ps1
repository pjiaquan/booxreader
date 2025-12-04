
$package = "my.hinoki.booxreader.foss"
$outDir = "fastlane\metadata\android\en-US\images\phoneScreenshots"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Take-Screenshot ($name) {
    Write-Host "Taking screenshot: $name"
    adb shell screencap -p /sdcard/screencap.png
    adb pull /sdcard/screencap.png "$outDir\$name.png"
    adb shell rm /sdcard/screencap.png
}

# 1. Main Activity
Write-Host "Launching Main Activity..."
adb shell am start -n "$package/my.hinoki.booxreader.data.ui.main.MainActivity"
Start-Sleep -Seconds 3
Take-Screenshot "1_main_screen"

# 2. Login Activity
Write-Host "Launching Login Activity..."
adb shell am start -n "$package/my.hinoki.booxreader.data.auth.LoginActivity"
Start-Sleep -Seconds 2
Take-Screenshot "2_login_screen"

# 3. AI Note List (Empty state likely)
Write-Host "Launching AI Note List..."
adb shell am start -n "$package/my.hinoki.booxreader.data.ui.notes.AiNoteListActivity"
Start-Sleep -Seconds 2
Take-Screenshot "3_ai_notes"

# Return to Main
adb shell am start -n "$package/my.hinoki.booxreader.data.ui.main.MainActivity"

Write-Host "Done! Screenshots saved to $outDir"
