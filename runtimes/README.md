# 内置 Java 运行时

2.0 的策略是 **四个 Java 版本（8 / 17 / 21 / 25）全部内置进 APK**，按 ABI 分目录：

```
app/src/main/assets/runtime/
  ├── arm64-v8a/jre-8.zip   jre-17.zip   jre-21.zip   jre-25.zip
  ├── armeabi-v7a/jre-8.zip jre-17.zip
  ├── x86/jre-8.zip         jre-17.zip
  └── x86_64/jre-8.zip      jre-17.zip   jre-21.zip   jre-25.zip
```

32 位架构没有 Java 21/25 的上游构建（不是打包遗漏，是确实没有移植）。

## 为什么不把 zip 提交进 Git

单个 JRE 压缩后 40–90 MB，四个架构十二个包加起来 1.5 GB 以上。
Git 仓库装不下这种体积，且每次改动都会产生完整副本。

所以仓库里只放**清单**（`jre-index.json`），二进制由构建时下载：

```bash
# 1. 把上游归档放到一个目录（文件名形如 jre17-arm64-v8a.tar.gz）
# 2. 整理成 assets 结构并生成清单
python3 scripts/package_runtimes.py --dir ./downloads \
    --out app/src/main/assets --manifest runtimes/jre-index.json

# 3. 正常打包，APK 里就带上了
./gradlew :app:assembleArm64Release
```

CI 上通过仓库变量 `JRE_ARCHIVES_URL` 指向一个 JSON 索引（或打包好的 tar.gz），
构建时会自动执行上面两步。没配这个变量时构建**依然成功**，只是 APK 里没有预装 Java，
首启会走「云端下载兜底」——那条路径能跑，但首次启动需要网络。

## 兜底路径

`RuntimeManager` 的两条来源：

| 来源 | 触发条件 | 需要网络 |
|---|---|---|
| APK 内置（`assets/runtime/<abi>/jre-N.zip`） | 首启「补全依赖」解压到 `files/bundles/runtime/java-N` | 否 |
| 远端下载（`jre-index.json` 的 `url` 字段） | 内置缺失、且设置里配了清单地址 | 是 |

内置优先，因为装机即用；远端兜底，保证漏打一个架构时不至于彻底不可用。
