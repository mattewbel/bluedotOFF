#pragma once
// AOSP pairing checks throw a bounded error at the JNI boundary; no secret logs or aborts.
#include <stdexcept>
struct AdbCheck {
    bool ok;
    ~AdbCheck() noexcept(false) { if (!ok) throw std::runtime_error("Pairing crypto failed"); }
    template<class T> AdbCheck& operator<<(const T&) { return *this; }
};
struct AdbNoLog { template<class T> AdbNoLog& operator<<(const T&) { return *this; } };
#define CHECK(x) AdbCheck{bool(x)}
#define CHECK_EQ(a,b) CHECK((a)==(b))
#define CHECK_NE(a,b) CHECK((a)!=(b))
#define CHECK_GT(a,b) CHECK((a)>(b))
#define CHECK_GE(a,b) CHECK((a)>=(b))
#define CHECK_LT(a,b) CHECK((a)<(b))
#define CHECK_LE(a,b) CHECK((a)<=(b))
#define LOG(level) AdbNoLog{}
