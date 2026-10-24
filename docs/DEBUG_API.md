# MizuLauncher 调试 API 文档

> 版本：v1 · 适用 MizuLauncher 1.0.1 及以上
> 这套接口是为**调试与自动化对接**准备的，不是公开 API。它跑在 App 进程内，只监听回环地址。

---

## 1. 它是什么，不是什么

**是**：一个能让你从 shell / 脚本 / 你自己的工具里，直接驱动启动器干活的本机 HTTP 接口。列实例、准备文件、启动游戏、查进度、看日志、切换下载源、设备码登录——都能用 curl 调。

**不是**：不是给游戏客户端连的联机协议，不是远程 API（外网连不进来），也不是稳定契约（调试接口允许随时改，改之前会更新本文档）。

### 为什么不做成「远程可访问」

启动器手里的东西很敏感：微软账号的 refresh_token、你所有存档的路径、能任意删目录的能力。把它开到 `0.0.0.0`，等于同一 Wi-Fi 下任何人用浏览器就能删你的存档。

所以设计上做了三层限制，**请不要试图绕过**：

| 限制 | 实现 | 你能怎么用 |
|---|---|---|
| 只绑回环 | `ServerSocket` 绑 `127.0.0.1` | 只能在本机 shell 里 `curl`，或用端口转发 |
| 每次启动换 token | `SecureRandom` 生成 128 位 | token 写在私有目录，进程退出即作废 |
| token 不打日志 | 只写 `files/mizu-debug-api.json` | 不会被别的应用从 logcat 扫到 |

---

## 2. 启用与连接

### 2.1 默认一直开着

**调试 API 无条件启用，没有任何开关文件，debug 和 release 包行为一致。**

第一次看到这条大概会皱眉——release 包常开一个 HTTP 口，听起来像后门。所以把三层防护摊开说清楚：

| 防护 | 做法 | 挡住谁 |
|---|---|---|
| **网络层** | `ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))`，只绑回环 | 局域网、路由器转发的任何外部连接。用 `InetSocketAddress(port)` 在部分 ROM 上会绑到 `0.0.0.0`，所以代码里显式指定了 IP |
| **凭据层** | 每次进程启动用 `SecureRandom` 重新生成 128 位 token，进程退出即失效 | 其他应用。token 不落盘到全局可读位置、**不打 logcat**，只写 App 私有目录 |
| **报文层** | 可选的 AES-256-GCM 全链路加密，密钥由 token 派生 | 同 UID 下能抓包但拿不到 token 的进程（见第 3.7 节） |

关键点是 **token 只存在于 App 私有目录**（`/data/user/0/com.mizulauncher/files/`）。同机其他应用即使猜到了端口，没有 `su` / `rish` / `run-as` 也读不到 token，所有接口返回 401。真正能读到 token 的只可能是你自己——也就是你拿来对接的那个 shell。

如果你确实需要「某些构建不带调试口」，改 `DebugApiHost.attach()` 加一个条件判断即可，但默认保持常开：调试口在需要用时打不开，比一直开着但受 token 保护要难受得多。

### 2.2 拿到端口和 token

服务启动时会把连接信息写进 `files/mizu-debug-api.json`：

```json
{
  "port": 38471,
  "host": "127.0.0.1",
  "token": "9f3a1c8e5b2d7046ae18c3f92b5d4e07",
  "basePath": "/api/v1",
  "healthUrl": "http://127.0.0.1:38471/api/v1/health",
  "pid": 12345,
  "startedAt": 1761283200000
}
```

读法（有 shell 权限时）：

```bash
su -c 'cat /data/data/com.mizulauncher/files/mizu-debug-api.json'
```

**端口每次启动都会变**（系统随机分配，避免和其他应用抢）。所以你的脚本不要写死端口，每次从文件里读：

```bash
INFO=$(su -c 'cat /data/data/com.mizulauncher/files/mizu-debug-api.json')
PORT=$(echo "$INFO" | grep -o '"port":[0-9]*' | grep -o '[0-9]*')
TOKEN=$(echo "$INFO" | grep -o '"token":"[a-f0-9]*"' | cut -d'"' -f4)
BASE="http://127.0.0.1:$PORT/api/v1"
```

想固定端口也不是不行：改 `DebugApiHost` 里 `DebugApiServer(port = 0)` 的 `0` 为一个具体端口。但**不推荐**——端口冲突会让服务静默启动失败。

### 2.3 探活

```bash
curl -s "$BASE/health"
```

`/health` **不需要 token**。这是故意的：脚本可以先 ping 一下，通了再去读 token 文件，省得每次都 cat。

```json
{"ok":true,"data":{"ok":true,"service":"mizulauncher-debug-api","version":1}}
```

---

## 3. 通用约定

### 3.1 鉴权

除 `/health` 外，**所有接口都需要 token**，两种传法等价：

```bash
# 推荐：请求头
curl -H "Authorization: Bearer $TOKEN" "$BASE/instances"

# 备选：查询参数（方便在浏览器里直接点开）
curl "$BASE/instances?token=$TOKEN"
```

token 错误或缺失：

```json
HTTP/1.1 401 Unauthorized
{"ok":false,"error":{"code":"unauthorized","message":"缺少或错误的 token。请求头加 Authorization: Bearer <token>"}}
```

### 3.2 响应包封

**成功**永远是 HTTP 200，数据在 `data` 里：

```json
{
  "ok": true,
  "data": { ... }
}
```

**失败**用对应的 HTTP 状态码，错误在 `error` 里：

