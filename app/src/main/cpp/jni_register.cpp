// 动态注册 JNI 方法。
//
// 默认情况下 ART 用静态命名（Java_<包>_<类>_<方法>）在本库导出符号里查找 native 方法。
// 某些 ARM 翻译层（如 Intel houdini64）在被翻译的库上做 dlsym 时拿不到这些符号，
// 于是调用任何 native 方法都抛 UnsatisfiedLinkError: No implementation found。
//
// 这里在 JNI_OnLoad 里用 RegisterNatives 显式登记一遍：即使符号查找不可用，
// JVM 也能直接拿到函数指针。
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "ShamrockJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ---- 库里已有的静态命名实现 ----
extern "C" {
jlong Java_moe_fuqiuluo_shamrock_helper_MessageHelper_createMessageUniseq(JNIEnv *, jobject, jint, jlong);
jobject Java_moe_fuqiuluo_shamrock_helper_MessageHelper_nativeDecodeCQCode(JNIEnv *, jobject, jstring);
jstring Java_moe_fuqiuluo_shamrock_helper_MessageHelper_nativeEncodeCQCode(JNIEnv *, jobject, jobject);

jstring Java_moe_fuqiuluo_shamrock_utils_MD5_getMd5Hex(JNIEnv *, jobject, jbyteArray);
jstring Java_moe_fuqiuluo_shamrock_utils_MD5_genFileMd5Hex(JNIEnv *, jobject, jstring);
jbyteArray Java_moe_fuqiuluo_shamrock_utils_MD5_genFileMd5(JNIEnv *, jobject, jstring);

jint Java_moe_fuqiuluo_shamrock_utils_AudioUtils_pcmToSilk(JNIEnv *, jobject, jint, jbyte, jstring, jstring);
jint Java_moe_fuqiuluo_shamrock_utils_AudioUtils_silkToPcm(JNIEnv *, jobject, jint, jbyte, jstring, jstring);

jobject Java_moe_fuqiuluo_shamrock_remote_action_handlers_GetTroopHonor_nativeDecodeHonor(JNIEnv *, jobject, jstring, jint, jbyte);

jstring Java_moe_fuqiuluo_shamrock_xposed_hooks_PullConfig_testNativeLibrary(JNIEnv *, jobject);
}

// Kotlin 侧为需要兜底的方法增加了包装函数，原实现仍使用静态命名。
// 这里补齐包装方法对应的静态入口，保证 JNI_OnLoad 未执行或注册失败时，
// 普通平台仍能按静态命名找到原生实现。
extern "C" {
JNIEXPORT jlong JNICALL
Java_moe_fuqiuluo_shamrock_helper_MessageHelper_nativeCreateMessageUniseq(
        JNIEnv *env, jobject thiz, jint chat_type, jlong time) {
    return Java_moe_fuqiuluo_shamrock_helper_MessageHelper_createMessageUniseq(
            env, thiz, chat_type, time);
}

JNIEXPORT jstring JNICALL
Java_moe_fuqiuluo_shamrock_utils_MD5_nativeGetMd5Hex(JNIEnv *env, jobject thiz, jbyteArray bytes) {
    return Java_moe_fuqiuluo_shamrock_utils_MD5_getMd5Hex(env, thiz, bytes);
}

JNIEXPORT jstring JNICALL
Java_moe_fuqiuluo_shamrock_utils_MD5_nativeGenFileMd5Hex(JNIEnv *env, jobject thiz, jstring path) {
    return Java_moe_fuqiuluo_shamrock_utils_MD5_genFileMd5Hex(env, thiz, path);
}

JNIEXPORT jbyteArray JNICALL
Java_moe_fuqiuluo_shamrock_utils_MD5_nativeGenFileMd5(JNIEnv *env, jobject thiz, jstring path) {
    return Java_moe_fuqiuluo_shamrock_utils_MD5_genFileMd5(env, thiz, path);
}

JNIEXPORT jstring JNICALL
Java_moe_fuqiuluo_shamrock_xposed_hooks_PullConfig_nativeTestNativeLibrary(JNIEnv *env,
                                                                           jobject thiz) {
    return Java_moe_fuqiuluo_shamrock_xposed_hooks_PullConfig_testNativeLibrary(env, thiz);
}
}

