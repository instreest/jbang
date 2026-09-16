@echo off
rem ===========================================================================
rem Installs the jkite executable for this platform: the URL and SHA-256 in the
rem jkite.properties next to this script, downloaded and unpacked into
rem %JKITE_CACHE_DIR%\native\<version> (%userprofile%\.jkite\cache\native\...).
rem It prints the executable's path on stdout and says nothing else there;
rem progress and errors go to stderr.
rem
rem The executable is a jkite that needs no JVM, so a machine using it
rem downloads no JDK to start jkite with - that is the whole reason it exists.
rem jkite builds one for each platform it can; a platform with no entry in
rem jkite.properties exits 2, which is the launcher's signal to use the jar.
rem
rem The launcher (jkite.cmd) runs it; it can just as well be run by hand, or
rem replaced by anything else that puts an executable there. It is
rem self-contained: no PowerShell, only what Windows ships with (curl, tar,
rem certutil).
rem
rem   jkite-bootstrap-bin.cmd          install if needed, print the path
rem   jkite-bootstrap-bin.cmd --check  say what would happen, download nothing
rem
rem --check exists because the launcher asks before it goes to the network, and
rem to ask it has to know whether there is anything to fetch. It prints
rem 'installed <version> <platform>' or 'download <version> <platform>', and
rem answers with its status too. Both, because a POSIX shell reads the status
rem naturally and cmd reads the line naturally - a CALL that carries a
rem redirection does not hand its exit code back here.
rem
rem Exit status:
rem   0  the path was printed (--check: it is already installed)
rem   1  something went wrong and was reported
rem   2  jkite publishes no executable for this platform; use the jar
rem   3  --check only: it would be downloaded
rem
rem Environment:
rem   JKITE_DIR, JKITE_CACHE_DIR   where jkite keeps things
rem   JKITE_NATIVE_URL             where to fetch the archive from, overriding
rem                                nativeUrl (a corporate mirror)
rem   JKITE_DOWNLOAD_RETRY, JKITE_DOWNLOAD_RETRY_DELAY
rem   JKITE_LOCK_TIMEOUT           seconds to wait for another run's download
rem
rem Several jkite runs can be started at the same time (a build matrix, a
rem multi-module build). They share the cache, so the download takes a
rem directory lock (mkdir is atomic) and the executable is unpacked under a
rem name of this run's own that is renamed into place.
rem
rem What is pinned is the archive, and it is verified before it is unpacked,
rem the way jkite-bootstrap-jdk.cmd verifies a JDK. Hashing the unpacked
rem executable on every later run was the other option and is not worth it
rem here: it is 33 MB, and reading it would cost more than the executable's
rem whole start.
rem
rem Two CMD rules shape the code below and are easy to trip over:
rem   - a variable set inside a parenthesized block cannot be read in that same
rem     block, so anything that reads what it just computed uses GOTO, not IF
rem     blocks;
rem   - CALL expands %% a second time, so URLs are handed to subroutines in
rem     variables, never as arguments.
rem ===========================================================================
setlocal

call :init_settings
call :read_properties
if errorlevel 2 exit /b 2
if errorlevel 1 exit /b 1

set "exec_dir=%cache_dir%\native\%native_version%"
set "exec_path=%exec_dir%\%exec_name%"

if /i "%~1"=="--check" goto :check_only

if not exist "%exec_path%" (
  call :install_exec || exit /b 1
  rem install_exec returns as soon as another run has put an executable there,
  rem so what ends up being used is checked here rather than only where it is
  rem downloaded
  if not exist "%exec_path%" (
    echo %exec_path% was not installed, refusing to go on 1>&2
    exit /b 1
  )
)
echo %exec_path%
exit /b 0

:check_only
if exist "%exec_path%" (
  echo installed %native_version% %platform%
  exit /b 0
)
echo download %native_version% %platform%
exit /b 3

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
rem How often a failed download is retried, and how long to wait in between
rem (0 means an exponential backoff of 1, 2, 4, ... seconds)
set "download_retry=5"
if not "%JKITE_DOWNLOAD_RETRY%"=="" set "download_retry=%JKITE_DOWNLOAD_RETRY%"
set "download_retry_delay=0"
if not "%JKITE_DOWNLOAD_RETRY_DELAY%"=="" set "download_retry_delay=%JKITE_DOWNLOAD_RETRY_DELAY%"

rem The directories jkite keeps its JDKs, jars and caches in.
set "jkite_dir=%userprofile%\.jkite"
if not "%JKITE_DIR%"=="" set "jkite_dir=%JKITE_DIR%"
set "cache_dir=%jkite_dir%\cache"
if not "%JKITE_CACHE_DIR%"=="" set "cache_dir=%JKITE_CACHE_DIR%"

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"

rem Tells this run's temporary files apart from those of a jkite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"

rem How long to wait (in seconds) for another run that is downloading it
set "lock_timeout=600"
if not "%JKITE_LOCK_TIMEOUT%"=="" set "lock_timeout=%JKITE_LOCK_TIMEOUT%"

rem The name this platform has in jkite.properties, the same name
rem jkite-bootstrap-jdk.cmd resolves. PROCESSOR_ARCHITECTURE reports the
rem process, not the machine, so a 32-bit cmd on ARM64 is caught by the
rem second variable Windows sets only in that case.
set "arch_name=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "arch_name=arm64"
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" set "arch_name=arm64"
set "platform=windows-%arch_name%"
set "exec_name=jkite-%platform%.exe"
exit /b 0

rem ===========================================================================
rem The properties
rem ===========================================================================

