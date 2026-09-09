@echo off
rem ===========================================================================
rem JBangLite launcher for Windows.
rem
rem It is self-contained: no PowerShell, only what Windows ships with (curl,
rem tar, certutil). What it does, in order:
rem
rem   1. Settings          - constants and the JBANG_* / JBANGLITE_* overrides
rem   2. Launch environment- what jbanglite.jar expects to find in the environment
rem   3. What to run       - a native binary, or the jar: next to this script, in
rem                          .jbanglite next to it, downloaded from the
rem                          repository jbanglite.properties names (that is how
rem                          a wrapper committed to a project gets its jar), or
rem                          an installed release in %%JBANG_DIR%%\bin
rem   4. Which Java to use - currentjdk, the bootstrap JDK, JAVA_HOME, or a
rem                          Temurin downloaded from the Maven Central JVM index
rem   5. Launch            - run it, and when it exits with 255 run the command
rem                          line it printed (that is how a script is started)
rem
rem Two CMD rules shape the code below and are easy to trip over:
rem   - a variable set inside a parenthesized block cannot be read in that same
rem     block, so anything that reads what it just computed uses GOTO, not IF
rem     blocks;
rem   - CALL expands %% a second time, so URLs (the Temurin ones contain %%2B)
rem     are handed to subroutines in variables, never as arguments.
rem ===========================================================================
setlocal

call :init_settings
call :init_launch_environment

rem --- 3. What to run -------------------------------------------------------
if "%JBANG_USE_NATIVE%"=="true" call :find_native_binary
if defined native_binary (
  set launch_cmd="%native_binary%"
  goto :launch
)

call :find_jar || exit /b 1
if not defined jar_path goto :run_installed_release

rem --- 4. Which Java to use -------------------------------------------------
call :find_java || exit /b 1
set launch_cmd="%java_exec%" %JBANG_JAVA_OPTIONS% -jar "%jar_path%"

rem --- 5. Launch ------------------------------------------------------------
:launch
rem The output is captured because exit code 255 means "run this command line
rem for me" (see :run_printed_command).
rem WARNING running jbanglite in parallel in quick succession will cause temp
rem name collisions!
set "output_file=%TEMP%\%RANDOM%.jbanglite.tmp"
%launch_cmd% %* > "%output_file%"
set "exit_code=%ERRORLEVEL%"
if %exit_code% EQU 255 goto :run_printed_command

type "%output_file%"
del /f /q "%output_file%"
exit /b %exit_code%

:run_printed_command
rem the command to run is the first line of the output
set "printed_command="
for /f "usebackq delims=" %%L in ("%output_file%") do if not defined printed_command set "printed_command=%%L"
del /f /q "%output_file%"
%printed_command%
exit /b %ERRORLEVEL%

rem Runs the release installed in %jbang_dir%\bin, installing it first when this
rem is neither a wrapper nor an unpacked distribution
:run_installed_release
if not exist "%jbang_dir%\bin\jbanglite.jar" goto :release_missing
if not exist "%jbang_dir%\bin\jbanglite.cmd" goto :release_missing
goto :launch_installed_release
:release_missing
call :install_release || exit /b 1
:launch_installed_release
call "%jbang_dir%\bin\jbanglite.cmd" %*
exit /b %ERRORLEVEL%

rem ===========================================================================
rem 1. Settings
rem ===========================================================================

:init_settings
rem JBangLite itself only needs a JVM to run; which one hardly matters, so when
rem the machine has none we simply fetch the newest Temurin of this version.
set "bootstrap_java_version=25"
rem The oldest Java that can run jbanglite.jar; anything newer is fine, and the
rem JDK a script asks for with //JAVA is chosen by jbanglite.jar itself
set "min_java_version=11"

rem Where the JVM index lives. It is the same index jbanglite.jar uses to
rem install the JDKs that scripts ask for with //JAVA, published on Maven
rem Central, so no JDK discovery service is involved. Override for a mirror.
set "jvm_index_base_url=https://repo1.maven.org/maven2"
if not "%JBANG_JVM_INDEX_BASEURL%"=="" set "jvm_index_base_url=%JBANG_JVM_INDEX_BASEURL%"

rem Where a wrapper downloads jbanglite.jar from. The wrapper is installed into
rem a project with install.sh/install.cmd, which records the repository and
rem revision to take the jar from in jbanglite.properties next to this script.
set "raw_base_url=https://raw.githubusercontent.com"
if not "%JBANGLITE_RAW_BASEURL%"=="" set "raw_base_url=%JBANGLITE_RAW_BASEURL%"