namespace {

struct ClassMethods {
    const char *cls;
    const JNINativeMethod *methods;
    int count;
};

void registerOne(JNIEnv *env, const char *cls, const JNINativeMethod *methods, int count) {
    jclass c = env->FindClass(cls);
    if (c == nullptr) {
        env->ExceptionClear();
        LOGW("class not found at load time: %s (跳过，稍后仍可静态查找)", cls);
        return;
    }
    if (env->RegisterNatives(c, methods, count) == JNI_OK) {
        LOGI("registered %d native methods for %s", count, cls);
    } else {
        env->ExceptionClear();
        LOGW("RegisterNatives failed for %s", cls);
    }
    env->DeleteLocalRef(c);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    static const JNINativeMethod messageHelper[] = {
        {const_cast<char *>("nativeCreateMessageUniseq"), const_cast<char *>("(IJ)J"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_helper_MessageHelper_createMessageUniseq)},
        {const_cast<char *>("nativeDecodeCQCode"), const_cast<char *>("(Ljava/lang/String;)Ljava/util/List;"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_helper_MessageHelper_nativeDecodeCQCode)},
        {const_cast<char *>("nativeEncodeCQCode"), const_cast<char *>("(Ljava/util/List;)Ljava/lang/String;"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_helper_MessageHelper_nativeEncodeCQCode)},
    };
    static const JNINativeMethod md5Methods[] = {
        {const_cast<char *>("nativeGetMd5Hex"), const_cast<char *>("([B)Ljava/lang/String;"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_utils_MD5_getMd5Hex)},
        {const_cast<char *>("nativeGenFileMd5Hex"), const_cast<char *>("(Ljava/lang/String;)Ljava/lang/String;"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_utils_MD5_genFileMd5Hex)},
        {const_cast<char *>("nativeGenFileMd5"), const_cast<char *>("(Ljava/lang/String;)[B"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_utils_MD5_genFileMd5)},
    };
    static const JNINativeMethod audioMethods[] = {
        {const_cast<char *>("pcmToSilk"), const_cast<char *>("(IBLjava/lang/String;Ljava/lang/String;)I"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_utils_AudioUtils_pcmToSilk)},
        {const_cast<char *>("silkToPcm"), const_cast<char *>("(IBLjava/lang/String;Ljava/lang/String;)I"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_utils_AudioUtils_silkToPcm)},
    };
    static const JNINativeMethod honorMethods[] = {
        {const_cast<char *>("nativeDecodeHonor"),
         const_cast<char *>("(Ljava/lang/String;IB)Lmoe/fuqiuluo/shamrock/remote/service/data/GroupMemberHonor;"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_remote_action_handlers_GetTroopHonor_nativeDecodeHonor)},
    };
    static const JNINativeMethod pullConfigMethods[] = {
        {const_cast<char *>("nativeTestNativeLibrary"), const_cast<char *>("()Ljava/lang/String;"),
         reinterpret_cast<void *>(Java_moe_fuqiuluo_shamrock_xposed_hooks_PullConfig_testNativeLibrary)},
    };

    registerOne(env, "moe/fuqiuluo/shamrock/helper/MessageHelper", messageHelper,
                sizeof(messageHelper) / sizeof(messageHelper[0]));
    registerOne(env, "moe/fuqiuluo/shamrock/utils/MD5", md5Methods,
                sizeof(md5Methods) / sizeof(md5Methods[0]));
    registerOne(env, "moe/fuqiuluo/shamrock/utils/AudioUtils", audioMethods,
                sizeof(audioMethods) / sizeof(audioMethods[0]));
    registerOne(env, "moe/fuqiuluo/shamrock/remote/action/handlers/GetTroopHonor", honorMethods,
                sizeof(honorMethods) / sizeof(honorMethods[0]));
    registerOne(env, "moe/fuqiuluo/shamrock/xposed/hooks/PullConfig", pullConfigMethods,
                sizeof(pullConfigMethods) / sizeof(pullConfigMethods[0]));

    LOGI("JNI_OnLoad done");
    return JNI_VERSION_1_6;
}
