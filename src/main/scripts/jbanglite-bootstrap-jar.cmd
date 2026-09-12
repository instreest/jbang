@echo off
rem ===========================================================================
rem Installs jbanglite.jar, the JBangLite a project asked for: the version, URL
rem and SHA-256 in the jbanglite.properties next to this script, downloaded
rem into %JBANG_CACHE_DIR%\jbanglite\<version>
rem (%userprofile%\.jbang\cache\jbanglite\<version>). It prints the jar's path
rem on stdout and says nothing else there; progress and errors go to stderr.
rem When the jar is already installed it only prints.
rem
rem The cache is per machine, not per project, so several projects that pin the
rem same version share one download. The launcher (jbanglite.cmd) runs this
rem when there is no jbanglite.jar next to it; it can just as well be run by
rem hand, or replaced by anything else that puts the jar there. It is
rem self-contained: no PowerShell, only what Windows ships with (curl,
rem certutil).
rem
rem   jbanglite-bootstrap-jar.cmd        install if needed, print the jar path
rem
rem Environment:
rem   JBANG_DIR, JBANG_CACHE_DIR       where JBangLite keeps things (~\.jbang)
rem   JBANGLITE_DIST_URL               where to fetch the jar from, overriding
rem                                    distributionUrl (a corporate mirror)
rem   JBANG_DOWNLOAD_RETRY, JBANG_DOWNLOAD_RETRY_DELAY
rem   JBANGLITE_LOCK_TIMEOUT           seconds to wait for another run's download
rem
rem Several JBangLite runs can be started at the same time (a build matrix, a
rem multi-module build). They share ~\.jbang, so the download takes a directory
rem lock (mkdir is atomic: :acquire_lock / :release_lock) and the jar is
rem written to a file of this run's own that is renamed into place.
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
call :read_properties || exit /b 1

set "jar_dir=%cache_dir%\jbanglite\%distribution_version%"
set "jar_path=%jar_dir%\jbanglite.jar"
if not exist "%jar_path%" call :install_jar || exit /b 1
echo %jar_path%
exit /b 0

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
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

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"

rem Tells this run's temporary files apart from those of a JBangLite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"

rem How long to wait (in seconds) for another run that is downloading the jar
set "lock_timeout=600"
if not "%JBANGLITE_LOCK_TIMEOUT%"=="" set "lock_timeout=%JBANGLITE_LOCK_TIMEOUT%"
exit /b 0

rem ===========================================================================
rem The properties
rem ===========================================================================

rem Reads jbanglite.properties next to this script into distribution_version,
rem distribution_url and distribution_sha256. The file is ours, so this reads
rem what we write: 'key=value' lines, '#' comments.
:read_properties
set "properties_file=%script_dir%jbanglite.properties"
if not exist "%properties_file%" (
  echo %properties_file% not found. Re-run %script_dir%install.cmd to restore it. 1>&2
  exit /b 1
)
set "distribution_version="
set "distribution_url="
set "distribution_sha256="
for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%properties_file%") do call :set_property "%%K" "%%L"
if not "%JBANGLITE_DIST_URL%"=="" set "distribution_url=%JBANGLITE_DIST_URL%"
if "%distribution_version%"=="" goto :properties_incomplete
if "%distribution_url%"=="" goto :properties_incomplete
exit /b 0

:properties_incomplete
echo %properties_file% needs a distributionVersion and a distributionUrl 1>&2
exit /b 1

rem Keeps the key %1 with the value %2 when it is one we know. The value may
rem carry trailing whitespace from the file, which FOR does not strip.
:set_property
set "prop_key=%~1"
set "prop_value=%~2"
:trim_property_value
if "%prop_value%"=="" goto :store_property
if "%prop_value:~-1%"==" " set "prop_value=%prop_value:~0,-1%" & goto :trim_property_value
if "%prop_value:~-1%"=="	" set "prop_value=%prop_value:~0,-1%" & goto :trim_property_value
:store_property
if /i "%prop_key%"=="distributionVersion" set "distribution_version=%prop_value%"
if /i "%prop_key%"=="distributionUrl" set "distribution_url=%prop_value%"
if /i "%prop_key%"=="distributionSha256Sum" set "distribution_sha256=%prop_value%"
exit /b 0

rem ===========================================================================
rem Installing
rem ===========================================================================

rem Downloads the jar into %jar_dir%, one run at a time; the others wait and
rem then use what it installed.
:install_jar
set "lock_dir=%jar_dir%.lock"
set "lock_done=%jar_path%"
call :acquire_lock
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1
call :install_jar_locked
set "jar_result=%ERRORLEVEL%"
call :release_lock
exit /b %jar_result%

:install_jar_locked
rem another run may have installed it while we waited for the lock
if exist "%jar_path%" exit /b 0
if not exist "%jar_dir%" mkdir "%jar_dir%" 2>nul
rem this run's own file, so the jar only appears under its real name once it
rem is complete
set "jar_tmp=%jar_dir%\jbanglite-%run_id%.tmp"

echo Downloading JBangLite %distribution_version%... 1>&2
set "dl_url=%distribution_url%"
set "dl_out=%jar_tmp%"
call :download
if errorlevel 1 (
  del /f /q "%jar_tmp%" 2>nul
  echo Error downloading JBangLite from %distribution_url% 1>&2
  exit /b 1
)

if "%distribution_sha256%"=="" goto :jar_unverified
call :sha256 "%jar_tmp%"
if /i not "%distribution_sha256%"=="%sha256_result%" goto :jar_sha_mismatch
goto :jar_verified

:jar_unverified
echo No distributionSha256Sum in %properties_file%, skipping verification 1>&2

:jar_verified
move /y "%jar_tmp%" "%jar_path%" >nul || exit /b 1
exit /b 0

:jar_sha_mismatch
del /f /q "%jar_tmp%" 2>nul
echo SHA-256 mismatch for %distribution_url%: expected %distribution_sha256% but got %sha256_result% 1>&2
echo If you changed the version, update distributionSha256Sum in %properties_file%. 1>&2
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
rem because CALL expands % a second time.
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
