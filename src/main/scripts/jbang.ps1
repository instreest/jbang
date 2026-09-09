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

# The Java version to install when it's not installed on the system yet
$javaVersion = if ($env:JBANG_DEFAULT_JAVA_VERSION) { $env:JBANG_DEFAULT_JAVA_VERSION } else { '17' }

$os='windows'
$arch='x64'
$libc_type='c_std_lib'

if ($env:JBANG_JDK_VENDOR) {
    $distro=$env:JBANG_JDK_VENDOR
} elseif (($javaVersion -eq 8) -or ($javaVersion -eq 11) -or ($javaVersion -ge 17)) {
    $distro='temurin'
} else {
    $distro='aoj'
}

$JBDIR = if ($env:JBANG_DIR) { $env:JBANG_DIR } else { "$env:userprofile\.jbang" }
$TDIR = if ($env:JBANG_CACHE_DIR) { $env:JBANG_CACHE_DIR } else { "$JBDIR\cache" }
$useNative = ($env:JBANG_USE_NATIVE -eq 'true')

# Base URL for downloading JBang releases.
# Override for testing or corporate mirrors.
# Example: $env:JBANG_DOWNLOAD_BASEURL='http://localhost:18080'
$jbangDownloadBaseUrl = if ($env:JBANG_DOWNLOAD_BASEURL) { $env:JBANG_DOWNLOAD_BASEURL } else { 'https://github.com/instreest/jbang/releases' }

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

# Downloads and installs a JDK into $TDIR\jdks\$javaVersion
function Install-Jdk {
    New-Item -ItemType Directory -Force -Path "$TDIR\jdks" >$null 2>&1
    [Console]::Error.WriteLine("Downloading JDK $javaVersion. Be patient, this can take several minutes...")
    $jdkurl="https://api.foojay.io/disco/v3.0/directuris?distro=$distro&javafx_bundled=false&libc_type=$libc_type&archive_type=zip&operating_system=$os&package_type=jdk&version=$javaVersion&architecture=$arch&latest=available"
    if (-not (Invoke-Download "$jdkurl" "$TDIR\bootstrap-jdk.zip")) { Fail "Error downloading JDK" }
    [Console]::Error.WriteLine("Installing JDK $javaVersion...")
    $tmpdir="$TDIR\jdks\$javaVersion.tmp"
    Remove-Item -LiteralPath "$tmpdir" -Force -Recurse -ErrorAction Ignore >$null 2>&1
    try { Expand-Archive -Path "$TDIR\bootstrap-jdk.zip" -DestinationPath "$tmpdir" } catch { Fail "Error installing JDK" }
    foreach ($d in Get-ChildItem -Directory -Path "$tmpdir") {
        Move-Item -Path "$($d.FullName)\*" -Destination "$tmpdir" -Force
    }
    # Check if the JDK was installed properly
    $ok=$false; try { & "$tmpdir\bin\javac" -version >$null 2>&1; $ok=$true } catch {}
    if (-not $ok) { Fail "Error installing JDK" }
    # Activate the downloaded JDK giving it its proper name
    Rename-Item -Path "$tmpdir" -NewName "$javaVersion" >$null 2>&1
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

# Determines the java executable to use for running the JAR, downloading a JDK if needed.
# Sets $env:JAVA_HOME to match.
function Find-JavaExec {
    # The JDK selected with 'jbang jdk default' takes precedence
    if (Test-Path "$JBDIR\currentjdk\bin\javac.exe") {
        $env:JAVA_HOME="$JBDIR\currentjdk"
        return "$JBDIR\currentjdk\bin\java.exe"
    }
    # Then the default JDK that JBang downloaded itself
    $defaultJdk="$TDIR\jdks\$javaVersion"
    if (Test-Path "$defaultJdk\bin\javac.exe") {
        $env:JAVA_HOME=$defaultJdk
        return "$defaultJdk\bin\java.exe"
    }
    # Then JAVA_HOME, but only when it points to a JDK that is recent enough
    if ($env:JAVA_HOME) {
        if (Test-Path "$env:JAVA_HOME\bin\javac.exe") {
            $major = Get-JavaMajorVersion $env:JAVA_HOME
            if (-not $major) {
                [Console]::Error.WriteLine("JAVA_HOME is set but the Java version could not be determined, ignoring it")
            } elseif ($major -lt [int]$javaVersion) {
                [Console]::Error.WriteLine("JAVA_HOME points to Java $major which is older than Java $javaVersion, ignoring it")
            } else {
                return "$env:JAVA_HOME\bin\java.exe"
            }
        } else {
            [Console]::Error.WriteLine("JAVA_HOME is set but does not seem to point to a valid Java JDK")
        }
    }
    # Nothing usable found: download and install the default JDK
    $env:JAVA_HOME=$defaultJdk
    Install-Jdk
    # Set the current JDK
    & "$defaultJdk\bin\java.exe" -jar "$jarPath" jdk default $javaVersion
    return "$defaultJdk\bin\java.exe"
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
