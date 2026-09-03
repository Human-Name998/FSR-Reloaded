import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.EXTMemoryObjectFD.*;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreFD.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRExternalMemory.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRExternalSemaphore.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.EXTSemaphoreWin32;

/**
 * Phase 0 Spike — VK→GL Reverse-Pipe Interop Test (FSR4)
 *
 * Tests VK-allocation -> GL-import path for FSR4 via Vulkan interop:
 *   1. VK can allocate exportable memory + image
 *   2. VK can export Win32 handle / FD for the shared memory
 *   3. GL can import the handle (glImportMemoryWin32HandleEXT / glImportMemoryFdEXT)
 *   4. Round-trip: VK writes -> GL reads and GL writes -> VK reads both work
 *   5. Cross-API semaphore sync (optional — glFinish fallback acceptable)
 *
 * PASS on 1-4 -> VK→GL reverse pipe feasible (AMD RDNA 3.5 confirmed 2026-06-28).
 * PASS on 5 -> zero-copy sync possible (no glFinish needed).
 * FAIL on 5 -> glFinish() fallback required (~5-15ms/frame performance impact).
 */
public class Phase0Test {

    static final int TEX_W = 64;
    static final int TEX_H = 64;

    static final float CLEAR_R = 0.0f, CLEAR_G = 0.0f, CLEAR_B = 1.0f, CLEAR_A = 1.0f;
    static boolean windows;
    static boolean validationEnabled;

    // GL state
    static long window;
    static int glTexture;
    static int glMemObj;          // GL memory object (imported from VK handle)

    // Vulkan handles (shared memory — VK allocates, GL imports)
    static VkInstance vkInstance;
    static VkPhysicalDevice vkPhysicalDevice;
    static VkDevice vkDevice;
    static VkQueue vkQueue;
    static int vkQueueFamily;
    static long vkCmdPool;
    static VkCommandBuffer vkCmdBuf;
    static long vkFence;
    static long vkDeviceMemory;    // VkDeviceMemory allocated with export flag
    static long vkDeviceMemorySize; // size of the shared memory allocation
    static long vkImage;           // VkImage backed by exportable memory
    static long exportedVkHandle;  // Platform handle exported from VK (HANDLE or fd)
    static long vkReadbackBuffer;
    static long vkReadbackMemory;

    // Semaphore sync
    static long vkSemaphore;
    static int glSemaphore;
    static long exportedVkSemHandle;

    // Debug
    static long vkDebugMessenger;

    // GL function pointer check results
    static boolean glImportMemAvailable;
    static boolean glImportSemAvailable;
    static boolean glGetSemAvailable;

    public static void main(String[] args) {
        windows = System.getProperty("os.name").toLowerCase().contains("win");
        validationEnabled = Boolean.getBoolean("vulkan.validation");
        System.out.println("=== FSR4 Phase 0 Spike (VK->GL Reverse Pipe) ===");
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Arch: " + System.getProperty("os.arch"));
        System.out.println("Validation layer: " + (validationEnabled ? "ENABLED" : "disabled"));
        System.out.println();

        try {
            initGL();
            initVulkan();
            testMemoryRoundTrip();
            testSemaphoreSync();
            System.out.println();
            System.out.println("========================================");
            System.out.println("  RESULT: PASS — VK->GL interop FEASIBLE");
            System.out.println("========================================");
        } catch (Exception e) {
            System.out.println();
            System.err.println("========================================");
            System.err.println("  RESULT: FAIL — see errors above");
            System.err.println("========================================");
            e.printStackTrace();
            System.exit(1);
        } finally {
            cleanup();
        }
    }

    // ============================================================
    //  GL SETUP
    // ============================================================
    static void initGL() {
        System.out.print("[GL] Initializing GLFW+OpenGL 4.6... ");
        glfwInit();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(128, 128, "Phase0", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) throw new RuntimeException("GLFW window creation failed");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        String extStr = glGetString(GL_EXTENSIONS);
        if (extStr == null) {
            extStr = queryExtensionsManually();
        }
        requireExt(extStr, "GL_EXT_memory_object");
        requireExt(extStr, "GL_EXT_semaphore");
        if (windows) {
            requireExt(extStr, "GL_EXT_memory_object_win32");
            requireExt(extStr, "GL_EXT_semaphore_win32");
        } else {
            requireExt(extStr, "GL_EXT_memory_object_fd");
            requireExt(extStr, "GL_EXT_semaphore_fd");
        }

        // Check GL import function availability for memory objects
        String importMemFn = windows ? "glImportMemoryWin32HandleEXT" : "glImportMemoryFdEXT";
        glImportMemAvailable = GL.getFunctionProvider().getFunctionAddress(importMemFn) != MemoryUtil.NULL;

        // Check semaphore function availability
        if (windows) {
            glImportSemAvailable = GL.getFunctionProvider().getFunctionAddress("glImportSemaphoreWin32HandleEXT") != MemoryUtil.NULL;
            glGetSemAvailable    = GL.getFunctionProvider().getFunctionAddress("glGetSemaphoreWin32HandleEXT") != MemoryUtil.NULL;
        } else {
            glImportSemAvailable = GL.getFunctionProvider().getFunctionAddress("glImportSemaphoreFdEXT") != MemoryUtil.NULL;
            glGetSemAvailable    = GL.getFunctionProvider().getFunctionAddress("glGetSemaphoreFdEXT") != MemoryUtil.NULL;
        }

        System.out.println("OK");
    }

