@echo off
rem Installs JBangLite into a project: the launchers, jbanglite.jar and this
rem installer go into jbanglitew\, which is committed, so the project can be
rem built and run without JBangLite (or a JDK) being installed on the machine.
rem
rem Running it again updates an existing installation: every file,
rem jbanglite.jar included, is replaced by the one from the chosen revision.
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

echo Installing JBangLite from %repo% (%ref%) into %dir% 1>&2
rem dist\ in the repository is exactly what a project gets
for %%F in (jbanglite jbanglite-bootstrap-jdk jbanglite.cmd jbanglite-bootstrap-jdk.cmd jbanglite.jar install.sh install.cmd README.md LICENSE) do (
  curl -fsSL "%base%/%%F" -o "%staging%\%%F" || goto :failed
)

if not exist "%dir%" mkdir "%dir%"
copy /y "%staging%\*" "%dir%" >nul || goto :failed
rmdir /s /q "%staging%"

for %%D in ("%dir%") do echo Installed. Commit %%~nxD\ and run '%%~nxD\jbanglite ^<script.java^>'. 1>&2
exit /b 0

:failed
echo Installation failed, %dir% was left unchanged 1>&2
rmdir /s /q "%staging%" 2>nul
exit /b 1
