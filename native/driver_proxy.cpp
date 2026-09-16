// Source-built Vulkan dispatch adapter for the unchanged official game engine.
// It neither patches libmain.so nor hooks dlopen/dlsym process-wide.
#include <jni.h>
#include <android/log.h>
#include <adrenotools/driver.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <limits.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define EXPORT extern "C" __attribute__((visibility("default")))

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance, const char*);
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
EXPORT VKAPI_ATTR void VKAPI_CALL vkDestroyInstance(VkInstance, const VkAllocationCallbacks*);

namespace {
constexpr const char* kTag = "Skate3Driver";
constexpr uint32_t kMaxPhysicalDevices = 16;
std::mutex g_init_mutex;
std::atomic<bool> g_ready{false};
std::atomic<uint32_t> g_game_instances{0};
bool g_attempted = false;
bool g_custom = false;
void* g_loader = nullptr; // Deliberately retained until process exit.
PFN_vkGetInstanceProcAddr g_get = nullptr;
PFN_vkDestroyInstance g_destroy = nullptr;
std::string g_arguments;
std::string g_status = "{\"ok\":false,\"error\":\"Driver bridge not initialized\"}";

std::string Quote(const std::string& value) {
  std::string out = "\"";
  for (unsigned char c : value) {
    switch (c) {
      case '\\': out += "\\\\"; break;
      case '"': out += "\\\""; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          constexpr char hex[] = "0123456789abcdef";
          out += "\\u00";
          out += hex[c >> 4];
          out += hex[c & 15];
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  return out + "\"";
}

std::string JavaString(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* bytes = env->GetStringUTFChars(value, nullptr);
  if (!bytes) return {};
  std::string result(bytes);
  env->ReleaseStringUTFChars(value, bytes);
  return result;
}

std::string CanonicalDirectory(const std::string& value) {
  char resolved[PATH_MAX];
  struct stat st{};
  if (value.empty() || value.front() != '/' || !realpath(value.c_str(), resolved) ||
      stat(resolved, &st) != 0 || !S_ISDIR(st.st_mode)) return {};
  return std::string(resolved) + "/";
}

bool RegularFile(const std::string& path) {
  struct stat st{};
  return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string LoaderPath() {
  Dl_info info{};
  if (g_get && dladdr(reinterpret_cast<void*>(g_get), &info) && info.dli_fname)
    return info.dli_fname;
  return {};
}

std::string Failure(const std::string& why) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", why.c_str());
  return "{\"ok\":false,\"mode\":" + Quote(g_custom ? "turnip" : "system") +
         ",\"error\":" + Quote(why) + ",\"loader_path\":" + Quote(LoaderPath()) + "}";
}

template<typename Fn>
Fn Get(VkInstance instance, const char* name) {
  return reinterpret_cast<Fn>(g_get(instance, name));
}

// Uses only functions from the selected loader, never this proxy's exports.
// A custom selection requires Vulkan 1.2 driver identity support. RP6/T30
// supports this; failing explicitly is safer than guessing from a GPU name.
bool VerifyInstance(VkInstance instance, uint32_t requested_api,
                    std::string& devices_json, std::string& error) {
  const auto enumerate = Get<PFN_vkEnumeratePhysicalDevices>(instance, "vkEnumeratePhysicalDevices");
  const auto properties = Get<PFN_vkGetPhysicalDeviceProperties>(instance, "vkGetPhysicalDeviceProperties");
  const auto properties2 = Get<PFN_vkGetPhysicalDeviceProperties2>(instance, "vkGetPhysicalDeviceProperties2");
  if (!enumerate || !properties) {
    error = "Selected loader is missing physical-device query functions";
    return false;
  }
  if (g_custom && (requested_api < VK_API_VERSION_1_2 || !properties2)) {
    error = "Turnip validation requires a Vulkan 1.2 instance and driver-properties query";
    return false;
  }
  uint32_t count = 0;
  VkResult result = enumerate(instance, &count, nullptr);
  if (result != VK_SUCCESS || count == 0 || count > kMaxPhysicalDevices) {
    error = "Physical-device enumeration failed or returned an unsupported count (result=" +
            std::to_string(result) + ", count=" + std::to_string(count) + ")";
    return false;
  }
  std::vector<VkPhysicalDevice> devices(count);
  result = enumerate(instance, &count, devices.data());
  if (result != VK_SUCCESS || count == 0 || count > devices.size()) {
    error = "Physical-device list changed or could not be read";
    return false;
  }
  devices_json = "[";
  for (uint32_t i = 0; i < count; ++i) {
    VkPhysicalDeviceProperties basic{};
    properties(devices[i], &basic);
    VkPhysicalDeviceDriverProperties driver{};
    driver.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
    if (requested_api >= VK_API_VERSION_1_2 && basic.apiVersion >= VK_API_VERSION_1_2 && properties2) {
      VkPhysicalDeviceProperties2 extended{};
      extended.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
      extended.pNext = &driver;
      properties2(devices[i], &extended);
    }
    if (g_custom && driver.driverID != VK_DRIVER_ID_MESA_TURNIP) {
      error = "Turnip was selected but the loaded physical device does not report Mesa Turnip (driverID=" +
              std::to_string(driver.driverID) + ", GPU=" + std::string(basic.deviceName) + ")";
      return false;
    }
    if (i) devices_json += ",";
    devices_json += "{\"name\":" + Quote(basic.deviceName) +
                    ",\"api_version\":" + std::to_string(basic.apiVersion) +
                    ",\"driver_version\":" + std::to_string(basic.driverVersion) +
                    ",\"vendor_id\":" + std::to_string(basic.vendorID) +
                    ",\"device_id\":" + std::to_string(basic.deviceID) +
                    ",\"driver_id\":" + std::to_string(driver.driverID) +
                    ",\"driver_name\":" + Quote(driver.driverName) +
                    ",\"driver_info\":" + Quote(driver.driverInfo) + "}";
  }
  devices_json += "]";
  return true;
}

bool Probe(std::string& devices, std::string& error) {
  const auto create = Get<PFN_vkCreateInstance>(VK_NULL_HANDLE, "vkCreateInstance");
  const auto version = Get<PFN_vkEnumerateInstanceVersion>(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
  uint32_t api = VK_API_VERSION_1_0;
  if (!create || (version && version(&api) != VK_SUCCESS)) {
    error = "Selected Vulkan loader could not provide instance creation/version";
    return false;
  }
  api = std::min(api, static_cast<uint32_t>(VK_API_VERSION_1_2));
  if (g_custom && api < VK_API_VERSION_1_2) {
    error = "Selected custom loader does not support Vulkan 1.2 identity validation";
    return false;
  }
  VkApplicationInfo app{};
  app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  app.pApplicationName = "Skate3 Driver Validation";
  app.apiVersion = api;
  VkInstanceCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  info.pApplicationInfo = &app;
  VkInstance instance = VK_NULL_HANDLE;
  const VkResult result = create(&info, nullptr, &instance);
  if (result != VK_SUCCESS || instance == VK_NULL_HANDLE) {
    error = "Selected Vulkan driver failed the startup probe (VkResult=" + std::to_string(result) + ")";
    return false;
  }
  const bool valid = VerifyInstance(instance, api, devices, error);
  g_destroy(instance, nullptr);
  return valid;
}
} // namespace

EXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

EXPORT jstring JNICALL Java_com_nakas_skate3_DriverBridge_nativeInit(
    JNIEnv* env, jclass, jstring native_dir_value, jstring driver_dir_value,
    jstring filename_value, jboolean custom_value) {
  std::lock_guard<std::mutex> lock(g_init_mutex);
  const std::string native_dir_arg = JavaString(env, native_dir_value);
  const std::string driver_dir_arg = JavaString(env, driver_dir_value);
  const std::string filename = JavaString(env, filename_value);
  if (env->ExceptionCheck()) return nullptr;
  const bool custom = custom_value == JNI_TRUE;
  const std::string arguments = Quote(native_dir_arg) + Quote(driver_dir_arg) + Quote(filename) + (custom ? "1" : "0");
  if (g_attempted) {
    const std::string response = arguments == g_arguments ? g_status :
        "{\"ok\":false,\"error\":\"Restart the application process before changing drivers\"}";
    return env->NewStringUTF(response.c_str());
  }
  g_attempted = true;
  g_arguments = arguments;
  g_custom = custom;
  const std::string native_dir = CanonicalDirectory(native_dir_arg);
  std::string error;
  if (native_dir.empty()) {
    error = "Native library directory is missing or not an absolute directory";
  } else {
    // Test the exact bare-name lookup that the unchanged engine will make.
    // Absolute preloading is insufficient if another libvulkan was inserted
    // earlier in this same application namespace. Do not guess in that case.
    void* selected = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    const auto selected_get = selected ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(selected, "vkGetInstanceProcAddr")) : nullptr;
    if (selected_get != &vkGetInstanceProcAddr) {
      error = "The application Vulkan lookup does not resolve to the driver proxy; restart before loading the game";
    }
    if (selected) dlclose(selected);
  }
  if (error.empty() && custom) {
    const std::string driver_dir = CanonicalDirectory(driver_dir_arg);
    char file_path[PATH_MAX];
    if (driver_dir.empty() || driver_dir.rfind("/data/", 0) != 0) {
      error = "Custom Vulkan driver must be in app-private /data storage";
    } else if (filename.empty() || filename.find('/') != std::string::npos ||
               filename == "." || filename == "..") {
      error = "Custom Vulkan driver filename is invalid";
    } else if (!realpath((driver_dir + filename).c_str(), file_path) ||
               std::string(file_path).rfind(driver_dir, 0) != 0 || !RegularFile(file_path)) {
      error = "Custom Vulkan driver file is missing or escapes its private directory";
    } else if (!RegularFile(native_dir + "libhook_impl.so") || !RegularFile(native_dir + "libmain_hook.so")) {
      error = "Custom driver support libraries are missing; native libraries must be extracted";
    } else {
      g_loader = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
          driver_dir.c_str(), native_dir.c_str(), driver_dir.c_str(), filename.c_str(), nullptr, nullptr);
      if (!g_loader) error = "libadrenotools could not initialize the selected custom driver";
    }
  } else if (error.empty()) {
    // An absolute system path cannot resolve back to this app's proxy by SONAME.
    g_loader = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_loader) {
      const char* detail = dlerror();
      error = std::string("System Vulkan loader could not open: ") + (detail ? detail : "unknown error");
    }
  }
  std::string devices;
  if (error.empty()) {
    g_get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(g_loader, "vkGetInstanceProcAddr"));
    g_destroy = reinterpret_cast<PFN_vkDestroyInstance>(dlsym(g_loader, "vkDestroyInstance"));
    if (!g_get || !g_destroy || g_get == &vkGetInstanceProcAddr || g_destroy == &vkDestroyInstance) {
      error = "Vulkan loader entrypoints are missing or resolve recursively to the proxy";
    } else if (!Probe(devices, error)) {
      // Keep the exact failure; custom selections never fall back to System.
    }
  }
  if (!error.empty()) {
    g_status = Failure(error);
  } else {
    g_status = "{\"ok\":true,\"mode\":" + Quote(custom ? "turnip" : "system") +
        ",\"error\":\"\",\"loader_path\":" + Quote(LoaderPath()) +
        ",\"devices\":" + devices + "}";
    g_ready.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Driver initialized: %s", g_status.c_str());
  }
  return env->NewStringUTF(g_status.c_str());
}

