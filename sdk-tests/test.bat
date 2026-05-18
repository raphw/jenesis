@echo off
setlocal EnableDelayedExpansion
REM Happy-path test for the Windows SDK scripts (sdk\bin\jenesis*.bat).
REM Mirrors sdk-tests\test.sh: jenesis-init -> jenesis-version -> jenesis-validate
REM against a freshly-staged SDK at <repo>\sdk\{lib,sources}\. Exits 0 only when
REM every check passes.

for %%i in ("%~dp0..") do set "REPO_ROOT=%%~fi"
set "SDK_HOME=%REPO_ROOT%\sdk"

set "SOURCES_JAR="
for %%f in ("%SDK_HOME%\sources\*-sources.jar") do (
    if not defined SOURCES_JAR set "SOURCES_JAR=%%f"
)
if not defined SOURCES_JAR (
    echo sdk-tests: no sources jar at %SDK_HOME%\sources - stage the SDK first 1>&2
    exit /b 1
)

for %%i in ("%SOURCES_JAR%") do set "JAR_NAME=%%~ni"
set "VERSION=!JAR_NAME!"
if /i "!VERSION:~0,14!"=="build.jenesis-" set "VERSION=!VERSION:~14!"
if /i "!VERSION:~-8!"=="-sources" set "VERSION=!VERSION:~0,-8!"
echo sdk-tests: SDK version !VERSION!

set "TMPDIR=%TEMP%\sdk-tests-%RANDOM%-%RANDOM%"
mkdir "%TMPDIR%" || exit /b 1
set "PROJ=%TMPDIR%\proj"
mkdir "%PROJ%"
set "OUTFILE=%TMPDIR%\out.txt"

REM [1/4] jenesis-version on fresh directory: exit 1, reports missing build/jenesis
echo [1/4] jenesis-version on fresh directory
call "%SDK_HOME%\bin\jenesis-version.bat" "%PROJ%" > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if not "!RC!"=="1" goto :fail
findstr /c:"sdk is at version !VERSION!" "%OUTFILE%" >nul || goto :fail
findstr /c:"no build/jenesis found" "%OUTFILE%" >nul || goto :fail
echo   ok

REM [2/4] jenesis-init: populates build\jenesis and writes jenesis.version
echo [2/4] jenesis-init
call "%SDK_HOME%\bin\jenesis-init.bat" "%PROJ%" > "%OUTFILE%" 2>&1
if errorlevel 1 goto :fail
if not exist "%PROJ%\build\jenesis\" goto :fail
if not exist "%PROJ%\build\jenesis\jenesis.version" goto :fail
set "RECORDED="
for /f "usebackq delims=" %%v in ("%PROJ%\build\jenesis\jenesis.version") do if not defined RECORDED set "RECORDED=%%v"
if not "!RECORDED!"=="!VERSION!" goto :fail
echo   ok

REM [3/4] jenesis-version on initialised project: exit 0, reports matching version
echo [3/4] jenesis-version on initialised project
call "%SDK_HOME%\bin\jenesis-version.bat" "%PROJ%" > "%OUTFILE%" 2>&1
if errorlevel 1 goto :fail
findstr /c:"build/jenesis is at version !VERSION!" "%OUTFILE%" >nul || goto :fail
echo   ok

REM [4/4] jenesis-validate: reports zero drift against the bundled sources
echo [4/4] jenesis-validate
call "%SDK_HOME%\bin\jenesis-validate.bat" "%PROJ%" > "%OUTFILE%" 2>&1
findstr /c:"0 differs, 0 missing, 0 additional" "%OUTFILE%" >nul || goto :fail
echo   ok

rmdir /s /q "%TMPDIR%" >nul 2>&1
echo sdk-tests: all checks passed
exit /b 0

:fail
echo sdk-tests: failure 1>&2
if exist "%OUTFILE%" type "%OUTFILE%" 1>&2
rmdir /s /q "%TMPDIR%" >nul 2>&1
exit /b 1