```json
{
  "ok": false,
  "error": {
    "code": "instance_not_found",
    "message": "实例 abc123 不存在"
  }
}
```

判断成败请用 `ok` 字段或 HTTP 状态码，**不要**去解析 `message` 文案（文案会改，`code` 不会）。

### 3.3 错误码表

| HTTP | `code` | 含义 | 通常怎么处理 |
|---|---|---|---|
| 400 | `bad_request` | 参数缺失/格式不对 | 修请求 |
| 400 | `secure_channel_failed` | 加密通道握手/解密失败 | 见 3.4 节排查清单，先 `--plain` 对照 |
| 401 | `unauthorized` | token 不对 | 重读 token 文件 |
| 404 | `not_found` | 路径不存在 | 检查路径拼写 |
| 404 | `instance_not_found` | 实例 id 不存在 | 先 `GET /instances` 看有哪些 |
| 405 | `method_not_allowed` | 路径对但方法不对 | 响应里会列出允许的方法 |
| 409 | `busy` | 已有任务在跑 | 等 `GET /task` 的 `installing` 变 false，或 `POST /task/cancel` |
| 500 | `internal_error` | 实现内部异常 | 看 `message`，再 `GET /logs` |
| 502 | `manifest_failed` | 版本清单拉取失败 | 检查网络/换下载源 |
| 502 | `update_check_failed` | 检查更新失败 | 同上 |
| 502 | `login_failed` | 拿不到设备码 | 同上 |
| 503 | `ui_not_ready` | App 界面没起来 | 先把 App 打开一次 |

### 3.4 加密通道（可选，但强烈建议）

明文通道（`curl` 直接带 token）够用来调试，但报文在回环上是裸的——同机任何能抓包的进程都能看到你在干什么。加密通道解决这个：**请求体和响应体都由 AES-256-GCM 加密，token 本身从不出现在任何报文里。**

#### 为什么不是 Diffie-Hellman

通道是回环地址，威胁来自**同机的其他进程**，不是链路窃听。DH 的安全性依赖「攻击者无法同时冒充两端」，但在这里攻击者可以直接扮演客户端去和服务端握手，从而自己拿到一份会话密钥——DH 在这个场景下解决不了真问题。

所以这里让 **token 成为派生密钥的必要输入**：没有 token 就算不出密钥，攻击者既读不了也伪造不了。

#### 密钥派生

```
salt      = 16 字节随机，客户端生成，明文随请求带上
timestamp = 毫秒，明文带上（用于拒绝过期会话）
prk       = HMAC-SHA256(key = token, msg = salt || ts_be64)
encKey    = HMAC-SHA256(key = prk,  msg = "mizu-enc"   || salt)
nonceKey  = HMAC-SHA256(key = prk,  msg = "mizu-nonce" || salt)
```

三段派生（`encKey` 加密、`nonceKey` 生成 nonce）是为了避免同一把密钥既加密又产出 IV。**IV 复用是 GCM 的致命错误**，一旦发生就等于把明文异或出来了。

#### 报文格式

```
nonce      = HMAC-SHA256(nonceKey, seq_be64)[:12]
aad        = seq_be64 || "<METHOD>|<path>"        ← path 不含 query string
ciphertext = AES-256-GCM(encKey, nonce, plaintext, aad)
```

> **⚠️ 序号只加一次。** 服务端的 `SecureChannel.encrypt()` 内部统一补 `seq_be64` 前缀，所以任何客户端只拼 `"<METHOD>|<path>"` 这一段。如果你自己又拼一遍序号，AAD 就变成 `seq||seq||...`，服务端会返回 `secure_channel_failed`。这是本项目实现时真实踩过的坑，`SecureChannelTest` 里有专门的互验用例钉住它。

AAD 绑定方法 + 路径 + 序号，因此攻击者**无法**把 `/health` 的密文挪到 `/instances` 上（后者会真的创建实例），也无法把 GET 的响应拿去冒充 POST，更无法重放旧报文。

#### 请求头

| 头 | 值 | 说明 |
|---|---|---|
| `Authorization` | `Bearer <token>` | 仍然需要，加密不替代鉴权 |
| `X-Mizu-Encrypted` | `1` | 声明这是加密请求 |
| `X-Mizu-Salt` | 16 字节 hex（32 字符） | 客户端每次请求重新随机 |
| `X-Mizu-Ts` | 毫秒时间戳字符串 | 与服务端偏差超过 **60 秒**直接拒 |
| `X-Mizu-Seq` | 递增序号字符串 | 建议从 1 开始，每个请求 +1 |
| `Content-Type` | `application/octet-stream` | 请求体是裸密文，不是 hex |

#### 响应

加密请求的响应体是 hex 包装的密文，并带 `X-Mizu-Encrypted: 1` 头：

```json
{"cipher":"6c6b7ee1ca0d2280..."}
```

解密用**与请求相同的 nonce 和 AAD**（同一 `seq` 派生而来）。这样一次请求-响应只用一个序号，客户端不需要维护两套计数器。

**明文请求仍然返回明文。** 是否加密只看请求有没有带 `X-Mizu-Encrypted: 1`，所以 `curl` 调试完全不受影响。

#### 现成的客户端

`scripts/mizu_api.py` 是完整参考实现，只依赖标准库 + `cryptography`：

```bash
pip3 install cryptography

# 自动从设备读取端口和 token（依次尝试 su / run-as）
python3 scripts/mizu_api.py --auto GET /diagnostics

# 手动指定
python3 scripts/mizu_api.py --token 9f3a1c... --port 38471 GET /instances

# 创建实例
python3 scripts/mizu_api.py --auto POST /instances \
    --body '{"name":"测试","versionId":"1.20.1"}'

# 明文对照（排查加密层问题时用）
python3 scripts/mizu_api.py --auto --plain GET /health
```

