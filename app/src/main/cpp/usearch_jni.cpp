#include <jni.h>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" {
typedef void* usearch_index_t;
typedef std::uint64_t usearch_key_t;
typedef float usearch_distance_t;
typedef char const* usearch_error_t;
typedef float (*usearch_metric_t)(void const*, void const*);
enum usearch_metric_kind_t { usearch_metric_cos_k = 1 };
enum usearch_scalar_kind_t { usearch_scalar_f32_k = 1 };
struct usearch_init_options_t {
    usearch_metric_kind_t metric_kind; usearch_metric_t metric; usearch_scalar_kind_t quantization;
    std::size_t dimensions, connectivity, expansion_add, expansion_search; bool multi;
};
usearch_index_t usearch_init(usearch_init_options_t*, usearch_error_t*);
void usearch_free(usearch_index_t, usearch_error_t*);
void usearch_reserve(usearch_index_t, std::size_t, usearch_error_t*);
void usearch_add(usearch_index_t, usearch_key_t, void const*, usearch_scalar_kind_t, usearch_error_t*);
std::size_t usearch_remove(usearch_index_t, usearch_key_t, usearch_error_t*);
std::size_t usearch_search(usearch_index_t, void const*, usearch_scalar_kind_t, std::size_t,
                           usearch_key_t*, usearch_distance_t*, usearch_error_t*);
void usearch_save(usearch_index_t, char const*, usearch_error_t*);
void usearch_load(usearch_index_t, char const*, usearch_error_t*);
std::size_t usearch_size(usearch_index_t, usearch_error_t*);
}

static void fail(JNIEnv* env, char const* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message ? message : "USearch failure");
}
static usearch_index_t handle(jlong value) { return reinterpret_cast<usearch_index_t>(static_cast<intptr_t>(value)); }

extern "C" JNIEXPORT void JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_reserve(JNIEnv* env, jobject, jlong raw, jlong capacity) {
    if (capacity < 0) { fail(env, "Invalid capacity"); return; }
    usearch_error_t error = nullptr; usearch_reserve(handle(raw), static_cast<std::size_t>(capacity), &error); if (error) fail(env, error);
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_create(JNIEnv* env, jobject, jint dimensions) {
    if (dimensions <= 0) { fail(env, "Invalid dimensions"); return 0; }
    usearch_init_options_t options{usearch_metric_cos_k, nullptr, usearch_scalar_f32_k,
        static_cast<std::size_t>(dimensions), 16, 128, 64, false};
    usearch_error_t error = nullptr;
    auto index = usearch_init(&options, &error);
    if (error || !index) { fail(env, error); return 0; }
    usearch_reserve(index, 1024, &error);
    if (error) { usearch_free(index, nullptr); fail(env, error); return 0; }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(index));
}

extern "C" JNIEXPORT void JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_upsert(JNIEnv* env, jobject, jlong raw,
                                                                                         jlong key, jfloatArray input) {
    usearch_error_t error = nullptr;
    usearch_remove(handle(raw), static_cast<usearch_key_t>(key), &error);
    if (error) { fail(env, error); return; }
    jfloat* vector = env->GetFloatArrayElements(input, nullptr);
    usearch_add(handle(raw), static_cast<usearch_key_t>(key), vector, usearch_scalar_f32_k, &error);
    env->ReleaseFloatArrayElements(input, vector, JNI_ABORT);
    if (error) fail(env, error);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_remove(JNIEnv* env, jobject, jlong raw, jlong key) {
    usearch_error_t error = nullptr; usearch_remove(handle(raw), static_cast<usearch_key_t>(key), &error); if (error) fail(env, error);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_search(JNIEnv* env, jobject, jlong raw,
                                                                                               jfloatArray input, jint limit) {
    if (limit <= 0) { fail(env, "Invalid limit"); return nullptr; }
    std::vector<usearch_key_t> keys(static_cast<std::size_t>(limit));
    std::vector<usearch_distance_t> distances(static_cast<std::size_t>(limit));
    jfloat* vector = env->GetFloatArrayElements(input, nullptr); usearch_error_t error = nullptr;
    std::size_t count = usearch_search(handle(raw), vector, usearch_scalar_f32_k, keys.size(), keys.data(), distances.data(), &error);
    env->ReleaseFloatArrayElements(input, vector, JNI_ABORT);
    if (error) { fail(env, error); return nullptr; }
    auto result = env->NewLongArray(static_cast<jsize>(count));
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(keys.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_save(JNIEnv* env, jobject, jlong raw, jstring path) {
    char const* value = env->GetStringUTFChars(path, nullptr); usearch_error_t error = nullptr;
    usearch_save(handle(raw), value, &error); env->ReleaseStringUTFChars(path, value); if (error) fail(env, error);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_load(JNIEnv* env, jobject, jlong raw, jstring path) {
    char const* value = env->GetStringUTFChars(path, nullptr); usearch_error_t error = nullptr;
    usearch_load(handle(raw), value, &error); env->ReleaseStringUTFChars(path, value); if (error) fail(env, error);
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_size(JNIEnv* env, jobject, jlong raw) {
    usearch_error_t error = nullptr;
    auto result = usearch_size(handle(raw), &error);
    if (error) { fail(env, error); return 0; }
    return static_cast<jlong>(result);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_mesteriis_lik_ai_USearchBridge_close(JNIEnv* env, jobject, jlong raw) {
    usearch_error_t error = nullptr; usearch_free(handle(raw), &error); if (error) fail(env, error);
}
