@echo off
rem ===========================================================================
rem JBangLite launcher for Windows.
rem
rem It runs jbanglite.jar, so a checkout needs nothing installed, not even a
rem JDK. What it does, in order:
rem
rem   1. Its own options   - --version and --update, answered here without
rem                          running the jar or needing a JDK
rem   2. Which jar to run  - a jbanglite.jar next to this script when a project
rem                          vendors one, otherwise the one that
rem                          jbanglite-bootstrap-jar.cmd installs from the
rem                          version pinned in jbanglite.properties
rem   3. Which Java to use - the bootstrap JDK, JAVA_HOME, javac on the PATH,
rem                          or the JDK that jbanglite-bootstrap-jdk.cmd (next
rem                          to this script) downloads when there is none
rem   4. Launch            - run the jar; it builds the script and runs it
rem
rem That is all it does. Fetching the jar and fetching a JDK belong to the two
rem bootstrap scripts, so each can be run, tested and replaced on its own.
rem
rem One CMD rule shapes the code below: a variable set inside a parenthesized
rem block cannot be read in that same block, so anything that reads what it
rem just computed uses GOTO, not IF blocks.
rem ===========================================================================
setlocal

call :init_settings

rem --- 1. This script's own options ------------------------------------------
rem Both are about the installation rather than about a script, so neither runs
rem the jar and neither needs a JDK.
if /i "%~1"=="--version" goto :print_version
if /i "%~1"=="-V" goto :print_version
if /i "%~1"=="--update" goto :run_update

rem --- 2. Which jar to run --------------------------------------------------
rem A project may vendor the jar by dropping it next to this script, and then
rem nothing is downloaded. Otherwise the bootstrap script installs the version
rem jbanglite.properties pins into the cache, shared by every project on this
rem machine, and prints where it put it; everything else goes to stderr.
set "jar_path=%script_dir%jbanglite.jar"

rem Nothing is fetched without the operator agreeing to it. What the two
rem bootstrap scripts would have to fetch is asked about at once, so a first run
rem asks a single question rather than one per download.
set "net_items="
if not exist "%jar_path%" call :add_net_item_jar
call :find_existing_java 2>nul || call :add_net_item_jdk
if not defined net_items goto :net_done
call :confirm_network || exit /b 1
rem The jar resolves the script's dependencies later in this same run. The
rem operator has just said yes, so it is told rather than asked again.
set "JBANGLITE_NETWORK=allow"
:net_done

if not exist "%jar_path%" (
  for /f "usebackq delims=" %%J in (`"%script_dir%jbanglite-bootstrap-jar.cmd"`) do set "jar_path=%%J"
)
if not exist "%jar_path%" exit /b 1

rem --- 3. Which Java to use -------------------------------------------------
call :find_java || exit /b 1
set launch_cmd="%java_exec%" %JBANGLITE_JAVA_OPTIONS% -jar "%jar_path%"

rem --- 4. Launch ------------------------------------------------------------
rem The jar does the rest: it builds the script and runs it as a child process
rem with our stdin, stdout and stderr, and exits with the script's status.
%launch_cmd% %*
exit /b %ERRORLEVEL%

rem ===========================================================================
rem This script's own options
rem ===========================================================================

rem Prints the version that will actually run, and under it where that was
rem decided: the version jbanglite.properties pins, and the jar that will be
rem used. Nothing is downloaded.
rem
rem The cached jar needs no asking to be named: jbanglite-bootstrap-jar.cmd
rem puts it under the version it pinned and only after its SHA-256 matched, so
rem the directory it sits in is its version. A jar a project vendored next to
rem the launcher is asked, because it is the one that will run and its version
rem is whatever the project put there.
:print_version
call :property distributionVersion
if "%property_value%"=="" (
  echo No distributionVersion in %properties_file% 1>&2
  exit /b 1
)
set "pinned=%property_value%"
set "vendored=%script_dir%jbanglite.jar"
set "cached=%cache_dir%\jbanglite\%pinned%\jbanglite.jar"
if exist "%vendored%" goto :print_vendored_jar
if exist "%cached%" goto :print_cached_jar
echo jbanglite %pinned%
echo   pinned %pinned% by %properties_file%
echo   jar not installed yet, it is downloaded on the first run
exit /b 0

:print_cached_jar
echo jbanglite %pinned%
echo   pinned %pinned% by %properties_file%
echo   jar %pinned% at %cached%
exit /b 0

:print_vendored_jar
call :jar_version "%vendored%"
if not defined jar_version_value goto :print_vendored_unknown
echo jbanglite %jar_version_value%
echo   pinned %pinned% by %properties_file%
echo   jar %jar_version_value% at %vendored% (vendored, so this jar runs and not the pinned version)
exit /b 0
:print_vendored_unknown
echo jbanglite %pinned%
echo   pinned %pinned% by %properties_file%
echo   jar of an unknown version at %vendored% (vendored, so this jar runs and not the pinned version)
exit /b 0

