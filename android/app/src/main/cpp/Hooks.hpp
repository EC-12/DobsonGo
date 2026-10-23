// DO NOT add this file to the separately compiled sources in CMakeLists: it is only meant to be #included
#pragma once

#include <cstdint>
#include "Il2cppSdk.h"
#include "shadowhook.h"

/**
 * @brief Hooks of the host app that send the object selected by the user to the Java menu.
 *
 * The app is a Unity game compiled with IL2CPP: its C# methods are native functions of libil2cpp.so.
 * MenuHandler uses ShadowHook to replace two of them with its own functions ("proxies"):
 * - <b>SelectionScript.SetSelectedObject</b>, called each time the user selects an object in the sky, or clears
 *   the selection: the proxy reads the object and calls AppMenuService.onObjectSelected() (Java);
 * - <b>the SkyObjectsInfoProvider constructor</b>: the proxy keeps the instance created by the app, to read the
 *   titles of the objects later.
 *
 * Other methods of the app (getters of SkyObject, StaticObject, Satellite...) are called directly through function
 * pointers, without being hooked.
 *
 * Everything is static: install() must be called once, with the load address of libil2cpp.so.
 */
struct MenuHandler {
    // ═══════════════════════════════════════════ STATE ════════════════════════════════════════════ //
    /** Name of this proxy, sent with each error (first argument of AppMenuService.reportSelectionError()). */
    static inline const char* struct_id = "MenuHandler proxy";
    /** Java VM of the app, set by install(): used by the hooks to get the JNI environment. */
    static inline JavaVM* _vm = nullptr;
    /** IL2CPP image of Sky.dll looked up at the first selection. nullptr until then. */
    static inline const Il2CppImage* skyImage = nullptr;
    /**
     * @brief Last SkyObjectsInfoProvider instance created by the app, captured by proxy_SkyObjectsInfoProvider().
     *
     * Used to read the titles of the objects. nullptr until the app creates one: the internal names are then used instead.
     */
    static inline const void* infoProviderInstance = nullptr;
    /** true once both hooks have been installed by install(). */
    static inline bool initialized = false;

    // ══════════════════════════════ SIGNATURES OF THE IL2CPP METHODS ══════════════════════════════ //
    // Every C# method compiled by IL2CPP becomes a C function that receives the instance first (thisPtr)
    // and its MethodInfo last (IL2CPP metadata of the method; nullptr when the method is called directly).
    /**
     * @brief SetSelectedObject: selects an object in the app.
     *
     * Called by the app each time the user selects an object, or clears the selection.
     * @param thisPtr               the SelectionScript instance
     * @param searchSelectionObject the selection (a SearchSelectionObject, which contains the SkyObject),
     *                              or nullptr when the selection is cleared
     * @param methodInfo            IL2CPP metadata of the method
     * @return the value returned by the original method (not used)
     */
    typedef void* (*SetSelectedObject_t)(
        void* thisPtr,
        void* searchSelectionObject,
        void* methodInfo
    );

    /**
     * @brief Constructor of SkyObjectsInfoProvider, the class that gives the titles of the objects.
     * @param thisPtr    the instance being built
     * @param methodInfo IL2CPP metadata of the method
     */
    typedef void (*SkyObjectsInfoProvider_t)(
        void* thisPtr,
        void* methodInfo
    );

