@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
echo VS_ENV_READY
cd /d "C:\Users\arshjot\Downloads\fsr2mod\src\native\fsrvk"
if exist build rmdir /s /q build
cmake -B build -G "Visual Studio 18 2026" -A x64 -DJAVA_HOME="C:/Program Files/Java/jdk-25.0.2" -DCMAKE_BUILD_TYPE=Release 2>&1
echo CMAKE_EXIT=%ERRORLEVEL%
cmake --build build --config Release 2>&1
echo BUILD_EXIT=%ERRORLEVEL%
