# Claude 桌面版（Windows Electron）风控、遥测与底层架构逆向实证报告

> 发布副本已脱敏账号、设备标识及设备注册记录；原始运行时文件不随仓库发布。

> **报告版本**：v1.0 (Desktop Engineering Audit)  
> **审计时间**：2026-10-25  
> **测试载体**：Windows 11 x64 (Build 26100 / Ultra 9 / RTX 4060 / 32GB RAM)  
> **目标实体**：Claude Desktop 官方 Windows 客户端（MSIX 安装包：`Claude_3.6556.2.0_x64__pzs8sxrjxfjjc`）  
> **核心数据源**：`app.asar` 核心业务与底层通信代码解包逆向、本地 `%APPDATA%\Claude` 运行时数据库与日志、MSIX 容器资源分析。  
> **结论评级标准**：`[已验证]`、`[较可能]`、`[待验证]`。

---

## 一、桌面版架构全貌与多进程模型

Claude 桌面版并不是简单的网页套壳，而是一个由 Anthropic 深度定制、融合了多进程隔离、本地微虚拟化沙箱、原生硬件抽象与高强度密码学绑定的复杂 Electron 混合应用。

```mermaid
flowchart TD
    subgraph Host[Windows 11 操作系统宿主]
        MSIX[MSIX 容器: Claude_3.6556.2.0_x64] --> MainProc[主进程: Electron Main / index.pre.js]
        MainProc --> NativeSvc[原生辅助进程]
        NativeSvc --> CoworkSvc[cowork-svc.exe 协同服务]
        NativeSvc --> SSHBroker[claude-ssh-broker.exe / proxy]
        NativeSvc --> VMSandbox[smol-bin.x64.vhdx 微虚拟机沙箱]
        
        MainProc --> RenderProc[渲染与 Web 视图]
        RenderProc --> MainView[主窗口: WebContentsView (https://claude.ai)]
        RenderProc --> ArtifactView[Cowork Artifacts 隔离视图]
        RenderProc --> QuickView[快捷浮窗 QuickWindow]
        
        MainProc --> LocalStorage[本地安全存储]
        LocalStorage --> AntDid[ant-did: 硬件绑定 UUID]
        LocalStorage --> DeviceReg[ant-device-registry.json: pk1 秘钥对]
        LocalStorage --> ConfigJson[config.json: DPAPI 加密 Token]
    end
    
    subgraph Cloud[Anthropic 远程云端]
        MainView -->|Sift 1x1 GIF / Datadog RUM| CloudAudit[Web 风控遥测]
        MainView -->|注入专属硬件头| CloudApi[api.claude.ai 业务集群]
        MainProc -->|signSessionBind 非对称签名| CloudAuth[Passport / Auth 鉴权中心]
    end
```

---

## 二、设备指纹与非对称密码学生命周期（与移动端对齐）

在上一阶段的 Android 逆向中，我们证实了 Claude 利用 `AndroidKeyStore` 在手机 TEE 硬件中生成密钥对与 `device_id` 强绑定。在 Windows 桌面端，**逆向证实其实现了一套完全等价的密码学设备注册与会话签名体系**。

### 1. `ant-did`（Anthropic Device ID）的生成与约束 `[已验证]`
* **文件位置**：`%APPDATA%\Claude\ant-did`
* **底层机制**：
  在 `index.chunk-CIsKxfLj.js` 中，通过 `HKt()` 函数初始化：
  ```javascript
  let e = path.resolve(app.getPath("userData"), "ant-did");
  // 检查是否为单一普通文件，防符号链接劫持
  let n = fs.lstatSync(e);
  if (!n.isFile() || n.nlink > 1) throw Error("ant-did is not a plain file");
  let t = randomUUID();
  // 写入 Base64 编码的设备 UUID
  ```
* **实测提取**：本机 `ant-did` 内容为 Base64 编码的 UUID；原始编码值和设备 UUID 已脱敏。

