#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* Header of the tracking service's ring buffers, see DataBufferHeader in PicoFacialDataDaemon. */
struct header {
    uint32_t version;
    uint32_t elementSize;
    uint32_t capacity;
    int32_t writeIndex; /* -1 until the first slot is written */
    uint32_t dataOffset;
};

struct buffer {
    const uint8_t *memory;
    size_t size;
    int fd;
    int32_t lastIndex;
};

JNIEXPORT jlong JNICALL
Java_io_github_codeyumx_picofacetracking_SharedBuffer_nativeMap(JNIEnv *env, jclass cls, jint fd, jint size) {
    (void) env;
    (void) cls;

    if (size < (jint) sizeof(struct header)) {
        close(fd);
        return 0;
    }

    void *memory = mmap(NULL, (size_t) size, PROT_READ, MAP_SHARED, fd, 0);
    if (memory == MAP_FAILED) {
        close(fd);
        return 0;
    }

    struct buffer *buffer = malloc(sizeof(*buffer));
    if (buffer == NULL) {
        munmap(memory, (size_t) size);
        close(fd);
        return 0;
    }

    buffer->memory = memory;
    buffer->size = (size_t) size;
    buffer->fd = fd;
    buffer->lastIndex = -1;
    return (jlong) (intptr_t) buffer;
}

/* Returns 1 when a new slot was copied, 0 when nothing new was written, -1 when the layout does not fit. */
JNIEXPORT jint JNICALL
Java_io_github_codeyumx_picofacetracking_SharedBuffer_nativeReadLatest(JNIEnv *env, jclass cls, jlong handle,
                                                                       jobject destination, jint offset, jint length) {
    (void) cls;
    struct buffer *buffer = (struct buffer *) (intptr_t) handle;
    const volatile struct header *header = (const volatile struct header *) buffer->memory;

    int32_t index = header->writeIndex;
    if (index < 0 || index == buffer->lastIndex)
        return 0;

    uint64_t elementSize = header->elementSize;
    uint64_t slotEnd = (uint64_t) header->dataOffset + elementSize * ((uint64_t) index + 1);
    if ((uint32_t) index >= header->capacity || length < 0 || (uint64_t) length > elementSize || slotEnd > buffer->size)
        return -1;

    uint8_t *out = (*env)->GetDirectBufferAddress(env, destination);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, destination);
    if (out == NULL || offset < 0 || (jlong) offset + length > capacity)
        return -1;

    memcpy(out + offset, buffer->memory + (slotEnd - elementSize), (size_t) length);
    buffer->lastIndex = index;
    return 1;
}

JNIEXPORT void JNICALL
Java_io_github_codeyumx_picofacetracking_SharedBuffer_nativeUnmap(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    struct buffer *buffer = (struct buffer *) (intptr_t) handle;
    munmap((void *) buffer->memory, buffer->size);
    close(buffer->fd);
    free(buffer);
}
