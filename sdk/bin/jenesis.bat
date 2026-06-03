@echo off
setlocal EnableDelayedExpansion
for %%i in ("%~dp0..") do set "JENESIS_HOME=%%~fi"

set "JAVA="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA (
    where java >nul 2>&1
    if errorlevel 1 (
        echo jenesis: no Java runtime found - set JAVA_HOME or add 'java' to PATH ^(Java 25 or newer required^) 1>&2
        exit /b 1
    )
    set "JAVA=java"
)

set "JAVA_VERSION="
for /f "usebackq tokens=3" %%v in (`""!JAVA!" -version 2^>^&1 ^| findstr /i version"`) do (
    if not defined JAVA_VERSION set "JAVA_VERSION=%%~v"
)
if not defined JAVA_VERSION (
    echo jenesis: failed to determine Java version from '!JAVA! -version' 1>&2
    exit /b 1
)

for /f "tokens=1 delims=." %%m in ("!JAVA_VERSION!") do set "JAVA_MAJOR=%%m"
if !JAVA_MAJOR! LSS 25 (
    echo jenesis: Java 25 or newer required, but '!JAVA!' reports version '!JAVA_VERSION!' 1>&2
    exit /b 1
)

"!JAVA!" %JAVA_OPTS% -p "!JENESIS_HOME!\lib" -m build.jenesis %*
exit /b %ERRORLEVEL%
