#Requires -Version 5

#
# To run this script remotely type this in your PowerShell
# (where <args>... are the arguments you want to pass to JBang):
#   iex "& { $(iwr -useb https://ps.jbang.dev) } <args>..."
#
# An alternative way is to type:
#   & ([scriptblock]::Create($(iwr -useb https://ps.jbang.dev))) <args>...
# Which even allows you to store the command in a variable for re-use:
#   $jbang = ([scriptblock]::Create($(iwr -useb https://ps.jbang.dev)))
#   & $jbang <args>...
#
# This script is also the engine behind jbang.cmd: when it is started with
# JBANG_RUNTIME_SHELL=cmd it does not execute the command JBang asks it to
# run (exit code 255) but prints it so that jbang.cmd can run it instead.
#

$old_erroractionpreference=$erroractionpreference
$erroractionpreference='stop'
$old_progresspreference=$global:progresspreference
$global:progresspreference='SilentlyContinue'

# Terminates the script with the given exit code. When this script was run
# from a file `exit` is safe to use, when it was run from a remote script
# block (see above) we only stop the script block instead of killing the shell.
function Stop-Script {
    param([int]$code)
    $global:progresspreference=$old_progresspreference
    if ($PSCommandPath) { exit $code }
    break
}

# Prints an error message and terminates the script with exit code 1
function Fail {
    param([string]$msg)
    [Console]::Error.WriteLine($msg)
    Stop-Script 1
}

# Check that we're running the correct PowerShell
if (($PSVersionTable.PSVersion.Major) -lt 5) {
    Fail ("PowerShell 5 or later is required to run. For instruction on how to update,`n" +
          "see: https://docs.microsoft.com/en-us/powershell/scripting/setup/installing-windows-powershell")
}

# Check that the correct Execution Policy is set
$allowedExecutionPolicy = @('Unrestricted', 'RemoteSigned', 'ByPass')
if ((Get-ExecutionPolicy).ToString() -notin $allowedExecutionPolicy) {
    Fail ("PowerShell requires an execution policy in [$($allowedExecutionPolicy -join ", ")] to continue.`n" +
          "For example, to set the execution policy to 'RemoteSigned' please run :`n" +
          "'Set-ExecutionPolicy RemoteSigned -scope CurrentUser'")
}

if ([System.Enum]::GetNames([System.Net.SecurityProtocolType]) -notcontains 'Tls12') {
    Fail (".NET Framework 4.5 or later is required to run. For instructions on how to update,`n" +
          "see: https://www.microsoft.com/net/download")
}

# JBang itself only needs a JVM to run; which one hardly matters, so when the
# machine has none we simply fetch the newest Temurin of this feature version.
$bootstrapJavaVersion = 25
# The oldest Java that can run jbang.jar
$minJavaVersion = 11
# Where the JVM index lives. It is the same index jbang.jar uses to install the
# JDKs that scripts ask for with //JAVA, published on Maven Central, so no JDK
# discovery service is involved. Override for a corporate mirror.
$jvmIndexBaseUrl = if ($env:JBANG_JVM_INDEX_BASEURL) { $env:JBANG_JVM_INDEX_BASEURL } else { 'https://repo1.maven.org/maven2' }


$JBDIR = if ($env:JBANG_DIR) { $env:JBANG_DIR } else { "$env:userprofile\.jbang" }
$TDIR = if ($env:JBANG_CACHE_DIR) { $env:JBANG_CACHE_DIR } else { "$JBDIR\cache" }
$useNative = ($env:JBANG_USE_NATIVE -eq 'true')

# Base URL for downloading JBang releases.
# Override for testing or corporate mirrors.
# Example: $env:JBANG_DOWNLOAD_BASEURL='http://localhost:18080'
$jbangDownloadBaseUrl = if ($env:JBANG_DOWNLOAD_BASEURL) { $env:JBANG_DOWNLOAD_BASEURL } else { 'https://github.com/instreest/jbang/releases' }

# Base URL the wrapper (jbangw) downloads jbang.jar from. The wrapper is
# installed into a project with install.sh/install.cmd, which records the
# repository and revision to take the jar from in jbanglite.properties next to
# this script. Override for testing or a corporate mirror.
$jbangRawBaseUrl = if ($env:JBANGLITE_RAW_BASEURL) { $env:JBANGLITE_RAW_BASEURL } else { 'https://raw.githubusercontent.com' }

