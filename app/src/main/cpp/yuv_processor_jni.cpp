#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>

#if defined(_OPENMP)
#include <omp.h>
#endif

namespace {

constexpr const char* kTag = "YuvProcessorJni";
constexpr int kFormatNv21 = 17;
constexpr int kFormatYuv420888 = 35;
constexpr int kFormatYcbcrP010 = 54;

inline uint8_t clamp_u8(int value) {
    return static_cast<uint8_t>(value < 0 ? 0 : (value > 255 ? 255 : value));
}

inline uint32_t yuv_to_abgr(int y, int u, int v) {
    const int c = y;
    const int d = u - 128;
    const int e = v - 128;
    // BT.601 full-range JPEG conversion
    const int r = c + ((1436 * e) >> 10);
    const int g = c - ((352 * d + 731 * e) >> 10);
    const int b = c + ((1815 * d) >> 10);
    return (0xFFu << 24) |
        (static_cast<uint32_t>(clamp_u8(b)) << 16) |
        (static_cast<uint32_t>(clamp_u8(g)) << 8) |
        static_cast<uint32_t>(clamp_u8(r));
}

int normalize_rotation(int rotation) {
    int r = rotation % 360;
    if (r < 0) r += 360;
    if (r != 0 && r != 90 && r != 180 && r != 270) {
        return 0;
    }
    return r;
}

bool convert_yuv_crop_rotate_rgba(
    const uint8_t* y_ptr,
    jlong y_cap,
    const uint8_t* u_ptr,
    jlong u_cap,
    const uint8_t* v_ptr,
    jlong v_cap,
    int width,
    int height,
    int y_row_stride,
    int uv_row_stride,
    int uv_pixel_stride,
    int rotation,
    int format,
    uint8_t* dst_pixels,
    int dst_width,
    int dst_height,
    uint32_t dst_stride_bytes
) {
    if (!y_ptr || !u_ptr || !v_ptr || !dst_pixels ||
        width <= 0 || height <= 0 || dst_width <= 0 || dst_height <= 0 ||
        y_row_stride <= 0 || uv_row_stride <= 0 || uv_pixel_stride <= 0) {
        return false;
    }

    const int norm_rot = normalize_rotation(rotation);
    const bool swapped = (norm_rot == 90 || norm_rot == 270);
    const int crop_w = std::min(width, swapped ? dst_height : dst_width);
    const int crop_h = std::min(height, swapped ? dst_width : dst_height);
    const int crop_x = std::max(0, (width - crop_w) / 2);
    const int crop_y = std::max(0, (height - crop_h) / 2);
    const bool is_p010 = (format == kFormatYcbcrP010);
    const int y_pixel_bytes = is_p010 ? 2 : 1;

    // Validate minimum capacities for worst-case coordinates before parallel loop
    const int max_sx = width - 1;
    const int max_sy = height - 1;
    const jlong max_y_off = static_cast<jlong>(max_sy) * y_row_stride +
        static_cast<jlong>(max_sx) * y_pixel_bytes + (is_p010 ? 1 : 0);
    const jlong max_uv_off = static_cast<jlong>(max_sy / 2) * uv_row_stride +
        static_cast<jlong>(max_sx / 2) * uv_pixel_stride + (is_p010 ? 1 : 0);
    if (max_y_off >= y_cap || max_uv_off >= u_cap || max_uv_off >= v_cap) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Buffer capacity too small: size=%dx%d fmt=%d caps=(%lld,%lld,%lld) req=(%lld,%lld)",
            width, height, format,
            static_cast<long long>(y_cap),
            static_cast<long long>(u_cap),
            static_cast<long long>(v_cap),
            static_cast<long long>(max_y_off + 1),
            static_cast<long long>(max_uv_off + 1)
        );
        return false;
    }

    const int render_w = std::min(dst_width, swapped ? crop_h : crop_w);
    const int render_h = std::min(dst_height, swapped ? crop_w : crop_h);

