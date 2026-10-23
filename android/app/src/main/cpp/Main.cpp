/**
 * @file
 * @brief Entry point of the native library of the mod (<code>libdobsongomenu.so</code>).
 *
 * This file is the link between the Java part of the mod and the Unity game (il2cpp) it runs in.
 * It is used in two steps:
 * <ol>
 *   <li><b>Loading</b>: <code>System.loadLibrary("dobsongomenu")</code>, in <code>DobsonGoMod</code>, makes
 *       Android call JNI_OnLoad(), which saves the Java virtual machine and connects the Java method
 *       <code>DobsonGoMod.c_init</code> to the C++ function c_init();</li>
 *   <li><b>Initialisation</b>: once the permissions are granted, <code>DobsonGoMod.init()</code> calls
 *       <code>c_init</code>, which finds <code>libil2cpp.so</code> in memory, initialises ShadowHook and the
 *       il2cpp SDK, then installs the hooks of the mod in the game.</li>
 * </ol>
 */

#include <jni.h>        // JNI types and functions (JNIEnv, JavaVM, jclass...)
#include <string>
#include <dlfcn.h>      // dlopen
#include <unistd.h>
#include "Hooks.hpp"    // MenuHandler: hooks installed in the game
#include "Il2cppSdk.h"  // il2cpp_sdk_init: access to the Unity classes and methods of the game

/**
 * @brief Java virtual machine of the app, saved by JNI_OnLoad().
 *
 * A <code>JNIEnv</code> is only valid on the thread that received it. Code running on another thread, such as
 * the hooks called by the game, must go through this <code>JavaVM</code> to get its own <code>JNIEnv</code>
 * before calling Java.
 */
JavaVM* g_vm = nullptr;

/**
 * @brief Returns the address at which a library is loaded in the process (its base address).
 *
 * The hooks target functions of the game by their <b>RVA</b> (Relative Virtual Address): their offset from the
 * start of <code>libil2cpp.so</code>. The real address of a function in memory is therefore
 * <code>base + RVA</code>, which is why this base is needed.
 *
 * The function reads <code>/proc/self/maps</code>, the list of the memory areas of the process, where each line
 * looks like:
 * <pre>7a1b200000-7a1b9f0000 r-xp 00000000 fd:01 1234  /data/app/.../lib/arm64/libil2cpp.so</pre>
 * The first line that contains <code>name</code> is kept, and its start address (the hexadecimal number before
 * the <code>-</code>) is returned: it is the first area of the library, so its base.
 *
 * @note Does not work with <code>shadowhook_dlopen</code>.
 *
 * @param name name of the library, or part of its path, e.g. <code>"libil2cpp.so"</code>
 * @return the base address of the library, or <code>0</code> if no memory area contains this name
 */
uintptr_t get_base(const char* name) {
    FILE* f = fopen("/proc/self/maps", "r");
    char line[512];
    uintptr_t addr = 0;

    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, name)) {
            addr = strtoull(line, nullptr, 16);   // Reads the hexadecimal start address, stops at the '-'
            break;
        }
    }
    fclose(f);
    return addr;
}

/**
 * @brief Installs the hooks of the mod in the game.
 *
 * Simple wrapper around <code>MenuHandler::install</code> (see <code>Hooks.hpp</code>), which receives the base
 * of <code>libil2cpp.so</code> to compute the addresses of the hooked functions, and the Java virtual machine
 * so that the hooks can call the Java code.
 *
 * @param il2cpp_base base address of <code>libil2cpp.so</code>, returned by get_base()
 * @return <code>true</code> if all the hooks were installed
 */
bool setup_hooks(uintptr_t il2cpp_base) {
    return MenuHandler::install(il2cpp_base, g_vm);
}

/**
 * @brief Native implementation of <code>DobsonGoMod.c_init(Context)</code>: initialises the mod in the game.
 *
 * <code>static</code>: this function is not exported by name; Java can only reach it because
 * JNI_OnLoad() registers it with <code>RegisterNatives</code>.
 *
 * Steps:
 * <ol>
 *   <li>gets a handle and the base address of <code>libil2cpp.so</code>. The game has already loaded this
 *       library: <code>dlopen</code> does not load a second copy, it returns the one in memory;</li>
 *   <li>initialises <b>ShadowHook</b>, the inline hook library, in <code>UNIQUE</code> mode (a function can only
 *       be hooked once) and without debug logs;</li>
 *   <li>initialises the <b>il2cpp SDK</b>, used to access the Unity classes and methods of the game;</li>
 *   <li>installs the hooks (setup_hooks());</li>
 *   <li>shows a toast with the credits of the mod, through <code>Utils.showToast</code> on the Java side.</li>
 * </ol>
 *
 * @warning The <code>return</code> after each error is commented out: an error is only written to Logcat
 *          (<code>Init Error #1</code> to <code>#5</code>), the next steps still run, and the function always
 *          returns <code>0</code>.
 *
 * @param env        JNI environment of the calling thread
 * @param clazz      the Java class <code>DobsonGoMod</code> (static method)
 * @param appContext application context, used to show the toast
 * @return <code>0</code>; the error codes 1, 2, 3 and 5 are prepared but disabled (see the warning above)
 */
