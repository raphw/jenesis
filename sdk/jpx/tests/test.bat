@echo off
setlocal EnableDelayedExpansion
REM Happy-path test for the Windows jpx launcher (sdk\jpx\bin\jpx.bat).
REM Mirrors sdk\jpx\tests\test.sh: the offline usage and validation paths, then
REM an install and launch of a sample tool from a file-backed Maven repository
REM staged in a temporary directory, with the home redirected so the
REM installation never touches the real %USERPROFILE%. Exits 0 only when every
REM check passes.

REM This script lives at <repo>\sdk\jpx\tests\, so its parent is the SDK home.
for %%i in ("%~dp0..") do set "SDK_HOME=%%~fi"

set "MODULE_JAR="
for %%f in ("%SDK_HOME%\lib\*.jar") do (
    if not defined MODULE_JAR set "MODULE_JAR=%%f"
)
if not defined MODULE_JAR (
    echo jpx-tests: no jar at %SDK_HOME%\lib - stage the SDK first 1>&2
    exit /b 1
)

REM The sample tool is compiled with the same Java the launcher resolves.
set "JAVAC=javac"
set "JAR=jar"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        set "JAVAC=%JAVA_HOME%\bin\javac.exe"
        set "JAR=%JAVA_HOME%\bin\jar.exe"
    )
)

set "TMPDIR=%TEMP%\jpx-tests-%RANDOM%-%RANDOM%"
mkdir "%TMPDIR%" || exit /b 1
set "OUTFILE=%TMPDIR%\out.txt"

REM [1/6] --help prints the usage and exits 0
echo [1/6] jpx --help
call "%SDK_HOME%\bin\jpx.bat" --help > "%OUTFILE%" 2>&1
if errorlevel 1 goto :fail
findstr /c:"Usage: jpx" "%OUTFILE%" >nul || goto :fail
echo   ok

REM [2/6] no target prints the usage and exits 64
echo [2/6] jpx without a target
call "%SDK_HOME%\bin\jpx.bat" > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if not "!RC!"=="64" goto :fail
findstr /c:"Usage: jpx" "%OUTFILE%" >nul || goto :fail
echo   ok

REM [3/6] an unknown option prints the usage and exits 64
echo [3/6] jpx with an unknown option
call "%SDK_HOME%\bin\jpx.bat" --unknown target > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if not "!RC!"=="64" goto :fail
findstr /c:"Unknown option: --unknown" "%OUTFILE%" >nul || goto :fail
echo   ok

REM [4/6] a malformed --hash is rejected before any resolution work
echo [4/6] jpx with a malformed --hash
call "%SDK_HOME%\bin\jpx.bat" --hash=xyz target > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if "!RC!"=="0" goto :fail
findstr /c:"at least 32 hex characters" "%OUTFILE%" >nul || goto :fail
echo   ok

REM [5/6] install and launch a sample tool from a file-backed Maven repository;
REM the redirected home deliberately has no .m2 repository, so the installation
REM must succeed without a local Maven cache to materialize into.
echo [5/6] jpx install and launch
mkdir "%TMPDIR%\src\exampletool"
mkdir "%TMPDIR%\classes"
mkdir "%TMPDIR%\home"
(
echo package exampletool;
echo public class Main {
echo     public static void main^(String[] arguments^) throws Exception {
echo         java.nio.file.Files.writeString^(java.nio.file.Path.of^(arguments[0]^), "jpx-sdk-test"^);
echo         System.exit^(7^);
echo     }
echo }
) > "%TMPDIR%\src\exampletool\Main.java"
"%JAVAC%" -d "%TMPDIR%\classes" "%TMPDIR%\src\exampletool\Main.java" > "%OUTFILE%" 2>&1
if errorlevel 1 goto :fail
set "REPO=%TMPDIR%\repo\org\example\tool\1.0"
mkdir "%REPO%"
"%JAR%" --create --file "%REPO%\tool-1.0.jar" --main-class exampletool.Main -C "%TMPDIR%\classes" . > "%OUTFILE%" 2>&1
if errorlevel 1 goto :fail
(
echo ^<project xmlns="http://maven.apache.org/POM/4.0.0"^>
echo     ^<groupId^>org.example^</groupId^>
echo     ^<artifactId^>tool^</artifactId^>
echo     ^<version^>1.0^</version^>
echo ^</project^>
) > "%REPO%\tool-1.0.pom"
set "TMPDIR_FWD=%TMPDIR:\=/%"
set "JAVA_OPTS=-Duser.home=%TMPDIR%\home -Djenesis.maven.uri=file:///%TMPDIR_FWD%/repo/"
call "%SDK_HOME%\bin\jpx.bat" org.example:tool@1.0 "%TMPDIR%\marker.txt" > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if not "!RC!"=="7" goto :fail
findstr /c:"jpx-sdk-test" "%TMPDIR%\marker.txt" >nul || goto :fail
set "DESCRIPTOR=%TMPDIR%\home\.jenesis\jpx\org.example--tool@1.0\jpx.properties"
if not exist "%DESCRIPTOR%" goto :fail
findstr /c:"classpath=tool-1.0.jar" "%DESCRIPTOR%" >nul || goto :fail
echo   ok

REM [6/6] --hash verifies the recorded checksum prefix and rejects a mismatch
echo [6/6] jpx --hash verification
set "CHECKSUM="
for /f "usebackq tokens=1* delims=/" %%a in (`findstr /b /c:"checksum=SHA-256/" "%DESCRIPTOR%"`) do (
    if not defined CHECKSUM set "CHECKSUM=%%b"
)
if not defined CHECKSUM goto :fail
call "%SDK_HOME%\bin\jpx.bat" --hash=!CHECKSUM:~0,32! org.example:tool@1.0 "%TMPDIR%\marker.txt" > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if not "!RC!"=="7" goto :fail
call "%SDK_HOME%\bin\jpx.bat" --hash=00000000000000000000000000000000 org.example:tool@1.0 "%TMPDIR%\marker.txt" > "%OUTFILE%" 2>&1
set "RC=!ERRORLEVEL!"
if "!RC!"=="0" goto :fail
findstr /c:"Checksum mismatch" "%OUTFILE%" >nul || goto :fail
echo   ok

rmdir /s /q "%TMPDIR%" >nul 2>&1
echo jpx-tests: all checks passed
exit /b 0

:fail
echo jpx-tests: failure 1>&2
if exist "%OUTFILE%" type "%OUTFILE%" 1>&2
rmdir /s /q "%TMPDIR%" >nul 2>&1
exit /b 1