rem Sets jar_version_value to the version the jar %1 reports for itself, empty
rem when it cannot be asked. The jar is the authority on its own version, and
rem asking it needs no tool for reading a zip; it only needs a JDK that is
rem already here, so nothing is downloaded to answer --version.
:jar_version
setlocal
set "found="
call :find_existing_java 2>nul || goto :jar_version_done
set "probe=%TEMP%\jbanglite-%run_id%-jarversion.txt"
"%java_exec%" -jar "%~1" --version > "%probe%" 2>nul
for /f "usebackq delims=" %%V in ("%probe%") do if not defined found set "found=%%V"
del /f /q "%probe%" 2>nul
:jar_version_done
endlocal & set "jar_version_value=%found%"
exit /b 0

rem Replaces this installation with the one from %2 (a branch, tag or commit of
rem the JBangLite repository; the default is whatever install.cmd defaults to)
rem by running the install.cmd that sits next to this script. Needs no Java and
rem no jar, so it works even when the pinned jar can no longer be downloaded.
:run_update
if not exist "%script_dir%install.cmd" (
  echo %script_dir%install.cmd not found, so this installation cannot update itself. 1>&2
  exit /b 1
)
set "net_items=  - a new JBangLite installation into %script_dir%"
call :confirm_network || exit /b 1
if not "%~2"=="" set "JBANGLITE_REF=%~2"
call "%script_dir%install.cmd" "%script_dir%." || exit /b 1
if not exist "%script_dir%jbanglite.jar" exit /b 0
echo. 1>&2
echo Warning: %script_dir%jbanglite.jar was not touched, and a jar next to 1>&2
echo the launcher wins over jbanglite.properties, so that old jar still runs. 1>&2
echo Remove it, or replace it with the jar of the version just installed. 1>&2
exit /b 0

rem Sets property_value to the value of the key %1 in jbanglite.properties next
rem to this script, empty when it is not there.
:property
setlocal enabledelayedexpansion
set "found="
if exist "%properties_file%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%properties_file%") do (
    if /i "%%K"=="%~1" if not defined found set "found=%%L"
  )
)
:trim_found
if not defined found goto :property_done
if not "!found:~-1!"==" " if not "!found:~-1!"=="	" goto :property_done
set "found=!found:~0,-1!"
goto :trim_found
:property_done
endlocal & set "property_value=%found%"
exit /b 0

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
rem The oldest Java that can run jbanglite.jar; anything newer is fine, and the
rem JDK a script asks for with //JAVA is chosen by jbanglite.jar itself
set "min_java_version=11"

rem The directories JBangLite keeps its JDKs, jars and caches in.
set "jbanglite_dir=%userprofile%\.jbanglite"
if not "%JBANGLITE_DIR%"=="" set "jbanglite_dir=%JBANGLITE_DIR%"
set "cache_dir=%jbanglite_dir%\cache"
if not "%JBANGLITE_CACHE_DIR%"=="" set "cache_dir=%JBANGLITE_CACHE_DIR%"

rem %~dp0 in a subroutine is the label, not this file, so remember where we are
set "script_dir=%~dp0"
set "properties_file=%~dp0jbanglite.properties"

rem Tells this run's temporary files apart from those of a JBangLite running at
rem the same time
set "run_id=%RANDOM%%RANDOM%"
exit /b 0

rem ===========================================================================
rem 1. Which Java to use
rem ===========================================================================

rem Sets java_exec (and JAVA_HOME) to the Java to run the jar with, downloading
rem one when the machine has none. Any Java %min_java_version% or newer will do:
rem the JDK a script asks for with //JAVA is chosen by jbanglite.jar itself.
rem The java to run jbanglite.jar with, installing one when the machine has
rem none.
:find_java
call :find_existing_java && exit /b 0
goto :install_bootstrap_jdk

rem Looks for a JDK that is already on this machine and sets java_exec to its
rem java, without installing anything; exits 1 when there is none.
:find_existing_java
rem The JDK jbanglite-bootstrap-jdk.cmd downloaded on an earlier run
call :usable_java "%cache_dir%\jdks\bootstrap" && (
  set "JAVA_HOME=%cache_dir%\jdks\bootstrap"
  set "java_exec=%cache_dir%\jdks\bootstrap\bin\java.exe"
  exit /b 0
)
rem Then JAVA_HOME, but only when it points to a Java that is recent enough
if "%JAVA_HOME%"=="" goto :find_path_javac
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo JAVA_HOME is set but does not seem to point to a Java runtime 1>&2
  goto :find_path_javac
)
call :java_major "%JAVA_HOME%"
if "%java_major%"=="" (
  echo JAVA_HOME is set but the Java version could not be determined, ignoring it 1>&2
  goto :find_path_javac
)
if %java_major% LSS %min_java_version% (
  echo JAVA_HOME points to Java %java_major% which is older than Java %min_java_version%, ignoring it 1>&2
  goto :find_path_javac
)
set "java_exec=%JAVA_HOME%\bin\java.exe"
exit /b 0