rem Where releases are downloaded from
set "release_base_url=https://github.com/instreest/jbang/releases"
if not "%JBANG_DOWNLOAD_BASEURL%"=="" set "release_base_url=%JBANG_DOWNLOAD_BASEURL%"

rem How often a failed download is retried, and how long to wait in between
rem (0 means an exponential backoff of 1, 2, 4, ... seconds)
set "download_retry=5"
if not "%JBANG_DOWNLOAD_RETRY%"=="" set "download_retry=%JBANG_DOWNLOAD_RETRY%"
set "download_retry_delay=0"
if not "%JBANG_DOWNLOAD_RETRY_DELAY%"=="" set "download_retry_delay=%JBANG_DOWNLOAD_RETRY_DELAY%"

rem The directories JBangLite keeps its JDKs, jars and caches in. The names are
rem the ones JBang uses, so an existing ~/.jbang is picked up as it is.
set "jbang_dir=%userprofile%\.jbang"
if not "%JBANG_DIR%"=="" set "jbang_dir=%JBANG_DIR%"
set "cache_dir=%jbang_dir%\cache"
if not "%JBANG_CACHE_DIR%"=="" set "cache_dir=%JBANG_CACHE_DIR%"

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"

rem The architecture, named the way the release bundles and the JVM index do
set "release_arch=x64"
set "index_arch=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" (
  set "release_arch=aarch64"
  set "index_arch=arm64"
)
exit /b 0

rem ===========================================================================
rem 2. Launch environment
rem ===========================================================================

rem What jbanglite.jar reads to know how it was started: which shell is waiting
rem for the command line it prints, how to spell a re-invocation of itself, and
rem whether stdin is a terminal.
:init_launch_environment
set "JBANG_RUNTIME_SHELL=cmd"
set "JBANG_LAUNCH_CMD=%~f0"
2>nul >nul timeout /t 0 && (set "JBANG_STDIN_NOTTY=false") || (set "JBANG_STDIN_NOTTY=true")
exit /b 0

rem ===========================================================================
rem 3. What to run
rem ===========================================================================

rem Sets native_binary to the native image next to this script, if there is one
:find_native_binary
set "native_binary="
if exist "%script_dir%jbanglite.bin-windows-%release_arch%.exe" (
  set "native_binary=%script_dir%jbanglite.bin-windows-%release_arch%.exe"
  exit /b 0
)
if exist "%script_dir%jbanglite.bin.exe" (
  set "native_binary=%script_dir%jbanglite.bin.exe"
  exit /b 0
)
echo WARNING: JBangLite native binary (jbanglite.bin-windows-%release_arch%.exe or jbanglite.bin.exe^) not found in %script_dir% 1>&2
exit /b 0

rem Sets jar_path to the jar to run, downloading it when this is a wrapper.
rem Leaves jar_path empty when there is no jar to be found next to this script,
rem which means the installed release should be used instead.
:find_jar
set "jar_path="
if exist "%script_dir%jbanglite.jar" (
  set "jar_path=%script_dir%jbanglite.jar"
  goto :find_jar_staged
)
if exist "%script_dir%.jbanglite\jbanglite.jar" (
  set "jar_path=%script_dir%.jbanglite\jbanglite.jar"
  goto :find_jar_staged
)
if not exist "%script_dir%jbanglite.properties" exit /b 0
call :download_wrapper_jar || exit /b 1
set "jar_path=%script_dir%.jbanglite\jbanglite.jar"

:find_jar_staged
rem a new version was staged next to the old one, so put it in place
if exist "%jar_path%.new" move /y "%jar_path%.new" "%jar_path%" >nul
exit /b 0

rem Downloads jbanglite.jar into <wrapper dir>\.jbanglite from the repository
rem and revision jbanglite.properties names, checked against the SHA-256 it
rem records. That is what makes a wrapper committed to a project work without
rem the jar being committed with it.
:download_wrapper_jar
set "wrapper_repo=" & set "wrapper_ref=" & set "wrapper_sha="
for /f "usebackq tokens=1,* delims==" %%K in ("%script_dir%jbanglite.properties") do (
  if "%%K"=="repo" set "wrapper_repo=%%L"
  if "%%K"=="ref" set "wrapper_ref=%%L"
  if "%%K"=="jarSha256" set "wrapper_sha=%%L"
)
if "%wrapper_repo%"=="" goto :download_wrapper_jar_unusable
if "%wrapper_ref%"=="" goto :download_wrapper_jar_unusable
set "wrapper_jar_url=%raw_base_url%/%wrapper_repo%/%wrapper_ref%/dist/jbanglite.jar"
set "wrapper_jar=%script_dir%.jbanglite\jbanglite.jar"
if not exist "%script_dir%.jbanglite" mkdir "%script_dir%.jbanglite"

