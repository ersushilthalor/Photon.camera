cmake_minimum_required(VERSION 3.22.1)
project("native-tool")

# Camera, photo-processing, and video native modules.
set(
    MGC_EIS_RECONSTRUCTION_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/mgc_eis_reconstruction"
)

set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -ffast-math")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -ffast-math")

include_directories(${MGC_EIS_RECONSTRUCTION_DIR})

find_package(OpenMP REQUIRED)
find_package(Threads REQUIRED)

add_library(my-native-lib SHARED
    native_runtime.cpp
    gles_gpu_timer_query_jni.cpp
    gles_hardware_buffer_jni.cpp
    lut_processor.cpp
    direct_buffer_allocator_jni.cpp
    yuv_processor_jni.cpp
    raw_capture_jni.cpp
    mgc_eis_reconstruction_jni.cpp
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_eis_reconstruction.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_engine.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_gyro.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_lookahead.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_projection.cpp"
)

# The reconstructed MGC type-18 state machine relies on ordered quaternion,
# crop-feasibility and homography arithmetic. Keep it independent of fast-math.
set_source_files_properties(
    mgc_eis_reconstruction_jni.cpp
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_eis_reconstruction.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_engine.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_gyro.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_lookahead.cpp"
    "${MGC_EIS_RECONSTRUCTION_DIR}/mgc_type18_projection.cpp"
    PROPERTIES
    COMPILE_OPTIONS "-fno-fast-math;-ffp-contract=off"
)

target_compile_options(my-native-lib PRIVATE -O3 -ffast-math -w ${OpenMP_CXX_FLAGS})

target_link_libraries(
    my-native-lib
    OpenMP::OpenMP_CXX
    log
    jnigraphics
    android
    EGL
    GLESv3
    z
)
