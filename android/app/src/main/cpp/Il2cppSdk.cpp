/**
 * @file Il2cppSdk.cpp
 * @brief Resolution of the IL2CPP functions used by the mod (see Il2cppSdk.h).
 */
#include "Il2cppSdk.h"

/** Global table of the IL2CPP functions: every field is null until il2cpp_sdk_init() succeeds. */
Il2CppAPI g_il2cpp = { nullptr };

/**
 * @brief Finds an exported symbol in a loaded library, and logs an error if it is missing.
 *
 * @param handle handle of the library, as returned by @c dlopen
 * @param name   name of the symbol, e.g. @c "il2cpp_string_new"
 * @return the address of the symbol, or @c nullptr if the library does not export it
 */
static void* load_symbol(void* handle, const char* name) {
    void* sym = dlsym(handle, name);
    if (!sym) {
        LogcatE("il2cpp SDK init error: unable to resolve il2cpp symbol %s", name);
        return nullptr;
    }
    return sym;
}

/**
 * @brief Implementation of Il2CppAPI::find_image: finds the image of a loaded assembly from its name.
 *
 * IL2CPP has no function to do this directly, so all the assemblies of the domain are listed, and the name
 * of the image of each one is compared with @p targetName.
 *
 * @pre il2cpp_sdk_init() must have succeeded: the function uses Il2CppAPI::domain_get,
 *      Il2CppAPI::domain_get_assemblies, Il2CppAPI::assembly_get_image and Il2CppAPI::image_get_name.
 *
 * @param targetName name of the image, with its extension, e.g. @c "Assembly-CSharp.dll" (case-sensitive)
 * @return the image, or @c nullptr if the domain cannot be read or no assembly has this name
 */
const Il2CppImage* il2cpp_find_image_impl(const char* targetName) {
    Il2CppDomain* domain = g_il2cpp.domain_get();
    if (domain == nullptr) {
        LogcatE("il2cpp SDK init error: unable to get il2cpp domain");
        return nullptr;
    }

    size_t count = 0;
    const Il2CppAssembly** assemblies = g_il2cpp.domain_get_assemblies(domain, &count);
    if (assemblies == nullptr) {
        LogcatE("il2cpp SDK init error: unable to get il2cpp assemblies");
    }

    // Compare the name of the image of each assembly with the one we are looking for
    for (size_t i = 0; i < count; i++) {
        const Il2CppImage* img = g_il2cpp.assembly_get_image(assemblies[i]);
        const char* name = g_il2cpp.image_get_name(img);
        if (name && strcmp(name, targetName) == 0) {
            return img;
        }
    }
    return nullptr;
}

/**
 * @brief Initialises the SDK: fills #g_il2cpp with the functions of @c libil2cpp.so.
 *
 * Il2CppAPI::find_image is implemented by this SDK (il2cpp_find_image_impl()); every other field is resolved
 * from the exported symbol of the same name, prefixed with @c il2cpp_.
 *
 * @param il2cpp_handle handle of @c libil2cpp.so, as returned by @c dlopen (the library must already be
 *                      loaded by the app: this function does not load it)
 * @return @c true if every function was found; @c false if the handle is null or a symbol is missing.
 *         In that case, the reason is written to Logcat, #g_il2cpp may be partially filled and must not be used.
 */
bool il2cpp_sdk_init(void* il2cpp_handle) {
    // The handle comes from dlopen("libil2cpp.so"): without it, no symbol can be resolved
    if (!il2cpp_handle) {
        LogcatE("il2cpp SDK init error: il2cpp handle is null");
        return false;
    }

    // Resolution of the IL2CPP functions (find_image is implemented by this SDK)
    g_il2cpp.find_image = il2cpp_find_image_impl;

    g_il2cpp.string_new = (il2cpp_string_new_t)load_symbol(il2cpp_handle, "il2cpp_string_new");
    g_il2cpp.object_get_class = (il2cpp_object_get_class_t)load_symbol(il2cpp_handle, "il2cpp_object_get_class");
    g_il2cpp.class_from_name = (il2cpp_class_from_name_t)load_symbol(il2cpp_handle, "il2cpp_class_from_name");
    g_il2cpp.class_is_assignable_from = (il2cpp_class_is_assignable_from_t)load_symbol(il2cpp_handle, "il2cpp_class_is_assignable_from");
    g_il2cpp.class_get_method_from_name = (il2cpp_class_get_method_from_name_t)load_symbol(il2cpp_handle, "il2cpp_class_get_method_from_name");
    g_il2cpp.runtime_invoke = (il2cpp_runtime_invoke_t)load_symbol(il2cpp_handle, "il2cpp_runtime_invoke");
    g_il2cpp.object_unbox = (il2cpp_object_unbox_t)load_symbol(il2cpp_handle, "il2cpp_object_unbox");
    g_il2cpp.domain_get = (il2cpp_domain_get_t)load_symbol(il2cpp_handle, "il2cpp_domain_get");
    g_il2cpp.domain_get_assemblies = (il2cpp_domain_get_assemblies_t)load_symbol(il2cpp_handle, "il2cpp_domain_get_assemblies");
    g_il2cpp.assembly_get_image = (il2cpp_assembly_get_image_t)load_symbol(il2cpp_handle, "il2cpp_assembly_get_image");
    g_il2cpp.image_get_name = (il2cpp_image_get_name_t)load_symbol(il2cpp_handle, "il2cpp_image_get_name");
    g_il2cpp.class_get_namespace = (il2cpp_class_get_namespace_t)load_symbol(il2cpp_handle, "il2cpp_class_get_namespace");

    // Every function is needed: if a single one is missing, the initialisation fails
    if (!g_il2cpp.string_new
        || !g_il2cpp.object_get_class
        || !g_il2cpp.class_from_name
        || !g_il2cpp.class_is_assignable_from
        || !g_il2cpp.class_get_method_from_name
        || !g_il2cpp.runtime_invoke
        || !g_il2cpp.object_unbox
        || !g_il2cpp.domain_get
        || !g_il2cpp.domain_get_assemblies
        || !g_il2cpp.assembly_get_image
        || !g_il2cpp.image_get_name
        || !g_il2cpp.class_get_namespace
    ) {
        LogcatE("il2cpp SDK init error: function is null after initialisation");
        return false;
    }

    return true;
}
