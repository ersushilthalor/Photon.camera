#include <jni.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

#if defined(_OPENMP)
#include <omp.h>
#endif

namespace {

constexpr const char* kTag = "RawCaptureJni";

inline float half_to_float(uint16_t h) {
    const uint32_t sign = static_cast<uint32_t>(h & 0x8000u) << 16;
    const uint32_t exp = (h >> 10) & 0x1Fu;
    const uint32_t mant = h & 0x03FFu;
    uint32_t f_bits;
    if (exp == 0) {
        if (mant == 0) {
            f_bits = sign;
        } else {
            uint32_t m = mant;
            int e = -14;
            while ((m & 0x0400u) == 0) {
                m <<= 1;
                --e;
            }
            m &= 0x03FFu;
            f_bits = sign | (static_cast<uint32_t>(e + 127) << 23) | (m << 13);
        }
    } else if (exp == 31) {
        f_bits = sign | 0x7F800000u | (mant << 13);
    } else {
        f_bits = sign | ((exp + 112u) << 23) | (mant << 13);
    }
    float out;
    std::memcpy(&out, &f_bits, sizeof(out));
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_Raw10Unpacker_validateHardwareBufferNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject buffer,
    jint width,
    jint height
) {
    if (!env || !buffer || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb) {
        return JNI_FALSE;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    const uint32_t min_bytes = static_cast<uint32_t>(width) * 5u / 4u;
    if (desc.width < min_bytes && desc.width < static_cast<uint32_t>(width)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "Unexpected RAW10 HardwareBuffer width=%u height=%u for %dx%d",
            desc.width, desc.height, width, height
        );
        return JNI_FALSE;
    }
    if (desc.height < static_cast<uint32_t>(height) || desc.stride == 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_Raw10Unpacker_unpackNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject source,
    jint sourceLimit,
    jobject destination,
    jint width,
    jint height,
    jint rowStride
) {
    if (!env || !source || !destination || width <= 0 || (width % 4) != 0 || height <= 0) {
        return JNI_FALSE;
    }
    const auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(destination));
    const jlong src_cap = env->GetDirectBufferCapacity(source);
    const jlong dst_cap = env->GetDirectBufferCapacity(destination);
    if (!src || !dst) {
        return JNI_FALSE;
    }

    const int packed_row_bytes = (width / 4) * 5;
    const jlong required_src = static_cast<jlong>(height - 1) * rowStride + packed_row_bytes;
    const jlong required_dst = static_cast<jlong>(width) * height * 2;
    if (rowStride < packed_row_bytes || sourceLimit < required_src ||
        src_cap < required_src || dst_cap < required_dst) {
        return JNI_FALSE;
    }

    const int groups_per_row = width / 4;
#if defined(_OPENMP)
#pragma omp parallel for schedule(static)
#endif
    for (int y = 0; y < height; ++y) {
        const uint8_t* src_row = src + static_cast<size_t>(y) * rowStride;
        uint16_t* dst_row = dst + static_cast<size_t>(y) * width;
        for (int g = 0; g < groups_per_row; ++g) {
            const uint8_t* p = src_row + g * 5;
            const uint8_t lsb = p[4];
            dst_row[g * 4 + 0] = static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 2) | (lsb & 0x03u));
            dst_row[g * 4 + 1] = static_cast<uint16_t>((static_cast<uint16_t>(p[1]) << 2) | ((lsb >> 2) & 0x03u));
            dst_row[g * 4 + 2] = static_cast<uint16_t>((static_cast<uint16_t>(p[2]) << 2) | ((lsb >> 4) & 0x03u));
            dst_row[g * 4 + 3] = static_cast<uint16_t>((static_cast<uint16_t>(p[3]) << 2) | ((lsb >> 6) & 0x03u));
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_raw_RawFloatTextureTransfer_nativeUpload(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pbo,
    jint texture,
    jint width,
    jint height
) {
    if (pbo <= 0 || texture <= 0 || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture));
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, static_cast<GLuint>(pbo));
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_FLOAT, nullptr);
    const GLenum err = glGetError();
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    return err == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferPixelPacker_unpackRgba16TileToRgb16(
    JNIEnv* env,
    jobject /* thiz */,
    jobject source,
    jint sourceWidth,
    jint sourceHeight,
    jobject destination,
    jint destinationWidth,
    jint destinationHeight,
    jint destinationLeft,
    jint destinationTop
) {
    if (!env || !source || !destination || sourceWidth <= 0 || sourceHeight <= 0 ||
        destinationWidth <= 0 || destinationHeight <= 0 ||
        destinationLeft < 0 || destinationTop < 0 ||
        destinationLeft + sourceWidth > destinationWidth ||
        destinationTop + sourceHeight > destinationHeight) {
        return JNI_FALSE;
    }
    const auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(source));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(destination));
    const jlong src_cap = env->GetDirectBufferCapacity(source);
    const jlong dst_cap = env->GetDirectBufferCapacity(destination);
    if (!src || !dst ||
        src_cap < static_cast<jlong>(sourceWidth) * sourceHeight * 8 ||
        dst_cap < static_cast<jlong>(destinationWidth) * destinationHeight * 6) {
        return JNI_FALSE;
    }

#if defined(_OPENMP)
#pragma omp parallel for schedule(static)
#endif
    for (int y = 0; y < sourceHeight; ++y) {
        const uint16_t* s = src + static_cast<size_t>(y) * sourceWidth * 4;
        uint16_t* d = dst + (static_cast<size_t>(destinationTop + y) * destinationWidth + destinationLeft) * 3;
        for (int x = 0; x < sourceWidth; ++x) {
            d[x * 3 + 0] = s[x * 4 + 0];
            d[x * 3 + 1] = s[x * 4 + 1];
            d[x * 3 + 2] = s[x * 4 + 2];
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_utils_DirectBufferPixelPacker_packRgba16fToRgb16(
    JNIEnv* env,
    jobject /* thiz */,
    jobject source,
    jint width,
    jint height,
    jobject destination
) {
    if (!env || !source || !destination || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    const auto* src = static_cast<const uint16_t*>(env->GetDirectBufferAddress(source));
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(destination));
    const jlong src_cap = env->GetDirectBufferCapacity(source);
    const jlong dst_cap = env->GetDirectBufferCapacity(destination);
    if (!src || !dst ||
        src_cap < static_cast<jlong>(width) * height * 8 ||
        dst_cap < static_cast<jlong>(width) * height * 6) {
        return JNI_FALSE;
    }

    const size_t total = static_cast<size_t>(width) * static_cast<size_t>(height);
#if defined(_OPENMP)
#pragma omp parallel for schedule(static)
#endif
    for (size_t i = 0; i < total; ++i) {
        for (int c = 0; c < 3; ++c) {
            float v = half_to_float(src[i * 4 + c]);
            if (!std::isfinite(v)) v = 0.0f;
            v = std::clamp(v, 0.0f, 1.0f);
            dst[i * 3 + c] = static_cast<uint16_t>(std::lround(v * 65535.0f));
        }
    }
    return JNI_TRUE;
}

}  // extern "C"
