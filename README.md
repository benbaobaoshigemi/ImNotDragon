# I’m not dragon

跨平台（Android LSPosed 模块 + 桌面端 Chromium 扩展）网络隐私与区域环境配置工具，面向 **Via** 与 **Chrome / Edge** 等使用场景。两端分别实现其平台允许的防护与环境对齐能力，具体范围见各端文档。

---

## 仓库结构

| 目录 | 说明 |
| :--- | :--- |
| [`outputs/im-not-dragon-workspace/module/`](outputs/im-not-dragon-workspace/module/) | **Android LSPosed 模块源码**（Java + Native C++17 `libdragon_network.so`，支持 Via、Chrome、Claude Android 客户端） |
| [`extension/`](extension/) | **Chromium 浏览器扩展 (Manifest V3，v1.1)**（WebRTC 策略、日期与时区、语言、近似定位、UA 降噪、代理配置与出口同步） |
| [`releases/`](releases/) | **预编译 Android APK 发布包**（[`Im-not-dragon-1.7.apk`](releases/Im-not-dragon-1.7.apk)） |

---

## 核心原理解析

客户端风控（如 Sift、Cloudflare Turnstile、Arkose Labs 等）通常通过交叉比对**代理出口 IP 归属地**与**端侧本地环境信号**来识别异常代理流量。本项目针对以下核心泄漏面进行底层闭环防护：

1. **UDP 路由表探测与 WebRTC 本机 IPv6 泄漏**：
   - 即使未调用 STUN 服务器，Chromium / WebRTC 仍可通过创建 `AF_INET6` `SOCK_DGRAM`（UDP）套接字并向公网地址发起 `connect()` 来读取内核路由表分配的源地址。当移动端 VPN 仅接管 IPv4 路由（`0.0.0.0/0 -> tun0`）时，内核会直接暴露 `wlan0` / `rmnet_data` 上的国内原生公网 IPv6 地址（如教育网 `2001:da8::/32` 或三大运营商 `2408::`/`2409::`/`240e::`）。
   - Android 模块在 Native 层（`libc.so`）拦截非流式套接字（`SOCK_DGRAM`）与原生公网 IPv6 直连；浏览器扩展使用 `chrome.privacy.network.webRTCIPHandlingPolicy` 限制非代理 WebRTC UDP，不拦截系统套接字或全局 IPv6。
2. **系统时区、语言与地理位置偏差**：
   - 同步覆盖 Java `TimeZone`、ICU `TimeZone`、POSIX `TZ`、Chromium `TimeZoneMonitor` 以及 JS `Intl.DateTimeFormat` / `Date.prototype.getTimezoneOffset`，确保端侧时区与代理出口 IP 严格一致。
3. **移动端 SIM 运营商与虚拟网卡特征**：
   - 针对 Claude Android 客户端内置的风控遥测，根据出口节点国家动态伪装 `TelephonyManager` 运营商参数（MCC/MNC 与 ISO 国家码），并对遥测调用链隐藏 `tun0` 等虚拟网卡接口。

---

## 快速开始

### Android 端 (LSPosed)
1. 安装 [`releases/Im-not-dragon-1.7.apk`](releases/Im-not-dragon-1.7.apk)，在 LSPosed 管理器中启用模块并勾选 `Via`、`Chrome`、`Claude`。
2. 打开模块界面，点击 **「同步当前出口」** 自动同步当前 VPN/代理节点的时区、国家与经纬度，点击 **「保存配置」**。
3. 强行停止并重启目标应用即可生效。详细构建说明参见 [Android 模块文档](outputs/im-not-dragon-workspace/module/README.md)。

### 桌面端 (Chrome / Edge 扩展)
1. 打开浏览器扩展管理页（`chrome://extensions/` 或 `edge://extensions/`），开启 **「开发者模式」**。
2. 点击 **「加载已解压的扩展程序」**，选择本仓库下的 [`extension/`](extension/) 目录。
3. 点击扩展图标，选择 **「同步当前出口」**，保存后刷新网页。可以独立设置时区语言、定位、UA 降噪及代理接管。详细范围和安装要求参见 [扩展文档](extension/README.md)。

---

## 开源许可

本项目采用 [MIT License](LICENSE) 开源。