`--auto` 会依次尝试 `/data/user/0/com.mizulauncher/files/mizu-debug-api.json` 和 `/data/data/com.mizulauncher/files/mizu-debug-api.json`，用 `su -c cat` 或 `run-as` 读取。你把它接在自己的 rish/shizuku 环境里时，只要保证能读到那个 JSON 就行。

#### 排查 `secure_channel_failed`

| 现象 | 原因 |
|---|---|
| `x-mizu-salt 长度必须是 16 字节` | salt 不是 32 个 hex 字符 |
| `时间戳偏差 xxx ms 超过 60000ms` | 设备与脚本宿主时钟不同步，先对时 |
| `解密失败（token 不对，或报文被篡改/重放）` | token 错、AAD 拼错（尤其序号加了两次）、或 body 被中间件改过 |

最后一条**故意不告诉具体原因**——回显「tag mismatch 在第 N 字节」会变成逐字节猜密钥的侧信道。要定位就把 `--plain` 跑一遍，明文能通说明是加密层的问题。

### 3.5 异步操作：触发 + 轮询

下载、解压这类操作动辄几分钟，**不能让 HTTP 请求挂在那儿等**。所有耗时操作都是两段式：

```bash
# 第一段：触发，立即返回
curl -X POST -H "Authorization: Bearer $TOKEN" "$BASE/instances/abc/prepare"
# → {"ok":true,"data":{"started":true,"id":"abc","hint":"用 GET /task 查进度"}}

# 第二段：轮询
while true; do
  curl -s -H "Authorization: Bearer $TOKEN" "$BASE/task" | grep -o '"installing":[a-z]*'
  sleep 2
done
```

### 3.6 数字类型

JSON 里所有数字进来都会被解析成浮点（Gson 的特性）。**写请求时** `javaMajor: 21` 和 `21.0` 等价；**读响应时**整数字段是整数，不会变成 `21.0`。

### 3.7 请求体大小

上限 4 MB。超了返回 400。正常调试用不到这么大。

---

## 4. 接口总览

共 **27** 个接口。`GET /api/v1/routes` 可以随时取到这份清单（代码里和文档里是同一份，`RouterTest` 会校验两者一致）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 探活，不需要 token |
| GET | `/api/v1/routes` | 列出所有接口 |
| GET | `/api/v1/diagnostics` | 设备/版本/运行时/任务总览 |
| GET | `/api/v1/instances` | 实例列表 |
| GET | `/api/v1/instances/{id}` | 单个实例详情 |
| POST | `/api/v1/instances` | 创建实例 |
| PATCH | `/api/v1/instances/{id}` | 部分更新实例 |
| DELETE | `/api/v1/instances/{id}` | 删除实例 |
| POST | `/api/v1/instances/{id}/prepare` | 准备文件（异步） |
| POST | `/api/v1/instances/{id}/launch` | 启动游戏（异步） |
| GET | `/api/v1/task` | 当前任务进度 |
| POST | `/api/v1/task/cancel` | 取消任务 |
| GET | `/api/v1/versions` | 版本清单 |
| GET | `/api/v1/sources` | 下载源列表 |
| POST | `/api/v1/sources` | 切换下载源 |
| GET | `/api/v1/bundles` | 运行依赖扫描结果 |
| POST | `/api/v1/bundles/install` | 补全运行依赖（异步） |
| GET | `/api/v1/runtimes` | Java 运行时状态 |
| GET | `/api/v1/account` | 账户状态 |
| POST | `/api/v1/account/login` | 开始设备码登录 |
| POST | `/api/v1/account/login/cancel` | 取消登录 |
| POST | `/api/v1/account/logout` | 退出登录 |
| POST | `/api/v1/account/offline` | 设置离线用户名 |
| GET | `/api/v1/update` | 检查应用更新 |
| GET | `/api/v1/settings` | 读取设置 |
| PATCH | `/api/v1/settings` | 修改设置 |
| GET | `/api/v1/logs` | 最近日志 |

---

## 5. 接口详解

下面每条都给了 curl 和真实响应。示例里的 `$BASE` 和 `$TOKEN` 见第 2.2 节。

---

### 5.1 诊断

#### `GET /health`

探活。**不需要 token**。

```bash
curl -s "$BASE/health"
```

```json
{
  "ok": true,
  "data": { "ok": true, "service": "mizulauncher-debug-api", "version": 1 }
}
```

#### `GET /routes`

列出本机开了哪些接口。

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/routes"
```

```json
{
  "ok": true,
  "data": {
    "count": 27,
    "endpoints": [
      { "method": "GET", "path": "/api/v1/health", "description": "探活，不需要 token" },
      { "method": "GET", "path": "/api/v1/routes", "description": "列出所有接口" }
    ]
  }
}
```

#### `GET /diagnostics`

所有「我该知道的环境信息」集合。排查问题第一步永远调它。

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/diagnostics"
```