#if defined(_OPENMP)
#pragma omp parallel for schedule(static)
#endif
    for (int oy = 0; oy < render_h; ++oy) {
        uint32_t* row_out = reinterpret_cast<uint32_t*>(dst_pixels + static_cast<size_t>(oy) * dst_stride_bytes);
        for (int ox = 0; ox < render_w; ++ox) {
            int sx;
            int sy;
            switch (norm_rot) {
                case 90:
                    sx = crop_x + oy;
                    sy = crop_y + (crop_h - 1 - ox);
                    break;
                case 180:
                    sx = crop_x + (crop_w - 1 - ox);
                    sy = crop_y + (crop_h - 1 - oy);
                    break;
                case 270:
                    sx = crop_x + (crop_w - 1 - oy);
                    sy = crop_y + ox;
                    break;
                default:
                    sx = crop_x + ox;
                    sy = crop_y + oy;
                    break;
            }
            sx = std::clamp(sx, 0, max_sx);
            sy = std::clamp(sy, 0, max_sy);

            const jlong uv_offset = static_cast<jlong>(sy >> 1) * uv_row_stride +
                static_cast<jlong>(sx >> 1) * uv_pixel_stride;

            int y_val;
            int u_val;
            int v_val;
            if (is_p010) {
                const jlong y_offset = static_cast<jlong>(sy) * y_row_stride + static_cast<jlong>(sx) * 2;
                const uint16_t y16 = static_cast<uint16_t>(y_ptr[y_offset]) |
                    (static_cast<uint16_t>(y_ptr[y_offset + 1]) << 8);
                const uint16_t u16 = static_cast<uint16_t>(u_ptr[uv_offset]) |
                    (static_cast<uint16_t>(u_ptr[uv_offset + 1]) << 8);
                const uint16_t v16 = static_cast<uint16_t>(v_ptr[uv_offset]) |
                    (static_cast<uint16_t>(v_ptr[uv_offset + 1]) << 8);
                y_val = y16 >> 8;
                u_val = u16 >> 8;
                v_val = v16 >> 8;
            } else {
                const jlong y_offset = static_cast<jlong>(sy) * y_row_stride + sx;
                y_val = y_ptr[y_offset];
                u_val = u_ptr[uv_offset];
                v_val = v_ptr[uv_offset];
            }
            row_out[ox] = yuv_to_abgr(y_val, u_val, v_val);
        }
        // Clear any trailing margin pixels if render_w < dst_width
        for (int ox = render_w; ox < dst_width; ++ox) {
            row_out[ox] = 0xFF000000u;
        }
    }
    if (render_h < dst_height) {
        for (int oy = render_h; oy < dst_height; ++oy) {
            std::memset(dst_pixels + static_cast<size_t>(oy) * dst_stride_bytes, 0,
                        static_cast<size_t>(dst_width) * 4u);
        }
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_hinnka_mycamera_utils_YuvProcessor_processToBitmap(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint rotation,
    jint /* targetWR */,
    jint /* targetHR */,
    jint format,
    jobject previewBitmap
) {
    if (!env || !yBuffer || !uBuffer || !vBuffer || !previewBitmap) {
        return;
    }

    const auto* y_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    const auto* u_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    const auto* v_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    const jlong y_cap = env->GetDirectBufferCapacity(yBuffer);
    const jlong u_cap = env->GetDirectBufferCapacity(uBuffer);
    const jlong v_cap = env->GetDirectBufferCapacity(vBuffer);

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, previewBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "AndroidBitmap_getInfo failed");
        return;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Unsupported bitmap format: %d", info.format);
        return;
    }

    void* dst_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, previewBitmap, &dst_pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        dst_pixels == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "AndroidBitmap_lockPixels failed");
        return;
    }

    const bool ok = convert_yuv_crop_rotate_rgba(
        y_ptr, y_cap,
        u_ptr, u_cap,
        v_ptr, v_cap,
        width, height,
        yRowStride, uvRowStride, uvPixelStride,
        rotation,
        format == 0 ? kFormatYuv420888 : (format == kFormatNv21 ? kFormatNv21 : format),
        static_cast<uint8_t*>(dst_pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        info.stride
    );

    AndroidBitmap_unlockPixels(env, previewBitmap);
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "YUV to Bitmap conversion failed");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hinnka_mycamera_utils_YuvProcessor_processToFile(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint rotation,
    jint format,
    jstring outputPath
) {
    if (!env || !yBuffer || !uBuffer || !vBuffer || !outputPath || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }

    const auto* y_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    const auto* u_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    const auto* v_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    const jlong y_cap = env->GetDirectBufferCapacity(yBuffer);
    const jlong u_cap = env->GetDirectBufferCapacity(uBuffer);
    const jlong v_cap = env->GetDirectBufferCapacity(vBuffer);

    const int norm_rot = normalize_rotation(rotation);
    const bool swapped = (norm_rot == 90 || norm_rot == 270);
    const int out_w = swapped ? height : width;
    const int out_h = swapped ? width : height;

    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    jclass compress_cls = env->FindClass("android/graphics/Bitmap$CompressFormat");
    jclass fos_cls = env->FindClass("java/io/FileOutputStream");
    if (!bitmap_cls || !config_cls || !compress_cls || !fos_cls) {
        return JNI_FALSE;
    }

    jfieldID argb8888_field = env->GetStaticFieldID(config_cls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jfieldID jpeg_field = env->GetStaticFieldID(compress_cls, "JPEG", "Landroid/graphics/Bitmap$CompressFormat;");
    jmethodID create_bitmap_mid = env->GetStaticMethodID(
        bitmap_cls,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    jmethodID compress_mid = env->GetMethodID(
        bitmap_cls,
        "compress",
        "(Landroid/graphics/Bitmap$CompressFormat;ILjava/io/OutputStream;)Z"
    );
    jmethodID recycle_mid = env->GetMethodID(bitmap_cls, "recycle", "()V");
    jmethodID fos_ctor = env->GetMethodID(fos_cls, "<init>", "(Ljava/lang/String;)V");
    jmethodID fos_close = env->GetMethodID(fos_cls, "close", "()V");
    if (!argb8888_field || !jpeg_field || !create_bitmap_mid || !compress_mid || !recycle_mid || !fos_ctor || !fos_close) {
        return JNI_FALSE;
    }

    jobject argb8888 = env->GetStaticObjectField(config_cls, argb8888_field);
    jobject jpeg_fmt = env->GetStaticObjectField(compress_cls, jpeg_field);
    jobject bitmap = env->CallStaticObjectMethod(bitmap_cls, create_bitmap_mid, out_w, out_h, argb8888);
    if (env->ExceptionCheck() || bitmap == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    AndroidBitmapInfo info{};
    void* dst_pixels = nullptr;
    bool converted = false;
    if (AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
        AndroidBitmap_lockPixels(env, bitmap, &dst_pixels) == ANDROID_BITMAP_RESULT_SUCCESS &&
        dst_pixels != nullptr) {
        converted = convert_yuv_crop_rotate_rgba(
            y_ptr, y_cap,
            u_ptr, u_cap,
            v_ptr, v_cap,
            width, height,
            yRowStride, uvRowStride, uvPixelStride,
            norm_rot,
            format,
            static_cast<uint8_t*>(dst_pixels),
            out_w,
            out_h,
            info.stride
        );
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    if (!converted) {
        env->CallVoidMethod(bitmap, recycle_mid);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    jobject fos = env->NewObject(fos_cls, fos_ctor, outputPath);
    if (env->ExceptionCheck() || fos == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->CallVoidMethod(bitmap, recycle_mid);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    const jboolean compressed = env->CallBooleanMethod(bitmap, compress_mid, jpeg_fmt, 95, fos);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->CallVoidMethod(fos, fos_close);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->CallVoidMethod(bitmap, recycle_mid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return compressed;
}

}  // extern "C"
