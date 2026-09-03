# Task 9: Final Build + Integration Test

## Files
- All modified files

## Context
Final verification that everything builds and bundles correctly. Run clean build, verify JAR contents, launch client to confirm no regressions.

## Steps
1. **Clean build**: `.\gradlew.bat clean build`
   Verify:
   - BUILD SUCCESSFUL
   - `build/libs/fsr-reloaded-*.jar` contains `amd_fsr4_api.dll` in `/natives/`
   - `build/libs/fsr-reloaded-*.jar` contains `AMD_FSR_API_LICENSE.txt` in `/natives/`
   - `build/libs/fsr-reloaded-*.jar` contains `fsrvk.dll` in `/natives/`

2. **Verify no regressions**: `.\gradlew.bat build`
   - Build passes cleanly
   - Existing FSR1/FSR2/FSR3 modes compile (unchanged paths)

3. **Quick-check FSR4 init (if GPU supports it)**:
   When amd_fsr4_api.dll is present and GPU is RDNA 3.5+:
   - Log "FSR4: init OK, semaphores=true" or "FSR4: init OK, semaphores=false"
   - Falls back to FSR2/3 on unsupported hardware without crash

4. **Commit**: `git add -A` + commit

## Important
- This is the final integration step — all tasks 1-8 must be committed first
- Do NOT run `.\gradlew.bat runClient` if you don't have a display (CI environment)
- Focus on build verification and JAR structure
