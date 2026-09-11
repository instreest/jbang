@echo off
rem ===========================================================================
rem Installs the JDK that runs jbanglite.jar when the machine has none: the
rem newest Temurin %bootstrap_java_version% from the JVM index on Maven Central,
rem into %JBANG_CACHE_DIR%\jdks\bootstrap (%userprofile%\.jbang\cache\jdks\bootstrap).
rem It prints that directory on stdout and says nothing else there; progress
rem and errors go to stderr. When the JDK is already installed it only prints.
rem
rem The launcher (jbanglite.cmd) runs it when it finds no usable JDK; it can
rem just as well be run by hand, or replaced by anything else that puts a JDK
rem there. It is self-contained: no PowerShell, only what Windows ships with
rem (curl, tar, certutil).
rem
rem   jbanglite-bootstrap-jdk.cmd        install if needed, print the JDK home
rem
rem Environment:
rem   JBANG_DIR, JBANG_CACHE_DIR       where JBangLite keeps things (~\.jbang)
rem   JBANG_JVM_INDEX_BASEURL          a mirror of Maven Central
rem   JBANG_DOWNLOAD_RETRY, JBANG_DOWNLOAD_RETRY_DELAY
rem   JBANGLITE_LOCK_TIMEOUT           seconds to wait for another run's download
rem
rem Several JBangLite runs can be started at the same time (a build matrix, a
rem multi-module build). They share ~\.jbang, so the download takes a
rem directory lock (mkdir is atomic: :acquire_lock / :release_lock) and every
rem other download goes to a file of this run's own that is renamed into place.
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
call :download_bootstrap_jdk || exit /b 1
echo %cache_dir%\jdks\bootstrap
exit /b 0

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
rem JBangLite itself only needs a JVM to run; which one hardly matters, so we
rem simply fetch the newest Temurin of this version.
set "bootstrap_java_version=25"

rem Where the JVM index lives. It is the same index jbanglite.jar uses to
rem install the JDKs that scripts ask for with //JAVA, published on Maven
rem Central, so no JDK discovery service is involved. Override for a mirror.
set "jvm_index_base_url=https://repo1.maven.org/maven2"
if not "%JBANG_JVM_INDEX_BASEURL%"=="" set "jvm_index_base_url=%JBANG_JVM_INDEX_BASEURL%"

rem How often a failed download is retried, and how long to wait in between
rem (0 means an exponential backoff of 1, 2, 4, ... seconds)
set "download_retry=5"
if not "%JBANG_DOWNLOAD_RETRY%"=="" set "download_retry=%JBANG_DOWNLOAD_RETRY%"
set "download_retry_delay=0"
if not "%JBANG_DOWNLOAD_RETRY_DELAY%"=="" set "download_retry_delay=%JBANG_DOWNLOAD_RETRY_DELAY%"

rem The directories JBangLite keeps its JDKs, jars and caches in. The names are
rem the ones JBang uses, so an existing ~\.jbang is picked up as it is.
set "jbang_dir=%userprofile%\.jbang"
if not "%JBANG_DIR%"=="" set "jbang_dir=%JBANG_DIR%"
set "cache_dir=%jbang_dir%\cache"
if not "%JBANG_CACHE_DIR%"=="" set "cache_dir=%JBANG_CACHE_DIR%"

rem The architecture, named the way the JVM index does
set "index_arch=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "index_arch=arm64"

rem Tells this run's temporary files apart from those of a JBangLite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"

rem How long to wait (in seconds) for another JBangLite that is downloading
rem into the same directory
set "lock_timeout=600"
if not "%JBANGLITE_LOCK_TIMEOUT%"=="" set "lock_timeout=%JBANGLITE_LOCK_TIMEOUT%"
exit /b 0

rem ===========================================================================
rem The JDK
rem ===========================================================================

rem Downloads the newest Temurin %bootstrap_java_version% from the JVM index
rem into %cache_dir%\jdks\bootstrap. Only one run does this at a time; the
rem others wait and then use what it installed.
:download_bootstrap_jdk
if not exist "%cache_dir%\jdks" mkdir "%cache_dir%\jdks" 2>nul
set "lock_dir=%cache_dir%\jdks\bootstrap.lock"
set "lock_done=%cache_dir%\jdks\bootstrap\bin\java.exe"
call :acquire_lock
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1
call :download_bootstrap_jdk_locked
set "jdk_result=%ERRORLEVEL%"
call :release_lock
exit /b %jdk_result%

