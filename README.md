# Mizu Launcher

手机上的 **Java 版 Minecraft 启动器**。内核用 PojavLauncher，下载策略学 PCL2，界面参考 ZalithLauncher2 —— 把三家最顺手的地方揉到一起。

> 当前状态：**可编译、可打包、五个 ABI 变体齐全、76 个单测全绿**。
> Pojav 内核按 [第四章](#四集成-pojavlauncher-内核) 引入 submodule 后即可真正进游戏。

---

## 一、它解决了什么

| 痛点 | 本项目的做法 |
|---|---|
| 手机启动器下资源慢、断线重来 | PCL2 式多线程 + 镜像熔断 + 断点续传 + SHA1 秒传跳过 |
| 多版本互相污染存档/模组 | 实例隔离（每个实例独立 `.minecraft`），libraries/assets 共享省几个 G |
| 加装模组加载器要开电脑 | Fabric/Quilt 纯 HTTP 安装；Forge/NeoForge 解包 installer 拼 profile |
| **Java 要自己下载、还要自己找对版本** | **内置 Java 8/17/21/25，首启解压即用；缺的按需自动下载** |
| 手机上出错完全没法查 | 内置日志页：选文件、看尾部 2000 行、一键复制 |
| 驱动/Java 选错直接崩 | 启动前兼容性预检（Vulkan、ABI、内存、Forge↔Java 版本） |

---

## 二、架构

```
app                      UI（Compose/Material3）+ 编排层
 ├─ LauncherViewModel    全局状态（StateFlow），所有写操作后主动刷新
 ├─ LaunchOrchestrator   装 → 拼参数 → 启动，一条链
 ├─ ui/screens/home       主页：实例卡片网格（2/3/4 列响应式）
 ├─ ui/screens/download   下载页：版本列表 + 加载器选择 + 进度
 ├─ ui/screens/account    账户：微软正版登录（设备码）/ 离线名
 ├─ ui/screens/runtime    Java 运行时管理（8/17/21/25 的安装与删除）
 ├─ ui/screens/dependencies 内置组件总览（解压状态/体积/重新扫描）
 ├─ ui/screens/settings   设置：下载源/线程/内存/渲染器/主题/背景
 ├─ ui/screens/control    触屏按键布局编辑器
 ├─ ui/screens/log        运行日志
 ├─ ui/screens/about      关于（含许可证声明）
 └─ bridge/PojavBridgeImpl 反射对接 Pojav 内核

core/download          高速下载引擎（镜像重写 + 分段并发 + 熔断）
core/minecraft         版本清单/规则引擎/下载计划/加载器安装
core/instance          实例管理 + 启动参数构造（纯 JVM，可单测）
core/runtime           Java 运行时管理（使用已解压 JRE + 远端下载 + SHA1 校验）
core/setup             内置组件补全（扫描 assets → 比对版本指纹 → 解压 + 中断恢复）
core/update            应用内更新（查 Release → 按 ABI 挑包 → 断点续传下载）
core/auth              微软登录（设备码 → XBL → XSTS → MC Services）+ 令牌续期
core/launcher-bridge   内核抽象接口 + 启动前兼容性预检
```

**分层原则**：`core/*` 除 Manifest 外无 Android 依赖，能直接跑单元测试；真机相关的只有 `:app` 与桥接层。

### 状态管理为什么必须放 ViewModel

早期版本把状态写在 `@Composable` 里用 `remember` 保存：

```kotlin
var instances by remember { mutableStateOf(container.instanceManager.list()) }
```

`remember` 的初始化块**只在首次组合时求值一次**。于是「装完新版本 → 列表不更新 → 必须切到别的页面再切回来」成了必然现象。
现在所有可变状态都迁到 `LauncherViewModel` 的 `StateFlow`，UI 用 `collectAsStateWithLifecycle()` 订阅，
且每个写操作结束都会主动 `refreshInstances()` —— 改完立刻反映，与组合时机无关。

---

## 三、编译与验证

```bash
# 环境：JDK 17+（推荐 20/21）、Android SDK 35
./gradlew test                                    # 全部单测

# 五个 ABI 变体（对齐 ZalithLauncher2 的产物结构）
./gradlew :app:assembleArm32Debug                 # armeabi-v7a
./gradlew :app:assembleArm64Debug                 # arm64-v8a（绝大多数手机）
./gradlew :app:assembleX86Debug                   # x86
./gradlew :app:assembleX64Debug                   # x86_64
./gradlew :app:assembleUniversalDebug             # 四架构合一

# 产物：app/build/outputs/apk/<abi>/debug/*.apk
```

已验证：

| 项目 | 结果 |
|---|---|
| `core:minecraft` 单测 | 13/13 通过（规则引擎语义修正后） |
| `core:instance` 单测 | 8/8 通过 |
| `core:download` 单测 | 10/10 通过 |
| `core:runtime` 单测 | 7/7 通过 |
| `core:auth` 单测 | 18/18 通过（登录链路、令牌续期、JSON 往返） |
| 五个 ABI 变体编译 | 全部 BUILD SUCCESSFUL，单包约 17 MB（不含 JRE） |

> 低内存机器编译若遇到 Gradle daemon 被杀，加参数：
> `./gradlew :app:assembleArm64Debug -Dorg.gradle.jvmargs="-Xmx2048m" -Dorg.gradle.workers.max=2`

### 为什么要分五个 APK

1. **体积**：每个 ABI 的 natives + JRE 合计 80~120 MB，五个架构塞一个包直接 400 MB+，而用户只需要其中一份；
2. **JRE 对不上**：JRE 按架构编译，arm64 的 `libjvm.so` 在 x86 设备上必崩。与其运行时判断，不如从打包阶段就杜绝装错包；
3. **对齐 ZalithLauncher2**：它同样发 `arm32 / arm64 / x86 / x64 / universal` 五个文件，用户找包习惯不用变。

`universal` 是兜底：四个架构都打进去，给分不清机型的人。

---

## 四、首启与更新后的「补全依赖」

APK 里带的 Java 运行时、LWJGL、渲染器都是 zip，必须先解压到私有目录才能用。
不把这一步摊在用户面前，第一次点「开始游戏」只会看到
`Cannot run program "java"` / `liblwjgl.so not found`，用户只会认为启动器是坏的。

行为对齐 ZalithLauncher2：

1. **首次启动**（或升级后组件版本变化）弹窗列出待解压清单 —— 体积、用途都写清楚；
2. 点「安装」开始逐项解压，显示当前项与总体进度。**这一步不联网**；
3. 装完写入「版本指纹」，下次启动直接跳过；
4. 升级 APK 若带来新版组件，指纹变化 → 再次提示；
5. 组件没补齐时点「开始游戏」会被拦下并重新叫出补全弹窗。

```
assets/runtime/<abi>/jre-<major>.zip   →  files/bundles/runtime/java-<major>/   (Java 按本机 ABI 只解一份)
assets/bundle/<kind>/<name>.zip        →  files/bundles/bundle/<kind>/<name>/   (LWJGL / 渲染器等通用组件)
```

**清单来自磁盘扫描，不是写死的表**：包里没有的组件不会出现在弹窗里，
所以「CI 没配 `JRE_ARCHIVES_URL` 打出的空包」也不会骗用户去点一个装不了的东西。
`RuntimeManager` 直接使用已解压的 JRE，不会重复解压第二份。

几个容易翻车的点都做了处理：

| 场景 | 处理 |
|---|---|
| 解压到一半被系统杀进程 | 留 `.installing` 标记，下次启动清理半截目录，避免「显示已就绪、一启动就崩」|
| 升级后同路径资产内容变了 | 资产缓存目录带 `versionCode + lastUpdateTime`，指纹必然变化，不会漏提示 |
| 用户手动清了应用数据 | 记录没了但目录也没了 → 重新提示补全 |
| 32 位机型包里带了 64 位 JRE | 按 `Build.SUPPORTED_ABIS` 只解本机架构，不会解错 |

「设置 → 运行依赖」是常驻入口，能看到每项的状态、体积与来源路径，也能手动重新扫描。

## 五、Java 运行时（内置 + 按需下载）

Minecraft 对 Java 是硬依赖，class file version 不匹配直接 `UnsupportedClassVersionError`：

| MC 版本 | Java |
|---|---|
| ≤ 1.16.5 | 8 |
| 1.17 ~ 1.20.4 | 17 |
| 1.20.5 ~ 1.21.x | 21 |
| 更新的版本 / 快照 | 25 |

两条来源并存，**先内置、后下载**：

```
APK assets/runtime/<abi>/jre-<major>.zip   ← CI 打包时塞进去，首启解压，零流量
            ↓（没有这个文件时）
远端 jre-index.json 里的 url              ← 走下载引擎，SHA1 校验
            ↓（都没有）
明确报错，并告诉用户缺哪个架构的哪个版本
```

清单格式（`scripts/package_runtimes.py --manifest` 会产出）：

```json
{
  "entries": [
    { "major": 21, "abi": "arm64-v8a", "url": "https://.../jre-21.zip", "sha1": "...", "size": 41234567 }
  ]
}
```

填在 App 的 **设置 → 运行时清单地址**，或在 CI 里用仓库变量 `JRE_ARCHIVES_URL` 注入。

### 打包内置 JRE

```bash
# 单个
python3 scripts/package_runtimes.py \
    --in jre21-arm64.tar.gz --abi arm64-v8a --major 21 \
    --out app/src/main/assets

# 批量（文件名里要含架构与版本，例如 jre17-arm64.tar.gz）
python3 scripts/package_runtimes.py --dir ./downloads --out app/src/main/assets \
    --manifest out/jre-index.json
```

脚本会：解 tar.gz/zip → 找到真正的 JRE 根（自动跳过外层多余目录）→ 补 `bin/*` 可执行位 →
重新打成保留 unix 权限位的 zip → 算 SHA1 写进清单。

> **32 位设备没有 Java 25**：`RuntimeManager.isSupported()` 会直接判为不可用并在 UI 上灰掉，
> 而不是让用户点了半天才失败。这与 ZalithLauncher2 的做法一致。

---

## 六、集成 PojavLauncher 内核

### 6.1 拉取源码

```bash
git submodule add https://github.com/PojavLauncherTeam/PojavLauncher.git pojav
git -C pojav checkout v3_openjdk
```

### 6.2 把内核模块接入构建

在 `settings.gradle.kts` 末尾追加：

```kotlin
// Pojav 的模块名是 app_pojavlauncher
include(":app_pojavlauncher")
project(":app_pojavlauncher").projectDir = file("pojav/app_pojavlauncher")
```

`:app/build.gradle.kts` 的 dependencies 里加：

```kotlin
implementation(project(":app_pojavlauncher"))
```

> 注意：Pojav 自带 `applicationId`/`namespace`，与本工程（`com.mizulauncher`）不冲突，
> 但资源前缀不同，合并时若报 `resource duplicate` 需在 `:app` 的
> `androidResources` / `packaging` 里排除冲突项。

### 6.3 注册游戏 Activity

`app/src/main/AndroidManifest.xml` 里已留好占位，取消注释即可：

```xml
<activity android:name="net.kdt.pojavlaunch.MainActivity"
          android:exported="false"
          android:configChanges="orientation|screenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode|density"
          android:screenOrientation="sensorLandscape"
          android:theme="@style/Theme.MizuLauncher.Game" />
```

### 6.4 桥接层打通（关键一步）

启动器把参数写成 JSON 放进 Intent：

```
extra: pcl.launch_config = /data/.../launch/launch-<ts>.json
```

在 Pojav 的 `MainActivity.onCreate` 里读该 extra 并转成它自己的结构：

```java
String cfg = getIntent().getStringExtra("pcl.launch_config");
if (cfg != null) {
    // 解析 JSON：mainClass / classpath / jvmArgs / gameArgs / nativesDir / javaMajor ...
    // 组装成 Pojav 需要的 String[] args，然后
    JREUtils.launchJavaVM(this, argsBuilder);
}
```

字段清单见 `PojavBridgeImpl.writeLaunchConfig()`，一一对应，不用猜。

---

## 七、许可证与合规

| 组件 | 许可 | 对我们的约束 |
|---|---|---|
| **本项目** | GPL-3.0 | 衍生作品须同样开源 |
| **PojavLauncher** | **LGPL-3.0**（2.x 起；早期版本是 GPLv3） | 以库的形式链接**不必**开源本工程；但修改了 Pojav 自身源码，改动部分须以 LGPL 公开 |
| **ZalithLauncher2** | GPL-3.0 + 附加条款（§7） | 仅作交互参考，未使用其代码；其附加条款禁止改版沿用 "ZalithLauncher" / "ZL" 名称 |
| **PCL2** | 仅参考策略，未使用代码 | — |

> 说明：本项目自己选择 GPL-3.0 发布（比 LGPL 提出的要求更严），这样无论你怎么组合分发都不会有许可冲突。

合规清单：

1. **提供完整对应源码**：本仓库 + 引入 Pojav 时的 submodule commit 号，一起公开；
2. **保留许可与版权声明**：`关于` 页已列出上游项目与许可（`ui/screens/about/AboutScreen.kt`）；
3. **不得附加额外限制**：不能给 APK 再加 EULA 限制用户反向工程；
4. **分发二进制时提供源码获取方式**：README 写明仓库地址即可。

想**闭源**发布的话，唯一干净的做法是把 Pojav 做成独立 App，用 Intent + 文件交换参数
（`core/launcher-bridge` 的接口设计已兼容这种做法，换一个 `LauncherBridge` 实现即可）。

---

## 八、下载加速说明

镜像映射（`core/download/UrlRewriter.kt`）：

| 原始域名 | 镜像 |
|---|---|
| `piston-meta.mojang.com` / `launchermeta.mojang.com` | BMCLAPI / MCIM |
| `launcher.mojang.com` / `resources.download.minecraft.net` | BMCLAPI |
| `libraries.minecraft.net` / `maven.minecraftforge.net` | BMCLAPI / MCIM |

引擎特性：

- **镜像熔断**：同一镜像连续失败 3 次 → 本任务回退官方源，不让用户卡死在坏镜像上；
- **分段并发**：>8 MB 的文件切 4 段并发 Range 拉取，小文件走单连接（避免握手开销反噬）；
- **断点续传**：写 `.part` 临时文件，重试时带 `Range` 续传；
- **秒传**：目标文件已存在且 SHA1 匹配直接跳过 —— 重装/修复几乎零流量。

---

## 九、CI

`.github/workflows/build.yml`：

- 每次 push / PR 跑全量单测 + 五个 ABI 变体编译，产物上传为 artifact；
- 打 `v*` tag 时自动建 Release，挂上五个 APK 与 `SHA256SUMS.txt`；
- **JRE 资源默认缺席**：仓库变量 `JRE_ARCHIVES_URL` 没配就跳过内置步骤，**构建依然成功**，只是 APK 里没预装 Java（运行时会走远端清单）。这条是硬要求——不能让「没配 Secret」变成「打不出包」。
- **调试 API 文档一致性校验**：`python3 scripts/check_api_doc.py` 会比对 `docs/DEBUG_API.md` 与 `ApiRoutes.kt` 的接口清单，只改一边就红。该文档**不入库**，脚本检测不到时会自动跳过，不影响构建。

---

## 九点五、调试 API

App 内置一个**只监听 `127.0.0.1`** 的 HTTP 接口，用来从 shell / 脚本驱动启动器：列实例、准备文件、启动游戏、查进度、切换下载源、设备码登录、看日志。

- **无条件默认开启**，没有开关文件，debug / release 行为一致
- 连接必须带 token：每次进程启动用 `SecureRandom` 重新生成 128 位，进程退出即失效
- 连接信息（端口 + token）写在 `files/mizu-debug-api.json`，**不打 logcat**
- 端口每次启动随机分配，别写死
- 可选 **AES-256-GCM 全链路加密**：密钥由 token 派生，报文带 `X-Mizu-Encrypted: 1`；明文 `curl` 仍然可用

三层安全边界是有意为之，别改成 `0.0.0.0`：接口能删你的存档、能读到账号续期令牌，开给局域网等于把家门钥匙挂在门口。

```bash
INFO=$(su -c 'cat /data/data/com.mizulauncher/files/mizu-debug-api.json')
PORT=$(echo "$INFO" | grep -o '"port":[0-9]*' | grep -o '[0-9]*')
TOKEN=$(echo "$INFO" | grep -o '"token":"[a-f0-9]*"' | cut -d'"' -f4)
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/diagnostics"
```

接口清单可随时从 `GET /api/v1/routes` 取，权威来源是 `core/debug-api/.../ApiRoutes.kt` 的 `ENDPOINTS`。

---

## 十、已知限制

- Forge/NeoForge 的 **processors（二进制补丁）不在手机上执行**，依赖 Pojav 内核运行期处理；
  极端老版本（1.7.10 之前）可能需要手工补库。
- OptiFine 不自动安装（许可不允许），只给引导。
- 微软登录用的 client id 是 Pojav 项目长期公开的那个；如需独立分发，请在 Azure 注册后
  通过 `MicrosoftAuth(clientId = "…")` 换掉。
- 正版皮肤不单独下载，交给游戏内核处理。
- 内置 JRE 需要自行准备归档；仓库不附带二进制（体积 + 分发合规）。
  没有内置资产时，首启不会弹补全框（没有可装的东西），Java 改为按需下载。
- 应用内更新走 GitHub Release。国内直连 `api.github.com` 可能较慢；
  查到的 Release 会落盘缓存，断网时仍能提示上次发现的新版本。

---

## 十一、路线图

- [x] 微软 OAuth 登录（device code flow）
- [x] 按键布局持久化（按横竖屏分别保存，可恢复默认）
- [x] 首启 / 更新后补全依赖（对应 ZalithLauncher2 的组件解压流程）
- [x] 应用内检查更新并直接安装
- [x] 全局下载任务卡（离开下载页也能看进度、随时取消）
- [ ] 按键布局导入/导出（分享给他人）
- [ ] 模组/整合包（.mrpack、CurseForge zip）一键安装
- [ ] 多实例并行下载队列
- [ ] 崩溃自动分析（读 `crash-reports` 给结论）

---

## 十二、致谢

- [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) — 启动内核（LGPL-3.0）
- [Plain Craft Launcher 2](https://github.com/Hex-Dragon/PCL2) — 下载策略与镜像映射的参考
- [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2) — UI 交互与控制器布局参考
- [BMCLAPI](https://bmclapi2.bangbang93.com) / [MCIM](https://mod.mcimirror.top) — 公益镜像

Minecraft 是 Mojang Studios 的商标，本项目与其无关联，且不包含任何游戏资源。
