/**
 * @file Il2cppSdk.h
 * @brief Minimal access to the IL2CPP runtime of a Unity app, from native code.
 *
 * A Unity app built with IL2CPP turns its C# code into native code, stored in @c libil2cpp.so.
 * This library exports a C API (@c il2cpp_class_from_name, @c il2cpp_runtime_invoke...) that gives
 * access to the C# classes, methods and objects of the app at runtime.
 *
 * This SDK resolves the functions it needs from this API once, with @c dlsym, and stores them in
 * the global table #g_il2cpp. Typical use:
 * @code
 * void* handle = dlopen("libil2cpp.so", RTLD_NOLOAD);
 * if (il2cpp_sdk_init(handle)) {
 *     const Il2CppImage* image = g_il2cpp.find_image("Assembly-CSharp.dll");
 *     Il2CppClass* klass = g_il2cpp.class_from_name(image, "MyNamespace", "MyClass");
 *     const MethodInfo* method = g_il2cpp.class_get_method_from_name(klass, "MyMethod", 0);
 *     Il2CppObject* result = g_il2cpp.runtime_invoke(method, nullptr, nullptr, nullptr);
 * }
 * @endcode
 *
 * @note Most IL2CPP structures change from one Unity version to another: they are only declared here
 *       (opaque types) and must always be handled through pointers and the functions of #g_il2cpp.
 *       Only the headers of strings and objects, which are stable, are described.
 */
#pragma once

#include <cstdint>
#include <dlfcn.h>
#include <malloc.h>
#include <cstring>
#include "Logcat.hpp"

/**
 * @brief Managed C# string (@c System.String).
 *
 * The characters are stored in UTF-16, right after the header, and are @b not null-terminated:
 * always use #length. The offsets given are those of a 64-bit process (arm64).
 *
 * @see Il2CppAPI::string_new to create one from a C string.
 */
struct Il2CppString {
    void* klass;        // 0x00
    void* monitor;      // 0x08
    int32_t length;     // 0x10
    uint16_t chars[];  // 0x14
};

/** @brief C# class or type (opaque). Obtained with Il2CppAPI::class_from_name or Il2CppAPI::object_get_class. */
struct Il2CppClass;

/** @brief Image of a .NET assembly, e.g. @c Assembly-CSharp.dll (opaque). Contains the classes of the assembly. */
struct Il2CppImage;

/**
 * @brief Header shared by every managed object (instance of a C# class).
 *
 * Any C# object starts with these two fields; its own fields come right after. A boxed value type
 * (e.g. an @c int returned by Il2CppAPI::runtime_invoke) has the same header, followed by the value.
 */
struct Il2CppObject {
    /** Class of the object. */
    Il2CppClass* klass;
    /** Lock used by the C# @c lock statement; managed by the runtime, never touched directly. */
    void* monitor;
};

/** @brief Description of a C# method (opaque). Obtained with Il2CppAPI::class_get_method_from_name. */
struct MethodInfo;
/** @brief C# exception thrown by a method called with Il2CppAPI::runtime_invoke (opaque). */
struct Il2CppException;
/** @brief Application domain: contains all the assemblies loaded by the app (opaque). */
struct Il2CppDomain;
/** @brief Loaded .NET assembly (opaque). Its classes are reached through its image. */
struct Il2CppAssembly;


// Signatures of the IL2CPP functions stored in Il2CppAPI. Each one is resolved from the symbol of the
// same name in libil2cpp.so (e.g. il2cpp_string_new_t <- "il2cpp_string_new"), except il2cpp_find_image_t.

/**
 * @brief Finds the image of a loaded assembly from its name. Field: Il2CppAPI::find_image.
 *
 * @note Not part of the IL2CPP API: implemented by this SDK (see @c il2cpp_find_image_impl in
 *       Il2cppSdk.cpp), by going through all the assemblies of the domain.
 *
 * @param targetName name of the image, with its extension, e.g. @c "Assembly-CSharp.dll"
 * @return the image, or @c nullptr if no loaded assembly has this name
 */
typedef const Il2CppImage* (*il2cpp_find_image_t)(const char* targetName);

