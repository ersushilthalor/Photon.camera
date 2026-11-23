#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <unordered_set>

namespace {

std::mutex g_alloc_mutex;
std::unordered_set<void*> g_native_allocations;

void free_tracked_buffer(JNIEnv* env, jobject buffer) {
    if (env == nullptr || buffer == nullptr) {
        return;
    }
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (ptr == nullptr) {
        return;
    }
    bool owned = false;
    {
        std::lock_guard<std::mutex> lock(g_alloc_mutex);
        auto it = g_native_allocations.find(ptr);
        if (it != g_native_allocations.end()) {
            g_native_allocations.erase(it);
            owned = true;
        }
    }
    if (owned) {
        std::free(ptr);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferAllocator_allocateNative(
    JNIEnv* env,
    jobject /* thiz */,
    jlong capacity
) {
    if (env == nullptr || capacity <= 0 ||
        capacity > static_cast<jlong>(std::numeric_limits<jint>::max())) {
        return nullptr;
    }
    const size_t size = static_cast<size_t>(capacity);
    void* ptr = nullptr;
    if (posix_memalign(&ptr, 64, size) != 0 || ptr == nullptr) {
        ptr = std::calloc(1, size);
        if (ptr == nullptr) {
            return nullptr;
        }
    } else {
        std::memset(ptr, 0, size);
    }

    jobject direct_buffer = env->NewDirectByteBuffer(ptr, capacity);
    if (direct_buffer == nullptr || env->ExceptionCheck()) {
        std::free(ptr);
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> lock(g_alloc_mutex);
        g_native_allocations.insert(ptr);
    }
    return direct_buffer;
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferAllocator_freeNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject buffer
) {
    free_tracked_buffer(env, buffer);
}

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_raw_DngRawData_freeNativeBuffer(
    JNIEnv* env,
    jobject /* thiz */,
    jobject buffer
) {
    free_tracked_buffer(env, buffer);
}

}  // extern "C"
