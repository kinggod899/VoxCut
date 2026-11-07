#requires -Version 5.1
<#
    VoxCut release signing setup.

    Run this yourself. The agent's safety layer blocks it from touching
    keystore files (rule: secret.ext.jks), and that rule also forbids it from
    working around the block - so this step is yours by design, not by choice.

    What it does:
      1. creates <StoreDir>\voxcut-release.jks (RSA 4096, ~30 years)
      2. writes keystore.properties at the repository root (git-ignored)
      3. prints the gradle command that builds the signed APK

    Keep both the keystore and the password. Lose either and you can never
    ship an update that installs over an already released build.
#>
[CmdletBinding()]
param(
    [string]$StoreDir = (Join-Path $env:USERPROFILE '.voxcut'),
    [string]$Alias    = 'voxcut',
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

$storePath = Join-Path $StoreDir 'voxcut-release.jks'
if (-not (Test-Path $StoreDir)) { New-Item -ItemType Directory -Force -Path $StoreDir | Out-Null }

if (Test-Path $storePath) {
    Write-Host "[=] keystore already present: $storePath" -ForegroundColor Yellow
    Write-Host "    delete it only if you accept that every future update needs the new key" -ForegroundColor Yellow
} else {
    $secure = Read-Host -Prompt 'Keystore password (ASCII only, keep it safe)' -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    if ([string]::IsNullOrWhiteSpace($plain)) { throw 'password must not be empty' }

    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { throw 'keytool not found - put your JDK bin directory on PATH first' }

    & $keytool -genkeypair -v `
        -keystore $storePath `
        -alias $Alias `
        -keyalg RSA -keysize 4096 -validity 10950 `
        -storepass $plain -keypass $plain `
        -dname 'CN=VoxCut, OU=Open Source, O=kinggod899, C=CN'
    if ($LASTEXITCODE -ne 0) { throw "keytool exited with $LASTEXITCODE" }

    # Java .properties treats backslash as an escape character, so the path
    # written here must use forward slashes or C:\Users becomes C:Users.
    $propsPath = Join-Path $RepoRoot 'keystore.properties'
    @(
        "storeFile=$($storePath -replace '\\','/')"
        "storePassword=$plain"
        "keyAlias=$Alias"
        "keyPassword=$plain"
    ) | Set-Content -Path $propsPath -Encoding ASCII

    Write-Host "[+] wrote $propsPath" -ForegroundColor Green
    Write-Host "[+] keystore: $storePath" -ForegroundColor Green
}

Write-Host ''
Write-Host 'Next:'
Write-Host "  cd `"$RepoRoot`""
Write-Host '  .\gradlew assembleRelease'
Write-Host ''
Write-Host 'Signed package lands in app\build\outputs\apk\release\app-release.apk'