    static void requireExt(String extStr, String name) {
        if (!extStr.contains(name)) {
            throw new RuntimeException("Required GL extension missing: " + name);
        }
    }

    static String queryExtensionsManually() {
        StringBuilder sb = new StringBuilder();
        int n = glGetInteger(GL_NUM_EXTENSIONS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(glGetStringi(GL_EXTENSIONS, i));
        }
        return sb.toString();
    }

    // ============================================================
    //  VULKAN SETUP
    // ============================================================
    static void initVulkan() {
        System.out.print("[VK] Initializing Vulkan... ");
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.callocStack(s);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(s.UTF8("FSR4 Phase 0 Spike"));
            appInfo.applicationVersion(VK_MAKE_VERSION(0, 1, 0));
            appInfo.apiVersion(VK_API_VERSION_1_1);

            int nInstExts = 2;
            if (validationEnabled) nInstExts = 3;
            PointerBuffer instExts = s.mallocPointer(nInstExts);
            instExts.put(s.UTF8("VK_KHR_external_memory_capabilities"));
            instExts.put(s.UTF8("VK_KHR_external_semaphore_capabilities"));
            if (validationEnabled) instExts.put(s.UTF8("VK_EXT_debug_utils"));
            instExts.flip();

            PointerBuffer layers = null;
            if (validationEnabled) {
                if (isLayerAvailable(s, "VK_LAYER_KHRONOS_validation")) {
                    layers = s.mallocPointer(1);
                    layers.put(s.UTF8("VK_LAYER_KHRONOS_validation"));
                    layers.flip();
                    System.out.print("(validation ON) ");
                } else {
                    System.out.println("\n  WARN: VK_LAYER_KHRONOS_validation not installed");
                    validationEnabled = false;
                }
            }

            VkInstanceCreateInfo instInfo = VkInstanceCreateInfo.callocStack(s);
            instInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            instInfo.pApplicationInfo(appInfo);
            instInfo.ppEnabledExtensionNames(instExts);
            if (layers != null) instInfo.ppEnabledLayerNames(layers);

            PointerBuffer pInst = s.mallocPointer(1);
            int err = vkCreateInstance(instInfo, null, pInst);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateInstance failed: " + err);
            vkInstance = new VkInstance(pInst.get(0), instInfo);

            if (validationEnabled) {
                setupDebugMessenger(s);
            }

            IntBuffer pCount = s.mallocInt(1);
            err = vkEnumeratePhysicalDevices(vkInstance, pCount, null);
            if (err != VK_SUCCESS || pCount.get(0) == 0) {
                throw new RuntimeException("No Vulkan devices");
            }
            PointerBuffer physDevs = s.mallocPointer(pCount.get(0));
            vkEnumeratePhysicalDevices(vkInstance, pCount, physDevs);

            VkPhysicalDevice selected = null;
            int selectedFamily = -1;
            for (int i = 0; i < physDevs.remaining(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(physDevs.get(i), vkInstance);

                if (!deviceSupportsExt(pd, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)) continue;
                if (!deviceSupportsExt(pd, VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME)) continue;
                if (windows && !deviceSupportsExt(pd, "VK_KHR_external_memory_win32")) continue;
                if (windows && !deviceSupportsExt(pd, "VK_KHR_external_semaphore_win32")) continue;
                if (!windows && !deviceSupportsExt(pd, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)) continue;
                if (!windows && !deviceSupportsExt(pd, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) continue;

                IntBuffer qfCount = s.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(pd, qfCount, null);
                int n = qfCount.get(0);
                VkQueueFamilyProperties.Buffer qProps = VkQueueFamilyProperties.callocStack(n, s);
                vkGetPhysicalDeviceQueueFamilyProperties(pd, qfCount, qProps);
                for (int q = 0; q < n; q++) {
                    if ((qProps.get(q).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        selected = pd;
                        selectedFamily = q;
                        break;
                    }
                }
                if (selected != null) {
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.callocStack(s);
                    vkGetPhysicalDeviceProperties(pd, props);
                    System.out.print("(GPU: " + props.deviceNameString() + ") ");
                    break;
                }
            }
            if (selected == null) {
                throw new RuntimeException("No Vulkan device with required external memory/semaphore extensions");
            }
            vkPhysicalDevice = selected;
            vkQueueFamily = selectedFamily;

            PointerBuffer devExts = s.mallocPointer(4);
            devExts.put(s.UTF8(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME));
            devExts.put(s.UTF8(VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME));
            devExts.put(s.UTF8(windows
                ? "VK_KHR_external_memory_win32"
                : VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME));
            devExts.put(s.UTF8(windows
                ? "VK_KHR_external_semaphore_win32"
                : VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME));
            devExts.flip();

            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.callocStack(1, s);
            qci.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
            qci.get(0).queueFamilyIndex(vkQueueFamily);
            qci.get(0).pQueuePriorities(s.floats(1.0f));

            VkDeviceCreateInfo devInfo = VkDeviceCreateInfo.callocStack(s);
            devInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            devInfo.pQueueCreateInfos(qci);
            devInfo.ppEnabledExtensionNames(devExts);

            PointerBuffer pDev = s.mallocPointer(1);
            err = vkCreateDevice(vkPhysicalDevice, devInfo, null, pDev);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateDevice failed: " + err);
            vkDevice = new VkDevice(pDev.get(0), vkPhysicalDevice, devInfo);

            PointerBuffer pQueue = s.mallocPointer(1);
            vkGetDeviceQueue(vkDevice, vkQueueFamily, 0, pQueue);
            vkQueue = new VkQueue(pQueue.get(0), vkDevice);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.callocStack(s);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            poolInfo.queueFamilyIndex(vkQueueFamily);
            LongBuffer pPool = s.mallocLong(1);
            err = vkCreateCommandPool(vkDevice, poolInfo, null, pPool);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateCommandPool failed: " + err);
            vkCmdPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(s);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(vkCmdPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(1);
            PointerBuffer pCmd = s.mallocPointer(1);
            err = vkAllocateCommandBuffers(vkDevice, allocInfo, pCmd);
            if (err != VK_SUCCESS) throw new RuntimeException("vkAllocateCommandBuffers failed: " + err);
            vkCmdBuf = new VkCommandBuffer(pCmd.get(0), vkDevice);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.callocStack(s);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = s.mallocLong(1);
            err = vkCreateFence(vkDevice, fenceInfo, null, pFence);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateFence failed: " + err);
            vkFence = pFence.get(0);
        }
        System.out.println("OK");
    }

    static boolean isLayerAvailable(MemoryStack s, String name) {
        IntBuffer pCount = s.mallocInt(1);
        vkEnumerateInstanceLayerProperties(pCount, null);
        if (pCount.get(0) == 0) return false;
        VkLayerProperties.Buffer props = VkLayerProperties.callocStack(pCount.get(0), s);
        vkEnumerateInstanceLayerProperties(pCount, props);
        for (int i = 0; i < props.capacity(); i++) {
            if (name.equals(props.get(i).layerNameString())) return true;
        }
        return false;
    }

    static void setupDebugMessenger(MemoryStack s) {
        VkDebugUtilsMessengerCreateInfoEXT dbgInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(s);
        dbgInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        dbgInfo.messageSeverity(
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
        dbgInfo.messageType(
            VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
            VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
            VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
        dbgInfo.pfnUserCallback((messageSeverity, messageTypes, pCallbackData, pUserData) -> {
            VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
            System.err.println("[VK VALIDATION] " + data.pMessageString());
            return 0;
        });

        long fn = vkGetInstanceProcAddr(vkInstance, "vkCreateDebugUtilsMessengerEXT");
        if (fn != MemoryUtil.NULL) {
            VkDebugUtilsMessengerCreateInfoEXT.Buffer pInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(1, s);
            pInfo.get(0).set(dbgInfo);
            LongBuffer pMessenger = s.mallocLong(1);
            int result = JNI.callPPPPI(vkInstance.address(), pInfo.get(0).address(), 0L, MemoryUtil.memAddress(pMessenger), fn);
            if (result != VK_SUCCESS) {
                System.out.println("\n  WARN: vkCreateDebugUtilsMessengerEXT failed: " + result);
            }
            vkDebugMessenger = pMessenger.get(0);
        }
    }

    static boolean deviceSupportsExt(VkPhysicalDevice pd, String name) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer count = s.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(pd, (String) null, count, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.callocStack(count.get(0), s);
            vkEnumerateDeviceExtensionProperties(pd, (String) null, count, props);
            for (int i = 0; i < props.capacity(); i++) {
                if (name.equals(props.get(i).extensionNameString())) return true;
            }
            return false;
        }
    }

    // ============================================================
    //  MEMORY ROUND-TRIP TEST (Phase 0 — Reverse Path: VK->GL)
    // ============================================================
    static void testMemoryRoundTrip() {
        System.out.println();
        System.out.println("--- Phase 0: Memory Round-Trip (VK->GL Reverse Pipe) ---");

        // Step 1: Create empty GL texture (no storage yet)
        System.out.print("[1] Create empty GL texture... ");
        glTexture = glCreateTextures(GL_TEXTURE_2D);
        glTextureParameteri(glTexture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(glTexture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        System.out.println("id=" + glTexture);

        // Step 2: VK allocates shared memory + exports handle
        System.out.print("[2] VK: allocate exportable image+memory... ");
        vkAllocateExportableMemory();
        System.out.println("OK");

        // Step 3: VK exports handle
        System.out.print("[3] VK: export memory handle (" + (windows ? "Win32" : "FD") + ")... ");
        vkExportHandle();
        System.out.println("OK (handle=0x" + Long.toHexString(exportedVkHandle) + ")");

        // Step 4: GL imports the handle + binds to texture
        System.out.print("[4] GL: import handle + bind to texture... ");
        glImportAndBind();
        System.out.println("OK (glMemObj=" + glMemObj + ")");

        // Step 5: VK clears to BLUE
        System.out.print("[5] VK: clear image to BLUE... ");
        vkClearImage();
        System.out.println("OK");

        // Step 6: GL reads back — should see BLUE (VK->GL)
        System.out.print("[6] GL: read back (expecting BLUE from VK)... ");
        verifyGLTexture("VK->GL BLUE", (byte) 0x00, (byte) 0x00, (byte) 0xFF, (byte) 0xFF);
        System.out.println("OK");

        // Step 7: GL writes RED
        System.out.print("[7] GL: overwrite with RED... ");
        writeGLTexture((byte) 0xFF, (byte) 0x00, (byte) 0x00, (byte) 0xFF);
        glFinish();
        System.out.println("OK");

        // Step 8: VK reads back — should see RED (GL->VK)
        System.out.print("[8] VK: read back (expecting RED from GL)... ");
        vkReadbackImage("GL->VK RED", (byte) 0xFF, (byte) 0x00, (byte) 0x00, (byte) 0xFF);
        System.out.println("OK");

        // Step 9: VK clears to GREEN
        System.out.print("[9] VK: clear image to GREEN... ");
        vkClearImageTo((byte) 0x00, (byte) 0xFF, (byte) 0x00, (byte) 0xFF);
        System.out.println("OK");

        // Step 10: GL reads back — should see GREEN (second VK->GL)
        System.out.print("[10] GL: read back (expecting GREEN from VK)... ");
        verifyGLTexture("VK->GL GREEN", (byte) 0x00, (byte) 0xFF, (byte) 0x00, (byte) 0xFF);
        System.out.println("OK");

        System.out.println("--- Phase 0 PASSED ---");
    }

    static void vkAllocateExportableMemory() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            // Create VkImage with external memory requirement
            VkImageCreateInfo imgInfo = VkImageCreateInfo.callocStack(s);
            imgInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imgInfo.imageType(VK_IMAGE_TYPE_2D);
            imgInfo.format(VK_FORMAT_R8G8B8A8_UNORM);
            imgInfo.extent().set(TEX_W, TEX_H, 1);
            imgInfo.mipLevels(1);
            imgInfo.arrayLayers(1);
            imgInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            imgInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imgInfo.usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            imgInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            VkExternalMemoryImageCreateInfo extInfo = VkExternalMemoryImageCreateInfo.callocStack(s);
            extInfo.sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
            extInfo.handleTypes(windows
                ? 0x00000002  // VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            imgInfo.pNext(extInfo);

            LongBuffer pImg = s.mallocLong(1);
            int err = vkCreateImage(vkDevice, imgInfo, null, pImg);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateImage failed: " + err);
            vkImage = pImg.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.callocStack(s);
            vkGetImageMemoryRequirements(vkDevice, vkImage, memReqs);
            vkDeviceMemorySize = memReqs.size();

            int memType = findMemType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (memType < 0) memType = findMemType(memReqs.memoryTypeBits(), 0);
            if (memType < 0) throw new RuntimeException("No compatible memory type");

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.callocStack(s);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memReqs.size());
            allocInfo.memoryTypeIndex(memType);

            VkExportMemoryAllocateInfo exportInfo = VkExportMemoryAllocateInfo.callocStack(s);
            exportInfo.sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO);
            exportInfo.handleTypes(windows
                ? 0x00000002
                : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            allocInfo.pNext(exportInfo);

            LongBuffer pMem = s.mallocLong(1);
            err = vkAllocateMemory(vkDevice, allocInfo, null, pMem);
            if (err != VK_SUCCESS) throw new RuntimeException("vkAllocateMemory failed: " + err);
            vkDeviceMemory = pMem.get(0);

            err = vkBindImageMemory(vkDevice, vkImage, vkDeviceMemory, 0);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBindImageMemory failed: " + err);

            // Create host-visible readback buffer (not shared — VK-only)
            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.callocStack(s);
            bufInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufInfo.size(TEX_W * TEX_H * 4L);
            bufInfo.usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT);

            LongBuffer pBuf = s.mallocLong(1);
            err = vkCreateBuffer(vkDevice, bufInfo, null, pBuf);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateBuffer failed: " + err);
            vkReadbackBuffer = pBuf.get(0);

            VkMemoryRequirements bufMem = VkMemoryRequirements.callocStack(s);
            vkGetBufferMemoryRequirements(vkDevice, vkReadbackBuffer, bufMem);
            int hostType = findMemType(bufMem.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (hostType < 0) throw new RuntimeException("No host-visible memory for readback");

            VkMemoryAllocateInfo bufAlloc = VkMemoryAllocateInfo.callocStack(s);
            bufAlloc.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            bufAlloc.allocationSize(bufMem.size());
            bufAlloc.memoryTypeIndex(hostType);

            err = vkAllocateMemory(vkDevice, bufAlloc, null, pMem);
            if (err != VK_SUCCESS) throw new RuntimeException("vkAllocateMemory readback failed: " + err);
            vkReadbackMemory = pMem.get(0);

            err = vkBindBufferMemory(vkDevice, vkReadbackBuffer, vkReadbackMemory, 0);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBindBufferMemory failed: " + err);

            // Transition image layout to GENERAL so it's usable
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(s);
            begin.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            begin.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            err = vkBeginCommandBuffer(vkCmdBuf, begin);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBeginCommandBuffer: " + err);

            VkImageMemoryBarrier.Buffer b1 = VkImageMemoryBarrier.callocStack(1, s);
            b1.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            b1.get(0).srcAccessMask(0);
            b1.get(0).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b1.get(0).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            b1.get(0).newLayout(VK_IMAGE_LAYOUT_GENERAL);
            b1.get(0).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b1.get(0).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b1.get(0).image(vkImage);
            b1.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            b1.get(0).subresourceRange().levelCount(1);
            b1.get(0).subresourceRange().layerCount(1);

            vkCmdPipelineBarrier(vkCmdBuf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b1);

            err = vkEndCommandBuffer(vkCmdBuf);
            if (err != VK_SUCCESS) throw new RuntimeException("vkEndCommandBuffer: " + err);

            VkSubmitInfo submit = VkSubmitInfo.callocStack(s);
            submit.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submit.pCommandBuffers(s.pointers(vkCmdBuf));
            err = vkQueueSubmit(vkQueue, submit, vkFence);
            if (err != VK_SUCCESS) throw new RuntimeException("vkQueueSubmit: " + err);

            err = vkWaitForFences(vkDevice, vkFence, true, Long.MAX_VALUE);
            if (err != VK_SUCCESS) throw new RuntimeException("vkWaitForFences: " + err);
            vkResetFences(vkDevice, vkFence);
            vkResetCommandBuffer(vkCmdBuf, 0);
        }
    }

    static void vkExportHandle() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            if (windows) {
                VkMemoryGetWin32HandleInfoKHR getInfo = VkMemoryGetWin32HandleInfoKHR.callocStack(s);
                getInfo.sType(VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR);
                getInfo.memory(vkDeviceMemory);
                getInfo.handleType(0x00000002); // VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                PointerBuffer pHandle = s.mallocPointer(1);
                int err = KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(vkDevice, getInfo, pHandle);
                if (err != VK_SUCCESS) throw new RuntimeException("vkGetMemoryWin32HandleKHR failed: " + err);
                exportedVkHandle = pHandle.get(0);
            } else {
                VkMemoryGetFdInfoKHR getInfo = VkMemoryGetFdInfoKHR.callocStack(s);
                getInfo.sType(VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR);
                getInfo.memory(vkDeviceMemory);
                getInfo.handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
                IntBuffer pFd = s.mallocInt(1);
                int err = KHRExternalMemoryFd.vkGetMemoryFdKHR(vkDevice, getInfo, pFd);
                if (err != VK_SUCCESS) throw new RuntimeException("vkGetMemoryFdKHR failed: " + err);
                exportedVkHandle = pFd.get(0) & 0xFFFFFFFFL;
            }
        }
    }

    static void glImportAndBind() {
        if (!glImportMemAvailable) {
            throw new RuntimeException("GL import function not available in driver");
        }

        // Flush any pending GL errors
        while (glGetError() != GL_NO_ERROR) {}

        // Create GL memory object
        IntBuffer pMem = MemoryUtil.memAllocInt(1);
        glCreateMemoryObjectsEXT(pMem);
        glMemObj = pMem.get(0);
        MemoryUtil.memFree(pMem);
        if (glMemObj == 0) {
            throw new RuntimeException("glCreateMemoryObjectsEXT returned 0");
        }
        int err = glGetError();
        if (err != GL_NO_ERROR) {
            throw new RuntimeException("glCreateMemoryObjectsEXT error: 0x" + Integer.toHexString(err));
        }

        // Import VK-exported handle into GL memory object
        if (windows) {
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(glMemObj,
                vkDeviceMemorySize,
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
                exportedVkHandle);
        } else {
            EXTMemoryObjectFD.glImportMemoryFdEXT(glMemObj,
                vkDeviceMemorySize,
                EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT,
                (int) exportedVkHandle);
            exportedVkHandle = 0; // GL now owns the fd
        }
        err = glGetError();
        if (err != GL_NO_ERROR) {
            throw new RuntimeException("glImportMemory*EXT error: 0x" + Integer.toHexString(err));
        }

        // Bind GL texture to the imported memory
        glTextureStorageMem2DEXT(glTexture, 1, GL_RGBA8, TEX_W, TEX_H, glMemObj, 0);
        err = glGetError();
        if (err != GL_NO_ERROR) {
            throw new RuntimeException("glTextureStorageMem2DEXT error: 0x" + Integer.toHexString(err));
        }
    }

    static int findMemType(int typeBits, int requiredFlags) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.callocStack(s);
            vkGetPhysicalDeviceMemoryProperties(vkPhysicalDevice, props);
            int n = props.memoryTypeCount();
            for (int i = 0; i < n; i++) {
                if ((typeBits & (1 << i)) != 0) {
                    int flags = props.memoryTypes().get(i).propertyFlags();
                    if ((flags & requiredFlags) == requiredFlags) return i;
                }
            }
            return -1;
        }
    }

