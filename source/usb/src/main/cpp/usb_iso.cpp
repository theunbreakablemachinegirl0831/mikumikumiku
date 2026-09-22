// Isochronous OUT transfers on a claimed USB audio interface, through usbfs directly.
//
// Android's Java USB API has bulk, interrupt and control transfers but no isochronous ones, and
// isochronous is the only kind a USB audio streaming endpoint accepts. The file descriptor from
// UsbDeviceConnection is an ordinary usbfs handle, so the kernel's own URB ioctls work on it - the
// same path libusb takes on Android, without taking on libusb.
//
// This layer is deliberately thin: it owns the URB memory and moves URBs in and out of the kernel.
// How many frames go in each packet, what the samples are, and what to do about drift are all
// decided in Kotlin, where they can be tested without hardware.

#include <jni.h>

#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

#include <linux/usbdevice_fs.h>

namespace {

struct Stream {
    int fd = -1;
    int endpoint = 0;
    int urbCount = 0;
    int packetsPerUrb = 0;
    int maxPacketBytes = 0;
    usbdevfs_urb** urbs = nullptr;
    unsigned char** buffers = nullptr;
    bool* inFlight = nullptr;
};

Stream* fromHandle(jlong handle) {
    return reinterpret_cast<Stream*>(static_cast<intptr_t>(handle));
}

size_t urbSize(int packets) {
    return sizeof(usbdevfs_urb) + static_cast<size_t>(packets) * sizeof(usbdevfs_iso_packet_desc);
}

void freeStream(Stream* stream) {
    if (stream == nullptr) return;
    for (int i = 0; i < stream->urbCount; ++i) {
        if (stream->urbs != nullptr) free(stream->urbs[i]);
        if (stream->buffers != nullptr) free(stream->buffers[i]);
    }
    free(stream->urbs);
    free(stream->buffers);
    free(stream->inFlight);
    delete stream;
}

// Takes one completed URB off the kernel's list without blocking.
// Returns its index, or -errno (-EAGAIN when nothing has completed yet).
int reapOnce(Stream* stream, jint* status, jint* failedPackets, jint* actualLength) {
    usbdevfs_urb* done = nullptr;
    if (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) return -errno;
    if (done == nullptr) return -EAGAIN;

    const int index = static_cast<int>(reinterpret_cast<intptr_t>(done->usercontext));
    if (index < 0 || index >= stream->urbCount) return -EFAULT;
    stream->inFlight[index] = false;

    int failed = 0;
    for (int p = 0; p < done->number_of_packets; ++p) {
        if (done->iso_frame_desc[p].status != 0) ++failed;
    }
    if (status != nullptr) *status = done->status;
    if (failedPackets != nullptr) *failedPackets = failed;
    if (actualLength != nullptr) *actualLength = done->actual_length;
    return index;
}

}  // namespace

