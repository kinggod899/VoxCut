#requires -Version 5.1
<#
    VoxCut release signing setup.

    Run this yourself. The agent's safety layer blocks it from touching
    keystore files (rule: secret.ext.jks), and that rule also forbids it from
    working around the block - so this step is yours by design, not by choice.

    What it does:
      1. creates <StoreDir>\voxcut-release.jks (RSA 4096, ~30 years)
      2. writes keystore.properties at the repository root (git-ignored)
      3. builds app-release.apk and prints its sha256

    Keep both the keystore and the password. Lose either and you can never
    ship an update that installs over an already released build.
#>
[CmdletBinding()]
param(
    [string]$StoreDir,
    [string]$Alias = 'voxcut',
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is not populated while param defaults are evaluated under
# Windows PowerShell 5.1, so every path is resolved here instead.
$scriptPath = $MyInvocation.MyCommand.Path
$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot }
              elseif ($scriptPath) { Split-Path -Parent $scriptPath }
              else { (Get-Location).Path }

if (-not $StoreDir) { $StoreDir = Join-Path $env:USERPROFILE '.voxcut' }
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path }

function Find-Tool {
    param([string]$Name)

    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    # Common JDK locations; add your own if the JDK lives elsewhere.
    $roots = @(
        'E:\game\rea-tools',
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
    )
    $hits = foreach ($root in $roots) {
        if (Test-Path $root) {
            Get-ChildItem -Path $root -Filter $Name -Recurse -Depth 3 -ErrorAction SilentlyContinue
        }
    }
    if (-not $hits) { return $null }
    # JDK 17's trust store can reject services.gradle.org behind a TLS proxy;
    # prefer a JDK 21+ when the environment pins nothing.
    $preferred = $hits | Where-Object { $_.FullName -match 'jdk-2[1-9]|jdk-[3-9][0-9]' } |
                 Select-Object -First 1
    if ($preferred) { return $preferred.FullName }
    return ($hits | Select-Object -First 1).FullName
}

$storePath = Join-Path $StoreDir 'voxcut-release.jks'
$propsPath = Join-Path $RepoRoot 'keystore.properties'

if (-not (Test-Path $StoreDir)) { New-Item -ItemType Directory -Force -Path $StoreDir | Out-Null }

if (Test-Path $storePath) {
    Write-Host "[=] keystore already present: $storePath" -ForegroundColor Yellow
    Write-Host "    delete it only if you accept that every future update needs the new key" -ForegroundColor Yellow
} else {
    $secure = Read-Host -Prompt 'Keystore password (ASCII only, keep it safe)' -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    if ([string]::IsNullOrWhiteSpace($plain)) { throw 'password must not be empty' }

    $keytool = Find-Tool -Name 'keytool.exe'
    if (-not $keytool) { throw 'keytool.exe not found - install a JDK 17+ or put its bin directory on PATH' }
    Write-Host "[i] keytool: $keytool"

    & $keytool -genkeypair -v `
        -keystore $storePath `
        -alias $Alias `
        -keyalg RSA -keysize 4096 -validity 10950 `
        -storepass $plain -keypass $plain `
        -dname 'CN=VoxCut, OU=Open Source, O=kinggod899, C=CN'
    if ($LASTEXITCODE -ne 0) { throw "keytool exited with $LASTEXITCODE" }

    # Java .properties treats backslash as an escape character, so the path
    # written here must use forward slashes or C:\Users becomes C:Users.
    @(
        "storeFile=$($storePath -replace '\\','/')"
        "storePassword=$plain"
        "keyAlias=$Alias"
        "keyPassword=$plain"
    ) | Set-Content -Path $propsPath -Encoding ASCII

    Write-Host "[+] keystore: $storePath" -ForegroundColor Green
    Write-Host "[+] wrote   : $propsPath" -ForegroundColor Green
}

$javaHome = $env:JAVA_HOME
if ($javaHome -and (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    Write-Host "[i] JAVA_HOME=$javaHome (environment)"
} else {
    $java = Find-Tool -Name 'java.exe'
    if ($java) {
        $javaHome = Split-Path (Split-Path $java -Parent) -Parent
        $env:JAVA_HOME = $javaHome
        Write-Host "[i] JAVA_HOME=$javaHome"
    }
}

$gradlew = Join-Path $RepoRoot 'gradlew.bat'
Write-Host ''
Write-Host 'Building the signed release...'
Push-Location $RepoRoot
try {
    & $gradlew assembleRelease --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradlew exited with $LASTEXITCODE" }
} finally {
    Pop-Location
}

$apk = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) {
    Write-Host "[!] $apk not found - check the gradle output above" -ForegroundColor Red
    exit 1
}

$hash = (Get-FileHash -Algorithm SHA256 -Path $apk).Hash.ToLower()
Write-Host ''
Write-Host "[+] signed package: $apk" -ForegroundColor Green
Write-Host "[+] sha256        : $hash" -ForegroundColor Green
Write-Host ''
Write-Host 'Send that path back to the agent and it will tag v1.0.0 and publish the release.'
