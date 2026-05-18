@echo off
setlocal EnableDelayedExpansion
for %%i in ("%~dp0..") do set "JENESIS_HOME=%%~fi"

set "JAR="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jar.exe" set "JAR=%JAVA_HOME%\bin\jar.exe"
)
if not defined JAR (
    where jar >nul 2>&1
    if errorlevel 1 (
        echo jenesis-init: no 'jar' tool found - install a JDK or set JAVA_HOME ^(Java 25 or newer required^) 1>&2
        exit /b 1
    )
    set "JAR=jar"
)

set "SOURCES_JAR="
for %%f in ("!JENESIS_HOME!\sources\*-sources.jar") do (
    if not defined SOURCES_JAR set "SOURCES_JAR=%%f"
)
if not defined SOURCES_JAR (
    echo jenesis-init: no sources jar found in !JENESIS_HOME!\sources 1>&2
    exit /b 1
)

for %%i in ("!SOURCES_JAR!") do set "JAR_NAME=%%~ni"
set "VERSION=!JAR_NAME!"
if /i "!VERSION:~0,14!"=="build.jenesis-" set "VERSION=!VERSION:~14!"
if /i "!VERSION:~-8!"=="-sources" set "VERSION=!VERSION:~0,-8!"

echo jenesis-init: jenesis !VERSION! - extracting bundled sources into build\jenesis

set "TMPDIR=%TEMP%\jenesis-init-%RANDOM%-%RANDOM%"
mkdir "!TMPDIR!" || exit /b 1
pushd "!TMPDIR!"
"!JAR!" xf "!SOURCES_JAR!"
set "EXTRACT_EXIT=!ERRORLEVEL!"
popd
if !EXTRACT_EXIT! NEQ 0 (
    rmdir /s /q "!TMPDIR!"
    exit /b !EXTRACT_EXIT!
)
if exist "!TMPDIR!\module-info.java" del /q "!TMPDIR!\module-info.java"

set "LABELED=0"
if not "%~1"=="" set "LABELED=1"

if "!LABELED!"=="0" (
    set "TARGET=."
    call :process_target
    set "FINAL_EXIT=!ERRORLEVEL!"
    rmdir /s /q "!TMPDIR!"
    exit /b !FINAL_EXIT!
)

:next_arg
if "%~1"=="" (
    rmdir /s /q "!TMPDIR!"
    exit /b 0
)
set "TARGET=%~1"
call :process_target
if errorlevel 1 (
    rmdir /s /q "!TMPDIR!"
    exit /b !ERRORLEVEL!
)
shift
goto :next_arg

:process_target
if not exist "!TARGET!\" (
    echo jenesis-init: target '!TARGET!' is not a directory 1>&2
    exit /b 1
)
if "!LABELED!"=="1" echo jenesis-init: !TARGET!
if exist "!TARGET!\build\jenesis" (
    if exist "!TARGET!\build\jenesis\jenesis.version" (
        set "PREVIOUS_VERSION="
        for /f "usebackq delims=" %%v in ("!TARGET!\build\jenesis\jenesis.version") do if not defined PREVIOUS_VERSION set "PREVIOUS_VERSION=%%v"
        echo jenesis-init: removing existing build\jenesis ^(was version !PREVIOUS_VERSION!^)
    ) else (
        echo jenesis-init: removing existing build\jenesis ^(unknown previous version^)
    )
    rmdir /s /q "!TARGET!\build\jenesis"
)
xcopy /s /e /y /i /q "!TMPDIR!\*" "!TARGET!\" >nul
if errorlevel 1 exit /b !ERRORLEVEL!
<nul set /p =!VERSION!>"!TARGET!\build\jenesis\jenesis.version"
exit /b 0
