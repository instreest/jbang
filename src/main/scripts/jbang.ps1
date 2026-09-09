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
# This script is laid out as configuration first, then functions, then the
# main flow at the bottom. Functions may read the configuration but take
# everything else as a parameter.
#

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

$originalErrorAction = $ErrorActionPreference
$originalProgress = $global:ProgressPreference
$ErrorActionPreference = 'Stop'
$global:ProgressPreference = 'SilentlyContinue'

# The exit code JBang uses to ask its launcher to run the command it printed
$exitCodeExecute = 255

# The Java version to install when no JDK is installed on the system yet
$javaVersion = if ($env:JBANG_DEFAULT_JAVA_VERSION) { [int]$env:JBANG_DEFAULT_JAVA_VERSION } else { 17 }

# The JDK distribution to download
$jdkVendor = if ($env:JBANG_JDK_VENDOR) {
    $env:JBANG_JDK_VENDOR
} elseif ($javaVersion -eq 8 -or $javaVersion -eq 11 -or $javaVersion -ge 17) {
    'temurin'
} else {
    'aoj'
}

$jbangDir = if ($env:JBANG_DIR) { $env:JBANG_DIR } else { "$env:userprofile\.jbang" }
$cacheDir = if ($env:JBANG_CACHE_DIR) { $env:JBANG_CACHE_DIR } else { "$jbangDir\cache" }

# The JDK selected with 'jbang jdk default'
$currentJdkHome = "$jbangDir\currentjdk"
# The JDK that JBang downloads for itself when it finds no other one
$defaultJdkHome = "$cacheDir\jdks\$javaVersion"

$useNativeBinary = ($env:JBANG_USE_NATIVE -eq 'true')

# Architecture of the native JBang binary to look for
$hostArch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64) { 'aarch64' } else { 'x64' }
# Architecture of the JDK to download. Always x64, because those run on ARM64
# Windows through emulation while not every vendor publishes Windows ARM64
# builds for the version we install by default.
$jdkArch = 'x64'

# Base URL for downloading JBang releases.
# Override for testing or corporate mirrors.
# Example: $env:JBANG_DOWNLOAD_BASEURL='http://localhost:18080'
$downloadBaseUrl = if ($env:JBANG_DOWNLOAD_BASEURL) { $env:JBANG_DOWNLOAD_BASEURL } else { 'https://github.com/jbangdev/jbang/releases' }

# Number of retry attempts for downloads, and the delay between them
# (a delay of 0 means exponential backoff)
$downloadRetries = if ($env:JBANG_DOWNLOAD_RETRY) { [int]$env:JBANG_DOWNLOAD_RETRY } else { 5 }
$downloadRetryDelay = if ($env:JBANG_DOWNLOAD_RETRY_DELAY) { [int]$env:JBANG_DOWNLOAD_RETRY_DELAY } else { 0 }

# Are we running on behalf of jbang.cmd?
$runningFromCmd = ($env:JBANG_RUNTIME_SHELL -eq 'cmd')

# ---------------------------------------------------------------------------
# Messages and termination
# ---------------------------------------------------------------------------

function Write-ErrorLine {
    param([string]$message)
    [Console]::Error.WriteLine($message)
}

# Restores the progress preference, which we changed for the whole shell.
# ErrorActionPreference is restored by the main flow instead: it only ever
# applies to the scope that sets it, so a function cannot restore it for us.
function Restore-Preferences {
    $global:ProgressPreference = $originalProgress
}

# Terminates the script with the given exit code. When this script was run
# from a file `exit` is safe to use, when it was run from a remote script
# block (see above) we only stop the script block instead of killing the shell.
function Stop-Script {
    param([int]$exitCode)
    Restore-Preferences
    if ($PSCommandPath) { exit $exitCode }
    break
}

# Prints an error message and terminates the script with exit code 1
function Stop-WithError {
    param([string]$message)
    Write-ErrorLine $message
    Stop-Script 1
}