EXPORT jstring JNICALL Java_com_nakas_skate3_DriverBridge_nativeStatus(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_init_mutex);
  return env->NewStringUTF(g_status.c_str());
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(
    const VkInstanceCreateInfo* info, const VkAllocationCallbacks* allocator, VkInstance* instance) {
  if (!g_ready.load(std::memory_order_acquire)) return VK_ERROR_INITIALIZATION_FAILED;
  const auto create = Get<PFN_vkCreateInstance>(VK_NULL_HANDLE, "vkCreateInstance");
  const VkResult result = create(info, allocator, instance);
  if (result != VK_SUCCESS) return result;
  const uint32_t api = info && info->pApplicationInfo && info->pApplicationInfo->apiVersion ?
      info->pApplicationInfo->apiVersion : VK_API_VERSION_1_0;
  std::string devices, error;
  if (!VerifyInstance(*instance, api, devices, error)) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "Game Vulkan instance rejected: %s", error.c_str());
    g_destroy(*instance, allocator);
    *instance = VK_NULL_HANDLE;
    return VK_ERROR_INITIALIZATION_FAILED;
  }
  const uint32_t count = g_game_instances.fetch_add(1, std::memory_order_relaxed) + 1;
  if (count <= 8) __android_log_print(ANDROID_LOG_INFO, kTag,
      "Game Vulkan instance %u verified: mode=%s loader=%s devices=%s", count,
      g_custom ? "turnip" : "system", LoaderPath().c_str(), devices.c_str());
  return VK_SUCCESS;
}