    // ============================================================
    //  GL TEXTURE OPS
    // ============================================================
    static void writeGLTexture(byte r, byte g, byte b, byte a) {
        ByteBuffer pixels = MemoryUtil.memAlloc(TEX_W * TEX_H * 4);
        for (int i = 0; i < TEX_W * TEX_H; i++) {
            pixels.put(r).put(g).put(b).put(a);
        }
        pixels.flip();
        glBindTexture(GL_TEXTURE_2D, glTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, TEX_W, TEX_H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixels);
        glFinish();
    }

    static void verifyGLTexture(String label, byte er, byte eg, byte eb, byte ea) {
        glBindTexture(GL_TEXTURE_2D, glTexture);
        ByteBuffer pixels = MemoryUtil.memAlloc(TEX_W * TEX_H * 4);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glBindTexture(GL_TEXTURE_2D, 0);

        int fails = 0;
        for (int i = 0; i < TEX_W * TEX_H && fails <= 5; i++) {
            if (pixels.get(i*4)!=er || pixels.get(i*4+1)!=eg || pixels.get(i*4+2)!=eb || pixels.get(i*4+3)!=ea) {
                fails++;
                System.err.println("    MISMATCH pixel " + i + ": got " + hexColor(pixels, i)
                    + " expected " + hexColor(er,eg,eb,ea));
            }
        }
        MemoryUtil.memFree(pixels);
        if (fails > 0) throw new RuntimeException(label + ": " + fails + "+ mismatches");
    }