# Terminates the script when this PowerShell cannot run JBang at all
function Assert-SupportedPowerShell {
    if ($PSVersionTable.PSVersion.Major -lt 5) {
        Stop-WithError ("PowerShell 5 or later is required to run. For instruction on how to update,`n" +
            "see: https://docs.microsoft.com/en-us/powershell/scripting/setup/installing-windows-powershell")
    }
    $allowedExecutionPolicy = @('Unrestricted', 'RemoteSigned', 'ByPass')
    if ((Get-ExecutionPolicy).ToString() -notin $allowedExecutionPolicy) {
        Stop-WithError ("PowerShell requires an execution policy in [$($allowedExecutionPolicy -join ', ')] to continue.`n" +
            "For example, to set the execution policy to 'RemoteSigned' please run :`n" +
            "'Set-ExecutionPolicy RemoteSigned -scope CurrentUser'")
    }
    if ([System.Enum]::GetNames([System.Net.SecurityProtocolType]) -notcontains 'Tls12') {
        Stop-WithError (".NET Framework 4.5 or later is required to run. For instructions on how to update,`n" +
            "see: https://www.microsoft.com/net/download")
    }
}

# ---------------------------------------------------------------------------
# Downloading and installing
# ---------------------------------------------------------------------------

# Downloads a file, retrying a couple of times. Returns $true when it succeeded.
function Invoke-Download {
    param([string]$url, [string]$outFile)
    $attempt = 0
    while ($true) {
        $attempt++
        try {
            Invoke-WebRequest $url -OutFile $outFile
            return $true
        } catch {
            if ($attempt -gt $downloadRetries) { return $false }
            $sleepSeconds = if ($downloadRetryDelay -gt 0) { $downloadRetryDelay } else { [Math]::Pow(2, $attempt - 1) }
            Write-ErrorLine "Download $attempt/$($downloadRetries + 1) failed. Retry in $sleepSeconds second(s)..."
            if ($attempt -eq 1) { Write-ErrorLine "(Set JBANG_DOWNLOAD_RETRY=0 to disable retries)" }
            Start-Sleep -Seconds $sleepSeconds
        }
    }
}

# Returns the URL to download JBang itself from
function Get-JBangDownloadUrl {
    if ($env:JBANG_DOWNLOAD_URL) { return $env:JBANG_DOWNLOAD_URL }
    $bundleName = if ($useNativeBinary) { "jbang-windows-$hostArch.zip" } else { 'jbang.zip' }
    if (-not $env:JBANG_DOWNLOAD_VERSION) { return "$downloadBaseUrl/latest/download/$bundleName" }
    # Numeric versions get a 'v' prefix (e.g. 0.120.0 -> v0.120.0); named
    # release tags (e.g. 'early-access', '1.0.0-rc1') are used as-is.
    $tag = if ($env:JBANG_DOWNLOAD_VERSION -match '^[0-9]+(\.[0-9]+)*$') { "v$env:JBANG_DOWNLOAD_VERSION" } else { $env:JBANG_DOWNLOAD_VERSION }
    return "$downloadBaseUrl/download/$tag/$bundleName"
}

# Downloads JBang and installs it into $jbangDir\bin
function Install-JBang {
    $url = Get-JBangDownloadUrl
    $unpackDir = "$cacheDir\urls"
    $zipFile = "$unpackDir\jbang.zip"
    $binDir = "$jbangDir\bin"
    $version = if ($env:JBANG_DOWNLOAD_VERSION) { $env:JBANG_DOWNLOAD_VERSION } else { 'latest' }

    New-Item -ItemType Directory -Force -Path $unpackDir >$null
    Write-ErrorLine "Downloading JBang $version from $url..."
    if (-not (Invoke-Download $url $zipFile)) {
        Stop-WithError "Error downloading JBang from $url to $zipFile"
    }

    Write-ErrorLine "Installing JBang..."
    Remove-Item -LiteralPath "$unpackDir\jbang" -Force -Recurse -ErrorAction Ignore
    try {
        Expand-Archive -Path $zipFile -DestinationPath $unpackDir
    } catch {
        Stop-WithError "Error unzipping JBang from $zipFile to $unpackDir`n$_"
    }

    New-Item -ItemType Directory -Force -Path $binDir >$null
    Remove-Item -LiteralPath "$binDir\jbang" -Force -ErrorAction Ignore
    Remove-Item -Path "$binDir\jbang.*" -Force -ErrorAction Ignore
    Copy-Item -Path "$unpackDir\jbang\bin\*" -Destination $binDir -Force
}