```json
{
  "ok": true,
  "data": {
    "app": {
      "packageName": "com.mizulauncher",
      "versionName": "1.0.1",
      "versionCode": 10001,
      "minSdk": 26
    },
    "device": {
      "abi": "arm64-v8a",
      "abis": ["arm64-v8a", "armeabi-v7a", "armeabi"],
      "sdk": 34,
      "model": "Pixel 7",
      "brand": "google"
    },
    "state": {
      "uiReady": true,
      "installing": false,
      "stage": "",
      "error": null,
      "instanceCount": 2,
      "bundlesScanned": true,
      "bundlesPending": 0,
      "loggedIn": true
    },
    "paths": {
      "root": "/storage/emulated/0/Android/data/com.mizulauncher/files/MizuLauncher",
      "cache": "/storage/emulated/0/Android/data/com.mizulauncher/files/MizuLauncher/cache",
      "bundles": "/data/user/0/com.mizulauncher/files/bundles"
    },
    "settings": {
      "downloadSource": "Bmclapi",
      "downloadThreads": 6,
      "maxMemoryMb": 2048,
      "renderer": "VIRGL",
      "lastAccount": "Steve",
      "themeMode": "system",
      "runtimeManifestUrl": null
    }
  }
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `state.uiReady` | ViewModel 是否就绪。`false` 时大部分接口会返回 503 |
| `state.installing` | 是否有任务在跑 |
| `state.stage` | 当前阶段描述，例如「下载游戏文件」 |
| `state.error` | 最近一次错误，没有则 `null` |
| `state.bundlesPending` | 待补全的运行依赖数量。**非 0 时无法启动游戏** |
| `paths.root` | 实例与共享库根目录 |
| `paths.bundles` | 内置组件解压根目录 |

---

### 5.2 实例

实例（Instance）= 一套独立的游戏目录。一个实例对应一个 `versionId`，可以是原版 `1.20.1`，也可以是 `1.20.1-fabric-0.15.11`。

#### `GET /instances`

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/instances"
```

```json
{
  "ok": true,
  "data": [
    {
      "id": "a1b2c3d4e5f6",
      "name": "机械动力生存",
      "displayName": "机械动力生存",
      "versionId": "1.20.1-fabric-0.15.11",
      "loaderType": "fabric",
      "loaderVersion": "0.15.11",
      "javaMajor": 17,
      "isolated": true,
      "shareLibsAndAssets": true,
      "lastPlayed": 1761280000000,
      "pinned": true,
      "jvmArgs": "",
      "iconKey": "default",
      "createdAt": 1761200000000,
      "gameDir": "/storage/emulated/0/Android/data/com.mizulauncher/files/MizuLauncher/instances/a1b2c3d4e5f6/.minecraft"
    }
  ]
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `id` | 12 位十六进制，创建时自动生成。所有 `/instances/{id}` 都用它 |
| `displayName` | `name` 为空时退回 `versionId` |
| `loaderType` | `vanilla` / `fabric` / `quilt` / `forge` / `neoforge` / `optifine` |
| `javaMajor` | 所需的 Java 大版本。`0` 表示还没探测过，启动时会自动填 |
| `isolated` | `true` = 独立 `.minecraft`；`false` = 共用根目录的 `.minecraft` |
| `shareLibsAndAssets` | `true` 时 libraries/assets 指回公共目录，省好几 GB |
| `lastPlayed` | 上次启动时间戳（毫秒），列表按它倒序 |
| `gameDir` | 游戏目录绝对路径，方便你直接 `adb pull` 存档 |

#### `GET /instances/{id}`

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/instances/a1b2c3d4e5f6"
```

成功返回单个实例对象（结构同列表元素）。不存在：

```json
HTTP/1.1 404 Not Found
{"ok":false,"error":{"code":"instance_not_found","message":"实例 zzz 不存在"}}
```

#### `POST /instances`

创建实例。

| 字段 | 必填 | 类型 | 默认 | 说明 |
|---|---|---|---|---|
| `versionId` | ✅ | string | — | 上游版本 id，例如 `1.20.1` |
| `name` | | string | 空 | 显示名，空则用 `versionId` |
| `loaderType` | | string | `vanilla` | 加载器类型 |
| `loaderVersion` | | string | 空 | 加载器版本，自动挑最新可留空 |
| `javaMajor` | | int | 0 | 0 = 启动时自动探测 |
| `isolated` | | bool | `true` | 是否版本隔离 |
| `shareLibsAndAssets` | | bool | `true` | 是否共享 libraries/assets |

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"versionId":"1.20.1","name":"纯原版","loaderType":"vanilla"}' \
  "$BASE/instances"
```

```json
{
  "ok": true,
  "data": {
    "id": "7f8e9d0c1b2a",
    "name": "纯原版",
    "displayName": "纯原版",
    "versionId": "1.20.1",
    "loaderType": "vanilla",
    "loaderVersion": "",
    "javaMajor": 0,
    "isolated": true,
    "shareLibsAndAssets": true,
    "lastPlayed": 0,
    "pinned": true,
    "jvmArgs": "",
    "iconKey": "default",
    "createdAt": 1761284000000,
    "gameDir": ".../instances/7f8e9d0c1b2a/.minecraft"
  }
}
```

> **注意**：创建只会建目录骨架，**不会下载任何文件**。要下游戏文件得再调 `POST /instances/{id}/prepare`。

缺 `versionId`：

```json
HTTP/1.1 400 Bad Request
{"ok":false,"error":{"code":"bad_request","message":"缺少必填字段 versionId"}}
```

#### `PATCH /instances/{id}`

部分更新。**只改 body 里出现的字段**，没出现的保持原样。

```bash
# 只改名
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"改名了"}' \
  "$BASE/instances/7f8e9d0c1b2a"
