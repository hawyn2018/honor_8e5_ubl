@echo off
setlocal
if defined KBL_PYTHON (
  "%KBL_PYTHON%" "%~dp0kbl_console.py" %*
) else (
  python "%~dp0kbl_console.py" %*
)
exit /b %errorlevel%
