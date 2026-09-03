@echo off
REM Find Visual Studio installation
for /f "usebackq delims=" %%I in (`"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath`) do set VSINSTALL=%%I
echo VS Install Path: %VSINSTALL%

REM Set up VC environment
call "%VSINSTALL%\VC\Auxiliary\Build\vcvarsall.bat" x64

REM Change to native source directory
cd /d C:\Users\arshjot\Downloads\fsr2mod\src\native\fsrvk

REM Configure and build
echo === Configuring CMake ===
cmake -B build -S . -DCMAKE_BUILD_TYPE=Release
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo === Building ===
cmake --build build --config Release
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo === DLL built successfully ===
dir build\Release\fsrvk.dll
