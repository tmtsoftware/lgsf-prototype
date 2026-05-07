/**
 * JNI bridge between the Scala PacCamera class and the Imperx C1911 camera SDK.
 *
 * Build with CMakeLists.txt in this directory.  The resulting shared library
 * must be on java.library.path when the HCD starts, e.g.:
 *   -Djava.library.path=/path/to/libpac-camera-jni.so:/opt/IpxCameraSDK-1.5.0.83/lib/Linux64_x64
 *
 * Frame wire format returned to the JVM:
 *   Bytes  0- 3  width  (int32 LE)
 *   Bytes  4- 7  height (int32 LE)
 *   Bytes  8-15  timestamp (int64 LE, nanoseconds)
 *   Bytes 16+    pixel data (8-bit mono, row-major)
 */

#include <jni.h>
#include "IpxCameraApi.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Global camera state
// All access is serialised through g_mutex; the HCD actor model means calls
// arrive sequentially, but a mutex makes it safe if that ever changes.
// ---------------------------------------------------------------------------
static std::mutex            g_mutex;
static IpxCam::System*       g_system   = nullptr;
static IpxCam::Device*       g_device   = nullptr;
static IpxCam::Stream*       g_stream   = nullptr;
static std::vector<IpxCam::Buffer*> g_buffers;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Find a GEV device whose IP address matches ipAddress. */
static IpxCam::DeviceInfo* findDeviceByIp(IpxCam::System* system, const std::string& ipAddress) {
    for (size_t i = 0; i < system->GetInterfaceCount(); ++i) {
        auto* iface = system->GetInterfaceByIndex(i);
        if (!iface) continue;
        iface->ReEnumerateDevices(500);

        auto* devList = iface->GetDeviceInfoList();
        if (!devList) continue;

        for (size_t d = 0; d < devList->GetCount(); ++d) {
            auto* info = devList->GetDeviceInfo(d);
            if (!info) continue;
            IpxCamErr err = IPX_CAM_ERR_OK;
            const char* ip = info->GetIPAddress(&err);
            if (ip && err == IPX_CAM_ERR_OK && ipAddress == ip)
                return info;
        }
    }
    return nullptr;
}

/** Allocate stream buffers. Caller holds g_mutex. */
static bool allocateBuffers() {
    size_t bufSize    = g_stream->GetBufferSize();
    size_t minBuffers = g_stream->GetMinNumBuffers();
    for (size_t i = 0; i < minBuffers; ++i) {
        auto* buf = g_stream->CreateBuffer(bufSize, nullptr, nullptr);
        if (!buf) return false;
        g_buffers.push_back(buf);
    }
    return true;
}

/** Revoke all stream buffers. Caller holds g_mutex. */
static void revokeBuffers() {
    for (auto* buf : g_buffers)
        g_stream->RevokeBuffer(buf);
    g_buffers.clear();
}

/**
 * Pack a Buffer into a JVM byte array using the wire format:
 *   [width:4][height:4][timestamp:8][pixelData]
 */
static jbyteArray packBuffer(JNIEnv* env, IpxCam::Buffer* buf) {
    if (!buf || buf->IsIncomplete()) return nullptr;

    int32_t  width     = static_cast<int32_t>(buf->GetWidth());
    int32_t  height    = static_cast<int32_t>(buf->GetHeight());
    int64_t  timestamp = static_cast<int64_t>(buf->GetTimestamp());
    size_t   dataSize  = static_cast<size_t>(width) * height; // 8-bit mono

    jsize totalSize = static_cast<jsize>(16 + dataSize);
    jbyteArray result = env->NewByteArray(totalSize);
    if (!result) return nullptr;

    // Header (little-endian on x86_64 — same as JVM on this platform)
    jbyte header[16];
    std::memcpy(header + 0, &width,     4);
    std::memcpy(header + 4, &height,    4);
    std::memcpy(header + 8, &timestamp, 8);
    env->SetByteArrayRegion(result, 0, 16, header);

    // Pixel data
    const auto* src = static_cast<const char*>(buf->GetBufferPtr()) + buf->GetImageOffset();
    env->SetByteArrayRegion(result, 16, static_cast<jsize>(dataSize),
                            reinterpret_cast<const jbyte*>(src));
    return result;
}