# Number of retry attempts for downloads
$downloadRetry = if ($env:JBANG_DOWNLOAD_RETRY) { [int]$env:JBANG_DOWNLOAD_RETRY } else { 5 }
$downloadRetryDelay = if ($env:JBANG_DOWNLOAD_RETRY_DELAY) { [int]$env:JBANG_DOWNLOAD_RETRY_DELAY } else { 0 }

# Are we running on behalf of jbang.cmd?
$fromCmd = ($env:JBANG_RUNTIME_SHELL -eq 'cmd')

function Invoke-Download {
    param([string]$url, [string]$outFile)
    $attempt=0
    while ($true) {
        $attempt++
        try {
            Invoke-WebRequest "$url" -OutFile "$outFile"
            return $true
        } catch {
            if ($attempt -gt $downloadRetry) {
                return $false
            }
            if ($downloadRetryDelay -gt 0) {
                $sleepSeconds = $downloadRetryDelay
            } else {
                # Exponential backoff: 1, 2, 4, 8, ...
                $sleepSeconds = [Math]::Pow(2, $attempt - 1)
            }
            [Console]::Error.WriteLine("Download $attempt/$($downloadRetry + 1) failed. Retry in $sleepSeconds second(s)...")
            if ($attempt -eq 1) {
                [Console]::Error.WriteLine("(Set JBANG_DOWNLOAD_RETRY=0 to disable retries)")
            }
            Start-Sleep -Seconds $sleepSeconds
        }
    }
}

# Downloads and installs JBang into $JBDIR\bin
function Install-JBang {
    $bundleName = if ($useNative) { "jbang-windows-${jbang_arch}.zip" } else { "jbang.zip" }
    if ($env:JBANG_DOWNLOAD_URL) {
        $jburl=$env:JBANG_DOWNLOAD_URL
    } elseif (-not $env:JBANG_DOWNLOAD_VERSION) {
        $jburl="$jbangDownloadBaseUrl/latest/download/$bundleName"
    } else {
        # Numeric versions get a 'v' prefix (e.g. 0.120.0 -> v0.120.0); named
        # release tags (e.g. 'early-access', '1.0.0-rc1') are used as-is.
        $jbtag = if ($env:JBANG_DOWNLOAD_VERSION -match '^[0-9]+(\.[0-9]+)*$') { "v$env:JBANG_DOWNLOAD_VERSION" } else { $env:JBANG_DOWNLOAD_VERSION }
        $jburl="$jbangDownloadBaseUrl/download/$jbtag/$bundleName"
    }
    $dlVersion = if ($env:JBANG_DOWNLOAD_VERSION) { $env:JBANG_DOWNLOAD_VERSION } else { 'latest' }
    New-Item -ItemType Directory -Force -Path "$TDIR\urls" >$null 2>&1
    [Console]::Error.WriteLine("Downloading JBang $dlVersion from $jburl...")
    if (-not (Invoke-Download "$jburl" "$TDIR\urls\jbang.zip")) {
        Fail "Error downloading JBang from $jburl to $TDIR\urls\jbang.zip"
    }
    [Console]::Error.WriteLine("Installing JBang...")
    Remove-Item -LiteralPath "$TDIR\urls\jbang" -Force -Recurse -ErrorAction Ignore >$null 2>&1
    try {
        Expand-Archive -Path "$TDIR\urls\jbang.zip" -DestinationPath "$TDIR\urls"
    } catch {
        Fail "Error unzipping JBang from $TDIR\urls\jbang.zip to $TDIR\urls`n$_"
    }
    New-Item -ItemType Directory -Force -Path "$JBDIR\bin" >$null 2>&1
    Remove-Item -LiteralPath "$JBDIR\bin\jbang" -Force -ErrorAction Ignore >$null 2>&1
    Remove-Item -Path "$JBDIR\bin\jbang.*" -Force -ErrorAction Ignore >$null 2>&1
    Copy-Item -Path "$TDIR\urls\jbang\bin\*" -Destination "$JBDIR\bin" -Force >$null 2>&1
}

# The name of the JVM index for this platform
function Get-JvmIndexPlatform {
    $indexArch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64) { 'arm64' } else { 'amd64' }
    return "windows-$indexArch"
}

