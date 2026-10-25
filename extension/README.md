# I’m not dragon — 浏览器扩展

Manifest V3 扩展，适用于 Chrome 119+ 及提供相同扩展 API 的 Chromium 浏览器。当前版本 1.1.1，界面沿用 Android LSPosed 模块的浅色分区、蓝色开关和深色保存按钮。

## 安装与使用

1. 在扩展管理页开启开发者模式，选择“加载已解压的扩展程序”，加载本目录。已有安装请点击扩展的重新加载按钮。
2. 打开面板，按需启用时区语言、虚拟定位和 UA 降噪。
3. 点击“同步当前出口”，或手动填写 IANA 时区与经纬度。同步操作会先保存面板设置，再查询出口并保存结果。
4. 点击“保存配置”并刷新已有网页。新设置不会追溯修改网页已经缓存的数据或创建的格式化器。

默认沿用现有系统、VPN 或浏览器代理。需要本扩展接管时，填写 `http://127.0.0.1:7890`、`https://…` 或 `socks5://…` 格式的代理地址，然后开启“浏览器代理接管”并保存。代理地址不支持内嵌账号密码；认证由浏览器或代理软件处理。此设置影响当前浏览器的常规配置。

## 与 Android 模块的功能对应

| 功能 | 扩展实现 |
| --- | --- |
| WebRTC 防泄漏 | 浏览器原生 `disable_non_proxied_udp` 策略，保留 WebRTC API；面板读取实际策略状态 |
| 时区与语言 | 页面 `Date`、`Intl.DateTimeFormat`、Navigator 语言及 HTTP `Accept-Language`；语言按模块统一为 en-US |
| 虚拟地理定位 | 页面定位 API 返回配置坐标，声明约 5 公里精度；支持连续订阅与取消 |
| 浏览器特征降噪 | 精简页面和请求头 UA 的版本信息、Android 机型；减少高熵 Client Hints |
| 浏览器代理接管 | 使用浏览器原生代理 API，为 HTTP/HTTPS 请求指定代理；未开启时沿用既有设置 |
| 出口同步 | 查询 `ipapi.co` 的出口 IP、IANA 时区和城市级坐标；可每 5 分钟自动同步 |
| SIM、运营商与网卡 | 扩展无 Android TelephonyManager 或网卡枚举钩子 |
| UDP、IPv6 与 DNS 隔离 | 扩展无 libc 拦截能力；系统网络路由由 VPN 或代理软件管理 |

关闭总开关会释放本扩展的 WebRTC 和代理设置，移除本扩展的请求头规则，停止定时同步，并让已注入页面中的接口恢复原始行为。其他扩展或企业策略可能控制相同浏览器设置，面板会显示读取到的实际 WebRTC 状态。

## 页面覆盖与边界

- 注入目标为允许访问的普通网页及其匹配的框架，包含来源匹配的 about:blank、data: 和 blob: 框架；不依赖 Claude 的域名或页面结构。
- 页面主环境无法同步读取扩展存储。钩子在 document_start 安装，配置由隔离环境异步传入；到达前保留原始行为，因此最早运行的页面脚本可能读取原始环境。
- Worker、Service Worker 与浏览器内部页面不覆盖。脚本覆盖也不等价于改变浏览器内核的时区或整个系统环境。
- 日期处理尊重网页显式指定的时区；时区配置只替代默认本地环境。已有 Intl 格式化器保留创建时的设置。
- WebRTC 策略限制非代理 UDP，音视频是否可用取决于对端及代理支持的传输方式，不承诺所有实时通信场景均可连接。
- 代理 API 不构成全局网络防火墙，扩展不保证全部协议、DNS、IPv6 或其他应用流量均被代理。TUN 路由与防旁路策略需在网络工具中配置。
- 本次实现使用 Chromium 扩展 API。手机浏览器是否支持安装、MAIN world、privacy、proxy 和 declarativeNetRequest 等能力，需分别确认。

## 出口查询与数据

仅手动同步或启用自动同步时向 `https://ipapi.co/json/` 发起查询；该服务会看到此次请求的出口 IP。分流代理下，这个请求的出口不一定等于每个网站的出口。

配置保存在本机 `chrome.storage.local`。仅所需环境参数传入页面，不向页面传递代理地址或查询记录。查询失败、服务限流或数据不完整时提示错误，保留已有时区、坐标与最近成功记录，不补造地区数据。

扩展需要 `storage`、`privacy`、`proxy`、`alarms`、`declarativeNetRequestWithHostAccess` 及普通网页访问权限，分别用于本地配置、WebRTC 策略、可选代理接管、定时查询、请求头同步与网页注入。

API 依据：[Chrome privacy](https://developer.chrome.com/docs/extensions/reference/api/privacy)、[Chrome proxy](https://developer.chrome.com/docs/extensions/reference/api/proxy)、[Chrome declarativeNetRequest](https://developer.chrome.com/docs/extensions/reference/api/declarativeNetRequest)。