```

可改字段：`name` `versionId` `loaderType` `loaderVersion` `javaMajor` `isolated` `shareLibsAndAssets` `jvmArgs` `iconKey`。

空 body（`{}` 或干脆不发 body）等于什么都不改，返回当前状态——可以用来当「读一次」用。

#### `DELETE /instances/{id}`

| 查询参数 | 默认 | 说明 |
|---|---|---|
| `deleteFiles` | `true` | `false` 时只从列表移除（目录改名为 `<id>.removed`），**存档还在** |

```bash
# 连文件一起删（不可恢复！）
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE/instances/7f8e9d0c1b2a"

# 只从列表移除，保留文件
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE/instances/7f8e9d0c1b2a?deleteFiles=false"
```

```json
{"ok":true,"data":{"deleted":true,"id":"7f8e9d0c1b2a","deleteFiles":true}}
```

> ⚠️ `deleteFiles=true` 会 `deleteRecursively()`，存档、模组配置全没。调试时建议先 `?deleteFiles=false`。

#### `POST /instances/{id}/prepare`

下载/校验这个实例需要的所有文件（客户端 jar、libraries、assets、加载器）。**异步**，立即返回。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/instances/a1b2c3d4e5f6/prepare"
```

```json
{"ok":true,"data":{"started":true,"id":"a1b2c3d4e5f6","hint":"用 GET /task 查进度"}}
```

前置条件（和 UI 点「启动」时完全一致，两条路径不会互相绕过）：

1. 运行依赖必须已扫描完（`/diagnostics` 的 `state.bundlesScanned = true`）；
2. 运行依赖不能有缺失（`state.bundlesPending = 0`），否则 409 并提示先补全；
3. 当前没有其他任务在跑，否则 409 `busy`。

已有文件会按 SHA1 校验跳过，**重复调用等价于「修复安装」**，不会重复下载。

#### `POST /instances/{id}/launch`

准备（如果需要）后拉起游戏。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/instances/a1b2c3d4e5f6/launch"
```

```json
{
  "ok": true,
  "data": {
    "launched": true,
    "id": "a1b2c3d4e5f6",
    "hint": "启动是异步的，用 GET /task 与 GET /logs 跟踪"
  }
}
```

> **注意**：返回 `launched:true` 只表示**流程已启动**，不保证游戏跑起来了。内核拉起失败会在 `GET /task` 的 `error` 字段里出现。

---

### 5.3 任务

#### `GET /task`

当前/最近一次任务的进度。**轮询这个接口就够跟踪一切**。

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/task"
```

```json
{
  "ok": true,
  "data": {
    "installing": true,
    "stage": "下载游戏文件",
    "error": null,
    "download": {
      "totalTasks": 312,
      "doneTasks": 148,
      "failedTasks": 0,
      "percent": 47,
      "totalBytes": 268435456,
      "downloadedBytes": 126164992,
      "speedBps": 3145728,
      "etaSeconds": 45,
      "currentFile": "1.20.1.jar",
      "finished": false
    },
    "bundles": {
      "scanned": true,
      "pending": 0,
      "phase": "Idle",
      "notice": null
    }
  }
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `installing` | **最重要**。`true` = 有任务在跑。变 `false` 且 `error` 为 `null` 就是成功 |
| `stage` | 人类可读的阶段，例如「检查 Java 运行时」「下载资源索引」 |
| `error` | 失败原因。`null` 表示没出错 |
| `download.percent` | 0–100。有总字节按字节算，没有则按任务数算 |
| `download.speedBps` | 当前速度（字节/秒），滑动窗口平滑过 |
| `download.etaSeconds` | 剩余秒数，`-1` 表示算不出来（速度为 0） |
| `download.currentFile` | 正在下的文件名 |
| `download.finished` | 本轮是否已结束 |
| `bundles.phase` | `Idle` / `Running(...)` / `Finished` / `Failed(...)` |

**轮询脚本模板**：

```bash
#!/bin/sh
# 等任务结束，最多等 30 分钟
i=0
while [ $i -lt 900 ]; do
  R=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/task")
  INS=$(echo "$R" | grep -o '"installing":[a-z]*' | cut -d: -f2)
  if [ "$INS" = "false" ]; then
    echo "$R"
    echo "$R" | grep -q '"error":null' && echo "✅ 成功" || echo "❌ 失败"
    exit 0
  fi
  PCT=$(echo "$R" | grep -o '"percent":[0-9]*' | cut -d: -f2)
  echo "进度 ${PCT}%"
  sleep 2
  i=$((i+1))
done
echo "超时"
```

#### `POST /task/cancel`

取消当前任务。没有任务时返回 `cancelled:false`。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/task/cancel"
```

```json
{"ok":true,"data":{"cancelled":true}}
```

> 取消后已下载的部分**会保留**（断点续传），下次 prepare 会接着下。

---

### 5.4 版本与下载源

#### `GET /versions`

可安装的版本列表。来自版本清单（首次需要网络）。

| 查询参数 | 默认 | 说明 |
|---|---|---|
| `force` | `false` | `true` 时绕过缓存重新拉清单 |

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/versions"
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/versions?force=true"
```

```json
{
  "ok": true,
  "data": [
    { "id": "1.21.4", "type": "release", "url": "https://..." },
    { "id": "1.20.1", "type": "release", "url": "https://..." },
    { "id": "1.12.2", "type": "release", "url": "https://..." }
  ]
}
```

`type` 是 `release` / `snapshot` / `old_beta` / `old_alpha`。

> 这个接口最多等 3 秒。超时会把**已有缓存**返回，不会卡住。如果清单拉取失败，返回 502 并带原因。

#### `GET /sources`

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/sources"
```

