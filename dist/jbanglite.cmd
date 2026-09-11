@echo off
rem ===========================================================================
rem JBangLite launcher for Windows.
rem
rem It runs the jbanglite.jar that sits next to it - committed to the project
rem together with this script, so a checkout needs nothing installed, not even
rem a JDK. It is self-contained: no PowerShell, only what Windows ships with
rem (curl, tar, certutil). What it does, in order:
rem
rem   1. Settings          - constants and the JBANG_* / JBANGLITE_* overrides
rem   2. Which Java to use - currentjdk, the bootstrap JDK, JAVA_HOME, javac on
rem                          the PATH, or a Temurin downloaded from the Maven
rem                          Central JVM index
rem   3. Launch            - run the jar; it builds the script and runs it
rem
rem Several JBangLite runs can be started at the same time (a build matrix, a
rem multi-module build). They share ~/.jbang, so the JDK download takes a
rem directory lock (mkdir is atomic: :acquire_lock / :release_lock) and every
rem other download goes to a file of this run's own that is renamed into place,
rem so no run ever fails because another one got there first.
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

rem The jar is committed next to this script; there is nothing to download
set "jar_path=%script_dir%jbanglite.jar"
if not exist "%jar_path%" (
  echo %jar_path% not found. Re-run %script_dir%install.cmd to restore it. 1>&2
  exit /b 1
)

rem --- 2. Which Java to use -------------------------------------------------
call :find_java || exit /b 1
set launch_cmd="%java_exec%" %JBANG_JAVA_OPTIONS% -jar "%jar_path%"

rem --- 3. Launch ------------------------------------------------------------
rem The jar does the rest: it builds the script and runs it as a child process
rem with our stdin, stdout and stderr, and exits with the script's status.
%launch_cmd% %*
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
rem 2. Which Java to use
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
if "%JAVA_HOME%"=="" goto :find_path_java
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME is set but does not seem to point to a Java runtime 1>&2
  goto :find_path_java
)
call :java_major "%JAVA_HOME%"
if "%java_major%"=="" (
  echo JAVA_HOME is set but the Java version could not be determined, ignoring it 1>&2
  goto :find_path_java
)
if %java_major% LSS %min_java_version% (
  echo JAVA_HOME points to Java %java_major% which is older than Java %min_java_version%, ignoring it 1>&2
  goto :find_path_java
)
set "java_exec=%JAVA_HOME%\bin\java.exe"
exit /b 0

rem Then javac on the PATH (javac rather than java, because a JRE cannot
rem compile scripts). It is asked where its home is, because what is on the
rem PATH is usually a stub (the Oracle javapath one, the App Execution alias)
rem rather than the JDK's own bin directory. -J hands the option to javac's
rem own JVM; it exists since Java 7, and an older javac just prints an error
rem and no java.home, which leaves it ignored.
:find_path_java
set "path_java="
for /f "delims=" %%J in ('where javac 2^>nul') do if not defined path_java set "path_java=%%J"
if not defined path_java goto :install_bootstrap_jdk
rem (through a file: a quoted path in front of a pipe is mangled by cmd /c)
set "path_java_probe=%TEMP%\jbanglite-%run_id%-java.txt"
"%path_java%" -J-XshowSettings:properties -version > "%path_java_probe%" 2>&1
set "path_java_home="
for /f "usebackq tokens=1,* delims== " %%A in (`findstr /r /c:"^ *java.home =" "%path_java_probe%"`) do set "path_java_home=%%B"
del /f /q "%path_java_probe%" 2>nul
if not defined path_java_home goto :install_bootstrap_jdk
call :usable_java "%path_java_home%" || goto :install_bootstrap_jdk
set "JAVA_HOME=%path_java_home%"
set "java_exec=%path_java_home%\bin\java.exe"
exit /b 0

rem Nothing usable found, so fetch a JVM of our own
:install_bootstrap_jdk
call :download_bootstrap_jdk || exit /b 1
set "JAVA_HOME=%cache_dir%\jdks\bootstrap"
set "java_exec=%cache_dir%\jdks\bootstrap\bin\java.exe"
exit /b 0

rem Succeeds when %1 holds a JDK (scripts have to be compiled, so a JRE is no
rem use) new enough to run jbanglite.jar
:usable_java
if not exist "%~1\bin\java.exe" exit /b 1
if not exist "%~1\bin\javac.exe" exit /b 1
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
