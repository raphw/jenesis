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
        echo jenesis-validate: no 'jar' tool found - install a JDK or set JAVA_HOME ^(Java 25 or newer required^) 1>&2
        exit /b 1
    )
    set "JAR=jar"
)

set "SOURCES_JAR="
for %%f in ("!JENESIS_HOME!\sources\*-sources.jar") do (
    if not defined SOURCES_JAR set "SOURCES_JAR=%%f"
)
if not defined SOURCES_JAR (
    echo jenesis-validate: no sources jar found in !JENESIS_HOME!\sources 1>&2
    exit /b 1
)

for %%i in ("!SOURCES_JAR!") do set "JAR_NAME=%%~ni"
set "VERSION=!JAR_NAME!"
if /i "!VERSION:~0,14!"=="build.jenesis-" set "VERSION=!VERSION:~14!"
if /i "!VERSION:~-8!"=="-sources" set "VERSION=!VERSION:~0,-8!"

echo jenesis-validate: Jenesis !VERSION! - comparing build\jenesis against bundled sources ^(SHA-256^)

set "TMPDIR=%TEMP%\jenesis-validate-%RANDOM%-%RANDOM%"
mkdir "!TMPDIR!" || exit /b 1
pushd "!TMPDIR!"
"!JAR!" xf "!SOURCES_JAR!"
set "EXTRACT_EXIT=!ERRORLEVEL!"
popd
if !EXTRACT_EXIT! NEQ 0 (
    rmdir /s /q "!TMPDIR!"
    exit /b !EXTRACT_EXIT!
)

set "JENESIS_VALIDATE_JAR_ROOT=!TMPDIR!\build\jenesis"
if exist "!JENESIS_VALIDATE_JAR_ROOT!" <nul set /p =!VERSION!>"!JENESIS_VALIDATE_JAR_ROOT!\jenesis.version"
set "JENESIS_VALIDATE_VERSION=!VERSION!"

set "LABELED=0"
if not "%~1"=="" set "LABELED=1"

if "!LABELED!"=="0" (
    set "TARGET=."
    call :compare_target
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
call :compare_target
if errorlevel 1 (
    rmdir /s /q "!TMPDIR!"
    exit /b !ERRORLEVEL!
)
shift
goto :next_arg

:compare_target
if not exist "!TARGET!\" (
    echo jenesis-validate: target '!TARGET!' is not a directory 1>&2
    exit /b 1
)
pushd "!TARGET!"
set "JENESIS_VALIDATE_CUR_ROOT=!CD!\build\jenesis"
popd
if "!LABELED!"=="1" (
    set "TARGET_CLEAN=!TARGET!"
    if "!TARGET_CLEAN:~-1!"=="\" set "TARGET_CLEAN=!TARGET_CLEAN:~0,-1!"
    if "!TARGET_CLEAN:~-1!"=="/" set "TARGET_CLEAN=!TARGET_CLEAN:~0,-1!"
    set "JENESIS_VALIDATE_PATH_PREFIX=!TARGET_CLEAN!/"
    set "JENESIS_VALIDATE_SUMMARY_PREFIX=!TARGET_CLEAN! - "
) else (
    set "JENESIS_VALIDATE_PATH_PREFIX="
    set "JENESIS_VALIDATE_SUMMARY_PREFIX="
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$jr = if (Test-Path -LiteralPath $env:JENESIS_VALIDATE_JAR_ROOT) { (Get-Item -LiteralPath $env:JENESIS_VALIDATE_JAR_ROOT).FullName } else { $env:JENESIS_VALIDATE_JAR_ROOT }; $cr = if (Test-Path -LiteralPath $env:JENESIS_VALIDATE_CUR_ROOT) { (Get-Item -LiteralPath $env:JENESIS_VALIDATE_CUR_ROOT).FullName } else { $env:JENESIS_VALIDATE_CUR_ROOT }; $pp = $env:JENESIS_VALIDATE_PATH_PREFIX; $sp = $env:JENESIS_VALIDATE_SUMMARY_PREFIX; $bv = $env:JENESIS_VALIDATE_VERSION; function H($p){(Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLower()}; $vf = Join-Path $cr 'jenesis.version'; if (Test-Path -LiteralPath $vf) { $cv = (Get-Content -Raw -LiteralPath $vf).Trim(); if ($cv -ne $bv) { Write-Output \"jenesis-validate: ${sp}version differs - build/jenesis is at $cv, bundled is $bv\" } }; $matched = 0; $differs = 0; $missing = 0; $additional = 0; if (Test-Path -LiteralPath $jr) { foreach ($f in Get-ChildItem -LiteralPath $jr -Recurse -File) { $rel = $f.FullName.Substring($jr.Length + 1); $relPosix = ($pp + 'build/jenesis/' + ($rel -replace '\\', '/')); $cur = Join-Path $cr $rel; if (Test-Path -LiteralPath $cur) { $h1 = H $f.FullName; $h2 = H $cur; if ($h1 -ne $h2) { Write-Output \"$relPosix differs (bundled $h1, target $h2)\"; $differs++ } else { $matched++ } } else { Write-Output \"$relPosix missing\"; $missing++ } } }; if (Test-Path -LiteralPath $cr) { foreach ($f in Get-ChildItem -LiteralPath $cr -Recurse -File) { $rel = $f.FullName.Substring($cr.Length + 1); $relPosix = ($pp + 'build/jenesis/' + ($rel -replace '\\', '/')); $jp = Join-Path $jr $rel; if (-not (Test-Path -LiteralPath $jp)) { Write-Output \"$relPosix additional\"; $additional++ } } }; Write-Output \"jenesis-validate: ${sp}$matched matched, $differs differs, $missing missing, $additional additional\""
exit /b !ERRORLEVEL!
