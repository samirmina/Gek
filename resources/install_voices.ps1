# GEK - Voice Install v5 (language packs + OneCore→SAPI5 registry copy)
# Right-click > Run with PowerShell as Administrator

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

$ErrorActionPreference = "Continue"
$log = @()
function Log($msg) { Write-Host $msg; $script:log += $msg }

Log "=== GEK Voice Install v4 ==="
Log "Time: $(Get-Date)"

# Step 1: Verify current status with correct names
Log "`n=== STEP 1: Current Status ==="
$tags = @("ar-SA","de-DE","fr-FR","es-ES","ja-JP","zh-CN","ko-KR","pl-PL","nl-NL")

$needed = @()
foreach ($tag in $tags) {
    $r = & dism /online /get-capability-info /capabilityname:"Language.TextToSpeech~~~$tag~0.0.1.0" 2>&1
    $stateLine = $r | Select-String "State :"
    $state = if ($stateLine) { ($stateLine -split ":")[1].Trim() } else { "UNKNOWN" }
    
    $r2 = & dism /online /get-capability-info /capabilityname:"Language.Basic~~~$tag~0.0.1.0" 2>&1
    $basicLine = $r2 | Select-String "State :"
    $basic = if ($basicLine) { ($basicLine -split ":")[1].Trim() } else { "UNKNOWN" }
    
    Log "  $tag - Basic: $basic | TextToSpeech: $state"
    if ($state -ne "Installed") { $needed += $tag }
}

Log "`n  Languages needing TextToSpeech: $($needed.Count)"
$needed | ForEach-Object { Log "    $_" }

if ($needed.Count -eq 0) {
    Log "`n  All languages already have TextToSpeech installed!"
    Read-Host "Press Enter to exit"
    exit
}

# Step 2: Start WU + disable GPO
Log "`n=== STEP 2: Starting WU ==="
$gpoPath = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU"
$gpoBackup = $null
try {
    if (Test-Path $gpoPath) {
        $gpoBackup = (Get-ItemProperty -Path $gpoPath -Name "NoAutoUpdate" -ErrorAction SilentlyContinue).NoAutoUpdate
    }
    if (-not (Test-Path $gpoPath)) { New-Item -Path $gpoPath -Force | Out-Null }
    Set-ItemProperty -Path $gpoPath -Name "NoAutoUpdate" -Value 0 -Type DWord
    Log "  NoAutoUpdate: 0"
} catch { Log "  GPO error: $($_.Exception.Message)" }

Start-Service wuauserv -ErrorAction SilentlyContinue
Start-Service BITS -ErrorAction SilentlyContinue
Start-Sleep 3
Log "  wuauserv: $((Get-Service wuauserv).Status)"
Log "  BITS: $((Get-Service BITS).Status)"

# Step 3: Install Language.TextToSpeech for each needed language
Log "`n=== STEP 3: Installing Language.TextToSpeech ==="
$success = 0
$failed = 0

foreach ($tag in $needed) {
    $capName = "Language.TextToSpeech~~~$tag~0.0.1.0"
    Log "`n  Installing: $capName"
    try {
        $result = & dism /online /add-capability /capabilityname:$capName 2>&1
        foreach ($line in $result) {
            $t = $line.Trim()
            if ($t -ne "") { Log "    $t" }
        }
        
        # Check if it actually installed
        $check = & dism /online /get-capability-info /capabilityname:$capName 2>&1
        $checkState = $check | Select-String "State :"
        if ($checkState) {
            $s = ($checkState -split ":")[1].Trim()
            if ($s -eq "Installed") {
                Log "  SUCCESS: $tag TextToSpeech installed"
                $success++
            } else {
                Log "  FAILED: $tag state is $s"
                $failed++
            }
        }
    } catch {
        Log "  ERROR: $($_.Exception.Message)"
        $failed++
    }
}

# Step 4: Cleanup
Log "`n=== STEP 4: Cleanup ==="
Stop-Service wuauserv -Force -ErrorAction SilentlyContinue
Log "  wuauserv: $((Get-Service wuauserv).Status)"