// ---------------------------------------------------------------------------
// JNI implementations — class lgsf.pacprototypehcd.PacCamera
// ---------------------------------------------------------------------------

extern "C" {

/**
 * Connect to the camera at the given IP address.
 * Returns 0 on success, non-zero on failure.
 */
JNIEXPORT jint JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeInitialize(JNIEnv* env, jobject /*obj*/,
                                                       jstring ipAddress) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_device) return 0; // already connected

    const char* ip = env->GetStringUTFChars(ipAddress, nullptr);
    std::string ipStr(ip ? ip : "");
    if (ip) env->ReleaseStringUTFChars(ipAddress, ip);

    if (!g_system) {
        g_system = IpxCam::IpxCam_GetSystem();
        if (!g_system) return -1;
    }

    IpxCam::DeviceInfo* devInfo = findDeviceByIp(g_system, ipStr);
    if (!devInfo) return -2;

    if (devInfo->GetAccessStatus() != IpxCam::DeviceInfo::AccessStatusReadWrite)
        return -3;

    g_device = IpxCam::IpxCam_CreateDevice(devInfo);
    return g_device ? 0 : -4;
}

/** Disconnect and release all camera resources. */
JNIEXPORT void JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeShutdown(JNIEnv* /*env*/, jobject /*obj*/) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_stream) {
        g_device->GetCameraParameters()->ExecuteCommand("AcquisitionStop");
        g_stream->StopAcquisition(1);
        revokeBuffers();
        g_stream->Release();
        g_stream = nullptr;
    }
    if (g_device) {
        g_device->Release();
        g_device = nullptr;
    }
}

/** Set ExposureTime in microseconds. Returns 0 on success. */
JNIEXPORT jint JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeSetExposureTime(JNIEnv* /*env*/, jobject /*obj*/,
                                                           jdouble microseconds) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_device) return -1;

    auto* params = g_device->GetCameraParameters();
    auto* param  = params->GetFloat("ExposureTime");
    if (!param) return -2;

    IpxCamErr err = param->SetValue(microseconds);
    return (err == IPX_CAM_ERR_OK) ? 0 : static_cast<jint>(err);
}

/** Set Gain. Returns 0 on success. */
JNIEXPORT jint JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeSetGain(JNIEnv* /*env*/, jobject /*obj*/,
                                                   jdouble gain) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_device) return -1;

    auto* params = g_device->GetCameraParameters();
    // C1911 exposes Gain as a float; try GetFloat first, fall back to integer
    IpxCamErr err;
    auto* floatParam = params->GetFloat("Gain", &err);
    if (floatParam && err == IPX_CAM_ERR_OK)
        return (floatParam->SetValue(gain) == IPX_CAM_ERR_OK) ? 0 : -3;

    auto* intParam = params->GetInt("Gain", &err);
    if (intParam && err == IPX_CAM_ERR_OK)
        return (intParam->SetValue(static_cast<int64_t>(gain)) == IPX_CAM_ERR_OK) ? 0 : -4;

    return -2;
}

