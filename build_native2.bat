@echo on
REM Find Visual Studio
for /f "usebackq delims=" %%I in (`"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath`) do set VSINSTALL=%%I
echo VS Path: %VSINSTALL%

REM Setup VC
call "%VSINSTALL%\VC\Auxiliary\Build\vcvarsall.bat" x64
echo After vcvarsall

cd /d C:\Users\arshjot\Downloads\fsr2mod\src\native\fsrvk
echo Current dir: %CD%

cmake -B build -S . -DCMAKE_BUILD_TYPE=Release
echo CMake configure done: %ERRORLEVEL%

cmake --build build --config Release
echo CMake build done: %ERRORLEVEL%

dir build\Release\fsrvk.dll
echo Done