try {
    if ($null -ne $gpoBackup) {
        Set-ItemProperty -Path $gpoPath -Name "NoAutoUpdate" -Value $gpoBackup -Type DWord
        Log "  NoAutoUpdate restored to: $gpoBackup"
    } else {
        Remove-ItemProperty -Path $gpoPath -Name "NoAutoUpdate" -ErrorAction SilentlyContinue
        Log "  NoAutoUpdate removed"
    }
} catch { Log "  GPO restore error: $($_.Exception.Message)" }

# Step 5: Copy OneCore voices to SAPI5 registry (fixes pyttsx3 detection)
Log "`n=== STEP 5: OneCore → SAPI5 Registry Copy ==="
$oneCorePath = 'HKLM:\SOFTWARE\Microsoft\Speech_OneCore\Voices\Tokens'
$sapi5Path = 'HKLM:\SOFTWARE\Microsoft\Speech\Voices\Tokens'
$sapi5Path32 = 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\SPEECH\Voices\Tokens'

$copied = 0
if (Test-Path $oneCorePath) {
    $voices = Get-ChildItem $oneCorePath -ErrorAction SilentlyContinue
    Log "  OneCore voices found: $($voices.Count)"
    
    foreach ($voice in $voices) {
        $voiceId = $voice.PSChildName
        $voiceName = (Get-ItemProperty $voice.PSPath).'(default)'
        
        # Check if already in SAPI5
        $existingSapi5 = Join-Path $sapi5Path $voiceId
        if (Test-Path $existingSapi5) {
            Log "  SKIP: $voiceId ($voiceName) - already in SAPI5"
            continue
        }
        
        Log "  COPY: $voiceId ($voiceName)"
        
        # Copy to SAPI5 (64-bit)
        try {
            Copy-Item -Path $voice.PSPath -Destination $sapi5Path -Recurse -Force
            # Remove SayAsSupport attribute (not needed for SAPI5)
            $destVoice = Join-Path $sapi5Path $voiceId
            Remove-ItemProperty -Path $destVoice -Name "SayAsSupport" -ErrorAction SilentlyContinue
            Log "    → SAPI5 (64-bit): OK"
        } catch {
            Log "    → SAPI5 (64-bit): FAILED - $($_.Exception.Message)"
        }
        
        # Copy to SAPI5 (32-bit)
        try {
            Copy-Item -Path $voice.PSPath -Destination $sapi5Path32 -Recurse -Force
            $destVoice32 = Join-Path $sapi5Path32 $voiceId
            Remove-ItemProperty -Path $destVoice32 -Name "SayAsSupport" -ErrorAction SilentlyContinue
            Log "    → SAPI5 (32-bit): OK"
        } catch {
            Log "    → SAPI5 (32-bit): FAILED - $($_.Exception.Message)"
        }
        
        $copied++
    }
    Log "  Copied: $copied voices"
} else {
    Log "  WARNING: OneCore path not found: $oneCorePath"
}

# Step 6: Final status
Log "`n=== STEP 6: Final Status ==="
foreach ($tag in $tags) {
    $r = & dism /online /get-capability-info /capabilityname:"Language.TextToSpeech~~~$tag~0.0.1.0" 2>&1
    $s = $r | Select-String "State :"
    $state = if ($s) { ($s -split ":")[1].Trim() } else { "UNKNOWN" }
    Log "  $tag - TextToSpeech: $state"
}

Log "`n--- SAPI5 Voices (pyttsx3 should detect these) ---"
$vPath = "HKLM:\SOFTWARE\Microsoft\Speech\Voices\Tokens"
if (Test-Path $vPath) {
    Get-ChildItem $vPath | ForEach-Object {
        $name = (Get-ItemProperty $_.PSPath).'(default)'
        Log "  $($_.PSChildName) = $name"
    }
} else {
    Log "  WARNING: SAPI5 path not found"
}

Log "`n=== SUMMARY ==="
Log "  Installed: $success | Copied to SAPI5: $copied | Failed: $failed"
Log "  Log: D:\gek\voice_install_log.txt"
Log ""
Log "  pyttsx3 should now detect all voices."
Log "  For future language packs, run fix_tts_voices.ps1"

$log | Out-File "D:\gek\voice_install_log.txt" -Encoding UTF8
Read-Host "Press Enter to exit"