rem Reads jkite.properties next to this script into native_version, native_url
rem and native_sha256, taking the row for this platform.
rem
rem The values are never handed to CALL: CALL expands %% a second time, and a
rem URL that contains one would turn into CALL's second argument. A FOR
rem variable is safe, so the whole file is read in one loop here.
rem
rem Exits 2 when there is no row for this platform, which is not a failure: the
rem launcher runs the jar instead, which works everywhere a JVM does.
:read_properties
set "properties_file=%script_dir%jkite.properties"
if not exist "%properties_file%" (
  echo %properties_file% not found. Re-run %script_dir%install.cmd to restore it. 1>&2
  exit /b 1
)
set "url_key=nativeUrl.%platform%"
set "sha_key=nativeSha256Sum.%platform%"
setlocal enabledelayedexpansion
set "found_version="
set "found_url="
set "found_sha="
for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%properties_file%") do (
  if /i "%%K"=="nativeVersion" set "found_version=%%L"
  if /i "%%K"=="!url_key!" set "found_url=%%L"
  if /i "%%K"=="!sha_key!" set "found_sha=%%L"
)
endlocal & set "native_version=%found_version%" & set "native_url=%found_url%" & set "native_sha256=%found_sha%"
if not "%JKITE_NATIVE_URL%"=="" set "native_url=%JKITE_NATIVE_URL%"
if "%native_url%"=="" exit /b 2
if "%native_version%"=="" exit /b 2
if "%native_sha256%"=="" goto :no_pinned_hash
rem Only https, so that neither a tampered properties file nor
rem JKITE_NATIVE_URL can point the download at a plaintext host. A loopback
rem address is allowed so that the tests can serve the archive locally.
if "%native_url:~0,8%"=="https://" exit /b 0
if "%native_url:~0,17%"=="http://127.0.0.1:" exit /b 0
if "%native_url:~0,17%"=="http://localhost:" exit /b 0
echo Refusing to download the executable over anything but https: %native_url% 1>&2
exit /b 1

:no_pinned_hash
echo %properties_file% pins no nativeSha256Sum.%platform%; refusing to run an unverified executable 1>&2
exit /b 1

rem ===========================================================================
rem Installing
rem ===========================================================================

rem Downloads and unpacks the executable, one run at a time; the others wait
rem and then use what it installed.
:install_exec
set "lock_dir=%exec_dir%.lock"
set "lock_done=%exec_path%"
call :acquire_lock
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1
call :install_exec_locked
set "exec_result=%ERRORLEVEL%"
call :release_lock
exit /b %exec_result%

:install_exec_locked
rem another run may have installed it while we waited for the lock. The
rem directory is named for the version and holds nothing that did not come out
rem of an archive whose SHA-256 matched, so being there is what says it is ours.
if exist "%exec_path%" exit /b 0
if not exist "%exec_dir%" mkdir "%exec_dir%" 2>nul
rem this run's own names, so the executable only appears under its real name
rem once it is complete and its hash has matched
set "archive=%cache_dir%\native-%run_id%.tgz"
set "unpack_dir=%exec_dir%\unpack-%run_id%.tmp"

echo Downloading jkite %native_version% for %platform%... 1>&2
set "dl_url=%native_url%"
set "dl_out=%archive%"
call :download
if errorlevel 1 (
  del /f /q "%archive%" 2>nul
  echo Error downloading the jkite executable from %native_url% 1>&2
  exit /b 1
)

rem Verified before it is unpacked: nothing out of an archive this machine
rem cannot vouch for is ever written where it might be run.
call :sha256 "%archive%"
if /i not "%native_sha256%"=="%sha256_result%" goto :archive_sha_mismatch

if exist "%unpack_dir%" rmdir /s /q "%unpack_dir%" 2>nul
mkdir "%unpack_dir%" 2>nul
rem a .tgz holding one file, which the tar Windows ships reads. This is why the
rem archive is a .tgz and not a .gz: there is no gzip here, and tar refuses a
rem file that is not an archive.
tar -xf "%archive%" -C "%unpack_dir%"
if errorlevel 1 (
  rmdir /s /q "%unpack_dir%" 2>nul
  del /f /q "%archive%" 2>nul
  echo Error unpacking the jkite executable 1>&2
  exit /b 1
)
del /f /q "%archive%" 2>nul

set "unpacked=%unpack_dir%\%exec_name%"
if not exist "%unpacked%" goto :missing_in_archive
move /y "%unpacked%" "%exec_path%" >nul || goto :move_failed
rmdir /s /q "%unpack_dir%" 2>nul
exit /b 0

:missing_in_archive
rmdir /s /q "%unpack_dir%" 2>nul
echo Error installing the jkite executable: no %exec_name% in %native_url% 1>&2
exit /b 1

:archive_sha_mismatch
del /f /q "%archive%" 2>nul
echo SHA-256 mismatch for %native_url%: expected %native_sha256% but got %sha256_result% 1>&2
echo If you changed the version, update nativeSha256Sum.%platform% in %properties_file%. 1>&2
exit /b 1

:move_failed
rmdir /s /q "%unpack_dir%" 2>nul
echo Error installing the jkite executable into %exec_path% 1>&2
exit /b 1

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
  echo Gave up after %lock_timeout% seconds waiting for another jkite to finish. 1>&2
  echo If no other jkite is running, remove %lock_dir% and try again. 1>&2
  exit /b 1
)
if %lock_waited% EQU 0 echo Waiting for another jkite to finish downloading... 1>&2
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
rem because CALL expands % a second time.
:download
setlocal
set /a attempt=0
:download_attempt
set /a attempt+=1
curl -fsSL --proto "=https,http" --proto-redir "=https" "%dl_url%" -o "%dl_out%" 2>nul && (endlocal & exit /b 0)
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
if %attempt% EQU 1 echo (Set JKITE_DOWNLOAD_RETRY=0 to disable retries^) 1>&2
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