```json
{
  "ok": true,
  "data": [
    { "id": "Official", "name": "官方源 (Mojang)", "current": false },
    { "id": "Bmclapi", "name": "BMCLAPI（bangbang93）", "current": true },
    { "id": "Mcim", "name": "MCIM 镜像", "current": false }
  ]
}
```

#### `POST /sources`

切换下载源。切换后**版本清单会重新拉取**。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"Official"}' \
  "$BASE/sources"
```

`id` 大小写不敏感，取值：`Official` / `Bmclapi` / `Mcim`。

```json
{"ok":true,"data":{"id":"Official","current":true}}
```

未知 id：

```json
HTTP/1.1 400 Bad Request
{"ok":false,"error":{"code":"bad_request","message":"未知下载源 xxx，可选：Official, Bmclapi, Mcim"}}
```

---

### 5.5 运行依赖

运行依赖 = 从 APK 内置资产里解压出来的东西：Java 运行时（JRE）、LWJGL、渲染器、游戏运行组件。

> **这一块是首次安装/更新后必须过的关卡**。没补全不能启动游戏——这是刻意的，宁可拦住也不要让用户点启动后看到 `UnsatisfiedLinkError`。

#### `GET /bundles`

扫描并返回每项状态。

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/bundles"
```

```json
{
  "ok": true,
  "data": {
    "scanned": true,
    "pending": 1,
    "items": [
      {
        "key": "java:jre-17",
        "kind": "java",
        "name": "jre-17",
        "label": "Java 运行时 17",
        "sizeBytes": 41943040,
        "state": "Pending"
      },
      {
        "key": "lwjgl:lwjgl-3.3.3",
        "kind": "lwjgl",
        "name": "lwjgl-3.3.3",
        "label": "LWJGL 图形库",
        "sizeBytes": 2097152,
        "state": "Done"
      }
    ]
  }
}
```

`state` 取值：`Pending`（待装）/ `Installing(percent, detail)` / `Done` / `Failed(message)` / `Unknown`。

`kind` 取值：`java` / `lwjgl` / `renderer` / `game`。

#### `POST /bundles/install`

补全所有 `Pending` 项。**异步**。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/bundles/install"
```

```json
{"ok":true,"data":{"started":true,"hint":"用 GET /task 查进度"}}
```

没有待补全项时返回 `started:false`。

> 重启/杀进程不影响：解压过程会写 `.installing` 标记，下次扫描时自动清理半截目录重来。

#### `GET /runtimes`

Java 运行时状态。

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/runtimes"
```

```json
{
  "ok": true,
  "data": [
    { "major": 8, "state": "Missing", "ready": false },
    { "major": 17, "state": "Ready(...)", "ready": true },
    { "major": 21, "state": "Ready(...)", "ready": true },
    { "major": 25, "state": "Missing", "ready": false }
  ]
}
```

`ready:true` 表示这个 Java 已经可用（含 `bin/java` 与 `lib/server/libjvm.so`）。

---

### 5.6 账户

#### `GET /account`

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/account"
```

未登录：

```json
{"ok":true,"data":{"loggedIn":false,"offlineName":"Steve"}}
```

已登录：

```json
{
  "ok": true,
  "data": {
    "loggedIn": true,
    "name": "Notch",
    "uuid": "069a79f444e94726a5befca90e38aaf5",
    "tokenExpiresAt": 1761370000000,
    "type": "msa"
  }
}
```

> **注意**：`accessToken` 和 `refreshToken` **永远不会**出现在响应里。调试接口不该成为凭据泄漏点。

#### `POST /account/login`

开始微软设备码登录。立即返回验证码。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/account/login"
```

```json
{
  "ok": true,
  "data": {
    "userCode": "K7QX9M2PL",
    "verificationUri": "https://www.microsoft.com/link",
    "verificationUriComplete": "https://www.microsoft.com/link?otc=K7QX9M2PL",
    "expiresIn": 900,
    "pollInterval": 5,
    "hint": "让用户在浏览器打开链接输入 userCode，然后轮询 GET /account 看 loggedIn"
  }
}
```

流程：

1. 调这个接口拿到 `userCode`；
2. 让用户在浏览器打开 `verificationUriComplete`（或 `verificationUri` 手动填 `userCode`）；
3. **轮询 `GET /account`**，直到 `loggedIn: true`；
4. 超过 `expiresIn` 秒没完成就失效，需重新调。

```bash
# 轮询登录结果
for i in $(seq 1 180); do
  R=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/account")
  echo "$R" | grep -q '"loggedIn":true' && { echo "✅ 登录成功"; break; }
  sleep 5
done
```

#### `POST /account/login/cancel`

```json
{"ok":true,"data":{"cancelled":true}}
```

#### `POST /account/logout`

```json
{"ok":true,"data":{"loggedOut":true}}
```

#### `POST /account/offline`

设置离线模式的用户名（离线 UUID 由名字派生，保证同名同 UUID）。

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Steve"}' \
  "$BASE/account/offline"
```

```json
{"ok":true,"data":{"ok":true,"name":"Steve"}}
```

名字为空白：

```json
HTTP/1.1 400 Bad Request
{"ok":false,"error":{"code":"bad_request","message":"用户名不能为空"}}
```

---

### 5.7 更新

#### `GET /update`

查 GitHub Release。

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/update"
```

已是最新：

```json
{
  "ok": true,
  "data": {
    "hasUpdate": false,
    "currentVersion": "1.0.1",
    "currentVersionCode": 10001
  }
}
```

有新版：