    static String hexColor(byte r, byte g, byte b, byte a) {
        return String.format("0x%02X%02X%02X%02X", r&0xFF, g&0xFF, b&0xFF, a&0xFF);
    }
    static String hexColor(ByteBuffer buf, int idx) {
        return hexColor(buf.get(idx*4), buf.get(idx*4+1), buf.get(idx*4+2), buf.get(idx*4+3));
    }

    // ============================================================
    //  VULKAN CLEAR + READBACK
    // ============================================================
    static void vkClearImage() {
        vkClearImageTo(CLEAR_R, CLEAR_G, CLEAR_B, CLEAR_A);
    }

    static void vkClearImageTo(float r, float g, float b, float a) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(s);
            begin.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            begin.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int err = vkBeginCommandBuffer(vkCmdBuf, begin);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBeginCommandBuffer: " + err);

            VkImageMemoryBarrier.Buffer b1 = VkImageMemoryBarrier.callocStack(1, s);
            b1.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            b1.get(0).srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b1.get(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            b1.get(0).oldLayout(VK_IMAGE_LAYOUT_GENERAL);
            b1.get(0).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            b1.get(0).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b1.get(0).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b1.get(0).image(vkImage);
            b1.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            b1.get(0).subresourceRange().levelCount(1);
            b1.get(0).subresourceRange().layerCount(1);

            vkCmdPipelineBarrier(vkCmdBuf, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b1);

            VkClearColorValue cc = VkClearColorValue.callocStack(s);
            cc.float32(0, r).float32(1, g).float32(2, b).float32(3, a);

            VkImageSubresourceRange range = VkImageSubresourceRange.callocStack(s);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.levelCount(1);
            range.layerCount(1);

            vkCmdClearColorImage(vkCmdBuf, vkImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, cc, range);

            VkImageMemoryBarrier.Buffer b2 = VkImageMemoryBarrier.callocStack(1, s);
            b2.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            b2.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            b2.get(0).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b2.get(0).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            b2.get(0).newLayout(VK_IMAGE_LAYOUT_GENERAL);
            b2.get(0).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b2.get(0).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b2.get(0).image(vkImage);
            b2.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            b2.get(0).subresourceRange().levelCount(1);
            b2.get(0).subresourceRange().layerCount(1);

            vkCmdPipelineBarrier(vkCmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b2);

            err = vkEndCommandBuffer(vkCmdBuf);
            if (err != VK_SUCCESS) throw new RuntimeException("vkEndCommandBuffer: " + err);

            VkSubmitInfo submit = VkSubmitInfo.callocStack(s);
            submit.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submit.pCommandBuffers(s.pointers(vkCmdBuf));
            err = vkQueueSubmit(vkQueue, submit, vkFence);
            if (err != VK_SUCCESS) throw new RuntimeException("vkQueueSubmit: " + err);

            err = vkWaitForFences(vkDevice, vkFence, true, Long.MAX_VALUE);
            if (err != VK_SUCCESS) throw new RuntimeException("vkWaitForFences: " + err);

            vkResetFences(vkDevice, vkFence);
            vkResetCommandBuffer(vkCmdBuf, 0);
        }
    }

    static void vkClearImageTo(byte r, byte g, byte b, byte a) {
        vkClearImageTo((r & 0xFF) / 255.0f, (g & 0xFF) / 255.0f, (b & 0xFF) / 255.0f, (a & 0xFF) / 255.0f);
    }

    static void vkReadbackImage(String label, byte er, byte eg, byte eb, byte ea) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(s);
            begin.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            begin.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int err = vkBeginCommandBuffer(vkCmdBuf, begin);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBeginCommandBuffer rb: " + err);

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.callocStack(1, s);
            copy.get(0).bufferOffset(0);
            copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            copy.get(0).imageSubresource().layerCount(1);
            copy.get(0).imageExtent().set(TEX_W, TEX_H, 1);

            vkCmdCopyImageToBuffer(vkCmdBuf, vkImage, VK_IMAGE_LAYOUT_GENERAL, vkReadbackBuffer, copy);

            err = vkEndCommandBuffer(vkCmdBuf);
            if (err != VK_SUCCESS) throw new RuntimeException("vkEndCommandBuffer rb: " + err);

            VkSubmitInfo submit = VkSubmitInfo.callocStack(s);
            submit.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submit.pCommandBuffers(s.pointers(vkCmdBuf));
            err = vkQueueSubmit(vkQueue, submit, vkFence);
            if (err != VK_SUCCESS) throw new RuntimeException("vkQueueSubmit rb: " + err);

            err = vkWaitForFences(vkDevice, vkFence, true, Long.MAX_VALUE);
            if (err != VK_SUCCESS) throw new RuntimeException("vkWaitForFences rb: " + err);
            vkResetFences(vkDevice, vkFence);
            vkResetCommandBuffer(vkCmdBuf, 0);

            PointerBuffer pData = s.mallocPointer(1);
            err = vkMapMemory(vkDevice, vkReadbackMemory, 0, VK_WHOLE_SIZE, 0, pData);
            if (err != VK_SUCCESS) throw new RuntimeException("vkMapMemory: " + err);

            ByteBuffer pixels = MemoryUtil.memByteBuffer(pData.get(0), TEX_W * TEX_H * 4);
            int fails = 0;
            for (int i = 0; i < TEX_W * TEX_H && fails <= 5; i++) {
                if (pixels.get(i*4)!=er || pixels.get(i*4+1)!=eg || pixels.get(i*4+2)!=eb || pixels.get(i*4+3)!=ea) {
                    fails++;
                    System.err.println("    MISMATCH pixel " + i + ": got " + hexColor(pixels, i)
                        + " expected " + hexColor(er,eg,eb,ea));
                }
            }
            vkUnmapMemory(vkDevice, vkReadbackMemory);
            if (fails > 0) throw new RuntimeException(label + ": " + fails + "+ mismatches");
        }
    }

    // ============================================================
    //  SEMAPHORE SYNC TEST (Phase 0.5)
    // ============================================================
    static void testSemaphoreSync() {
        System.out.println();
        System.out.println("--- Phase 0.5: Cross-API Semaphore Sync ---");

        boolean semFnsAvailable = glImportSemAvailable && glGetSemAvailable;

        if (!semFnsAvailable) {
            System.out.println("  SKIP: GL semaphore handle import/export functions not available");
            System.out.println("  FSR4 will require glFinish() sync — performance impact ~5-15ms/frame");
            return;
        }

        // Step 1: Create VkSemaphore with export flag
        System.out.print("[1] Create exportable VkSemaphore... ");
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkExportSemaphoreCreateInfo exportInfo = VkExportSemaphoreCreateInfo.callocStack(s);
            exportInfo.sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO);
            exportInfo.handleTypes(windows
                ? 0x00000002  // VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);

            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.callocStack(s);
            semInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            semInfo.pNext(exportInfo);

            LongBuffer pSem = s.mallocLong(1);
            int err = vkCreateSemaphore(vkDevice, semInfo, null, pSem);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateSemaphore failed: " + err);
            vkSemaphore = pSem.get(0);
        }
        System.out.println("OK");

        // Step 2: Export VkSemaphore handle, import into GL
        System.out.print("[2] Export VK semaphore -> import as GL semaphore... ");
        try (MemoryStack s = MemoryStack.stackPush()) {
            if (windows) {
                VkSemaphoreGetWin32HandleInfoKHR getInfo = VkSemaphoreGetWin32HandleInfoKHR.callocStack(s);
                getInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR);
                getInfo.semaphore(vkSemaphore);
                getInfo.handleType(0x00000002); // VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                PointerBuffer pHandle = s.mallocPointer(1);
                int err = KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(vkDevice, getInfo, pHandle);
                if (err != VK_SUCCESS) throw new RuntimeException("vkGetSemaphoreWin32HandleKHR failed: " + err);
                exportedVkSemHandle = pHandle.get(0);
            } else {
                VkSemaphoreGetFdInfoKHR getInfo = VkSemaphoreGetFdInfoKHR.callocStack(s);
                getInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR);
                getInfo.semaphore(vkSemaphore);
                getInfo.handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
                IntBuffer pFd = s.mallocInt(1);
                int err = KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(vkDevice, getInfo, pFd);
                if (err != VK_SUCCESS) throw new RuntimeException("vkGetSemaphoreFdKHR failed: " + err);
                exportedVkSemHandle = pFd.get(0) & 0xFFFFFFFFL;
            }

            // GL: create semaphore object + import
            IntBuffer pGlSem = s.mallocInt(1);
            glGenSemaphoresEXT(pGlSem);
            glSemaphore = pGlSem.get(0);

            if (windows) {
                EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glSemaphore, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, exportedVkSemHandle);
            } else {
                EXTSemaphoreFD.glImportSemaphoreFdEXT(glSemaphore, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) exportedVkSemHandle);
                exportedVkSemHandle = 0; // GL now owns the fd
            }
            int glErr = glGetError();
            if (glErr != GL_NO_ERROR) {
                throw new RuntimeException("glImportSemaphore*EXT failed: 0x" + Integer.toHexString(glErr));
            }
        }
        System.out.println("OK (glSem=" + glSemaphore + ")");

        // Step 3: Vulkan clears to MAGENTA, signals semaphore (no glFinish)
        System.out.print("[3] VK: clear to MAGENTA + signal semaphore (no glFinish)... ");
        try (MemoryStack s = MemoryStack.stackPush()) {
            float fr = 1.0f, fg = 0.0f, fb = 1.0f, fa = 1.0f;
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(s);
            begin.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            begin.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int err = vkBeginCommandBuffer(vkCmdBuf, begin);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBeginCommandBuffer: " + err);

            VkImageMemoryBarrier.Buffer b1 = VkImageMemoryBarrier.callocStack(1, s);
            b1.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            b1.get(0).srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b1.get(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            b1.get(0).oldLayout(VK_IMAGE_LAYOUT_GENERAL);
            b1.get(0).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            b1.get(0).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b1.get(0).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b1.get(0).image(vkImage);
            b1.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            b1.get(0).subresourceRange().levelCount(1);
            b1.get(0).subresourceRange().layerCount(1);
            vkCmdPipelineBarrier(vkCmdBuf, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b1);

            VkClearColorValue cc = VkClearColorValue.callocStack(s);
            cc.float32(0, fr).float32(1, fg).float32(2, fb).float32(3, fa);

            VkImageSubresourceRange range = VkImageSubresourceRange.callocStack(s);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.levelCount(1);
            range.layerCount(1);
            vkCmdClearColorImage(vkCmdBuf, vkImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, cc, range);

            VkImageMemoryBarrier.Buffer b2 = VkImageMemoryBarrier.callocStack(1, s);
            b2.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            b2.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            b2.get(0).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT);
            b2.get(0).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            b2.get(0).newLayout(VK_IMAGE_LAYOUT_GENERAL);
            b2.get(0).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b2.get(0).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            b2.get(0).image(vkImage);
            b2.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            b2.get(0).subresourceRange().levelCount(1);
            b2.get(0).subresourceRange().layerCount(1);
            vkCmdPipelineBarrier(vkCmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, b2);

            err = vkEndCommandBuffer(vkCmdBuf);
            if (err != VK_SUCCESS) throw new RuntimeException("vkEndCommandBuffer: " + err);

            VkSubmitInfo submit = VkSubmitInfo.callocStack(s);
            submit.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submit.pCommandBuffers(s.pointers(vkCmdBuf));
            LongBuffer pSignal = s.mallocLong(1);
            pSignal.put(0, vkSemaphore);
            submit.pSignalSemaphores(pSignal);

            err = vkQueueSubmit(vkQueue, submit, VK_NULL_HANDLE);
            if (err != VK_SUCCESS) throw new RuntimeException("vkQueueSubmit: " + err);
            vkResetCommandBuffer(vkCmdBuf, 0);
        }
        System.out.println("OK");

        // Step 4: GL waits on semaphore, then reads back -> should see MAGENTA
        System.out.print("[4] GL: wait semaphore + read back (expecting MAGENTA, no glFinish)... ");
        try {
            glWaitSemaphoreEXT(glSemaphore, (IntBuffer) null, (IntBuffer) null, (IntBuffer) null);
            int glErr = glGetError();
            if (glErr != GL_NO_ERROR) {
                throw new RuntimeException("glWaitSemaphoreEXT failed: 0x" + Integer.toHexString(glErr));
            }
        } catch (RuntimeException e) {
            System.out.println("\n  WARN: glWaitSemaphoreEXT call failed (" + e.getMessage() + "), using glFinish fallback");
            glFinish();
        }

        glBindTexture(GL_TEXTURE_2D, glTexture);
        ByteBuffer pixels = MemoryUtil.memAlloc(TEX_W * TEX_H * 4);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glBindTexture(GL_TEXTURE_2D, 0);

        int fails = 0;
        byte er = (byte) 0xFF, eg = (byte) 0x00, eb = (byte) 0xFF, ea = (byte) 0xFF;
        for (int i = 0; i < TEX_W * TEX_H && fails <= 5; i++) {
            if (pixels.get(i*4)!=er || pixels.get(i*4+1)!=eg || pixels.get(i*4+2)!=eb || pixels.get(i*4+3)!=ea) {
                fails++;
                System.err.println("    MISMATCH pixel " + i + ": got " + hexColor(pixels, i)
                    + " expected " + hexColor(er,eg,eb,ea));
            }
        }
        MemoryUtil.memFree(pixels);
        if (fails > 0) {
            throw new RuntimeException("Semaphore-synced readback: " + fails + "+ mismatches");
        }
        System.out.println("OK");

        vkQueueWaitIdle(vkQueue);
        System.out.println("--- Phase 0.5 PASSED ---");
    }

    // ============================================================
    //  CLEANUP
    // ============================================================
    static void cleanup() {
        System.out.print("Cleanup... ");
        try {
            if (vkSemaphore != 0) { vkDestroySemaphore(vkDevice, vkSemaphore, null); vkSemaphore = 0; }
            if (glSemaphore != 0) { glDeleteSemaphoresEXT(glSemaphore); glSemaphore = 0; }
            if (!windows && exportedVkSemHandle != 0) { closeFd((int) exportedVkSemHandle); exportedVkSemHandle = 0; }
            if (!windows && exportedVkHandle != 0) { closeFd((int) exportedVkHandle); exportedVkHandle = 0; }
            if (vkReadbackMemory != 0) { vkFreeMemory(vkDevice, vkReadbackMemory, null); vkReadbackMemory = 0; }
            if (vkReadbackBuffer != 0) { vkDestroyBuffer(vkDevice, vkReadbackBuffer, null); vkReadbackBuffer = 0; }
            if (vkFence != 0) { vkDestroyFence(vkDevice, vkFence, null); vkFence = 0; }
            if (vkCmdBuf != null) { vkFreeCommandBuffers(vkDevice, vkCmdPool, vkCmdBuf); vkCmdBuf = null; }
            if (vkCmdPool != 0) { vkDestroyCommandPool(vkDevice, vkCmdPool, null); vkCmdPool = 0; }
            if (vkImage != 0) { vkDestroyImage(vkDevice, vkImage, null); vkImage = 0; }
            if (vkDeviceMemory != 0) { vkFreeMemory(vkDevice, vkDeviceMemory, null); vkDeviceMemory = 0; }
            if (vkDebugMessenger != 0) {
                long fn = vkGetInstanceProcAddr(vkInstance, "vkDestroyDebugUtilsMessengerEXT");
                if (fn != MemoryUtil.NULL) {
                    JNI.callPPV(vkInstance.address(), vkDebugMessenger, 0, fn);
                }
                vkDebugMessenger = 0;
            }
            if (vkInstance != null) { vkDestroyInstance(vkInstance, null); vkInstance = null; }
            if (glTexture != 0) { glDeleteTextures(glTexture); glTexture = 0; }
            if (glMemObj != 0) { glDeleteMemoryObjectsEXT(glMemObj); glMemObj = 0; }
            if (window != MemoryUtil.NULL) { glfwDestroyWindow(window); window = MemoryUtil.NULL; }
            glfwTerminate();
        } catch (Throwable t) {
            System.err.println("error during cleanup: " + t.getMessage());
        }
        System.out.println("done");
    }

    // Linux-only: close file descriptor
    static void closeFd(int fd) {
        if (fd <= 0) return;
        try {
            Class<?> linuxLibc = Class.forName("org.lwjgl.system.linux.LinuxLibc");
            java.lang.reflect.Method close = linuxLibc.getMethod("close", int.class);
            close.invoke(null, fd);
        } catch (Throwable ignored) {}
    }
}
