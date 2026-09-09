@echo off
rem Installs the JBangLite wrapper into a project, so that the project can be
rem built and run without JBangLite (or a JDK) being installed on the machine:
rem only the small launcher scripts are committed, and they fetch jbang.jar -
rem and a JDK, if needed - on first use.
rem
rem Running it again updates an existing installation: the launchers, this
rem script and the pinned jar revision are refreshed and the cached jar is
rem dropped, so the next run picks up the new one.
rem
rem Usage: install.cmd [<target directory>]  (default: .\jbanglitew, or the
rem                                           directory this script is in)
rem
rem Environment:
rem   JBANGLITE_REPO         GitHub repository to install from (default instreest/jbang)
rem   JBANGLITE_REF          branch, tag or commit to install (default main)
rem   JBANGLITE_RAW_BASEURL  where raw files are served from
rem                          (default https://raw.githubusercontent.com)
setlocal enabledelayedexpansion

if "%JBANGLITE_REPO%"=="" (set "repo=instreest/jbang") else (set "repo=%JBANGLITE_REPO%")
if "%JBANGLITE_REF%"=="" (set "ref=main") else (set "ref=%JBANGLITE_REF%")
if "%JBANGLITE_RAW_BASEURL%"=="" (set "rawBaseUrl=https://raw.githubusercontent.com") else (set "rawBaseUrl=%JBANGLITE_RAW_BASEURL%")
set "base=%rawBaseUrl%/%repo%/%ref%/dist"

if not "%~1"=="" (
  set "dir=%~f1"
) else (
  rem updating an existing installation when this script sits in a jbanglitew directory
  for %%D in ("%~dp0.") do set "here=%%~fD"
  for %%D in ("%~dp0.") do set "hereName=%%~nxD"
  if /i "!hereName!"=="jbanglitew" (set "dir=!here!") else (set "dir=%CD%\jbanglitew")
)

rem Everything is fetched into a staging directory first, so a failed download
rem leaves an existing installation as it was
set "staging=%TEMP%\jbanglite-%RANDOM%%RANDOM%"
mkdir "%staging%" || exit /b 1

echo Installing the JBangLite wrapper from %repo% (%ref%) into %dir% 1>&2
call :fetch jbanglite       "%staging%\jbanglite"     || goto :failed
call :fetch jbanglite.cmd   "%staging%\jbanglite.cmd" || goto :failed
call :fetch install.sh      "%staging%\install.sh"   || goto :failed
call :fetch install.cmd     "%staging%\install.cmd"  || goto :failed
call :fetch README.md       "%staging%\README.md"    || goto :failed
call :fetch gitignore       "%staging%\.gitignore"   || goto :failed
call :fetch LICENSE         "%staging%\LICENSE"     || goto :failed
call :fetch jbanglite.jar.sha256 "%staging%\jar.sha256" || goto :failed

set "jarSha256="
for /f "usebackq tokens=1" %%A in ("%staging%\jar.sha256") do if not defined jarSha256 set "jarSha256=%%A"
del /f /q "%staging%\jar.sha256"

> "%staging%\jbanglite.properties" (
  echo # Written by install.cmd - where the launchers get jbang.jar from.
  echo # Re-run install.cmd ^(or install.sh^) to update; set JBANGLITE_REF to pin
  echo # another revision.
  echo repo=%repo%
  echo ref=%ref%
  echo jarSha256=%jarSha256%
)

if not exist "%dir%" mkdir "%dir%"
copy /y "%staging%\*" "%dir%" >nul || goto :failed
copy /y "%staging%\.gitignore" "%dir%\.gitignore" >nul || goto :failed
rem drop the cached jar so the next run downloads the one this revision pins
if exist "%dir%\.jbanglite" rmdir /s /q "%dir%\.jbanglite"
rmdir /s /q "%staging%"

for %%D in ("%dir%") do echo Installed. Commit %%~nxD\ and run '%%~nxD\jbanglite ^<script.java^>'. 1>&2
exit /b 0

:fetch
curl -fsSL "%base%/%~1" -o %2
exit /b %ERRORLEVEL%

:failed
echo Installation failed, %dir% was left unchanged 1>&2
rmdir /s /q "%staging%" 2>nul
exit /b 1
