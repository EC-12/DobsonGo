#pragma once

#include <android/log.h>

#define Logcat(...) __android_log_print(ANDROID_LOG_INFO,  "_TERROROO_LOG", ##__VA_ARGS__)
#define LogcatE(...) __android_log_print(ANDROID_LOG_ERROR, "_TERROROO_LOG", ##__VA_ARGS__)