:download_bootstrap_jdk_locked
rem another run may have installed it while we waited for the lock
if exist "%cache_dir%\jdks\bootstrap\bin\java.exe" exit /b 0
call :jvm_index_entry "windows-%index_arch%" || exit /b 1
if "%index_url%"=="" (
  echo No Temurin %bootstrap_java_version% found in the JVM index for windows-%index_arch% 1>&2
  exit /b 1
)
rem The index says how the archive is packed; keep its extension so the file on
rem disk matches what was downloaded, as jbanglite.jar does
if "%index_type%"=="tgz" (set "archive_ext=tar.gz") else (set "archive_ext=%index_type%")
set "jdk_archive=%cache_dir%\bootstrap-jdk-%run_id%.%archive_ext%"
set "jdk_sha_file=%cache_dir%\bootstrap-jdk-%run_id%.sha256"
set "jdk_unpack_dir=%cache_dir%\jdks\bootstrap-%run_id%.tmp"

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
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
mkdir "%jdk_unpack_dir%"
tar -xf "%jdk_archive%" -C "%jdk_unpack_dir%"
if errorlevel 1 goto :bootstrap_jdk_broken
rem the archive holds a single root folder, which becomes the JDK directory
set "jdk_root=%jdk_unpack_dir%"
for /d %%D in ("%jdk_unpack_dir%\*") do if exist "%%D\bin\java.exe" set "jdk_root=%%D"
if not exist "%jdk_root%\bin\java.exe" goto :bootstrap_jdk_broken
if exist "%cache_dir%\jdks\bootstrap" rmdir /s /q "%cache_dir%\jdks\bootstrap"
move "%jdk_root%" "%cache_dir%\jdks\bootstrap" >nul
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
del /f /q "%jdk_archive%" "%jdk_sha_file%" 2>nul
exit /b 0

:bootstrap_jdk_broken
if exist "%jdk_unpack_dir%" rmdir /s /q "%jdk_unpack_dir%"
del /f /q "%jdk_archive%" "%jdk_sha_file%" 2>nul
echo Error installing JDK 1>&2
exit /b 1

rem Checks %jdk_archive% against the SHA-256 Temurin publishes next to it. A
rem missing checksum only warns; a wrong one deletes the archive and fails.
:verify_jdk_archive
set "dl_url=%index_url%.sha256.txt"
set "dl_out=%jdk_sha_file%"
call :download
if errorlevel 1 (
  echo No published SHA-256 found for %index_url%, skipping verification 1>&2
  exit /b 0
)
set "expected_sha="
for /f "usebackq tokens=1" %%S in ("%jdk_sha_file%") do if not defined expected_sha set "expected_sha=%%S"
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
set "dl_out=%cache_dir%\jvm-index-%run_id%.xml"
call :download || (
  echo Could not read the JVM index from !index_base! 1>&2
  endlocal & exit /b 1
)
set "index_release="
for /f "usebackq delims=" %%L in (`findstr "<release>" "%cache_dir%\jvm-index-%run_id%.xml"`) do (
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
set "dl_out=%cache_dir%\jvm-index-%run_id%.jar"
call :download || (
  echo Could not download the JVM index 1>&2
  endlocal & exit /b 1
)
if exist "%cache_dir%\jvm-index-%run_id%" rmdir /s /q "%cache_dir%\jvm-index-%run_id%"
mkdir "%cache_dir%\jvm-index-%run_id%"
tar -xf "%cache_dir%\jvm-index-%run_id%.jar" -C "%cache_dir%\jvm-index-%run_id%" "coursier/jvm/indices/v1/%platform%.json" >nul 2>&1
set "index_json=%cache_dir%\jvm-index-%run_id%\coursier\jvm\indices\v1\%platform%.json"
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
rem the index was only needed to pick an entry
del /f /q "%cache_dir%\jvm-index-%run_id%.xml" "%cache_dir%\jvm-index-%run_id%.jar" 2>nul
rmdir /s /q "%cache_dir%\jvm-index-%run_id%" 2>nul
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
rem Locking
rem ===========================================================================

rem Takes the lock %lock_dir% for the calling run. MKDIR is atomic, so exactly
rem one run gets it; the others wait, and give up as soon as %lock_done% shows
rem that the work they were waiting for is done.
rem   exit 0 - the lock is ours, do the work and call :release_lock afterwards
rem   exit 1 - gave up (another run is stuck, or its lock directory is stale)
rem   exit 2 - no need to do anything, another run already did the work
:acquire_lock
rem the lock sits next to what it protects, which may not exist yet
for %%P in ("%lock_dir%\..") do if not exist "%%~fP" mkdir "%%~fP" 2>nul
set /a lock_waited=0
:acquire_lock_try
mkdir "%lock_dir%" 2>nul && exit /b 0
if exist "%lock_done%" exit /b 2
if %lock_waited% GEQ %lock_timeout% (
  echo Gave up after %lock_timeout% seconds waiting for another JBangLite to finish. 1>&2
  echo If no other JBangLite is running, remove %lock_dir% and try again. 1>&2
  exit /b 1
)
if %lock_waited% EQU 0 echo Waiting for another JBangLite to finish downloading... 1>&2
call :sleep 1
set /a lock_waited+=1
goto :acquire_lock_try

rem Gives up the lock %lock_dir% again
:release_lock
rmdir /s /q "%lock_dir%" 2>nul
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