rem Then javac on the PATH (javac rather than java, because a JRE cannot
rem compile scripts). It is asked where its home is, because what is on the
rem PATH is usually a stub (the Oracle javapath one, the App Execution alias)
rem rather than the JDK's own bin directory. -J hands the option to javac's
rem own JVM; it exists since Java 7, and an older javac just prints an error
rem and no java.home, which leaves it ignored.
:find_path_javac
set "path_java="
for /f "delims=" %%J in ('where javac 2^>nul') do if not defined path_java set "path_java=%%J"
if not defined path_java exit /b 1
rem (through a file: a quoted path in front of a pipe is mangled by cmd /c)
set "path_java_probe=%TEMP%\jbanglite-%run_id%-java.txt"
"%path_java%" -J-XshowSettings:properties -version > "%path_java_probe%" 2>&1
set "path_java_home="
for /f "usebackq tokens=1,* delims== " %%A in (`findstr /r /c:"^ *java.home =" "%path_java_probe%"`) do set "path_java_home=%%B"
del /f /q "%path_java_probe%" 2>nul
if not defined path_java_home exit /b 1
call :usable_java "%path_java_home%" || exit /b 1
set "JAVA_HOME=%path_java_home%"
set "java_exec=%path_java_home%\bin\java.exe"
exit /b 0

rem Nothing usable found, so have a JDK of our own installed. The bootstrap
rem script prints where it put the JDK; everything else it says goes to stderr.
:install_bootstrap_jdk
call "%script_dir%jbanglite-bootstrap-jdk.cmd" >nul || exit /b 1
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

rem --- Asking before going to the network ------------------------------------
rem
rem JBangLite downloads three kinds of thing: its own jar, a JDK to run that jar
rem with, and the dependencies a script declares. This script can see the first
rem two; the dependencies only the jar knows about, so it asks for those itself,
rem and the answer given here is passed on so one run never asks twice.
rem
rem   JBANGLITE_NETWORK=ask    the default: ask when something has to be fetched
rem   JBANGLITE_NETWORK=allow  never ask (for CI, where there is nothing to ask on)
rem   JBANGLITE_NETWORK=deny   never fetch

:add_net_item_jar
call :property distributionVersion
set "net_items=%net_items%  - jbanglite.jar %property_value%|"
exit /b 0

:add_net_item_jdk
call :property bootstrapJdkVersion
set "net_items=%net_items%  - a JDK to run it with (Temurin %property_value%); this machine has none|"
exit /b 0

rem Prints the things in net_items, one per line
:print_net_items
setlocal enabledelayedexpansion
set "rest=%net_items%"
:print_net_items_loop
if not defined rest goto :print_net_items_done
for /f "tokens=1* delims=|" %%A in ("!rest!") do (
  echo %%A
  set "rest=%%B"
)
goto :print_net_items_loop
:print_net_items_done
endlocal
exit /b 0

rem Asks whether the things in net_items may be downloaded. 0 to go ahead, 1 to stop.
:confirm_network
set "net_mode=%JBANGLITE_NETWORK%"
if not defined net_mode set "net_mode=ask"
if /i "%net_mode%"=="allow" exit /b 0
if /i "%net_mode%"=="deny" goto :net_deny
if /i "%net_mode%"=="ask" goto :net_ask
echo JBANGLITE_NETWORK is '%JBANGLITE_NETWORK%'; it has to be ask, allow or deny 1>&2
exit /b 1

:net_deny
echo JBangLite has to download something, and JBANGLITE_NETWORK=deny forbids it: 1>&2
call :print_net_items 1>&2
exit /b 1

:net_ask
rem timeout fails when stdin is redirected, which is how this tells a terminal
rem from a pipe. Without one there is nobody to ask.
2>nul >nul timeout /t 0 || goto :net_no_terminal
echo.
echo JBangLite has to download:
call :print_net_items
echo.
set "net_answer=n"
set /p "net_answer=Go ahead? [y/N]: "
if /i "%net_answer%"=="y" exit /b 0
if /i "%net_answer%"=="yes" exit /b 0
echo Stopped. Nothing was downloaded. 1>&2
exit /b 1

:net_no_terminal
echo JBangLite has to download: 1>&2
call :print_net_items 1>&2
echo There is no terminal to ask on. Set JBANGLITE_NETWORK=allow to permit it. 1>&2
exit /b 1