echo Downloading JBangLite from %wrapper_jar_url%... 1>&2
set "dl_url=%wrapper_jar_url%"
set "dl_out=%wrapper_jar%.tmp"
call :download
if errorlevel 1 (
  del /f /q "%wrapper_jar%.tmp" 2>nul
  echo Error downloading JBangLite from %wrapper_jar_url% 1>&2
  exit /b 1
)
if "%wrapper_sha%"=="" goto :download_wrapper_jar_keep
call :sha256 "%wrapper_jar%.tmp"
if /i "%wrapper_sha%"=="%sha256_result%" goto :download_wrapper_jar_keep
del /f /q "%wrapper_jar%.tmp" 2>nul
echo SHA-256 mismatch for %wrapper_jar_url%: expected %wrapper_sha% but got %sha256_result% 1>&2
exit /b 1

:download_wrapper_jar_keep
move /y "%wrapper_jar%.tmp" "%wrapper_jar%" >nul
exit /b 0

:download_wrapper_jar_unusable
echo %script_dir%jbanglite.properties does not name a repo and a ref to get jbanglite.jar from 1>&2
exit /b 1

rem Downloads and installs a release into %jbang_dir%\bin
:install_release
if "%JBANG_USE_NATIVE%"=="true" (
  set "bundle_name=jbanglite-windows-%release_arch%.zip"
) else (
  set "bundle_name=jbanglite.zip"
)
call :release_url
set "release_version=latest"
if not "%JBANG_DOWNLOAD_VERSION%"=="" set "release_version=%JBANG_DOWNLOAD_VERSION%"
if not exist "%cache_dir%\urls" mkdir "%cache_dir%\urls"

echo Downloading JBangLite %release_version% from %release_url%... 1>&2
set "dl_url=%release_url%"
set "dl_out=%cache_dir%\urls\jbanglite.zip"
call :download
if errorlevel 1 (
  echo Error downloading JBangLite from %release_url% to %cache_dir%\urls\jbanglite.zip 1>&2
  exit /b 1
)
echo Installing JBangLite... 1>&2
if exist "%cache_dir%\urls\jbanglite" rmdir /s /q "%cache_dir%\urls\jbanglite"
tar -xf "%cache_dir%\urls\jbanglite.zip" -C "%cache_dir%\urls"
if errorlevel 1 (
  echo Error unzipping JBangLite from %cache_dir%\urls\jbanglite.zip to %cache_dir%\urls 1>&2
  exit /b 1
)
if not exist "%jbang_dir%\bin" mkdir "%jbang_dir%\bin"
del /f /q "%jbang_dir%\bin\jbanglite" "%jbang_dir%\bin\jbanglite.*" 2>nul
copy /y "%cache_dir%\urls\jbanglite\bin\*" "%jbang_dir%\bin" >nul
exit /b 0

rem Sets release_url from the JBANG_DOWNLOAD_* settings and %bundle_name%
:release_url
if not "%JBANG_DOWNLOAD_URL%"=="" (
  set "release_url=%JBANG_DOWNLOAD_URL%"
  exit /b 0
)
if "%JBANG_DOWNLOAD_VERSION%"=="" (
  set "release_url=%release_base_url%/latest/download/%bundle_name%"
  exit /b 0
)
rem Numeric versions get a 'v' prefix (e.g. 0.120.0 -> v0.120.0); named release
rem tags (e.g. 'early-access', '1.0.0-rc1') are used as-is.
call :release_tag "%JBANG_DOWNLOAD_VERSION%"
set "release_url=%release_base_url%/download/%release_tag%/%bundle_name%"
exit /b 0

rem Sets release_tag for the version %1
:release_tag
setlocal
set "rest=%~1"
for %%D in (0 1 2 3 4 5 6 7 8 9 .) do call set "rest=%%rest:%%D=%%"
if "%rest%"=="" (set "tag=v%~1") else (set "tag=%~1")
endlocal & set "release_tag=%tag%"
exit /b 0

rem ===========================================================================
rem 4. Which Java to use
rem ===========================================================================

