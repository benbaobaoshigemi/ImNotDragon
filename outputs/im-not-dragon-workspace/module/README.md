# I’m not dragon (Android LSPosed Module)

面向 **Via (`mark.via`)**、**Chrome (`com.android.chrome`)** 与 **Claude Android (`com.anthropic.claude`)** 的轻量级网络防泄漏与区域环境隔离模块。无侵入式设计，不修改系统全局设置，自动适配任意代理软件（Clash、Sing-box、v2rayNG、NekoBox、Surfboard 等）的 **TUN 模式** 与 **HTTP 代理模式**。

---

## 核心特性

### 1. 网络层防泄漏 (`libdragon_network.so`)
- **UDP / WebRTC 物理阻断**：在 `libc.so` 层拦截目标进程的 `AF_INET` / `AF_INET6` 非流式套接字（`SOCK_DGRAM`）创建与外部接口 `bind()`，彻底阻断 WebRTC STUN 穿透及 Chromium 内核通过 UDP 套接字探测内核路由表泄露本地公网 IPv6（如运营商/教育网原生 IPv6）的通道。
- **原生公网 IPv6 防旁路**：拦截非回环、非 IPv4-mapped（`::ffff:x.x.x.x`）的原生 IPv6 直连，强制双栈连接回退至 IPv4，防止在仅接管 IPv4 路由的 TUN 虚拟网卡下发生 IPv6 流量旁路泄漏。
- **代理与 TUN 自适应**：
  - 自动通过 `ConnectivityManager` 与系统属性感知当前活动的本地 HTTP 代理端口（兼容任意端口及 IPv4-mapped 回环地址 `[::ffff:127.0.0.1]`）。
  - 在纯 TUN 模式下自动放行经由 `tun0` 路由的流量，无需硬编码特定代理软件端口。

### 2. 区域与设备环境对齐 (`ViaHook`)
- **时区与语言同步**：将目标进程内的 Java `TimeZone`、ICU `TimeZone`、环境变量 `TZ`、Chromium `TimeZoneMonitor` 以及 `Locale` / `navigator.language(s)` 统一对齐至出口节点所在时区与 `en-US`。
- **运营商与网卡特征隔离**：
  - 根据出口节点所属国家（支持 `US`、`JP`、`SG`、`GB`、`DE`、`FR`、`NL`、`KR`、`TW`、`AU`、`CA` 等）动态伪装 `TelephonyManager` 运营商代码（MCC/MNC）与 ISO 国家码。
  - 针对风控遥测 SDK（如 Sift）净化 `NetworkInterface.getNetworkInterfaces()` 枚举结果，隐藏 `tun0`、`wlan0`、`rmnet_data` 等敏感接口特征。
- **虚拟定位与 UA 降噪**：拦截 WebView 真实硬件定位请求，注入与代理出口城市匹配的近似坐标，并对 User-Agent 进行标准化降噪（Reduced UA）。

---

## 目录结构

```text
module/
├── app/
│   ├── libs/
│   │   └── xposed-api-82.jar                  # Xposed 编译期 API (compileOnly)
│   ├── src/main/
│   │   ├── AndroidManifest.xml                # 模块声明与作用域配置
│   │   ├── assets/
│   │   │   └── browser.js                     # WebView 文档起始注入脚本 (语言与近似定位)
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt                 # Native C++17 构建配置
│   │   │   └── network.cpp                    # libc socket/connect/bind/getaddrinfo 钩子
│   │   ├── java/io/github/imnotdragon/
│   │   │   ├── MainActivity.java              # 模块配置与节点同步界面
│   │   │   ├── Profile.java                   # 配置存储与时区国家映射模型
│   │   │   └── ViaHook.java                   # Xposed 入口与各应用环境隔离逻辑
│   │   └── res/values/
│   │       └── arrays.xml                     # 默认作用域列表 (Via / Chrome / Claude)
│   └── build.gradle
├── build.gradle
├── gradle.properties
└── settings.gradle
```

---

## 使用说明

1. 安装 APK 并在 **LSPosed** 中启用本模块，勾选需要保护的目标应用（`Via`、`Chrome`、`Claude`）。
2. 打开模块主界面：
   - **环境与防泄漏保护**：默认开启（阻断 UDP 路由探测、拦截原生 IPv6 旁路、同步时区/语言/运营商/网卡隔离）。
   - **严格代理与 DNS 隔离**：可选开启（强制浏览器接管本地 HTTP 代理隧道并阻断本地域名解析）。
3. 开启 VPN / 代理软件后，点击 **「同步当前出口」** 即可自动获取当前节点的出口 IP、国家代码、IANA 时区与城市级近似坐标，点击 **「保存配置」** 后强行停止并重启目标应用即可生效。

---

## 构建指南

- **环境要求**：JDK 17、Android SDK 35、NDK `26.1.10909125`、CMake `3.22.1`、Gradle `8.6`。
- **编译命令**：
  ```bash
  gradle :app:assembleDebug
  ```
- 生成产物位于 `app/build/outputs/apk/debug/app-debug.apk`（目标架构 `arm64-v8a`）。