    /**
     * @brief SkyObjectsInfoProvider.GetEnglishTitle: English title of an object.
     * @param thisPtr           the SkyObjectsInfoProvider instance
     * @param tupleElementNames key (type, id) of the object, packed by makeTuple()
     * @param methodInfo        IL2CPP metadata of the method
     * @return the title, or nullptr if the object has none
     */
    typedef Il2CppString* (*GetEnglishTitle_t)(const void* thisPtr, uint64_t tupleElementNames, void* methodInfo);
    /**
     * @brief SkyObjectsInfoProvider.GetTitle: title of an object, in the language of the app.
     * @param thisPtr           the SkyObjectsInfoProvider instance
     * @param tupleElementNames key (type, id) of the object, packed by makeTuple()
     * @param methodInfo        IL2CPP metadata of the method
     * @return the title, or nullptr if the object has none
     */
    typedef Il2CppString* (*GetTitle_t)(const void* thisPtr, uint64_t tupleElementNames, void* methodInfo);
    /** SkyObject.get_name: internal name of the object. */
    typedef Il2CppString* (*get_name_t)(void* thisPtr, void* methodInfo);
    /** SkyObject.get_id: identifier of the object in the app. */
    typedef int32_t (*get_id_t)(void* thisPtr, void* methodInfo);
    /** SolarObject.get_IsMoon: true if the object is a natural satellite of a planet. */
    typedef bool (*get_IsMoon_t)(void* thisPtr, void* methodInfo);
    /** StaticObject.get_ra: right ascension, in hours. */
    typedef float (*get_ra_t)(Il2CppObject* thisPtr, void* methodInfo);
    /** StaticObject.get_dec: declination, in degrees. */
    typedef float (*get_dec_t)(Il2CppObject* thisPtr, void* methodInfo);
    /** Getter of the apparent magnitude of a fixed object. */
    typedef float (*get_magnitude_t)(Il2CppObject* thisPtr, void* methodInfo);
    /** Satellite.get_NoradId: NORAD catalogue number of the satellite. */
    typedef int (*get_NoradId_t)(Il2CppObject* thisPtr, void* methodInfo);
    /** Satellite.get_Tle1: first line of the TLE of the satellite. */
    typedef Il2CppString* (*get_Tle1_t)(Il2CppObject* thisPtr, void* methodInfo);
    /** Satellite.get_Tle2: second line of the TLE of the satellite. */
    typedef Il2CppString* (*get_Tle2_t)(Il2CppObject* thisPtr, void* methodInfo);

    // ═════════════════════════════ POINTERS TO THE METHODS OF THE APP ═════════════════════════════ //
    /**
     * @brief Original SelectionScript.SetSelectedObject.
     *
     * After install(), ShadowHook replaces this pointer with a trampoline that runs the original method: the address
     * itself now leads to proxy_SetSelectedObject().
     */
    static inline SetSelectedObject_t set_selected_object = nullptr;
    //static inline InfoViewState_t info_view_state = nullptr;
    //static inline Close_t close = nullptr;
    /** Original SkyObjectsInfoProvider constructor (trampoline set by ShadowHook, like set_selected_object). */
    static inline SkyObjectsInfoProvider_t sky_objects_info_provider = nullptr;
    /** SkyObjectsInfoProvider.GetEnglishTitle (called directly). */
    static inline GetEnglishTitle_t get_english_title = nullptr;
    /** SkyObjectsInfoProvider.GetTitle (called directly). */
    static inline GetTitle_t get_title = nullptr;
    /** SkyObject.get_name (called directly). */
    static inline get_name_t get_name = nullptr;
    /** SkyObject.get_id (called directly). */
    static inline get_id_t get_id = nullptr;
    /** SolarObject.get_IsMoon (called directly). */
    static inline get_IsMoon_t get_is_moon = nullptr;
    /** StaticObject.get_ra (called directly). */
    static inline get_ra_t get_ra = nullptr;
    /** StaticObject.get_dec (called directly). */
    static inline get_dec_t get_dec = nullptr;
    /**
     * @brief Getter of the magnitude (called directly).
     * @warning Not set by install() at the moment: it is still nullptr.
     */
    static inline get_magnitude_t get_magnitude = nullptr;
    /** Satellite.get_NoradId (called directly). */
    static inline get_NoradId_t get_norad_id = nullptr;
    /** Satellite.get_Tle1 (called directly). */
    static inline get_Tle1_t get_tle_1 = nullptr;
    /** Satellite.get_Tle2 (called directly). */
    static inline get_Tle2_t get_tle_2 = nullptr;

    // ══════════════════════════════════ ADDRESSES OF THE METHODS ══════════════════════════════════ //
    /**
     * @brief Address in memory of SelectionScript.SetSelectedObject.
     *
     * Like all the rva_* variables, it is computed by install(): load address of libil2cpp.so + RVA of the method.
     * Despite their name, these variables therefore hold absolute addresses. The RVA (offset of the method in
     * libil2cpp.so) is only valid for one version of the app: it must be updated after each update of the app.
     */
    static inline uintptr_t rva_SetSelectedObject = 0;
    //static inline uintptr_t rva_InfoViewState = 0;
    //static inline uintptr_t rva_Close = 0;
    static inline uintptr_t rva_SkyObjectsInfoProvider = 0;
    static inline uintptr_t rva_GetEnglishTitle = 0;
    static inline uintptr_t rva_GetTitle = 0;
    static inline uintptr_t rva_get_name = 0;
    static inline uintptr_t rva_get_id = 0;
    static inline uintptr_t rva_get_IsMoon = 0;
    static inline uintptr_t rva_get_ra = 0;
    static inline uintptr_t rva_get_dec = 0;
    /**
     * @brief Address of the magnitude getter.
     * @warning Not set by install() at the moment: it is still 0.
     */
    static inline uintptr_t rva_get_magnitude = 0;
    static inline uintptr_t rva_get_NoradId = 0;
    static inline uintptr_t rva_get_Tle1 = 0;
    static inline uintptr_t rva_get_Tle2 = 0;

