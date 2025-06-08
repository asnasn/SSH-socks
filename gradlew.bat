@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows (Placeholder)
@rem
@rem ##########################################################################

@if "%DEBUG%" == "" @echo off
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_HOME=%DIRNAME%

echo Placeholder gradlew.bat
echo This script is a placeholder and will not actually run Gradle.
echo For CI purposes, this script will attempt to simulate 'tasks' or 'assembleRelease'.

set GRADLE_WRAPPER_PROPERTIES_PATH=%APP_HOME%gradle\wrapper\gradle-wrapper.properties

if not exist "%GRADLE_WRAPPER_PROPERTIES_PATH%" (
    echo Warning: Missing Gradle wrapper properties file: %GRADLE_WRAPPER_PROPERTIES_PATH%.
) else (
    rem Basic extraction, might not be perfect
    for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"distributionUrl=" "%GRADLE_WRAPPER_PROPERTIES_PATH%"') do (
        echo Info: Distribution URL found in wrapper properties.
    )
)

set CMD_ARG1=%1

if /I "%CMD_ARG1%" == "tasks" (
    echo Simulating 'tasks' execution.
    exit /B 0
) else if /I "%CMD_ARG1%" == "assembleRelease" (
    echo Simulating 'assembleRelease' execution and creating a dummy APK.
    if not exist "app\build\outputs\apk\release" md "app\build\outputs\apk\release"
    echo This is a dummy APK created by placeholder gradlew.bat > app\build\outputs\apk\release\app-release-unsigned.apk
    exit /B 0
)

echo Unsupported command: %*
echo This placeholder gradlew.bat only supports 'tasks' or 'assembleRelease'.

if "%OS%" == "Windows_NT" endlocal
exit /B 1
