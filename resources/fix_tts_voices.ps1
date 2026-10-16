# GEK - Fix TTS Voices (OneCore → SAPI5 registry copy)
# Right-click > Run with PowerShell as Administrator
#
# Windows 10 language packs install OneCore voices only.
# pyttsx3 uses SAPI5 which reads from a different registry path.
# This script copies OneCore voice entries to SAPI5 so pyttsx3 can find them.
# Run this after installing any new language packs.

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

$ErrorActionPreference = "Continue"

Write-Host "`n=== GEK Fix TTS Voices ===" -ForegroundColor Cyan
Write-Host "Time: $(Get-Date)"

$oneCorePath = 'HKLM:\SOFTWARE\Microsoft\Speech_OneCore\Voices\Tokens'
$sapi5Path = 'HKLM:\SOFTWARE\Microsoft\Speech\Voices\Tokens'
$sapi5Path32 = 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\SPEECH\Voices\Tokens'

# Ensure SAPI5 paths exist
foreach ($p in @($sapi5Path, $sapi5Path32)) {
    if (-not (Test-Path $p)) {
        New-Item -Path $p -Force | Out-Null
        Write-Host "  Created: $p" -ForegroundColor Yellow
    }
}

Write-Host "`nOneCore path: $oneCorePath"
Write-Host "SAPI5 path:   $sapi5Path"

if (-not (Test-Path $oneCorePath)) {
    Write-Host "`nERROR: OneCore voice registry not found!" -ForegroundColor Red
    Write-Host "No language packs appear to be installed."
    Read-Host "Press Enter to exit"
    exit 1
}

$voices = Get-ChildItem $oneCorePath -ErrorAction SilentlyContinue
Write-Host "`nOneCore voices found: $($voices.Count)"

$copied = 0
$skipped = 0

foreach ($voice in $voices) {
    $voiceId = $voice.PSChildName
    $voiceName = (Get-ItemProperty $voice.PSPath).'(default)'
    
    # Check if already in SAPI5
    $existingSapi5 = Join-Path $sapi5Path $voiceId
    if (Test-Path $existingSapi5) {
        Write-Host "  SKIP: $voiceId ($voiceName)" -ForegroundColor DarkGray
        $skipped++
        continue
    }
    
    Write-Host "  COPY: $voiceId ($voiceName)" -ForegroundColor Green
    
    # Copy to SAPI5 (64-bit)
    try {
        Copy-Item -Path $voice.PSPath -Destination $sapi5Path -Recurse -Force
        $destVoice = Join-Path $sapi5Path $voiceId
        Remove-ItemProperty -Path $destVoice -Name "SayAsSupport" -ErrorAction SilentlyContinue
        Write-Host "    → SAPI5 (64-bit): OK" -ForegroundColor DarkGreen
    } catch {
        Write-Host "    → SAPI5 (64-bit): FAILED - $($_.Exception.Message)" -ForegroundColor Red
    }
    
    # Copy to SAPI5 (32-bit)
    try {
        Copy-Item -Path $voice.PSPath -Destination $sapi5Path32 -Recurse -Force
        $destVoice32 = Join-Path $sapi5Path32 $voiceId
        Remove-ItemProperty -Path $destVoice32 -Name "SayAsSupport" -ErrorAction SilentlyContinue
        Write-Host "    → SAPI5 (32-bit): OK" -ForegroundColor DarkGreen
    } catch {
        Write-Host "    → SAPI5 (32-bit): FAILED - $($_.Exception.Message)" -ForegroundColor Red
    }
    
    $copied++
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
Write-Host "  Copied:  $copied" -ForegroundColor Green
Write-Host "  Skipped: $skipped (already in SAPI5)"

Write-Host "`n--- All SAPI5 Voices ---" -ForegroundColor Cyan
$sapi5All = Get-ChildItem $sapi5Path -ErrorAction SilentlyContinue
foreach ($v in $sapi5All) {
    $name = (Get-ItemProperty $v.PSPath).'(default)'
    Write-Host "  $($v.PSChildName) = $name"
}

Write-Host "`nDone. pyttsx3 should now detect all voices."
Read-Host "Press Enter to exit"