    // ══════════════════════════════════════════ HELPERS ═══════════════════════════════════════════ //
    /**
     * @brief Packs the key of an object for SkyObjectsInfoProvider.
     *
     * On the C# side, the key is a tuple (SkyObjectType type, int id): a structure of two 32-bit integers, which is
     * passed by value in a single 64-bit register. It is rebuilt here as a 64-bit integer: type in the low 32 bits,
     * id in the high 32 bits.
     * @param skyType type of the object (value of SkyObjectType)
     * @param id      identifier of the object in the app
     * @return the packed key, to pass to get_title() or get_english_title()
     */
    static inline uint64_t makeTuple(uint32_t skyType, uint32_t id) {
        return ((uint64_t)id << 32) | (uint64_t)skyType;
    }

    /**
     * @brief Finds the title to display for an object.
     *
     * In order: the title given by SkyObjectsInfoProvider (in the language of the app), otherwise the name, otherwise
     * "Inconnu".
     * @param name          name of the object, used if it has no title (can be nullptr)
     * @param tupleElements key (type, id) of the object, built by makeTuple()
     * @return the title, never nullptr
     */
    static inline Il2CppString* titleResolver(Il2CppString* name, uint64_t tupleElements) {
        Il2CppString* title = nullptr;
        if (infoProviderInstance != nullptr) {
            title = get_title(infoProviderInstance, tupleElements, nullptr);
        }
        if (title == nullptr) {
            if (name == nullptr) { title = g_il2cpp.string_new("Inconnu"); }
            else { title = name; }
        }
        return title;
    }

    /**
     * @brief Asks the Java side whether a telescope is connected and has accepted the authentication.
     *
     * Calls the static Java method BLEManager.isServerConnected().
     * @param env JNI environment of the current thread
     * @return true if a trusted telescope is connected
     */
    static inline bool isBleConnected(JNIEnv* env) {
        jclass ble_manager_k = env->FindClass("com/terroroo/dobsongomenu/BLEManager");
        jmethodID is_conn_mid = env->GetStaticMethodID(ble_manager_k, "isServerConnected", "()Z");
        jboolean is_conn = env->CallStaticBooleanMethod(ble_manager_k, is_conn_mid);
        return is_conn == JNI_TRUE;
    }

    /**
     * @brief Types of objects of the app (copy of the C# enum SkyObjectType).
     *
     * The values are sent as is to Java, where RealAstronomy.SkyObjectType uses the same numbers.
     * The Java side only knows the values up to Comet (9): the other ones are treated as None.
     */
    enum SkyObjectType: int32_t {
        None = 0,
        Star,
        Planet,
        Constellation,
        DeepSpace,
        MeteorShower,
        Satellite,
        Asteroid,
        Dwarf,
        Comet,
        Mission,
        UFO,
        SatelliteGroup,
        LastSkyObjectType
    };