### 2. `ant-device-registry.json` 与 `pk1` 公私钥签名体系 `[已验证]`
* **文件位置**：`%APPDATA%\Claude\ant-device-registry.json`
* **结构解析**：
  文件维护了一个本地字典，将用户的 `Account UUID` 映射到带前缀的密钥签名三元组：
  ```json
  {
    "<account-uuid-1>": "pk1:<public-key-hash-1>:<device-token-uuid-1>",
    "<account-uuid>": "pk1:<public-key-hash>:<device-token-uuid>"
  }
  ```
  * `pk1:`：Public Key Format Version 1（基于 Ed25519 或 ECDSA P-256）。
  * 中间段：公钥的 SHA-256 Hex Hash（`createHash("sha256").update(pubKey).digest("hex")`）。
  * 尾段：云端签发的 `device_token_uuid`。

### 3. 全链路会话绑定签名（Session Bind Signing） `[已验证]`
在 `index.chunk-CIsKxfLj.js` 中，桌面端对每一个敏感操作均强制执行客户端本地私钥签名：
* `signSessionBind([accountUuid, orgUuid, sessionUuid, expectedCurrentDeviceId])`
* `signConversationBind([accountUuid, orgUuid, conversationUuid, expectedCurrentDeviceId])`
* `signAttestationPreimage([accountUuid, preimageB64])`
* `signProjectThreadLink(...)` / `signSessionFileCopy(...)`

> [!IMPORTANT]
> **风控致命点**：所有会话建立必须传入 `expectedCurrentDeviceId`（即 `ant-did`），并由本地私钥生成签名。直接从别处搬运登录态 Cookie 或 Session Token 是无效的，云端会验证签名是否由该设备登记的 `pk1` 公钥签署。

---

## 三、专属硬件请求头静默注入与流量伪装

在主进程初始化网络层时，通过 Electron 的 `session.defaultSession.webRequest.onBeforeSendHeaders` 对所有发往 `claude.ai` 和 `api.anthropic.com` 的请求注入了极高密度的客户端硬件指标。

### 1. 静态注入的请求头清单 `[已验证]`
通过解构 `by()` 和 `NKt()` 函数，确认桌面端注入了以下专有标头：

| 请求头名称 | 捕获数值（本机实测） | 来源与物理含义 |
| :--- | :--- | :--- |
| **`anthropic-client-platform`** | `desktop_app` | 告知服务端当前会话来自桌面原生端。 |
| **`anthropic-client-app`** | `com.anthropic.claudefordesktop` | 客户端包名标识。 |
| **`anthropic-client-version`** | `3.6556.2` | 客户端版本号（MSIX App Version）。 |
| **`anthropic-client-device-class`** | `kKt`（根据总内存计算出的分级） | 硬件等级（如高端工作站 / 低功耗设备）。 |
| **`anthropic-client-total-memory-gb`** | `32` | 直接读取物理 RAM（`Math.round(os.totalmem() / 1024**3)`）。 |
| **`anthropic-client-os-platform`** | `win32` | `process.platform`，操作系统平台。 |
| **`anthropic-client-os-version`** | `10.0.26100` | `process.getSystemVersion()`，精确读取 Windows 内部构建号。 |
| **`anthropic-desktop-topbar`** | `1` | 界面集成模式。 |
| **`anthropic-device-id`** | `<redacted-device-id>` | 当前设备的 `ant-did`。 |

> [!WARNING]
> 如果在 Windows 桌面端使用某些插件或工具尝试把 UA 伪装成 macOS 或手机端，但上述底层注入头依然报告 `win32` 和 `10.0.26100`，会立刻触发**协议指纹矛盾（Protocol Mismatch）**。

---

## 四、网络栈、代理配置与 WebRTC 防穿透机制

