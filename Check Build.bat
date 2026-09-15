@echo off
title Personal Space - build check
cd /d "%~dp0"
echo.
echo  Compiling the plugin and running its unit tests. Keep this window open.
echo.
call gradlew.bat build
echo.
if %ERRORLEVEL% equ 0 (echo  BUILD OK - everything compiled and the tests passed.) else (echo  BUILD FAILED - scroll up for the red error text.)
pause
