# SDD Progress Ledger - FSR Pipeline Optimizations

Baseline: HEAD (starting commit for pipeline optimization work)

Task 1: complete (commit c89f358, texture formats RGBA16F->R11G11B10F/RGBA8)
Task 2: complete (narrowed barriers - done by Task 1 subagent)
Task 3: complete (commit eadbec8, cached FBO in Iris upscale path)
Task 4: complete (commit 847fb11, removed redundant RCAS barrier)


Task 1: complete (commits c7e84a9..cb111d9, review clean)

# Phase 2 - Shared Memory mainRT

Baseline: a4639ef

(All Phase 2 tasks implemented and committed at 1fd91f7)

# Phase 3 - FSR4 SDK Integration

Baseline: f0a5498

**Goal:** Integrate the closed-source AMD FSR4 SDK into the mod, replacing GL compute shader FSR2/3 with native Vulkan FSR4 dispatch, with dual-path GL-VK synchronization.

Task 1: complete (commit d6a96bc, stub headers + build system configured)
Task 2: complete (commit 4a2396d, dual queue — 2 queues from same family)
Task 3: complete (commit ce48356, SyncManager exportable semaphore)
Task 4: complete (commit d01fc13, Fsr4SharedResources + Java pool API)
Task 5: complete (commit 744268c, Fsr4Context + Fsr4Native — note: LWJGL 3.3.3 Kernel32 lacks SetDllDirectory, need alternate DLL loading approach)
Task 6: complete (commit 97f98f0, GLSemaphoreManager rewritten to Hybrid Command-Buffer Flush — glFlush only, no semaphore import)
Task 7: complete (commit b6c6d62, FSRProcessor FSR4 dispatch — Hybrid Command-Buffer Flush path)
Task 8: complete (commit e69088f, MV gen routed to shared RG16F texture)
Task 9: complete (commit 0e53a73, final build verified — JAR contains fsrvk.dll, amd_fsr4_api.dll, AMD_FSR_API_LICENSE.txt)

---

**Phase 3 complete — all 9 tasks implemented and committed.**
Task 1: complete (commits 855a223..cc92add, review clean)
Task 2: complete (commits cc92add..fb33b35, review clean)
Task 3: complete (commits fb33b35..c1c315b, review clean)