# Returns @{ Version; Type; Url } of the newest Temurin $bootstrapJavaVersion in
# the JVM index, the same index jbang.jar uses for the JDKs that //JAVA asks for
function Get-JvmIndexEntry {
    param([string]$platform)
    New-Item -ItemType Directory -Force -Path "$TDIR" >$null 2>&1
    $base = "$jvmIndexBaseUrl/io/get-coursier/jvm/indices/index-$platform"
    if (-not (Invoke-Download "$base/maven-metadata.xml" "$TDIR\jvm-index.xml")) {
        Fail "Could not read the JVM index from $base"
    }
    $metaVersion = ([xml](Get-Content -LiteralPath "$TDIR\jvm-index.xml")).metadata.versioning.release
    if (-not $metaVersion) { Fail "Could not determine the newest JVM index version" }
    if (-not (Invoke-Download "$base/$metaVersion/index-$platform-$metaVersion.jar" "$TDIR\jvm-index.jar")) {
        Fail "Could not download the JVM index"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead("$TDIR\jvm-index.jar")
    try {
        $entry = $zip.GetEntry("coursier/jvm/indices/v1/$platform.json")
        if (-not $entry) { Fail "The JVM index has no data for $platform" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $json = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }

    $best = $null
    foreach ($p in $json.temurin.PSObject.Properties) {
        if ($p.Name -ne "$bootstrapJavaVersion" -and -not $p.Name.StartsWith("$bootstrapJavaVersion.")) { continue }
        $plus = $p.Value.IndexOf('+')
        $candidate = @{ Version = $p.Name; Type = $p.Value.Substring(0, $plus); Url = $p.Value.Substring($plus + 1) }
        if (-not $best -or (Compare-JavaVersion $candidate.Version $best.Version) -gt 0) { $best = $candidate }
    }
    return $best
}

# Compares two dotted versions numerically, returning -1, 0 or 1
function Compare-JavaVersion {
    param([string]$a, [string]$b)
    $x = $a -split '[^0-9]+' | Where-Object { $_ -ne '' }
    $y = $b -split '[^0-9]+' | Where-Object { $_ -ne '' }
    for ($i = 0; $i -lt [Math]::Max($x.Count, $y.Count); $i++) {
        $xi = if ($i -lt $x.Count) { [int]$x[$i] } else { 0 }
        $yi = if ($i -lt $y.Count) { [int]$y[$i] } else { 0 }
        if ($xi -ne $yi) { return $(if ($xi -lt $yi) { -1 } else { 1 }) }
    }
    return 0
}

# Downloads the newest Temurin $bootstrapJavaVersion into $TDIR\jdks\bootstrap
function Install-BootstrapJdk {
    New-Item -ItemType Directory -Force -Path "$TDIR\jdks" >$null 2>&1
    $entry = Get-JvmIndexEntry (Get-JvmIndexPlatform)
    if (-not $entry) { Fail "No Temurin $bootstrapJavaVersion found in the JVM index" }
    # The index says how the archive is packed; keep its extension so the file
    # on disk matches what was downloaded, as jbang.jar does
    $type = if ($entry.Type -eq 'tgz') { 'tar.gz' } else { $entry.Type }
    $archive = "$TDIR\bootstrap-jdk.$type"

    [Console]::Error.WriteLine("No Java found. Downloading Temurin $($entry.Version). Be patient, this can take several minutes...")
    if (-not (Invoke-Download $entry.Url $archive)) { Fail "Error downloading JDK from $($entry.Url)" }

    if (Invoke-Download "$($entry.Url).sha256.txt" "$TDIR\bootstrap-jdk.sha256") {
        $expected = (Get-Content -LiteralPath "$TDIR\bootstrap-jdk.sha256" -Raw).Trim().Split()[0]
        $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower()
        if ($expected.ToLower() -ne $actual) {
            Remove-Item -LiteralPath $archive -Force -ErrorAction Ignore
            Fail "SHA-256 mismatch for $($entry.Url): expected $expected but got $actual"
        }
    } else {
        [Console]::Error.WriteLine("No published SHA-256 found for $($entry.Url), skipping verification")
    }

    [Console]::Error.WriteLine("Installing Temurin $($entry.Version)...")
    $tmpdir = "$TDIR\jdks\bootstrap.tmp"
    Remove-Item -LiteralPath "$tmpdir" -Force -Recurse -ErrorAction Ignore >$null 2>&1
    New-Item -ItemType Directory -Force -Path "$tmpdir" >$null 2>&1
    if ($type -eq 'zip') {
        try { Expand-Archive -Path $archive -DestinationPath "$tmpdir" } catch { Fail "Error installing JDK" }
    } else {
        & tar -xf "$archive" -C "$tmpdir"
        if ($LASTEXITCODE -ne 0) {
            Remove-Item -LiteralPath "$tmpdir" -Force -Recurse -ErrorAction Ignore >$null 2>&1
            Fail "Error installing JDK"
        }
    }
    foreach ($d in Get-ChildItem -Directory -Path "$tmpdir") {
        Move-Item -Path "$($d.FullName)\*" -Destination "$tmpdir" -Force
    }
    if (-not (Test-Path "$tmpdir\bin\java.exe")) {
        Remove-Item -LiteralPath "$tmpdir" -Force -Recurse -ErrorAction Ignore >$null 2>&1
        Fail "Error installing JDK"
    }
    Remove-Item -LiteralPath "$TDIR\jdks\bootstrap" -Force -Recurse -ErrorAction Ignore >$null 2>&1
    Rename-Item -Path "$tmpdir" -NewName "bootstrap" >$null 2>&1
    Remove-Item -LiteralPath $archive, "$TDIR\bootstrap-jdk.sha256" -Force -ErrorAction Ignore >$null 2>&1
}

# Returns the major version (e.g. 8, 11, 17) of the JDK in the given directory as
# read from its 'release' file, or $null when it cannot be determined.
function Get-JavaMajorVersion {
    param([string]$jdkHome)
    if (-not (Test-Path "$jdkHome\release")) { return $null }
    $m = Select-String -Path "$jdkHome\release" -Pattern '^JAVA_VERSION="?(\d+)(\.(\d+))?' | Select-Object -First 1
    if (-not $m) { return $null }
    $major = [int]$m.Matches[0].Groups[1].Value
    if ($major -eq 1) { $major = [int]$m.Matches[0].Groups[3].Value }
    return $major
}

# Determines the java to run the JAR with, fetching one when the machine has
# none. Any Java $minJavaVersion or newer will do; the JDK a script asks for
# with //JAVA is chosen by jbang.jar itself.
function Find-JavaExec {
    # The JDK JBang picked as the default
    if (Test-Java "$JBDIR\currentjdk") {
        $env:JAVA_HOME="$JBDIR\currentjdk"
        return "$JBDIR\currentjdk\bin\java.exe"
    }
    # The JDK this script downloaded on an earlier run
    $bootstrap = "$TDIR\jdks\bootstrap"
    if (Test-Java $bootstrap) {
        $env:JAVA_HOME=$bootstrap
        return "$bootstrap\bin\java.exe"
    }
    # Then JAVA_HOME, but only when it points to a Java that is recent enough
    if ($env:JAVA_HOME) {
        if (Test-Path "$env:JAVA_HOME\bin\java.exe") {
            $major = Get-JavaMajorVersion $env:JAVA_HOME
            if (-not $major) {
                [Console]::Error.WriteLine("JAVA_HOME is set but the Java version could not be determined, ignoring it")
            } elseif ($major -lt $minJavaVersion) {
                [Console]::Error.WriteLine("JAVA_HOME points to Java $major which is older than Java $minJavaVersion, ignoring it")
            } else {
                return "$env:JAVA_HOME\bin\java.exe"
            }
        } else {
            [Console]::Error.WriteLine("JAVA_HOME is set but does not seem to point to a Java runtime")
        }
    }
    # Nothing usable found, so fetch a JVM of our own
    Install-BootstrapJdk
    $env:JAVA_HOME=$bootstrap
    return "$bootstrap\bin\java.exe"
}

# True when $1 holds a Java new enough to run jbang.jar
function Test-Java {
    param([string]$jdkHome)
    if (-not (Test-Path "$jdkHome\bin\java.exe")) { return $false }
    $major = Get-JavaMajorVersion $jdkHome
    return ($major -and $major -ge $minJavaVersion)
}

# Downloads jbang.jar into <wrapper dir>\.jbang as told by jbanglite.properties
function Install-WrapperJar {
    $props = @{}
    foreach ($line in Get-Content -LiteralPath "$PSScriptRoot\jbanglite.properties") {
        if ($line -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
    }
    if (-not $props['repo'] -or -not $props['ref']) {
        Fail "$PSScriptRoot\jbanglite.properties does not name a repo and a ref to get jbang.jar from"
    }
    $url = "$jbangRawBaseUrl/$($props['repo'])/$($props['ref'])/dist/jbang.jar"
    $target = "$PSScriptRoot\.jbang\jbang.jar"
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\.jbang" >$null 2>&1
    [Console]::Error.WriteLine("Downloading JBangLite from $url...")
    if (-not (Invoke-Download $url "$target.tmp")) {
        Remove-Item -LiteralPath "$target.tmp" -Force -ErrorAction Ignore
        Fail "Error downloading JBangLite from $url"
    }
    if ($props['jarSha256']) {
        $actual = (Get-FileHash -LiteralPath "$target.tmp" -Algorithm SHA256).Hash.ToLower()
        if ($props['jarSha256'].ToLower() -ne $actual) {
            Remove-Item -LiteralPath "$target.tmp" -Force -ErrorAction Ignore
            Fail "SHA-256 mismatch for ${url}: expected $($props['jarSha256']) but got $actual"
        }
    }
    Move-Item -Path "$target.tmp" -Destination "$target" -Force
}

# detect architecture for platform-specific binary lookup
$jbang_arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64) { "aarch64" } else { "x64" }

# resolve native binary or jar path from script location
$binaryPath=""
$jarPath=""
if ($useNative) {
    # Look for platform-specific native binary first, then fall back to jbang.bin.exe
    if (Test-Path "$PSScriptRoot\jbang.bin-windows-${jbang_arch}.exe") {
        $binaryPath="$PSScriptRoot\jbang.bin-windows-${jbang_arch}.exe"
    } elseif (Test-Path "$PSScriptRoot\jbang.bin.exe") {
        $binaryPath="$PSScriptRoot\jbang.bin.exe"
    } else {
        [Console]::Error.WriteLine("WARNING: JBang native binary (jbang.bin-windows-${jbang_arch}.exe or jbang.bin.exe) not found in $PSScriptRoot")
    }
}
if (-not $binaryPath) {
    # Fall back to JAR if no native binary found or native binary disabled
    if (Test-Path "$PSScriptRoot\jbang.jar") {
        $jarPath="$PSScriptRoot\jbang.jar"
    } elseif (Test-Path "$PSScriptRoot\.jbang\jbang.jar") {
        $jarPath="$PSScriptRoot\.jbang\jbang.jar"
    }
}
# A wrapper installed in a project takes its jar from the repository it was
# installed from, so there are no releases to look for
if (-not $binaryPath -and -not $jarPath -and (Test-Path "$PSScriptRoot\jbanglite.properties")) {
    Install-WrapperJar
    $jarPath="$PSScriptRoot\.jbang\jbang.jar"
}

if (-not $binaryPath -and -not $jarPath) {
    # Nothing to run next to this script: use (and if needed install) the JBang in $JBDIR\bin
    if (-not (Test-Path "$JBDIR\bin\jbang.jar") -or -not (Test-Path "$JBDIR\bin\jbang.ps1")) {
        Install-JBang
    }
    & "$JBDIR\bin\jbang.ps1" @args
    Stop-Script $LASTEXITCODE
}

if (Test-Path "$jarPath.new") {
    # a new jbang version was found, we replace the old one with it
    Move-Item -Path "$jarPath.new" -Destination "$jarPath" -Force
}

# Setup environment for execution (jbang.cmd has already set the shell and launch command)
$oldJavaHome, $oldShell, $oldNotty, $oldCmd = $env:JAVA_HOME, $env:JBANG_RUNTIME_SHELL, $env:JBANG_STDIN_NOTTY, $env:JBANG_LAUNCH_CMD
if (-not $fromCmd) {
    $env:JBANG_RUNTIME_SHELL="powershell"
    $env:JBANG_LAUNCH_CMD=$PSCommandPath
}
# tell jbang whether stdin is a tty or not
$env:JBANG_STDIN_NOTTY=[Console]::IsInputRedirected.ToString().ToLower()

if (-not $binaryPath) {
    $javaExec = Find-JavaExec
}

# Execute jbang (either native binary or JAR) and capture its output.
# A non-zero exit code is not an error here (PowerShell 7.4+ would treat it as one).
$erroractionpreference='Continue'
if ($binaryPath) {
    $output = & "$binaryPath" @args
} else {
    $javaOpts = @()
    if ($env:JBANG_JAVA_OPTIONS) { $javaOpts = $env:JBANG_JAVA_OPTIONS.Trim() -split '\s+' }
    $output = & "$javaExec" @javaOpts -jar "$jarPath" @args
}
$err=$LASTEXITCODE

$env:JAVA_HOME, $env:JBANG_RUNTIME_SHELL, $env:JBANG_STDIN_NOTTY, $env:JBANG_LAUNCH_CMD = $oldJavaHome, $oldShell, $oldNotty, $oldCmd

if ($err -eq 255 -and -not $fromCmd) {
    # JBang asks us to run a command on its behalf
    $erroractionpreference=$old_erroractionpreference
    $global:progresspreference=$old_progresspreference
    $global:LASTEXITCODE=0
    Invoke-Expression "& $output"
    if ($PSCommandPath) { exit $LASTEXITCODE }
} else {
    if ($output) { Write-Output $output }
    Stop-Script $err
}
