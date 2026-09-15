@echo off
title Personal Space - RuneLite dev client
cd /d "%~dp0"
echo.
echo  Starting a RuneLite client with the Personal Space plugin loaded.
echo  The first run downloads RuneLite and can take a few minutes - keep this window open.
echo  Log in as normal, then find "Personal Space" in the plugin list (wrench icon).
echo.
call gradlew.bat run
echo.
echo  The client has closed. You can close this window now.
pause