```json
{
  "ok": true,
  "data": {
    "hasUpdate": true,
    "currentVersion": "1.0.1",
    "currentVersionCode": 10001,
    "latestVersion": "1.0.2",
    "latestVersionCode": 10002,
    "tag": "v1.0.2",
    "publishedAt": "2026-10-24T10:00:00Z",
    "notes": "修了什么什么",
    "deviceAbi": "arm64",
    "asset": {
      "name": "MizuLauncher-1.0.2-10002-arm64.apk",
      "url": "https://github.com/.../MizuLauncher-1.0.2-10002-arm64.apk",
      "sizeBytes": 1572864,
      "sizeText": "1.5 MB",
      "abi": "arm64"
    },
    "allAssets": [ ... ]
  }
}
```

| 字段 | 说明 |
|---|---|
| `deviceAbi` | 本机判定出的架构标记，解释「为什么选了这个包」 |
| `asset` | 为**本机**挑的包。`null` = 有新版但没适配本机的包 |
| `asset.abi` | `arm64` / `arm32` / `x64` / `x86` / `universal` |

> **不会**把 arm 包推给 x86 设备。匹配不到精确架构时只回退 `universal`，绝不装错包。

---

### 5.8 设置

#### `GET /settings`

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/settings"
```

```json
{
  "ok": true,
  "data": {
    "downloadSource": "Bmclapi",
    "downloadThreads": 6,
    "maxMemoryMb": 2048,
    "renderer": "VIRGL",
    "lastAccount": "Steve",
    "themeMode": "system",
    "runtimeManifestUrl": null
  }
}
```

#### `PATCH /settings`

部分更新，只改出现的字段。

```bash
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"maxMemoryMb":4096,"downloadThreads":8}' \
  "$BASE/settings"
```

可改字段：

| 字段 | 类型 | 范围 | 说明 |
|---|---|---|---|
| `downloadSource` | string | 源 id | 等价于 `POST /sources` |
| `downloadThreads` | int | 1–32 | 并发文件数 |
| `maxMemoryMb` | int | — | 建议不超过设备内存的 1/2 |
| `renderer` | string | `VIRGL` / `GL4ES` / ... | 渲染器 |
| `lastAccount` | string | — | 离线用户名 |
| `themeMode` | string | `system` / `light` / `dark` | 主题 |
| `runtimeManifestUrl` | string | — | 远端 JRE 清单地址，用来补 APK 里没带的 Java |

---

### 5.9 日志

#### `GET /logs`

| 查询参数 | 默认 | 范围 |
|---|---|---|
| `lines` | 200 | 1–2000 |

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/logs?lines=500"
```

```json
{
  "ok": true,
  "data": {
    "lines": [
      "[10:23:41] 检查 Java 运行时",
      "[10:23:42] Java 17 已就绪",
      "[10:23:43] 下载资源索引"
    ],
    "capacity": 3000,
    "hint": "只有在 App 里打开过调试日志采集才会有内容"
  }
}
```

缓冲区容量 3000 行，超出丢最旧的。

---

## 6. 常见调试场景

### 6.1 从零跑起来一个原版 1.20.1

```bash
#!/bin/sh
set -e
INFO=$(su -c 'cat /data/data/com.mizulauncher/files/mizu-debug-api.json')
PORT=$(echo "$INFO" | grep -o '"port":[0-9]*' | grep -o '[0-9]*')
TOKEN=$(echo "$INFO" | grep -o '"token":"[a-f0-9]*"' | cut -d'"' -f4)
BASE="http://127.0.0.1:$PORT/api/v1"
H="Authorization: Bearer $TOKEN"

# 1. 确认服务和界面都就绪
curl -s "$BASE/health" | grep -q '"ok":true' || { echo "服务没起来"; exit 1; }
curl -s -H "$H" "$BASE/diagnostics" | grep -q '"uiReady":true' || { echo "先把 App 打开"; exit 1; }

# 2. 补全运行依赖（首启必需）
PENDING=$(curl -s -H "$H" "$BASE/bundles" | grep -o '"pending":[0-9]*' | cut -d: -f2)
if [ "$PENDING" != "0" ]; then
  echo "补全 $PENDING 个运行依赖…"
  curl -s -X POST -H "$H" "$BASE/bundles/install"
  while :; do
    INS=$(curl -s -H "$H" "$BASE/task" | grep -o '"installing":[a-z]*' | cut -d: -f2)
    [ "$INS" = "false" ] && break
    sleep 2
  done
fi

# 3. 建实例
ID=$(curl -s -X POST -H "$H" -H "Content-Type: application/json" \
  -d '{"versionId":"1.20.1","name":"调试实例"}' "$BASE/instances" \
  | grep -o '"id":"[a-f0-9]*"' | head -1 | cut -d'"' -f4)
echo "实例 id: $ID"

# 4. 准备文件（可能要几分钟）
curl -s -X POST -H "$H" "$BASE/instances/$ID/prepare"
while :; do
  R=$(curl -s -H "$H" "$BASE/task")
  echo "$R" | grep -q '"installing":false' && break
  echo "$R" | grep -o '"percent":[0-9]*'
  sleep 3
done

# 5. 启动
curl -s -X POST -H "$H" "$BASE/instances/$ID/launch"
echo "已触发启动，用 GET /logs 看输出"
```

### 6.2 下载卡住了怎么查