rem Sets java_exec (and JAVA_HOME) to the Java to run the jar with, downloading
rem one when the machine has none. Any Java %min_java_version% or newer will do:
rem the JDK a script asks for with //JAVA is chosen by jbanglite.jar itself.
:find_java
rem The JDK JBangLite installed for a script and made the default
call :usable_java "%jbang_dir%\currentjdk" && (
  set "JAVA_HOME=%jbang_dir%\currentjdk"
  set "java_exec=%jbang_dir%\currentjdk\bin\java.exe"
  exit /b 0
)
rem Then the JDK this script downloaded on an earlier run
call :usable_java "%cache_dir%\jdks\bootstrap" && (
  set "JAVA_HOME=%cache_dir%\jdks\bootstrap"
  set "java_exec=%cache_dir%\jdks\bootstrap\bin\java.exe"
  exit /b 0
)
rem Then JAVA_HOME, but only when it points to a Java that is recent enough
if "%JAVA_HOME%"=="" goto :install_bootstrap_jdk
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME is set but does not seem to point to a Java runtime 1>&2
  goto :install_bootstrap_jdk
)
call :java_major "%JAVA_HOME%"
if "%java_major%"=="" (
  echo JAVA_HOME is set but the Java version could not be determined, ignoring it 1>&2
  goto :install_bootstrap_jdk
)
if %java_major% LSS %min_java_version% (
  echo JAVA_HOME points to Java %java_major% which is older than Java %min_java_version%, ignoring it 1>&2
  goto :install_bootstrap_jdk
)
set "java_exec=%JAVA_HOME%\bin\java.exe"
exit /b 0

rem Nothing usable found, so fetch a JVM of our own
:install_bootstrap_jdk
call :download_bootstrap_jdk || exit /b 1
set "JAVA_HOME=%cache_dir%\jdks\bootstrap"
set "java_exec=%cache_dir%\jdks\bootstrap\bin\java.exe"
exit /b 0

rem Succeeds when %1 holds a Java new enough to run jbanglite.jar
:usable_java
if not exist "%~1\bin\java.exe" exit /b 1
call :java_major "%~1"
if "%java_major%"=="" exit /b 1
if %java_major% LSS %min_java_version% exit /b 1
exit /b 0

rem Sets java_major to the major version of the JDK in %1 as read from its
rem 'release' file (e.g. 8, 11, 17); leaves it empty when it cannot be
rem determined. Only the JAVA_VERSION line is used, so a broken or fake
rem 'release' file just causes the JDK to be ignored.
:java_major
set "java_major="
if not exist "%~1\release" exit /b 0
for /f "usebackq tokens=1* delims==" %%A in ("%~1\release") do if "%%A"=="JAVA_VERSION" set "java_major=%%~B"
if "%java_major%"=="" exit /b 0
for /f "tokens=1,2 delims=." %%A in ("%java_major%") do (
  if "%%A"=="1" (set "java_major=%%B") else (set "java_major=%%A")
)
exit /b 0

rem Downloads the newest Temurin %bootstrap_java_version% from the JVM index
rem into %cache_dir%\jdks\bootstrap
:download_bootstrap_jdk
if not exist "%cache_dir%\jdks" mkdir "%cache_dir%\jdks"
call :jvm_index_entry "windows-%index_arch%" || exit /b 1
if "%index_url%"=="" (
  echo No Temurin %bootstrap_java_version% found in the JVM index for windows-%index_arch% 1>&2
  exit /b 1
)
rem The index says how the archive is packed; keep its extension so the file on
rem disk matches what was downloaded, as jbanglite.jar does
if "%index_type%"=="tgz" (set "archive_ext=tar.gz") else (set "archive_ext=%index_type%")
set "jdk_archive=%cache_dir%\bootstrap-jdk.%archive_ext%"

echo No Java found. Downloading Temurin %index_version%. Be patient, this can take several minutes... 1>&2
set "dl_url=%index_url%"
set "dl_out=%jdk_archive%"
call :download
if errorlevel 1 (
  echo Error downloading JDK from %index_url% 1>&2
  exit /b 1
)
call :verify_jdk_archive || exit /b 1