extern "C" {

// Allocates the URBs and their buffers. The fd stays owned by the Java UsbDeviceConnection.
JNIEXPORT jlong JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeOpen(
        JNIEnv*, jclass, jint fd, jint endpoint, jint urbCount, jint packetsPerUrb,
        jint maxPacketBytes) {
    if (fd < 0 || urbCount <= 0 || packetsPerUrb <= 0 || maxPacketBytes <= 0) return 0;

    auto* stream = new Stream();
    stream->fd = fd;
    stream->endpoint = endpoint;
    stream->urbCount = urbCount;
    stream->packetsPerUrb = packetsPerUrb;
    stream->maxPacketBytes = maxPacketBytes;
    stream->urbs = static_cast<usbdevfs_urb**>(calloc(urbCount, sizeof(usbdevfs_urb*)));
    stream->buffers = static_cast<unsigned char**>(calloc(urbCount, sizeof(unsigned char*)));
    stream->inFlight = static_cast<bool*>(calloc(urbCount, sizeof(bool)));
    if (stream->urbs == nullptr || stream->buffers == nullptr || stream->inFlight == nullptr) {
        freeStream(stream);
        return 0;
    }

    const size_t bufferBytes = static_cast<size_t>(packetsPerUrb) * maxPacketBytes;
    for (int i = 0; i < urbCount; ++i) {
        stream->urbs[i] = static_cast<usbdevfs_urb*>(calloc(1, urbSize(packetsPerUrb)));
        stream->buffers[i] = static_cast<unsigned char*>(calloc(1, bufferBytes));
        if (stream->urbs[i] == nullptr || stream->buffers[i] == nullptr) {
            freeStream(stream);
            return 0;
        }
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(stream));
}

// The URB's sample buffer, shared with Kotlin so packing needs no copy across JNI.
JNIEXPORT jobject JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeBuffer(JNIEnv* env, jclass, jlong handle, jint index) {
    Stream* stream = fromHandle(handle);
    if (stream == nullptr || index < 0 || index >= stream->urbCount) return nullptr;
    const jlong capacity = static_cast<jlong>(stream->packetsPerUrb) * stream->maxPacketBytes;
    return env->NewDirectByteBuffer(stream->buffers[index], capacity);
}

// Queues URB [index] with one packet per entry of [lengths]; packet data lies back to back in its
// buffer. Returns 0 or -errno.
JNIEXPORT jint JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeSubmit(
        JNIEnv* env, jclass, jlong handle, jint index, jintArray lengths, jint packets) {
    Stream* stream = fromHandle(handle);
    if (stream == nullptr || index < 0 || index >= stream->urbCount) return -EINVAL;
    if (packets <= 0 || packets > stream->packetsPerUrb) return -EINVAL;
    if (stream->inFlight[index]) return -EBUSY;

    jint lengthValues[256];
    if (packets > 256) return -EINVAL;
    env->GetIntArrayRegion(lengths, 0, packets, lengthValues);

    usbdevfs_urb* urb = stream->urbs[index];
    memset(urb, 0, urbSize(stream->packetsPerUrb));
    int total = 0;
    for (int p = 0; p < packets; ++p) {
        const int length = lengthValues[p];
        if (length < 0 || length > stream->maxPacketBytes) return -EMSGSIZE;
        urb->iso_frame_desc[p].length = static_cast<unsigned int>(length);
        total += length;
    }
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = static_cast<unsigned char>(stream->endpoint);
    // ASAP: let the host controller schedule it right after whatever is already queued, rather
    // than us naming a frame number and racing the bus to it.
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = stream->buffers[index];
    urb->buffer_length = total;
    urb->number_of_packets = packets;
    urb->usercontext = reinterpret_cast<void*>(static_cast<intptr_t>(index));

    if (ioctl(stream->fd, USBDEVFS_SUBMITURB, urb) < 0) return -errno;
    stream->inFlight[index] = true;
    return 0;
}

// Waits up to [timeoutMs] for a URB to complete and takes it back.
// Returns its index, or -errno: -EAGAIN on timeout, -ENODEV once the device is gone.
// [result] receives { urb status, packets that failed, bytes actually sent }.
JNIEXPORT jint JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeReap(
        JNIEnv* env, jclass, jlong handle, jint timeoutMs, jintArray result) {
    Stream* stream = fromHandle(handle);
    if (stream == nullptr) return -EINVAL;

    jint values[3] = {0, 0, 0};
    int index = reapOnce(stream, &values[0], &values[1], &values[2]);
    if (index == -EAGAIN) {
        // usbfs signals completed URBs as writable.
        pollfd waiter{};
        waiter.fd = stream->fd;
        waiter.events = POLLOUT | POLLWRNORM;
        const int ready = poll(&waiter, 1, timeoutMs);
        if (ready < 0) return -errno;
        if (ready == 0) return -EAGAIN;
        if ((waiter.revents & (POLLERR | POLLHUP)) != 0 &&
            (waiter.revents & (POLLOUT | POLLWRNORM)) == 0) {
            return -ENODEV;
        }
        index = reapOnce(stream, &values[0], &values[1], &values[2]);
    }
    if (index >= 0 && result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return index;
}

// Cancels every URB still with the kernel and collects them, so their memory can be reused or
// freed. Safe to call when nothing is queued.
JNIEXPORT void JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeDiscardAll(JNIEnv*, jclass, jlong handle) {
    Stream* stream = fromHandle(handle);
    if (stream == nullptr) return;

    for (int i = 0; i < stream->urbCount; ++i) {
        if (stream->inFlight[i]) ioctl(stream->fd, USBDEVFS_DISCARDURB, stream->urbs[i]);
    }
    // A discarded URB still has to be reaped before its memory is ours again. Bounded, so a
    // device that vanished mid-discard cannot hang the caller.
    for (int attempt = 0; attempt < 50; ++attempt) {
        bool pending = false;
        for (int i = 0; i < stream->urbCount; ++i) pending = pending || stream->inFlight[i];
        if (!pending) return;

        const int index = reapOnce(stream, nullptr, nullptr, nullptr);
        if (index == -EAGAIN) {
            pollfd waiter{};
            waiter.fd = stream->fd;
            waiter.events = POLLOUT | POLLWRNORM;
            poll(&waiter, 1, 20);
        } else if (index < 0 && index != -EFAULT) {
            // The device is gone and the kernel has already dropped its URBs.
            for (int i = 0; i < stream->urbCount; ++i) stream->inFlight[i] = false;
            return;
        }
    }
}

JNIEXPORT void JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeClose(JNIEnv* env, jclass clazz, jlong handle) {
    Stream* stream = fromHandle(handle);
    if (stream == nullptr) return;
    Java_mmm_source_usb_UsbIsoNative_nativeDiscardAll(env, clazz, handle);
    freeStream(stream);
}

// The bus speed the device enumerated at (enum usb_device_speed: 2 = full, 3 = high), or -errno.
// It decides whether the endpoint is serviced per 1 ms frame or per 125 us microframe.
JNIEXPORT jint JNICALL
Java_mmm_source_usb_UsbIsoNative_nativeSpeed(JNIEnv*, jclass, jint fd) {
    const int speed = ioctl(fd, USBDEVFS_GET_SPEED, nullptr);
    return speed < 0 ? -errno : speed;
}

}  // extern "C"