# Downloads and installs the default JDK into $defaultJdkHome
function Install-DefaultJdk {
    $archive = "$cacheDir\bootstrap-jdk.zip"
    $stagingHome = "$defaultJdkHome.tmp"
    $url = "https://api.foojay.io/disco/v3.0/directuris?distro=$jdkVendor&javafx_bundled=false" +
        "&libc_type=c_std_lib&archive_type=zip&operating_system=windows&package_type=jdk" +
        "&version=$javaVersion&architecture=$jdkArch&latest=available"

    New-Item -ItemType Directory -Force -Path "$cacheDir\jdks" >$null
    Write-ErrorLine "Downloading JDK $javaVersion. Be patient, this can take several minutes..."
    if (-not (Invoke-Download $url $archive)) { Stop-WithError "Error downloading JDK" }

    Write-ErrorLine "Installing JDK $javaVersion..."
    Remove-Item -LiteralPath $stagingHome -Force -Recurse -ErrorAction Ignore
    try {
        Expand-Archive -Path $archive -DestinationPath $stagingHome
    } catch {
        Stop-WithError "Error installing JDK"
    }
    # The archive holds the JDK inside a single directory, move it up one level
    foreach ($dir in Get-ChildItem -Directory -Path $stagingHome) {
        Move-Item -Path "$($dir.FullName)\*" -Destination $stagingHome -Force
    }
    # Check if the JDK was installed properly
    if (-not (Test-Path "$stagingHome\bin\javac.exe")) { Stop-WithError "Error installing JDK" }
    # Activate the downloaded JDK giving it its proper name
    Rename-Item -Path $stagingHome -NewName "$javaVersion"
}

# ---------------------------------------------------------------------------
# Finding a JDK
# ---------------------------------------------------------------------------

# Returns the major version (e.g. 8, 11, 17) of the JDK in the given directory
# as read from its 'release' file, or $null when it cannot be determined.
function Get-JavaMajorVersion {
    param([string]$javaHome)
    $releaseFile = "$javaHome\release"
    if (-not (Test-Path $releaseFile)) { return $null }
    $match = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="?(\d+)(\.(\d+))?' | Select-Object -First 1
    if (-not $match) { return $null }
    $major = [int]$match.Matches[0].Groups[1].Value
    # Java 8 and older are written as 1.x
    if ($major -eq 1) { $major = [int]$match.Matches[0].Groups[3].Value }
    return $major
}

# Returns the home of an already installed JDK that can run JBang, or $null.
# The JDK selected with 'jbang jdk default' wins, then the one JBang installed
# for itself, and only then JAVA_HOME. JDKs on the PATH are not considered.
function Find-InstalledJavaHome {
    if (Test-Path "$currentJdkHome\bin\javac.exe") { return $currentJdkHome }
    if (Test-Path "$defaultJdkHome\bin\javac.exe") { return $defaultJdkHome }
    if (-not $env:JAVA_HOME) { return $null }
    if (-not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        Write-ErrorLine "JAVA_HOME is set but does not seem to point to a valid Java JDK"
        return $null
    }
    $major = Get-JavaMajorVersion $env:JAVA_HOME
    if (-not $major) {
        Write-ErrorLine "JAVA_HOME is set but the Java version could not be determined, ignoring it"
        return $null
    }
    if ($major -lt $javaVersion) {
        Write-ErrorLine "JAVA_HOME points to Java $major which is older than Java $javaVersion, ignoring it"
        return $null
    }
    return $env:JAVA_HOME
}

# Returns the home of the JDK to run the JBang JAR with, installing the
# default JDK when there is no usable one yet.
function Get-JavaHome {
    param([string]$jarPath)
    $javaHome = Find-InstalledJavaHome
    if ($javaHome) { return $javaHome }
    # The JDK we are about to install is the one JBang will run with from now on
    $env:JAVA_HOME = $defaultJdkHome
    Install-DefaultJdk
    & "$defaultJdkHome\bin\java.exe" -jar $jarPath jdk default $javaVersion
    return $defaultJdkHome
}

# ---------------------------------------------------------------------------
# Finding and running JBang
# ---------------------------------------------------------------------------