/** Start the continuous acquisition stream. Returns 0 on success. */
JNIEXPORT jint JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeStartStream(JNIEnv* /*env*/, jobject /*obj*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_device) return -1;
    if (g_stream)  return 0; // already streaming

    if (g_device->GetNumStreams() < 1) return -2;
    g_stream = g_device->GetStreamByIndex(0);
    if (!g_stream) return -3;

    if (!allocateBuffers()) {
        g_stream->Release();
        g_stream = nullptr;
        return -4;
    }

    auto* params = g_device->GetCameraParameters();
    params->SetIntegerValue("TLParamsLocked", 1);

    if (g_stream->StartAcquisition() != IPX_CAM_ERR_OK) {
        revokeBuffers();
        params->SetIntegerValue("TLParamsLocked", 0);
        g_stream->Release();
        g_stream = nullptr;
        return -5;
    }

    if (params->ExecuteCommand("AcquisitionStart") != IPX_CAM_ERR_OK) {
        g_stream->StopAcquisition(1);
        revokeBuffers();
        params->SetIntegerValue("TLParamsLocked", 0);
        g_stream->Release();
        g_stream = nullptr;
        return -6;
    }

    return 0;
}

/** Stop the continuous acquisition stream. */
JNIEXPORT jint JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeStopStream(JNIEnv* /*env*/, jobject /*obj*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_stream) return 0;

    auto* params = g_device->GetCameraParameters();
    params->ExecuteCommand("AcquisitionStop");
    g_stream->StopAcquisition(1);
    revokeBuffers();
    params->SetIntegerValue("TLParamsLocked", 0);
    g_stream->Release();
    g_stream = nullptr;
    return 0;
}

/**
 * Take a single triggered exposure.
 * Configures TriggerMode=On, TriggerSource=Software, acquires one frame,
 * then restores free-run mode.
 * Returns the packed frame byte array, or null on timeout/error.
 */
JNIEXPORT jbyteArray JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeTakeSingleExposure(JNIEnv* env, jobject /*obj*/,
                                                              jint timeoutMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_device) return nullptr;

    // A stream must not already be running when we do a single-frame trigger acquisition
    if (g_stream) return nullptr;

    IpxCam::Stream* stream = g_device->GetStreamByIndex(0);
    if (!stream) return nullptr;

    size_t bufSize = stream->GetBufferSize();
    IpxCam::Buffer* buf = stream->CreateBuffer(bufSize, nullptr, nullptr);
    if (!buf) { stream->Release(); return nullptr; }

    auto* params = g_device->GetCameraParameters();

    // Configure software trigger
    auto* trigMode = params->GetEnum("TriggerMode");
    if (trigMode) trigMode->SetValueStr("On");

    auto* trigSrc = params->GetEnum("TriggerSource");
    if (trigSrc) trigSrc->SetValueStr("Software");

    params->SetIntegerValue("TLParamsLocked", 1);
    stream->StartAcquisition(1); // request exactly one frame
    params->ExecuteCommand("AcquisitionStart");

    // Fire software trigger
    auto* swTrig = params->GetCommand("TriggerSoftware");
    if (swTrig) swTrig->Execute();

    // Wait for the frame
    IpxCamErr err = IPX_CAM_ERR_OK;
    IpxCam::Buffer* acquired = stream->GetBuffer(static_cast<uint64_t>(timeoutMs) * 1000000ULL, &err);

    jbyteArray result = nullptr;
    if (acquired && err == IPX_CAM_ERR_OK) {
        result = packBuffer(env, acquired);
        stream->QueueBuffer(acquired);
    }

    // Tear down
    params->ExecuteCommand("AcquisitionStop");
    stream->StopAcquisition(1);
    stream->RevokeBuffer(buf);
    params->SetIntegerValue("TLParamsLocked", 0);

    // Restore free-run trigger mode
    if (trigMode) trigMode->SetValueStr("Off");
    stream->Release();

    return result;
}

/**
 * Dequeue one frame from the running continuous stream.
 * Returns the packed frame byte array, or null on timeout/error.
 * The stream must have been started with nativeStartStream() first.
 */
JNIEXPORT jbyteArray JNICALL
Java_lgsf_pacprototypehcd_PacCamera_nativeGetStreamFrame(JNIEnv* env, jobject /*obj*/,
                                                          jint timeoutMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_stream) return nullptr;

    IpxCamErr err = IPX_CAM_ERR_OK;
    // Convert milliseconds to nanoseconds for the SDK timeout
    uint64_t timeoutNs = static_cast<uint64_t>(timeoutMs) * 1000000ULL;
    IpxCam::Buffer* buf = g_stream->GetBuffer(timeoutNs, &err);
    if (!buf || err != IPX_CAM_ERR_OK) {
        if (buf) g_stream->QueueBuffer(buf);
        return nullptr;
    }

    jbyteArray result = packBuffer(env, buf);
    g_stream->QueueBuffer(buf); // always re-queue
    return result;
}

} // extern "C"