/**
 * @brief Creates a managed C# string from a C string. Field: Il2CppAPI::string_new.
 *
 * Used to pass a @c string argument to a C# method through Il2CppAPI::runtime_invoke.
 *
 * @param str null-terminated C string, encoded in UTF-8
 * @return the new managed string (owned by the garbage collector of the app: never freed by hand)
 */
typedef Il2CppString* (*il2cpp_string_new_t)(const char* str);

/**
 * @brief Returns the class of an object. Field: Il2CppAPI::object_get_class.
 *
 * @param obj the managed object (an @c Il2CppObject*)
 * @return the class the object is an instance of
 */
typedef Il2CppClass* (*il2cpp_object_get_class_t)(void* obj);

/**
 * @brief Finds a class from its namespace and name in an image. Field: Il2CppAPI::class_from_name.
 *
 * @param image     image in which to search (see Il2CppAPI::find_image)
 * @param namespaze namespace of the class, e.g. @c "UnityEngine", or @c "" for the global namespace
 *                  (spelt @c namespaze because @c namespace is a C++ keyword)
 * @param name      name of the class, without its namespace, e.g. @c "Camera"
 * @return the class, or @c nullptr if it does not exist in this image
 */
typedef Il2CppClass* (*il2cpp_class_from_name_t)(
    const Il2CppImage* image,
    const char* namespaze,
    const char* name
);

/**
 * @brief Checks whether a class derives from another one. Field: Il2CppAPI::class_is_assignable_from.
 *
 * Same meaning as @c klass.IsAssignableFrom(oklass) in C#: @c true if an instance of @p oklass can be stored
 * in a variable of type @p klass, i.e. if @p oklass is @p klass, inherits from it or implements it.
 *
 * @param klass  the parent class or interface
 * @param oklass the class to test
 * @return @c true if @p oklass is, derives from, or implements @p klass
 */
typedef bool (*il2cpp_class_is_assignable_from_t)(
    const Il2CppClass* klass,
    const Il2CppClass* oklass
);

/**
 * @brief Finds a method of a class from its name. Field: Il2CppAPI::class_get_method_from_name.
 *
 * The parent classes are searched too. If several overloads have the same name and the same number of
 * parameters, the first one found is returned.
 *
 * @param klass     class in which to search
 * @param name      name of the method, e.g. @c "get_name" for the getter of a C# property @c name
 * @param argsCount number of parameters of the method, or @c -1 to accept any number
 * @return the method, or @c nullptr if it is not found
 */
typedef MethodInfo* (*il2cpp_class_get_method_from_name_t)(
    Il2CppClass* klass,
    const char* name,
    int argsCount
);

/**
 * @brief Calls a C# method. Field: Il2CppAPI::runtime_invoke.
 *
 * @param method the method to call (see Il2CppAPI::class_get_method_from_name)
 * @param obj    the object on which the method is called, or @c nullptr for a static method
 * @param params array of pointers to the arguments, in order, or @c nullptr if there is none:
 *               - value type (@c int, @c float, @c bool, struct...): pointer to the value;
 *               - reference type (object, @c string...): the object pointer itself.
 * @param exc    [out] receives the C# exception if the method throws one, or @c nullptr otherwise.
 *               If @p exc itself is @c nullptr, an exception is not caught and can crash the app.
 * @return the return value: an object for a reference type, a @b boxed value for a value type
 *         (see Il2CppAPI::object_unbox), or @c nullptr for a @c void method
 */
typedef Il2CppObject* (*il2cpp_runtime_invoke_t)(
    const MethodInfo* method,
    void* obj,
    void** params,
    Il2CppException** exc
);

/**
 * @brief Gives access to the value stored in a boxed value type. Field: Il2CppAPI::object_unbox.
 *
 * Example, for a method that returns an @c int:
 * @code
 * Il2CppObject* boxed = g_il2cpp.runtime_invoke(method, obj, nullptr, nullptr);
 * int value = *(int*) g_il2cpp.object_unbox(boxed);
 * @endcode
 *
 * @param obj the boxed value
 * @return pointer to the value, right after the object header (no copy is made)
 */
typedef void* (*il2cpp_object_unbox_t)(Il2CppObject* obj);