    // ═══════════════════════════════════════════ HOOKS ════════════════════════════════════════════ //
    /**
     * @brief Replaces SelectionScript.SetSelectedObject: sends the object selected by the user to the Java menu.
     *
     * The original method is always run first, so the app behaves normally. Then, only if a trusted telescope is
     * connected (isBleConnected()):
     * - <b>no selection</b> (searchSelectionObject is nullptr): calls AppMenuService.onObjectUnselected();
     * - <b>an object is selected</b>: reads the SkyObject contained in the selection, then:
     *   1. reads its type with get_type(), called through IL2CPP (runtime_invoke);
     *   2. reads its identifier, its name (English title if possible, used for Horizons) and its title (displayed);
     *   3. reads the data specific to its kind:
     *      - fixed object (StaticObject): right ascension and declination, converted to radians, and magnitude;
     *      - satellite: NORAD number and the two lines of its TLE;
     *      - other objects (planets, asteroids, comets...): whether it is a moon; the name is required for Horizons;
     *   4. calls AppMenuService.onObjectSelected() with all these values (NaN for the values that do not apply).
     *
     * If a step fails, the error is sent to AppMenuService.reportSelectionError() and nothing is selected.
     * @param thisPtr               the SelectionScript instance
     * @param searchSelectionObject the selection, or nullptr when the selection is cleared
     * @param methodInfo            IL2CPP metadata of the method, passed to the original method
     */
    static void proxy_SetSelectedObject(
        void* thisPtr,
        void* searchSelectionObject,
        void* methodInfo
    ) {
        Logcat("MenuHandler proxy called: ObjectInfoProvider method hooked");
        set_selected_object(thisPtr, searchSelectionObject, methodInfo); // Run the original method first, so that the app keeps working normally

        // Image of Sky.dll, looked up once, at the first selection
        if (skyImage == nullptr) {
            skyImage = g_il2cpp.find_image("Sky.dll");
            Logcat("MenuHandler proxy: Sky.dll image set?");
        }

        // JNI environment of the current thread (the main thread of Unity, already attached to the Java VM)
        JNIEnv* env;
        _vm->GetEnv((void**)&env, JNI_VERSION_1_6);

        // Selections are only sent to Java while a trusted telescope is connected
        if (isBleConnected(env)) {
            jclass service_k = env->FindClass("com/terroroo/dobsongomenu/AppMenuService");
            if (searchSelectionObject == nullptr) {
                // No object: the selection was cleared
                jmethodID unselect_mid = env->GetStaticMethodID(service_k, "onObjectUnselected", "()V");
                env->CallStaticVoidMethod(service_k, unselect_mid);
            } else {
                auto sso_base = (uintptr_t)searchSelectionObject;
                // The SkyObject is at offset 0x10 of the SearchSelectionObject, right after the IL2CPP object header (klass + monitor)
                Il2CppObject* skyObj = *(Il2CppObject**)(sso_base + 0x10);

                // Java callbacks: AppMenuService.reportSelectionError() and AppMenuService.onObjectSelected()
                jmethodID select_error_mid = env->GetStaticMethodID(service_k, "reportSelectionError", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                jmethodID select_mid = env->GetStaticMethodID(service_k, "onObjectSelected", "(ILjava/lang/String;Ljava/lang/String;IZDDDILjava/lang/String;Ljava/lang/String;)V");

                // get_type() is looked up by name on the actual class of the object, then called with runtime_invoke
                MethodInfo* getTypeMethod = g_il2cpp.class_get_method_from_name(skyObj->klass, "get_type", 0);
                if (getTypeMethod == nullptr) {
                    env->CallStaticVoidMethod(
                        service_k,
                        select_error_mid,
                        env->NewStringUTF(struct_id),
                        env->NewStringUTF("get_type method not found on SkyObject instance!"),
                        env->NewStringUTF("La méthode get_type n'existe pas sur l'instance de SkyObject sélectionnée")
                    );
                } else if (skyImage == nullptr) {
                    env->CallStaticVoidMethod(
                        service_k,
                        select_error_mid,
                        env->NewStringUTF(struct_id),
                        env->NewStringUTF("unable to find image Sky.dll"),
                        env->NewStringUTF("L'image Sky.dll n'a pas pu être trouvée")
                    );
                } else {
                    Il2CppException* exc = nullptr;
                    void* get_type_retval = g_il2cpp.runtime_invoke(getTypeMethod, skyObj, nullptr, &exc);
                    if (exc != nullptr) {
                        env->CallStaticVoidMethod(
                            service_k,
                            select_error_mid,
                            env->NewStringUTF(struct_id),
                            env->NewStringUTF("runtime invoke on get_type method raised il2cpp exception!"),
                            env->NewStringUTF("L'invocation de la méthode get_type a échoué au niveau IL2CPP")
                        );
                    } else {
                        // Class of the object, and StaticObject class: used to know whether the object has fixed coordinates
                        Il2CppClass* skyObjKlass = g_il2cpp.object_get_class(skyObj);
                        Il2CppClass* staticObjKlass = g_il2cpp.class_from_name(skyImage, "Sky", "StaticObject");
                        if (skyObjKlass == nullptr) {
                            env->CallStaticVoidMethod(
                                service_k,
                                select_error_mid,
                                env->NewStringUTF(struct_id),
                                env->NewStringUTF("Unable to find the instantiated SkyObject child subclass"),
                                env->NewStringUTF("La classe enfant dérivée de l'instance SkyObject sélectionnée n'a pas pu être trouvée")
                            );
                        } else if (staticObjKlass == nullptr) {
                            env->CallStaticVoidMethod(
                                service_k,
                                select_error_mid,
                                env->NewStringUTF(struct_id),
                                env->NewStringUTF("unable to find StaticObject child subclass for given image and namespace"),
                                env->NewStringUTF("La classe enfant dérivée de StaticObject n'a pas pu être trouvée")
                            );
                        } else {
                            // runtime_invoke returns the enum boxed in an object: unbox it to read its int32 value
                            auto* boxed_enum_skyType = (Il2CppObject*)get_type_retval;
                            void* unboxed_enum_skyType = g_il2cpp.object_unbox(boxed_enum_skyType);

                            int32_t skyType = *(int32_t*)unboxed_enum_skyType;
                            int32_t id = get_id(skyObj, nullptr);
                            // Key (type, id) of the object for SkyObjectsInfoProvider
                            uint64_t tupleElements = makeTuple(skyType, id);
                            // Name sent to Java, used for the Horizons search:
                            // English title, otherwise title in the language of the app, otherwise internal name
                            Il2CppString* name = nullptr;
                            if (infoProviderInstance != nullptr) {
                                name = get_english_title(infoProviderInstance, tupleElements, nullptr);
                                if (name == nullptr) {
                                    name = get_title(infoProviderInstance, tupleElements, nullptr);
                                    if (name == nullptr) {
                                        name = get_name(skyObj, nullptr);
                                    }
                                }
                            } else { name = get_name(skyObj, nullptr); }

                            // Title displayed to the user, in the language of the app
                            Il2CppString* title = titleResolver(name, tupleElements);

                            // Default values: each type of object below only fills the fields it needs (NaN = not available)
                            bool isMoon = false;
                            int noradId = std::numeric_limits<int>::infinity();
                            Il2CppString* tle1 = nullptr;
                            Il2CppString* tle2 = nullptr;
                            double ra = std::numeric_limits<double>::quiet_NaN();
                            double dec = std::numeric_limits<double>::quiet_NaN();
                            double magnitude = std::numeric_limits<double>::quiet_NaN();

                            // true if the object is a StaticObject or one of its subclasses (star, constellation, deep-sky object...)
                            bool isStatic = g_il2cpp.class_is_assignable_from(staticObjKlass, skyObjKlass);
                            if (isStatic) {
                                // Fixed object: equatorial coordinates (used as J2000 coordinates by the Java side) and magnitude
                                float ra_hours = get_ra(skyObj, nullptr);
                                float dec_deg = get_dec(skyObj, nullptr);
                                magnitude = (double)get_magnitude(skyObj, nullptr);
                                // Conversion to radians: the RA is in hours (24 h = 2π), the Dec in degrees
                                ra = (double)ra_hours * M_PI / 12.0;
                                dec = (double)dec_deg * M_PI / 180.0;
                            } else if (skyType == SkyObjectType::Satellite) {
                                // Satellite: NORAD number and TLE, used to compute its orbit
                                noradId = get_norad_id(skyObj, nullptr);
                                tle1 = get_tle_1(skyObj, nullptr);
                                tle2 = get_tle_2(skyObj, nullptr);
                            } else {
                                // Solar system object: its orbit is downloaded from Horizons, which needs its name
                                if (name == nullptr) {
                                    env->CallStaticVoidMethod(
                                        service_k,
                                        select_error_mid,
                                        env->NewStringUTF(struct_id),
                                        env->NewStringUTF("object name is null: Horizons request will fail!"),
                                        env->NewStringUTF("L'objet n'a pas de nom, la requête vers Horizons est donc impossible")
                                    );
                                    return;
                                }
                                isMoon = get_is_moon(skyObj, nullptr);
                            }

                            // Send the object to Java. IL2CPP strings are UTF-16, like Java strings: they are copied as is
                            env->CallStaticVoidMethod(
                                service_k, select_mid,
                                (int)skyType,
                                env->NewString((const jchar*)title->chars, title->length),
                                env->NewString((const jchar*)name->chars, name->length),
                                (int)id, isMoon,
                                ra, dec, magnitude,
                                noradId,
                                tle1 != nullptr ? env->NewString((const jchar*)tle1->chars, tle1->length) : nullptr,
                                tle2 != nullptr ? env->NewString((const jchar*)tle2->chars, tle2->length) : nullptr
                            );
                        }
                    }
                }
            }
        }
    }

    // Former hook of InfoView.Close, disabled
    /*static void proxy_Close(void* thisPtr, void* methodInfo) {
        JNIEnv* env;
        _vm->GetEnv((void**)&env, JNI_VERSION_1_6);

        if (isConnected(env)) {
            jclass menu_k = env->FindClass("com/terroroo/dobsongomenu/AppMenuService");
            jmethodID leftView_mid = env->GetStaticMethodID(menu_k, "leftView", "()V");
            env->CallStaticVoidMethod(menu_k, leftView_mid);
        }

        close(thisPtr, methodInfo);
    }*/

    /**
     * @brief Replaces the SkyObjectsInfoProvider constructor: keeps the instance, then runs the original constructor.
     *
     * The instance is stored in infoProviderInstance, to read the titles of the objects later.
     * @param thisPtr    the instance being built
     * @param methodInfo IL2CPP metadata of the method
     */
    static void proxy_SkyObjectsInfoProvider(void* thisPtr, void* methodInfo) {
        Logcat("MenuHandler proxy called: SkyObjectsInfoProvider method hooked");
        infoProviderInstance = thisPtr;
        sky_objects_info_provider(thisPtr, methodInfo);
    }

    // ════════════════════════════════════════ INSTALLATION ════════════════════════════════════════ //
    /**
     * @brief Computes the addresses of the methods of the app and installs the two hooks.
     *
     * The addresses are the RVA of the methods (offsets in libil2cpp.so) added to the load address of the library.
     * They are only valid for one version of the app. ShadowHook then replaces SetSelectedObject and the
     * SkyObjectsInfoProvider constructor with the proxies, and stores a trampoline to each original method in
     * set_selected_object and sky_objects_info_provider.
     *
     * Calling it again once the hooks are installed only updates the Java VM.
     * @param base load address of libil2cpp.so in memory
     * @param vm   Java VM of the app, used later by the hooks
     * @return true if both hooks are installed
     */
    static bool install(uintptr_t base, JavaVM* vm) {
        _vm = vm;
        if (initialized) { return true; }

        // 1. Absolute addresses of the methods: load address of libil2cpp.so + RVA
        rva_SetSelectedObject = base + 0x3E52C9C;
        rva_SkyObjectsInfoProvider = base + 0x3DE8EE9;
        rva_GetEnglishTitle = base + 0x3DE9C90;
        rva_GetTitle = base + 0x3DF9940;
        rva_get_name = base + 0x3E1E024;
        rva_get_id = base + 0x3E1F024;
        rva_get_IsMoon = base + 0x3E2BCE0;
        rva_get_ra = base + 0x3E525A2;
        rva_get_dec = base + 0x3E526BE;
        rva_get_NoradId = base + 0x3E161FC;
        rva_get_Tle1 = base + 0x3E161EC;
        rva_get_Tle2 = base + 0x421E468;

        // 2. Function pointers (the two hooked methods are replaced by trampolines below)
        set_selected_object = (SetSelectedObject_t)rva_SetSelectedObject;
        //info_view_state = (InfoViewState_t)rva_InfoViewState;
        //close = (Close_t)rva_Close;
        sky_objects_info_provider = (SkyObjectsInfoProvider_t)rva_SkyObjectsInfoProvider;
        get_english_title = (GetEnglishTitle_t)rva_GetEnglishTitle;
        get_title = (GetTitle_t)rva_GetTitle;
        get_name = (get_name_t)rva_get_name;
        get_id = (get_id_t)rva_get_id;
        get_is_moon = (get_IsMoon_t)rva_get_IsMoon;
        get_ra = (get_ra_t)rva_get_ra;
        get_dec = (get_dec_t)rva_get_dec;
        get_norad_id = (get_NoradId_t)rva_get_NoradId;
        get_tle_1 = (get_Tle1_t)rva_get_Tle1;
        get_tle_2 = (get_Tle2_t)rva_get_Tle2;

        // 3. Hooks: the app now calls the proxies, and the pointers above lead to the original methods
        void* hook = shadowhook_hook_func_addr(
            (void*)rva_SetSelectedObject,
            (void*)proxy_SetSelectedObject,
            (void**)&set_selected_object
        );

        void* hook1 = shadowhook_hook_func_addr(
            (void*)rva_SkyObjectsInfoProvider,
            (void*)proxy_SkyObjectsInfoProvider,
            (void**)&sky_objects_info_provider
        );

        initialized = hook != nullptr
            && hook1 != nullptr;
        return initialized;
    }
};
