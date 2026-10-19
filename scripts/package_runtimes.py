#!/usr/bin/env python3
"""
把外部的移动版 JRE 归档（tar.gz / zip）整理成 APK 能直接吃的 assets 结构。

## 为什么需要这一步
APK 里 assets 的路径是 `runtime/<abi>/jre-<major>.zip`，一个 ABI 一个 major 一个包。
但上游（Pojav 的 CI、社区自建 JDK、Termux 包）给出的东西五花八门：
有的是 `tar.gz`、有的内层套了一层 `jre/`、有的把 `bin/` 直接放在根。
如果让 App 在手机上做这些判断，既慢又容易出错 —— 挪到构建期做，装机即用。

## 用法
    # 单个文件
    python3 scripts/package_runtimes.py \
        --in jre8-arm64.tar.gz --abi arm64-v8a --major 8 \
        --out app/src/main/assets/runtime

    # 按目录批量：文件名需形如 jre17-arm64-v8a.tar.gz / jre21-x86_64.zip
    python3 scripts/package_runtimes.py --dir ./downloads --out app/src/main/assets/runtime

## 同时产出清单（供 CI 填进 release 附件 / 远端清单）
    --manifest out/jre-index.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import zipfile

# (abi 目录名) -> 识别用的别名；用于从未知文件名里猜 ABI
ABI_ALIASES = {
    "arm64-v8a": ["arm64-v8a", "arm64", "aarch64"],
    "armeabi-v7a": ["armeabi-v7a", "arm32", "armv7", "armhf"],
    "x86": ["x86", "i686", "i386"],
    "x86_64": ["x86_64", "x64", "amd64"],
}

MARKERS = [
    ("bin/java", "bin/java"),
    ("lib/server/libjvm.so", "lib/server/libjvm.so"),
    ("lib/client/libjvm.so", "lib/client/libjvm.so"),
]


def log(msg: str) -> None:
    print(f"[jre] {msg}", flush=True)


def guess_abi(name: str) -> str | None:
    low = name.lower()
    # 先匹配更长的别名，避免 "arm64" 被 "arm" 抢先
    best = None
    best_len = 0
    for abi, aliases in ABI_ALIASES.items():
        for a in aliases:
            if a in low and len(a) > best_len:
                best, best_len = abi, len(a)
    return best


def guess_major(name: str) -> int | None:
    m = re.search(r"(?:jre|jdk|java)[-_]?(\d+)", name, re.IGNORECASE)
    if m:
        return int(m.group(1))
    m = re.search(r"\b(8|11|17|21|25)\b", name)
    return int(m.group(1)) if m else None


def is_jre_root(path: str) -> bool:
    return any(os.path.exists(os.path.join(path, m)) for m, _ in MARKERS)


def find_jre_root(path: str, depth: int = 3) -> str | None:
    """在一棵目录里找真正的 JRE 根（含 bin/java 或 libjvm.so 的那一层）"""
    if is_jre_root(path):
        return path
    if depth <= 0:
        return None
    try:
        entries = sorted(os.listdir(path))
    except OSError:
        return None
    for e in entries:
        sub = os.path.join(path, e)
        if os.path.isdir(sub):
            found = find_jre_root(sub, depth - 1)
            if found:
                return found
    return None


def extract(archive: str, dest: str) -> None:
    if tarfile.is_tarfile(archive):
        with tarfile.open(archive) as tf:
            # Python 3.12+ 才有 filter 参数，手动做一层防护
            for member in tf.getmembers():
                target = os.path.realpath(os.path.join(dest, member.name))
                if not target.startswith(os.path.realpath(dest) + os.sep):
                    raise RuntimeError(f"非法归档路径：{member.name}")
            tf.extractall(dest)
    elif zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as zf:
            for name in zf.namelist():
                target = os.path.realpath(os.path.join(dest, name))
                if not target.startswith(os.path.realpath(dest) + os.sep):
                    raise RuntimeError(f"非法归档路径：{name}")
            zf.extractall(dest)
    else:
        raise RuntimeError(f"无法识别的归档格式：{archive}")


def fix_exec_bits(root: str) -> None:
    """bin/ 下的所有文件都补上可执行位；否则解压出来的 java 无法 exec"""
    bin_dir = os.path.join(root, "bin")
    if not os.path.isdir(bin_dir):
        return
    for f in os.listdir(bin_dir):
        p = os.path.join(bin_dir, f)
        if os.path.isfile(p):
            os.chmod(p, os.stat(p).st_mode | 0o755)


def zip_dir(src: str, out_zip: str) -> None:
    """把目录打成 zip，保留 unix 权限位（ZipInfo.external_attr）"""
    os.makedirs(os.path.dirname(out_zip), exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for dirpath, dirnames, filenames in os.walk(src):
            dirnames.sort()
            for fn in sorted(filenames):
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, src)
                st = os.stat(full)
                info = zipfile.ZipInfo(rel)
                info.compress_type = zipfile.ZIP_DEFLATED
                # 高 16 位放 unix mode，低 16 位放 dos 属性
                info.external_attr = (st.st_mode & 0xFFFF) << 16
                info.file_size = st.st_size
                with open(full, "rb") as fh:
                    zf.writestr(info, fh.read())


def sha1_of(path: str) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def package_one(archive: str, abi: str, major: int, out_root: str) -> dict:
    log(f"处理 {os.path.basename(archive)} → {abi} / Java {major}")
    with tempfile.TemporaryDirectory() as tmp:
        extract(archive, tmp)
        root = find_jre_root(tmp)
        if not root:
            raise RuntimeError(
                f"{archive} 里找不到 JRE（没有 bin/java 或 lib/*/libjvm.so）"
            )
        fix_exec_bits(root)
        rel = os.path.join("runtime", abi, f"jre-{major}.zip")
        out_zip = os.path.join(out_root, rel)
        zip_dir(root, out_zip)

    size = os.path.getsize(out_zip)
    digest = sha1_of(out_zip)
    log(f"  → {out_zip} ({size / 1024 / 1024:.1f} MB, sha1={digest[:12]}…)")
    return {
        "major": major,
        "abi": abi,
        "url": rel.replace(os.sep, "/"),
        "sha1": digest,
        "size": size,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="打包移动版 JRE 到 APK assets")
    ap.add_argument("--in", dest="single", help="单个归档文件")
    ap.add_argument("--abi", help="ABI 目录名（arm64-v8a / armeabi-v7a / x86 / x86_64）")
    ap.add_argument("--major", type=int, help="Java 大版本（8/17/21/25）")
    ap.add_argument("--dir", help="批量处理目录，文件名需含 abi 与版本号")
    ap.add_argument("--out", default="app/src/main/assets", help="assets 输出目录")
    ap.add_argument("--manifest", help="同时输出 jre-index.json 的路径")
    args = ap.parse_args()

    entries: list[dict] = []

    if args.single:
        if not (args.abi and args.major):
            ap.error("--in 必须配合 --abi 与 --major")
        entries.append(package_one(args.single, args.abi, args.major, args.out))

    if args.dir:
        files = [
            os.path.join(args.dir, f)
            for f in sorted(os.listdir(args.dir))
            if f.lower().endswith((".tar.gz", ".tgz", ".zip", ".tar.xz", ".tar"))
        ]
        if not files:
            log(f"{args.dir} 里没有找到归档文件")
        for f in files:
            base = os.path.basename(f)
            abi = guess_abi(base)
            major = guess_major(base)
            if not abi or not major:
                log(f"跳过 {base}：无法从文件名推断 ABI 或 Java 版本")
                continue
            try:
                entries.append(package_one(f, abi, major, args.out))
            except Exception as e:  # noqa: BLE001
                log(f"失败 {base}：{e}")

    if not entries:
        log("没有任何条目被处理")
        return 1

    if args.manifest:
        os.makedirs(os.path.dirname(os.path.abspath(args.manifest)), exist_ok=True)
        with open(args.manifest, "w", encoding="utf-8") as fh:
            json.dump({"entries": entries}, fh, ensure_ascii=False, indent=2)
        log(f"清单已写入 {args.manifest}")

    log(f"完成，共 {len(entries)} 个运行时")
    return 0


if __name__ == "__main__":
    sys.exit(main())
