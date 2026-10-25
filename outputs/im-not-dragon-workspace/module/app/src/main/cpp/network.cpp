#include <android/log.h>
#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <netdb.h>
#include <sys/socket.h>

using HookFunction = int (*)(void *, void *, void **);
using LibraryLoaded = void (*)(const char *, void *);
struct NativeAPIEntries {
    uint32_t version;
    HookFunction hook_func;
    int (*unhook_func)(void *);
};

static std::atomic<uint16_t> proxy_port{0};
static std::atomic<bool> strict_proxy_enabled{true};
static int hook_error = -1;

static int (*original_socket)(int, int, int) = nullptr;
static int (*original_connect)(int, const sockaddr *, socklen_t) = nullptr;
static int (*original_bind)(int, const sockaddr *, socklen_t) = nullptr;
static int (*original_getaddrinfo)(const char *, const char *, const addrinfo *, addrinfo **) = nullptr;
static int (*original_android_getaddrinfofornet)(const char *, const char *, const addrinfo *, unsigned, unsigned, addrinfo **) = nullptr;

static FILE *net_log_file = nullptr;
static std::mutex log_mutex;

static void write_net_log(const char *fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    __android_log_print(ANDROID_LOG_INFO, "ImNotDragon-Net", "%s", buf);

    std::lock_guard<std::mutex> lock(log_mutex);
    if (net_log_file) {
        time_t now = time(nullptr);
        struct tm tm_info;
        localtime_r(&now, &tm_info);
        char time_str[32];
        strftime(time_str, sizeof(time_str), "%Y-%m-%d %H:%M:%S", &tm_info);
        fprintf(net_log_file, "[%s] %s\n", time_str, buf);
        fflush(net_log_file);
    }
}

static void format_sockaddr(const sockaddr *address, socklen_t length, char *out, size_t out_len) {
    if (!address || length < sizeof(sa_family_t) || out_len == 0) {
        snprintf(out, out_len, "<invalid>");
        return;
    }
    if (address->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
        const auto *sin = reinterpret_cast<const sockaddr_in *>(address);
        char ip[INET_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof(ip));
        snprintf(out, out_len, "%s:%u", ip, ntohs(sin->sin_port));
    } else if (address->sa_family == AF_INET6 && length >= sizeof(sockaddr_in6)) {
        const auto *sin6 = reinterpret_cast<const sockaddr_in6 *>(address);
        char ip[INET6_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET6, &sin6->sin6_addr, ip, sizeof(ip));
        snprintf(out, out_len, "[%s]:%u", ip, ntohs(sin6->sin6_port));
    } else {
        snprintf(out, out_len, "family_%d", address->sa_family);
    }
}

static bool internet(const sockaddr *address, socklen_t length) {
    return address && length >= sizeof(sa_family_t) &&
        (address->sa_family == AF_INET || address->sa_family == AF_INET6);
}

static bool is_loopback(const sockaddr *address, socklen_t length) {
    if (!address || length < sizeof(sa_family_t)) return false;
    if (address->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
        const auto *a = reinterpret_cast<const sockaddr_in *>(address);
        return (ntohl(a->sin_addr.s_addr) >> 24) == 127;
    }
    if (address->sa_family == AF_INET6 && length >= sizeof(sockaddr_in6)) {
        const auto *a = reinterpret_cast<const sockaddr_in6 *>(address);
        return IN6_IS_ADDR_LOOPBACK(&a->sin6_addr) ||
            (IN6_IS_ADDR_V4MAPPED(&a->sin6_addr) && a->sin6_addr.s6_addr[12] == 127);
    }
    return false;
}

static bool is_native_ipv6(const sockaddr *address, socklen_t length) {
    if (!address || length < sizeof(sockaddr_in6) || address->sa_family != AF_INET6) return false;
    const auto *a = reinterpret_cast<const sockaddr_in6 *>(address);
    return !IN6_IS_ADDR_LOOPBACK(&a->sin6_addr) && !IN6_IS_ADDR_V4MAPPED(&a->sin6_addr);
}

static bool local_or_numeric(const char *name) {
    if (!name || !strcmp(name, "localhost")) return true;
    in_addr v4{};
    in6_addr v6{};
    return inet_pton(AF_INET, name, &v4) == 1 || inet_pton(AF_INET6, name, &v6) == 1;
}

// Always-on core protection: block non-stream (UDP/datagram) sockets to prevent
// WebRTC STUN traversal and kernel routing-table local IPv6 probing.
static int restricted_socket(int domain, int type, int protocol) {
    if ((domain == AF_INET || domain == AF_INET6) && (type & 0xf) != SOCK_STREAM) {
        write_net_log("[CORE-BLOCK] socket: UDP/datagram blocked (domain=%d, type=0x%x) -> EACCES", domain, type);
        errno = EACCES;
        return -1;
    }
    return original_socket(domain, type, protocol);
}