### 1. `[egress-proxy]` 原生隔离代理接口 `[已验证]`
在 `index.pre.js` 的 `Sie()` 与 `Die()` 函数中，我们发现了桌面端专门留有的代理引脚逻辑：
```javascript
let e = Sie();
for (let [t, n] of Die(e)) app.commandLine.appendSwitch(t, n);
if (e) info(`[egress-proxy] pinned to ${e.kind === "pac" ? "PAC script" : "fixed proxy"} at ${new URL(e.url).host}; OS proxy settings ignored`);
```
* **绕过系统全局代理**：如果用户或企业策略指定了 `egressProxyUrl` 或 `egressProxyPacUrl`，Claude 桌面端将直接把 `--proxy-server` 与 `--proxy-bypass-list=localhost,127.0.0.1,::1,.local` 写入 Chromium 底层启动参数，**完全忽略 Windows 操作系统的系统代理设置（WinINet）**。
* **参数白名单限制**：在 `claude_desktop_config.json` 中，用户自定义的 `chromiumFlags` 受到内部集合 `B7` 的严格校验，**仅允许注入 GPU/渲染相关参数**（如 `disable-gpu`, `use-gl`），任何尝试在配置中塞入 `--proxy-server` 或 `--webrtc-ip-handling-policy` 的行为都会被警告并剥离。

### 2. 桌面端的 WebRTC 泄漏风险与防御实证 `[已验证]`
* **Anthropic 官方内置策略**：
  在 `index.chunk-CIsKxfLj.js` 中，逆向发现 Anthropic 在构建 `WebContentsView` 时，明确对以下视图设置了 WebRTC 策略：
  ```javascript
  t.setWebRTCIPHandlingPolicy("disable_non_proxied_udp");
  ```
  这证明**Anthropic 官方非常清楚 WebRTC 的 UDP 穿透特性会造成 IP 泄露**，因此在 Artifacts、Cowork 等内置视图中默认启用了该策略。
* **潜在风险暴露点**：
  当用户通过标准系统代理（HTTP/SOCKS5）翻墙，而未开启全局虚拟网卡 TUN 模式时，主视图在加载外部认证网页（如 Google OAuth、hCaptcha 交互、Persona KYC）或第三方通信时，如果未被 `disable_non_proxied_udp` 严格覆盖，操作系统仍会通过本地物理网卡发出 STUN UDP 报文，导致中国大陆物理 IP 泄漏。

---

## 五、Web 视图寄生：Sift Science 与 Datadog 依然全速运行

Claude 桌面端的主交互界面本质上是通过 `WebContentsView` 载入了远程的 `https://claude.ai`。这意味着：

1. **Sift Science 脚本完全生效** `[已验证]`：
   Web 端被解剖的 `s-cdn.anthropic.com/s.js`（含 `fingerprintjs2/v3`、`detectIncognito`）依然会在桌面端的渲染进程中下载并执行。
2. **1x1 GIF 隐蔽探针依旧上报** `[已验证]`：
   向 `s-cdn.anthropic.com/images/*.gif` 发起的请求依然携带：
   * `ft=Asia/Shanghai`（读取自 Windows 系统时区设置）
   * `to=-480`（UTC+8 分钟偏置）
   * `l=zh-CN` 或 `zh-Hans`（Windows 系统语言）
   * `fvch`（基于桌面端 DirectX11 / ANGLE / NVIDIA RTX 4060 GPU 计算出的显卡上下文哈希）
   * `fad`（Windows CoreAudio 音频动态合成浮点指纹）
3. **Datadog RUM**：每 30 秒向 `https://claude.ai/api/v2/rum` 持续汇报一次桌面端活跃度指标。

---

## 六、特权能力与沙箱隔离：Computer Use 与 Cowork

相比移动端和纯网页端，桌面版拥有极深度的本地系统控制权限：