EXPORT VKAPI_ATTR void VKAPI_CALL vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks* allocator) {
  if (g_ready.load(std::memory_order_acquire)) g_destroy(instance, allocator);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name) {
  if (!g_ready.load(std::memory_order_acquire) || !name) return nullptr;
  const auto real = g_get(instance, name);
  if (!real) return nullptr;
  if (std::strcmp(name, "vkGetInstanceProcAddr") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(&vkGetInstanceProcAddr);
  if (std::strcmp(name, "vkCreateInstance") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(&vkCreateInstance);
  if (std::strcmp(name, "vkDestroyInstance") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(&vkDestroyInstance);
  return real;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name) {
  if (!g_ready.load(std::memory_order_acquire)) return nullptr;
  const auto fn = reinterpret_cast<PFN_vkGetDeviceProcAddr>(dlsym(g_loader, "vkGetDeviceProcAddr"));
  return fn ? fn(device, name) : nullptr;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceVersion(uint32_t* version) {
  if (!g_ready.load(std::memory_order_acquire)) return VK_ERROR_INITIALIZATION_FAILED;
  const auto fn = Get<PFN_vkEnumerateInstanceVersion>(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
  if (fn) return fn(version);
  if (version) *version = VK_API_VERSION_1_0;
  return VK_SUCCESS;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(
    const char* layer, uint32_t* count, VkExtensionProperties* properties) {
  if (!g_ready.load(std::memory_order_acquire)) return VK_ERROR_INITIALIZATION_FAILED;
  const auto fn = Get<PFN_vkEnumerateInstanceExtensionProperties>(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");
  return fn ? fn(layer, count, properties) : VK_ERROR_INITIALIZATION_FAILED;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(uint32_t* count, VkLayerProperties* properties) {
  if (!g_ready.load(std::memory_order_acquire)) return VK_ERROR_INITIALIZATION_FAILED;
  const auto fn = Get<PFN_vkEnumerateInstanceLayerProperties>(VK_NULL_HANDLE, "vkEnumerateInstanceLayerProperties");
  return fn ? fn(count, properties) : VK_ERROR_INITIALIZATION_FAILED;
}