echo Installing Temurin %index_version%... 1>&2
if exist "%cache_dir%\jdks\bootstrap.tmp" rmdir /s /q "%cache_dir%\jdks\bootstrap.tmp"
mkdir "%cache_dir%\jdks\bootstrap.tmp"
tar -xf "%jdk_archive%" -C "%cache_dir%\jdks\bootstrap.tmp"
if errorlevel 1 goto :bootstrap_jdk_broken
rem the archive holds a single root folder, which becomes the JDK directory
set "jdk_root=%cache_dir%\jdks\bootstrap.tmp"
for /d %%D in ("%cache_dir%\jdks\bootstrap.tmp\*") do if exist "%%D\bin\java.exe" set "jdk_root=%%D"
if not exist "%jdk_root%\bin\java.exe" goto :bootstrap_jdk_broken
if exist "%cache_dir%\jdks\bootstrap" rmdir /s /q "%cache_dir%\jdks\bootstrap"
move "%jdk_root%" "%cache_dir%\jdks\bootstrap" >nul
if exist "%cache_dir%\jdks\bootstrap.tmp" rmdir /s /q "%cache_dir%\jdks\bootstrap.tmp"
del /f /q "%jdk_archive%" "%cache_dir%\bootstrap-jdk.sha256" 2>nul
exit /b 0

:bootstrap_jdk_broken
if exist "%cache_dir%\jdks\bootstrap.tmp" rmdir /s /q "%cache_dir%\jdks\bootstrap.tmp"
echo Error installing JDK 1>&2
exit /b 1

rem Checks %jdk_archive% against the SHA-256 Temurin publishes next to it. A
rem missing checksum only warns; a wrong one deletes the archive and fails.
:verify_jdk_archive
set "dl_url=%index_url%.sha256.txt"
set "dl_out=%cache_dir%\bootstrap-jdk.sha256"
call :download
if errorlevel 1 (
  echo No published SHA-256 found for %index_url%, skipping verification 1>&2
  exit /b 0
)
set "expected_sha="
for /f "usebackq tokens=1" %%S in ("%cache_dir%\bootstrap-jdk.sha256") do if not defined expected_sha set "expected_sha=%%S"
call :sha256 "%jdk_archive%"
if /i "%expected_sha%"=="%sha256_result%" exit /b 0
del /f /q "%jdk_archive%" 2>nul
echo SHA-256 mismatch for %index_url%: expected %expected_sha% but got %sha256_result% 1>&2
exit /b 1

rem Sets index_version, index_type and index_url to the newest Temurin
rem %bootstrap_java_version% in the JVM index for the platform %1
:jvm_index_entry
setlocal enabledelayedexpansion
set "platform=%~1"
if not exist "%cache_dir%" mkdir "%cache_dir%"
set "index_base=%jvm_index_base_url%/io/get-coursier/jvm/indices/index-%platform%"

rem the newest published index, from the Maven metadata
set "dl_url=!index_base!/maven-metadata.xml"
set "dl_out=%cache_dir%\jvm-index.xml"
call :download || (
  echo Could not read the JVM index from !index_base! 1>&2
  endlocal & exit /b 1
)
set "index_release="
for /f "usebackq delims=" %%L in (`findstr "<release>" "%cache_dir%\jvm-index.xml"`) do (
  set "line=%%L"
  set "line=!line:*<release>=!"
  for /f "delims=<" %%V in ("!line!") do set "index_release=%%V"
)
if "!index_release!"=="" (
  echo Could not determine the newest JVM index version 1>&2
  endlocal & exit /b 1
)

rem the index itself, a jar holding one JSON file per platform
set "dl_url=!index_base!/!index_release!/index-%platform%-!index_release!.jar"
set "dl_out=%cache_dir%\jvm-index.jar"
call :download || (
  echo Could not download the JVM index 1>&2
  endlocal & exit /b 1
)
if exist "%cache_dir%\jvm-index" rmdir /s /q "%cache_dir%\jvm-index"
mkdir "%cache_dir%\jvm-index"
tar -xf "%cache_dir%\jvm-index.jar" -C "%cache_dir%\jvm-index" "coursier/jvm/indices/v1/%platform%.json" >nul 2>&1
set "index_json=%cache_dir%\jvm-index\coursier\jvm\indices\v1\%platform%.json"
if not exist "!index_json!" (
  echo The JVM index has no data for %platform% 1>&2
  endlocal & exit /b 1
)