/**
 * @brief Returns the application domain of the app. Field: Il2CppAPI::domain_get.
 * @return the domain, which contains all the loaded assemblies
 */
typedef Il2CppDomain* (*il2cpp_domain_get_t)();

/**
 * @brief Lists the assemblies loaded in a domain. Field: Il2CppAPI::domain_get_assemblies.
 *
 * @param domain the domain (see Il2CppAPI::domain_get)
 * @param size   [out] receives the number of assemblies
 * @return array of @p size assemblies, owned by the runtime (never freed by hand)
 */
typedef const Il2CppAssembly** (*il2cpp_domain_get_assemblies_t)(
    const Il2CppDomain* domain,
    size_t* size
);

/**
 * @brief Returns the image of an assembly, which contains its classes. Field: Il2CppAPI::assembly_get_image.
 * @param assembly the assembly
 * @return its image
 */
typedef const Il2CppImage* (*il2cpp_assembly_get_image_t)(
    const Il2CppAssembly* assembly
);

/**
 * @brief Returns the name of an image. Field: Il2CppAPI::image_get_name.
 * @param image the image
 * @return its name, with its extension, e.g. @c "Assembly-CSharp.dll"
 */
typedef const char* (*il2cpp_image_get_name_t)(
    const Il2CppImage* image
);

/**
 * @brief Returns the namespace of a class. Field: Il2CppAPI::class_get_namespace.
 * @param klass the class
 * @return its namespace, e.g. @c "UnityEngine", or @c "" for the global namespace
 */
typedef const char* (*il2cpp_class_get_namespace_t)(
    const Il2CppClass* klass
);


// ##############################
// ########## API ##########
// ##############################

/**
 * @brief Table of the IL2CPP functions used by the mod.
 *
 * Filled once by il2cpp_sdk_init(); every field is then a pointer to the function of the same name in
 * @c libil2cpp.so (see the typedef of each field for its parameters). All fields are @c nullptr before
 * the initialisation.
 */
struct Il2CppAPI {
    il2cpp_find_image_t find_image;                                 ///< See il2cpp_find_image_t
    il2cpp_string_new_t string_new;                                 ///< See il2cpp_string_new_t
    il2cpp_object_get_class_t object_get_class;                     ///< See il2cpp_object_get_class_t
    il2cpp_class_from_name_t class_from_name;                       ///< See il2cpp_class_from_name_t
    il2cpp_class_is_assignable_from_t class_is_assignable_from;     ///< See il2cpp_class_is_assignable_from_t
    il2cpp_class_get_method_from_name_t class_get_method_from_name; ///< See il2cpp_class_get_method_from_name_t
    il2cpp_runtime_invoke_t runtime_invoke;                         ///< See il2cpp_runtime_invoke_t
    il2cpp_object_unbox_t object_unbox;                             ///< See il2cpp_object_unbox_t
    il2cpp_domain_get_t domain_get;                                 ///< See il2cpp_domain_get_t
    il2cpp_domain_get_assemblies_t domain_get_assemblies;           ///< See il2cpp_domain_get_assemblies_t
    il2cpp_assembly_get_image_t assembly_get_image;                 ///< See il2cpp_assembly_get_image_t
    il2cpp_image_get_name_t image_get_name;                         ///< See il2cpp_image_get_name_t
    il2cpp_class_get_namespace_t class_get_namespace;               ///< See il2cpp_class_get_namespace_t
};

/** @brief Global table of the IL2CPP functions, filled by il2cpp_sdk_init(). */
extern Il2CppAPI g_il2cpp;

/**
 * @brief Initialises the SDK: resolves every IL2CPP function needed and stores it in #g_il2cpp.
 *
 * Must be called once, after @c libil2cpp.so has been loaded by the app, and before any use of
 * #g_il2cpp.
 *
 * @param il2cpp_handle handle of @c libil2cpp.so, as returned by @c dlopen (this function does not load
 *                      the library itself)
 * @return @c true if every function was found; @c false if the handle is null or a symbol is missing
 *         (the reason is written to Logcat, and #g_il2cpp must then not be used)
 */
bool il2cpp_sdk_init(void* il2cpp_handle);
