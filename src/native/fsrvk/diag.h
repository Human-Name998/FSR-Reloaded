#pragma once

// Global diagnostics gate. All native diagLog() implementations (jni_bridge.cpp,
// fsr4_context.cpp, fg4_context.cpp) early-return when this is false, so per-frame
// fopen/fprintf/fflush logging costs ZERO CPU when diagnostics are disabled.
// Java sets it via Fsr4Native.setDiagnosticsEnabled(config.debugMode > 0).
namespace fsr {
extern bool g_diagEnabled;
}
