@echo off
setlocal EnableDelayedExpansion
for %%i in ("%~dp0..") do set "JENESIS_HOME=%%~fi"

set "SOURCES_JAR="
for %%f in ("!JENESIS_HOME!\sources\*-sources.jar") do (
    if not defined SOURCES_JAR set "SOURCES_JAR=%%f"
)
if not defined SOURCES_JAR (
    echo jenesis-version: no sources jar found in !JENESIS_HOME!\sources 1>&2
    exit /b 1
)

for %%i in ("!SOURCES_JAR!") do set "JAR_NAME=%%~ni"
set "VERSION=!JAR_NAME!"
if /i "!VERSION:~0,14!"=="build.jenesis-" set "VERSION=!VERSION:~14!"
if /i "!VERSION:~-8!"=="-sources" set "VERSION=!VERSION:~0,-8!"

echo jenesis-version: sdk is at version !VERSION!

set "LABELED=0"
if not "%~1"=="" set "LABELED=1"

set "ALL_MATCH=1"

if "!LABELED!"=="0" (
    set "TARGET=."
    call :process_target
    if errorlevel 1 exit /b !ERRORLEVEL!
    goto :done
)

:next_arg
if "%~1"=="" goto :done
set "TARGET=%~1"
call :process_target
if errorlevel 1 exit /b !ERRORLEVEL!
shift
goto :next_arg

:done
if "!ALL_MATCH!"=="1" exit /b 0
exit /b 1

:process_target
if not exist "!TARGET!\" (
    echo jenesis-version: target '!TARGET!' is not a directory 1>&2
    exit /b 1
)
if "!LABELED!"=="1" (
    set "TARGET_CLEAN=!TARGET!"
    if "!TARGET_CLEAN:~-1!"=="\" set "TARGET_CLEAN=!TARGET_CLEAN:~0,-1!"
    if "!TARGET_CLEAN:~-1!"=="/" set "TARGET_CLEAN=!TARGET_CLEAN:~0,-1!"
    set "SUMMARY_PREFIX=!TARGET_CLEAN! - "
) else (
    set "SUMMARY_PREFIX="
)
if not exist "!TARGET!\build\jenesis\" (
    echo jenesis-version: !SUMMARY_PREFIX!no build/jenesis found
    set "ALL_MATCH=0"
    exit /b 0
)
if not exist "!TARGET!\build\jenesis\jenesis.version" (
    echo jenesis-version: !SUMMARY_PREFIX!no version found
    set "ALL_MATCH=0"
    exit /b 0
)
set "CUR_VERSION="
for /f "usebackq delims=" %%v in ("!TARGET!\build\jenesis\jenesis.version") do if not defined CUR_VERSION set "CUR_VERSION=%%v"
echo jenesis-version: !SUMMARY_PREFIX!build/jenesis is at version !CUR_VERSION!
if not "!CUR_VERSION!"=="!VERSION!" set "ALL_MATCH=0"
exit /b 0