1. **`playwright-core 1.57.0` 集成**：内嵌 Playwright 自动化框架，是 Anthropic “Computer Use”（AI 操控电脑桌面）在 Windows 平台的核心驱动引擎。
2. **`node-pty` 原生伪终端**：允许 Claude 直接在后台静默拉起 `cmd.exe` 或 `powershell.exe` 执行命令行任务。
3. **`smol-bin.x64.vhdx` 微型虚拟机**：
   在 `%ProgramFiles%\WindowsApps\...` 目录下内置了 `smol-bin.x64.vhdx` 虚拟磁盘，配合 `cowork-svc.exe` 实现轻量级 Hyper-V 容器沙箱，用于执行不可信的代码和文件转换。
4. **凭据安全**：`config.json` 中的 `oauth:tokenCache` 以 `djEw...`（`v10`）前缀开头，使用 Windows **DPAPI**（`CryptProtectData`）进行非对称密钥加密，防止其他非管理员进程盗取本地 Token。

---

## 七、桌面端 vs 移动端 vs Web 端风控攻防对比矩阵

| 维度 / 特征 | 移动端（Android App） | 纯 Web 端（Chrome/Edge） | 桌面端（Claude Desktop） |
| :--- | :--- | :--- | :--- |
| **运行时基座** | 原生 Android Java/Kotlin + Cronet | 纯浏览器沙箱 | Electron 34+ / Node.js 22+ / Chromium |
| **设备标识** | `device_id` + `AndroidKeyStore` 签名 | Cookie + Sift LocalStorage UUID | `ant-did` + `ant-device-registry` (`pk1` 签名) |
| **通信标头** | 标准移动端 App 标头 | 标准浏览器标头（带 Sec-CH-UA） | 强制静默注入 8 项专属物理硬件标头 |
| **运营商/SIM** | `TelephonyManager` 强制提取 MCC/MNC | 无 | 无（无基站及 SIM 物理层） |
| **网卡与物理连接** | 扫描 `wlan0`, `rmnet0` 物理网卡 | 仅能通过 WebRTC 探测内网 IP | 可直接通过 Node.js `os.networkInterfaces()` 扫描物理 MAC |
| **WebRTC 策略** | 需 C++ libc hook 或命令行参数阻断 | 需扩展或浏览器设置策略拦截 | 部分内部视图默认注入 `disable_non_proxied_udp` |
| **Sift 探针行为** | 混淆类 `Ln87` / `Lb100`（定时任务） | `s.js` 采集并以 1x1 GIF 上报 | 主视图内 `s.js` 采集并以 1x1 GIF 上报 |
| **时区/语言泄露** | 读取 Android `TimeZone.getDefault()` | 读取 JS `Intl.DateTimeFormat` | 同时读取 Windows 系统时区与 JS `Intl` |
| **本地提权能力** | 受 Android 沙箱与权限弹窗限制 | 无（严格沙箱） | 极高（Playwright 操控、ConPTY 终端、VM 沙箱） |

---

## 八、技术结论与对抗策略

1. **桌面端风控重心在于“密码学设备指纹 + 硬件特征注入”**：
   桌面端无需担心 SIM 卡基站锁区，但其通过 `ant-did` 与 `pk1` 非对称签名牢牢锁定了当前 Windows 硬件。
2. **时区与 IP 泄露依然是首要封禁源**：
   虽然桌面版有 `[egress-proxy]` 和部分 WebRTC 防护，但若 Windows 本身处于东八区（`Asia/Shanghai`），通过主视图上报给 Sift 和 Datadog 的时区指标依然为 `to=-480`，与海外出口代理严重冲突。
3. **最严密的桌面环境构建建议**：
   * 将 Windows 系统显示语言设为英文（`en-US`），时区与出口节点一致（如 `Pacific Standard Time`）。
   * 必须使用全局 TUN 虚拟网卡模式（强制所有 UDP/TCP 进代理隧道），或利用启动参数强制指定代理与 WebRTC 策略。
   * 切勿随意删除或跨机器迁移 `%APPDATA%\Claude` 目录，否则会因公私钥对丢失或不匹配触发重新校验或封锁。