```bash
# 看卡在哪个文件
curl -s -H "$H" "$BASE/task" | grep -o '"currentFile":"[^"]*"'

# 看速度和 ETA
curl -s -H "$H" "$BASE/task" | grep -o '"speedBps":[0-9]*\|"etaSeconds":[0-9-]*'

# 换源重试
curl -s -X POST -H "$H" -d '{"id":"Official"}' -H "Content-Type: application/json" "$BASE/sources"
curl -s -X POST -H "$H" "$BASE/task/cancel"
curl -s -X POST -H "$H" "$BASE/instances/$ID/prepare"
```

### 6.3 游戏起不来

```bash
# 1. 看最后的错误
curl -s -H "$H" "$BASE/task" | grep -o '"error":"[^"]*"'

# 2. 看日志尾巴
curl -s -H "$H" "$BASE/logs?lines=100"

# 3. 确认运行时齐不齐
curl -s -H "$H" "$BASE/runtimes"

# 4. 确认实例的 javaMajor
curl -s -H "$H" "$BASE/instances/$ID" | grep -o '"javaMajor":[0-9]*'
```

常见原因对照：

| 现象 | 大概率原因 | 怎么办 |
|---|---|---|
| `error` 含 `Java N 不可用` | 运行时缺失 | `POST /bundles/install`，或设 `runtimeManifestUrl` 走远端清单 |
| `error` 含 `版本清单中找不到` | `versionId` 拼错，或加载器没装 | `GET /versions` 核对 id |
| `error` 含 `Pojav 内核` | 内核 submodule 没引入 | 这是源码构建问题，不是接口问题 |
| 一直 `installing:true` 不动 | 网络断了，或镜像挂了 | 换源 + cancel + prepare |
| `bundlesPending > 0` | 依赖没补全 | `POST /bundles/install` |

### 6.4 设备码登录全流程

```bash
# 1. 拿码
RESP=$(curl -s -X POST -H "$H" "$BASE/account/login")
CODE=$(echo "$RESP" | grep -o '"userCode":"[^"]*"' | cut -d'"' -f4)
URL=$(echo "$RESP" | grep -o '"verificationUriComplete":"[^"]*"' | cut -d'"' -f4)
echo "打开：$URL"
echo "或输入：$CODE"

# 2. 轮询
for i in $(seq 1 180); do
  curl -s -H "$H" "$BASE/account" | grep -q '"loggedIn":true' && { echo "登录成功"; break; }
  sleep 5
done
```

---

## 7. 对接实现说明（给你写客户端时参考）

### 7.1 时间常量

| 场景 | 建议超时 |
|---|---|
| 普通 GET | 5 秒 |
| `/versions` | 10 秒（内部最多等 3 秒） |
| `/update` | 30 秒（要连 GitHub） |
| `/account/login` | 15 秒（内部最多等 5 秒） |
| `/task` 轮询间隔 | 2 秒（下载）/ 5 秒（登录） |

### 7.2 并发

服务端是 4 线程池。**不要**并发几十个请求——会被排队。正常调试一两个并发足够。

### 7.3 状态机

```
空闲 ──prepare/launch──> 运行中 ──┬──> 成功（installing=false, error=null）
                                  └──> 失败（installing=false, error="..."）
```

`installing` 是唯一可靠的「在不在跑」判据。不要根据 `stage` 文案判断。

### 7.4 幂等性

| 接口 | 幂等 |
|---|---|
| `GET *` | ✅ |
| `POST /instances/{id}/prepare` | ✅（已下好的按 SHA1 跳过） |
| `POST /task/cancel` | ✅ |
| `PATCH /instances/{id}` | ✅ |
| `POST /instances` | ❌（每次都会新建） |
| `DELETE` | ❌（第二次返回 404） |

---

## 8. 实现位置

想改接口时改这几个文件：

| 文件 | 职责 |
|---|---|
| `core/debug-api/.../ApiRoutes.kt` | **路由表**。加接口只改这里，同时更新 `ENDPOINTS` |
| `core/debug-api/.../Router.kt` | 分发、参数解析、错误映射 |
| `core/debug-api/.../DebugApiServer.kt` | HTTP 服务、token 鉴权、加密通道、端点文件 |
| `core/debug-api/.../SecureChannel.kt` | 密钥派生、AES-GCM 加解密、AAD 约定 |
| `core/debug-api/.../LauncherFacade.kt` | 能力契约 |
| `app/.../debug/AppLauncherFacade.kt` | 真实实现，接 ViewModel |
| `app/.../debug/DebugApiHost.kt` | 启停（当前是无条件启动） |
| `scripts/mizu_api.py` | Python 参考客户端，加密协议的第二份实现 |

改完跑：

```bash
./gradlew :core:debug-api:test --rerun-tasks
```

`RouterTest` 里有一条 `文档里列出的每个接口都真的存在`——它会拿 `ENDPOINTS` 逐个打一遍。
**加了路由却忘了加进 `ENDPOINTS`（或反之）会直接失败**，从机制上防了文档和代码脱节。

`SecureChannelTest` 末尾有一组**跨语言参考向量**：密钥、密文都写死成常量，这些常量是用 Python 独立算出来的。任何改动（换标签、改字节序、动 AAD 分隔符）都会立刻让它们变红——加密协议一旦漂移，要等到用户拿脚本连不上才发现，排查成本高得多。

CI 还会跑 `python3 scripts/check_api_doc.py` 比对文档与路由表。

---

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-24 | v1 首发，27 个接口 |
| 2026-10-25 | 改为**无条件默认开启**（去掉开关文件）；新增 AES-256-GCM 全链路加密通道（§3.4）；修正 AAD 序号重复拼接，与 Python 参考实现逐字节对齐 |
