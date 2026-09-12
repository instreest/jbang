@echo off
rem Installs JBangLite into a project: the launcher scripts, this installer and
rem jbanglite.properties go into jbanglite\, which is committed, so the project
rem can be built and run without JBangLite (or a JDK) being installed on the
rem machine.
rem
rem Everything comes from a GitHub release, over https. jbanglite.jar and a JDK
rem are not installed here: jbanglite.properties pins the version, URL and
rem SHA-256 of each, and the launcher downloads and verifies them once per
rem machine, into %%userprofile%%\.jbanglite. A project that would rather vendor
rem the jar can drop a jbanglite.jar into jbanglite\ next to the launcher, and
rem then only a JDK is ever fetched.
rem
rem Running it again updates an existing installation: every file, the
rem properties included, is replaced by the one from the chosen release.
rem
rem Usage: install.cmd [<target directory>]  (default: .\jbanglite, or the
rem                                           directory this script is in)
rem
rem Environment:
rem   JBANGLITE_REPO         GitHub repository to install from (default instreest/jbanglite)
rem   JBANGLITE_REF          release tag to install (default: the latest release)
rem   JBANGLITE_DIST_BASEURL install from here instead of from a GitHub release
setlocal enabledelayedexpansion

if "%JBANGLITE_REPO%"=="" (set "repo=instreest/jbanglite") else (set "repo=%JBANGLITE_REPO%")

if not "%JBANGLITE_DIST_BASEURL%"=="" (
  set "base=%JBANGLITE_DIST_BASEURL%"
) else if not "%JBANGLITE_REF%"=="" (
  set "base=https://github.com/%repo%/releases/download/%JBANGLITE_REF%"
) else (
  rem GitHub redirects this to the newest release, so no release has to be
  rem looked up and no API has to be called
  set "base=https://github.com/%repo%/releases/latest/download"
)

rem A plaintext install would let anyone on the path replace the scripts a
rem project is about to commit. A loopback address is allowed so that the tests
rem can serve a release locally.
if "!base:~0,8!"=="https://" goto :base_ok
if "!base:~0,17!"=="http://127.0.0.1:" goto :base_ok
if "!base:~0,17!"=="http://localhost:" goto :base_ok
echo Refusing to install over anything but https: !base! 1>&2
exit /b 1
:base_ok

if not "%~1"=="" (
  set "dir=%~f1"
) else (
  rem updating an existing installation when this script sits in a jbanglite directory
  for %%D in ("%~dp0.") do set "here=%%~fD"
  for %%D in ("%~dp0.") do set "hereName=%%~nxD"
  if /i "!hereName!"=="jbanglite" (set "dir=!here!") else (set "dir=%CD%\jbanglite")
)

rem Everything is fetched into a staging directory first, so a failed download
rem leaves an existing installation as it was
set "staging=%TEMP%\jbanglite-%RANDOM%%RANDOM%"
mkdir "%staging%" || exit /b 1

echo Installing JBangLite from !base! into !dir! 1>&2
rem what a project gets; dist\ in the repository holds the same set
for %%F in (jbanglite jbanglite.cmd jbanglite-bootstrap-jdk jbanglite-bootstrap-jdk.cmd jbanglite-bootstrap-jar jbanglite-bootstrap-jar.cmd jbanglite.properties install.sh install.cmd README.md LICENSE) do (
  curl -fsSL --proto "=https,http" --proto-redir "=https" "!base!/%%F" -o "%staging%\%%F" || goto :failed
)

if not exist "!dir!" mkdir "!dir!"
copy /y "%staging%\*" "!dir!" >nul || goto :failed
rmdir /s /q "%staging%"

for %%D in ("!dir!") do echo Installed. Commit %%~nxD\ and run '%%~nxD\jbanglite ^<script.java^>'. 1>&2
exit /b 0

:failed
echo Installation failed, !dir! was left unchanged 1>&2
rmdir /s /q "%staging%" 2>nul
exit /b 1