rem The index is a pretty-printed JSON object per distribution, whose entries
rem read  "<version>": "<zip|tgz>+<url>" . Lines are read whole and split on the
rem quote character here, so no line has to be matched with a pattern: a line
rem without a quoted field is the } that ends the temurin object.
set "in_temurin=" & set "best_key=" & set "best_version=" & set "best_type=" & set "best_url="
for /f "usebackq delims=" %%L in ("!index_json!") do (
  if not "!in_temurin!"=="done" (
    set "line=%%L"
    set "entry_key=" & set "entry_value="
    for /f tokens^=2^,4^ delims^=^" %%V in ("!line!") do (
      set "entry_key=%%V"
      set "entry_value=%%W"
    )
    if defined in_temurin (
      if not defined entry_key (
        set "in_temurin=done"
      ) else (
        set "candidate_version=!entry_key!"
        set "candidate_value=!entry_value!"
        call :keep_newest_candidate
      )
    ) else (
      if "!entry_key!"=="temurin" set "in_temurin=1"
    )
  )
)
endlocal & (
  set "index_version=%best_version%"
  set "index_type=%best_type%"
  set "index_url=%best_url%"
)
exit /b 0

rem Keeps %candidate_version% / %candidate_value% ("<type>+<url>") when it is a
rem newer Temurin %bootstrap_java_version% than the best one so far. Both are
rem passed in variables, not as arguments, because CALL would expand the %2B in
rem the URL.
:keep_newest_candidate
for /f "delims=." %%M in ("%candidate_version%") do set "candidate_major=%%M"
if not "%candidate_major%"=="%bootstrap_java_version%" exit /b 0
set "candidate_type=" & set "candidate_url="
for /f "tokens=1,* delims=+" %%T in ("%candidate_value%") do (
  set "candidate_type=%%T"
  set "candidate_url=%%U"
)
if not defined candidate_url exit /b 0
call :version_key "%candidate_version%"
if not "%version_key%" GTR "%best_key%" exit /b 0
set "best_key=%version_key%"
set "best_version=%candidate_version%"
set "best_type=%candidate_type%"
set "best_url=%candidate_url%"
exit /b 0

rem Sets version_key to a zero-padded form of the dotted version %1, so that two
rem versions can be compared as strings
:version_key
setlocal
set "part1=0" & set "part2=0" & set "part3=0" & set "part4=0"
for /f "tokens=1-4 delims=." %%A in ("%~1") do (
  if not "%%A"=="" set "part1=%%A"
  if not "%%B"=="" set "part2=%%B"
  if not "%%C"=="" set "part3=%%C"
  if not "%%D"=="" set "part4=%%D"
)
set "pad1=00000%part1%" & set "pad2=00000%part2%"
set "pad3=00000%part3%" & set "pad4=00000%part4%"
endlocal & set "version_key=%pad1:~-5%%pad2:~-5%%pad3:~-5%%pad4:~-5%"
exit /b 0

rem ===========================================================================
rem Downloading and checksums
rem ===========================================================================

rem Downloads %dl_url% to %dl_out%, retrying with a backoff. Fails when the
rem download does. The URL is passed in a variable rather than as an argument
rem because CALL expands % a second time and the JDK URLs contain %2B.
:download
setlocal
set /a attempt=0
:download_attempt
set /a attempt+=1
curl -fsSL "%dl_url%" -o "%dl_out%" 2>nul && (endlocal & exit /b 0)
if %attempt% GTR %download_retry% (endlocal & exit /b 1)
if %download_retry_delay% GTR 0 (
  set /a wait_seconds=%download_retry_delay%
) else (
  rem Exponential backoff: 1, 2, 4, 8, ...
  set /a wait_seconds=1
  for /l %%I in (2,1,%attempt%) do set /a wait_seconds*=2
)
set /a attempts_total=%download_retry%+1
call echo Download %attempt%/%attempts_total% failed. Retry in %%wait_seconds%% second(s)... 1>&2
if %attempt% EQU 1 echo (Set JBANG_DOWNLOAD_RETRY=0 to disable retries^) 1>&2
call :sleep %%wait_seconds%%
goto :download_attempt

rem Waits %1 seconds without needing a console (timeout /t fails when redirected)
:sleep
set /a ping_count=%~1+1
ping -n %ping_count% 127.0.0.1 >nul 2>&1
exit /b 0

rem Sets sha256_result to the lower-case SHA-256 of the file %1
:sha256
setlocal enabledelayedexpansion
set "hash="
for /f "usebackq skip=1 delims=" %%H in (`certutil -hashfile "%~1" SHA256 2^>nul`) do if not defined hash set "hash=%%H"
set "hash=!hash: =!"
endlocal & set "sha256_result=%hash%"
exit /b 0