static jint c_init(JNIEnv* env, jclass clazz, jobject appContext) {
    // 1. libil2cpp.so: handle (for the il2cpp SDK) and base address (for the RVAs of the hooks)
    const char* lib_name = "libil2cpp.so";
    void* il2cpp_handle = dlopen(lib_name, RTLD_NOW);
    uintptr_t il2cpp_base = get_base(lib_name);

    // 2. ShadowHook
    int shadow_err = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (shadow_err != 0) {
        LogcatE("Init Error #1: shadowhook_init failed -> (%d) %s", shadow_err, shadowhook_to_errmsg(shadow_err));
        return 1;
    } else { Logcat("Shadowhook loaded"); }

    if (il2cpp_base == 0 || !il2cpp_handle) {
        LogcatE("Init Error #2: \"%s\" not found in app!", lib_name);
        return 2;
    } else { Logcat("il2cpp found: %lu", il2cpp_base); }

    // 3. il2cpp SDK
    if (!il2cpp_sdk_init(il2cpp_handle)) {
        LogcatE("Init Error #3: unable to load il2cpp SDK");
        return 3;
    } else { Logcat("il2cpp SDK loaded"); }

    // 4. Hooks
    if (!setup_hooks(il2cpp_base)) {
        LogcatE("Init Error #5: unable to install hooks and proxies");
        return 5;
    } else { Logcat("All hooks installed"); }

    // 5. Credits
    jclass launcherClazz = env->FindClass("com/terroroo/dobsongomenu/Utils");
    jmethodID showToast_id = env->GetStaticMethodID(launcherClazz, "showToast", "(Landroid/content/Context;Ljava/lang/String;)V");
    jstring ad_msg = env->NewStringUTF("DobsonGo [MOD] - by @Terroroo12 <3");
    env->CallStaticVoidMethod(launcherClazz, showToast_id, appContext, ad_msg);

    return 0;
}

/**
 * @brief Called by Android when the library is loaded by <code>System.loadLibrary("dobsongomenu")</code>.
 *
 * <ol>
 *   <li>saves the Java virtual machine in <code>g_vm</code>;</li>
 *   <li>gets the <code>JNIEnv</code> of the current thread;</li>
 *   <li>registers the native methods of <code>DobsonGoMod</code>: the Java declaration
 *       <code>private static native int c_init(Context)</code> is connected to the C++ function c_init().</li>
 * </ol>
 *
 * Registering the methods with <code>RegisterNatives</code> avoids the long exported names
 * (<code>Java_com_terroroo_dobsongomenu_DobsonGoMod_c_1init</code>) that JNI looks for otherwise.
 *
 * @param vm       the Java virtual machine of the app
 * @param reserved not used
 * @return <code>JNI_VERSION_1_6</code>, the JNI version needed by the library, or <code>JNI_ERR</code> if the
 *         registration fails: <code>System.loadLibrary</code> then throws an <code>UnsatisfiedLinkError</code>
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    /* ------ JNI signatures ------
     *  Java      → Signature   → Native type
     *  boolean   → Z           → jboolean
     *  byte      → B           → jbyte
     *  char      → C           → jchar
     *  short     → S           → jshort
     *  int       → I           → jint
     *  long      → J           → jlong
     *  float     → F           → jfloat
     *  double    → D           → jdouble
     *  void      → V           → void
     *  object    → Lpath/Class; → jobject   (e.g. Context → Landroid/content/Context;)
     *  array     → [type       → jarray    (e.g. int[] → [I)
     *
     *  Method: (parameters)return  →  int c_init(Context) = "(Landroid/content/Context;)I"
     */

    g_vm = vm;
    JNIEnv* env;
    g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);

    // ##### Native Func Registration in Java ##### //
    // { Java name, JNI signature, C++ function }
    static JNINativeMethod l_methods[] = {
        { "c_init", "(Landroid/content/Context;)I",(void*)c_init }
    };
    jclass l_clazz = env->FindClass("com/terroroo/dobsongomenu/DobsonGoMod");
    if (env->RegisterNatives(l_clazz, l_methods, sizeof(l_methods) / sizeof(l_methods[0])) != 0) {
        LogcatE("Unable to load native methods in Java!");
        return JNI_ERR;
    }

    Logcat("DobsonGo lib loaded");
    return JNI_VERSION_1_6;
}
