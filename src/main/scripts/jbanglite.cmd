@echo off
rem ===========================================================================
rem JBangLite launcher for Windows.
rem
rem It runs the jbanglite.jar that sits next to it - committed to the project
rem together with this script, so a checkout needs nothing installed, not even
rem a JDK. What it does, in order:
rem
rem   1. Which Java to use - the bootstrap JDK, JAVA_HOME, javac on the PATH,
rem                          or the JDK that jbanglite-bootstrap-jdk.cmd (next
rem                          to this script) downloads when there is none
rem   2. Launch            - run the jar; it builds the script and runs it
rem
rem That is all it does. Downloading a JDK is jbanglite-bootstrap-jdk.cmd's
rem job, so it can be run, tested and replaced on its own.
rem
rem One CMD rule shapes the code below: a variable set inside a parenthesized
rem block cannot be read in that same block, so anything that reads what it
rem just computed uses GOTO, not IF blocks.
rem ===========================================================================
setlocal

call :init_settings

rem The jar is committed next to this script; there is nothing to download
set "jar_path=%script_dir%jbanglite.jar"
if not exist "%jar_path%" (
  echo %jar_path% not found. Re-run %script_dir%install.cmd to restore it. 1>&2
  exit /b 1
)

rem --- 1. Which Java to use -------------------------------------------------
call :find_java || exit /b 1
set launch_cmd="%java_exec%" %JBANG_JAVA_OPTIONS% -jar "%jar_path%"

rem --- 2. Launch ------------------------------------------------------------
rem The jar does the rest: it builds the script and runs it as a child process
rem with our stdin, stdout and stderr, and exits with the script's status.
%launch_cmd% %*
exit /b %ERRORLEVEL%

rem ===========================================================================
rem Settings
rem ===========================================================================

:init_settings
rem The oldest Java that can run jbanglite.jar; anything newer is fine, and the
rem JDK a script asks for with //JAVA is chosen by jbanglite.jar itself
set "min_java_version=11"

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
exit /b 0

rem ===========================================================================
rem 1. Which Java to use
rem ===========================================================================

rem Sets java_exec (and JAVA_HOME) to the Java to run the jar with, downloading
rem one when the machine has none. Any Java %min_java_version% or newer will do:
rem the JDK a script asks for with //JAVA is chosen by jbanglite.jar itself.
:find_java
rem The JDK jbanglite-bootstrap-jdk.cmd downloaded on an earlier run
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