static int restricted_connect(int fd, const sockaddr *address, socklen_t length) {
    char addr_str[128] = {0};
    format_sockaddr(address, length, addr_str, sizeof(addr_str));

    // Always-on core protection: block native global IPv6 connections so dual-stack
    // clients fall back to IPv4 (preventing IPv6 bypass when VPN only routes IPv4).
    if (is_native_ipv6(address, length)) {
        write_net_log("[CORE-BLOCK] connect: native IPv6 blocked to %s -> EACCES", addr_str);
        errno = EACCES;
        return -1;
    }

    if (!strict_proxy_enabled.load() || proxy_port.load() == 0) {
        if (is_loopback(address, length)) {
            write_net_log("[CONN-PROXY] connect: local proxy connection allowed to %s", addr_str);
        }
        return original_connect(fd, address, length);
    }

    if (internet(address, length) && !is_loopback(address, length)) {
        write_net_log("[CONN-BLOCK] connect: non-proxy direct connection blocked to %s -> EACCES", addr_str);
        errno = EACCES;
        return -1;
    }
    if (is_loopback(address, length)) {
        write_net_log("[CONN-PROXY] connect: proxy tunnel connection allowed to %s", addr_str);
    }
    return original_connect(fd, address, length);
}

// Always-on core protection: prevent ICE-TCP from binding a listening socket on non-loopback interfaces.
static int restricted_bind(int fd, const sockaddr *address, socklen_t length) {
    if (internet(address, length) && !is_loopback(address, length)) {
        char addr_str[128] = {0};
        format_sockaddr(address, length, addr_str, sizeof(addr_str));
        write_net_log("[CORE-BLOCK] bind: non-loopback bind blocked (%s) -> EACCES", addr_str);
        errno = EACCES;
        return -1;
    }
    return original_bind(fd, address, length);
}

static int restricted_getaddrinfo(const char *name, const char *service,
        const addrinfo *hints, addrinfo **result) {
    const char *domain = name ? name : "<null>";
    if (!strict_proxy_enabled.load() || proxy_port.load() == 0) {
        return original_getaddrinfo(name, service, hints, result);
    }
    if (!local_or_numeric(name)) {
        write_net_log("[DNS-BLOCK] getaddrinfo: blocked local resolution '%s' -> EAI_NONAME", domain);
        *result = nullptr;
        return EAI_NONAME;
    }
    return original_getaddrinfo(name, service, hints, result);
}

static int restricted_android_getaddrinfofornet(const char *name, const char *service,
        const addrinfo *hints, unsigned netid, unsigned mark, addrinfo **result) {
    const char *domain = name ? name : "<null>";
    if (!strict_proxy_enabled.load() || proxy_port.load() == 0) {
        return original_android_getaddrinfofornet(name, service, hints, netid, mark, result);
    }
    if (!local_or_numeric(name)) {
        write_net_log("[DNS-BLOCK] android_getaddrinfofornet: blocked local resolution '%s' (netid=%u) -> EAI_NONAME", domain, netid);
        *result = nullptr;
        return EAI_NONAME;
    }
    return original_android_getaddrinfofornet(name, service, hints, netid, mark, result);
}

extern "C" [[gnu::visibility("default"), gnu::used]]
LibraryLoaded native_init(const NativeAPIEntries *api) {
    void *libc = dlopen("libc.so", RTLD_NOW);
    hook_error = 0;
    const struct { const char *name; void *replacement; void **original; } hooks[] = {
        {"socket", reinterpret_cast<void *>(restricted_socket), reinterpret_cast<void **>(&original_socket)},
        {"connect", reinterpret_cast<void *>(restricted_connect), reinterpret_cast<void **>(&original_connect)},
        {"bind", reinterpret_cast<void *>(restricted_bind), reinterpret_cast<void **>(&original_bind)},
        {"getaddrinfo", reinterpret_cast<void *>(restricted_getaddrinfo), reinterpret_cast<void **>(&original_getaddrinfo)},
        {"android_getaddrinfofornet", reinterpret_cast<void *>(restricted_android_getaddrinfofornet), reinterpret_cast<void **>(&original_android_getaddrinfofornet)},
    };
    for (const auto &hook : hooks) {
        void *target = dlsym(libc, hook.name);
        int error = target ? api->hook_func(target, hook.replacement, hook.original) : -1;
        if (error) {
            hook_error = error;
            __android_log_print(ANDROID_LOG_ERROR, "ImNotDragon", "Cannot hook %s: %d", hook.name, error);
            break;
        }
    }
    dlclose(libc);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_imnotdragon_ViaHook_configureNetwork(JNIEnv *env, jclass, jint port,
                                                   jboolean enable_strict_proxy,
                                                   jstring log_path) {
    if (hook_error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native network hooks could not be installed");
        return;
    }
    proxy_port.store(static_cast<uint16_t>(port));
    strict_proxy_enabled.store(enable_strict_proxy);

    if (log_path != nullptr) {
        const char *c_path = env->GetStringUTFChars(log_path, nullptr);
        if (c_path) {
            std::lock_guard<std::mutex> lock(log_mutex);
            if (net_log_file) {
                fclose(net_log_file);
                net_log_file = nullptr;
            }
            net_log_file = fopen(c_path, "a");
            env->ReleaseStringUTFChars(log_path, c_path);
        }
    }

    write_net_log("[CONFIG] Core protection ALWAYS-ON (UDP/WebRTC/IPv6 blocked), proxyPort=%d, strictProxy=%s",
                  port, enable_strict_proxy ? "ON" : "OFF");
}