# Returns the native JBang binary next to this script, or "" when native use
# is disabled or no binary was found.
function Find-NativeBinary {
    if (-not $useNativeBinary) { return "" }
    # Look for platform-specific native binary first, then fall back to jbang.bin.exe
    if (Test-Path "$PSScriptRoot\jbang.bin-windows-$hostArch.exe") { return "$PSScriptRoot\jbang.bin-windows-$hostArch.exe" }
    if (Test-Path "$PSScriptRoot\jbang.bin.exe") { return "$PSScriptRoot\jbang.bin.exe" }
    Write-ErrorLine "WARNING: JBang native binary (jbang.bin-windows-$hostArch.exe or jbang.bin.exe) not found in $PSScriptRoot"
    return ""
}

# Returns the JBang JAR next to this script, or "" when there is none
function Find-JBangJar {
    if (Test-Path "$PSScriptRoot\jbang.jar") { return "$PSScriptRoot\jbang.jar" }
    if (Test-Path "$PSScriptRoot\.jbang\jbang.jar") { return "$PSScriptRoot\.jbang\jbang.jar" }
    return ""
}

# Runs JBang and returns its captured output together with its exit code
function Invoke-JBang {
    param([string]$binaryPath, [string]$jarPath, [string]$javaHome, [object[]]$jbangArgs = @())
    # A non-zero exit code is not an error here (PowerShell 7.4+ would treat it
    # as one). This is scoped to the function, so it ends with it.
    $ErrorActionPreference = 'Continue'
    if ($binaryPath) {
        $output = & $binaryPath @jbangArgs
    } else {
        $javaOptions = @()
        if ($env:JBANG_JAVA_OPTIONS) { $javaOptions = $env:JBANG_JAVA_OPTIONS.Trim() -split '\s+' }
        $output = & "$javaHome\bin\java.exe" @javaOptions -jar $jarPath @jbangArgs
    }
    return [PSCustomObject]@{ Output = $output; ExitCode = $LASTEXITCODE }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

Assert-SupportedPowerShell

$binaryPath = Find-NativeBinary
$jarPath = if ($binaryPath) { "" } else { Find-JBangJar }

if (-not $binaryPath -and -not $jarPath) {
    # Nothing to run next to this script: use (and if needed install) the JBang in $jbangDir\bin
    if (-not (Test-Path "$jbangDir\bin\jbang.jar") -or -not (Test-Path "$jbangDir\bin\jbang.ps1")) {
        Install-JBang
    }
    & "$jbangDir\bin\jbang.ps1" @args
    Stop-Script $LASTEXITCODE
}

if ($jarPath -and (Test-Path "$jarPath.new")) {
    # a new jbang version was found, we replace the old one with it
    Move-Item -Path "$jarPath.new" -Destination $jarPath -Force
}

# Tell JBang how it was started (jbang.cmd already set the shell and launch command)
$previousJavaHome = $env:JAVA_HOME
$previousShell = $env:JBANG_RUNTIME_SHELL
$previousStdinNotty = $env:JBANG_STDIN_NOTTY
$previousLaunchCmd = $env:JBANG_LAUNCH_CMD
if (-not $runningFromCmd) {
    $env:JBANG_RUNTIME_SHELL = 'powershell'
    $env:JBANG_LAUNCH_CMD = $PSCommandPath
}
# tell jbang whether stdin is a tty or not
$env:JBANG_STDIN_NOTTY = [Console]::IsInputRedirected.ToString().ToLower()

$javaHome = ""
if (-not $binaryPath) {
    $javaHome = Get-JavaHome -jarPath $jarPath
    $env:JAVA_HOME = $javaHome
}

$result = Invoke-JBang -binaryPath $binaryPath -jarPath $jarPath -javaHome $javaHome -jbangArgs $args

$env:JAVA_HOME = $previousJavaHome
$env:JBANG_RUNTIME_SHELL = $previousShell
$env:JBANG_STDIN_NOTTY = $previousStdinNotty
$env:JBANG_LAUNCH_CMD = $previousLaunchCmd

if ($result.ExitCode -eq $exitCodeExecute -and -not $runningFromCmd) {
    # JBang asked us to run a command on its behalf, run it with the
    # preferences the shell had before we started
    Restore-Preferences
    $ErrorActionPreference = $originalErrorAction
    $global:LASTEXITCODE = 0
    Invoke-Expression "& $($result.Output)"
    if ($PSCommandPath) { exit $LASTEXITCODE }
} else {
    # Print the output as it is. When jbang.cmd started us and the exit code
    # says so, it runs the command that JBang printed.
    if ($result.Output) { Write-Output $result.Output }
    Stop-Script $result.ExitCode
}
