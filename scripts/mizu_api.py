#!/usr/bin/env python3
"""
MizuLauncher 调试 API 加密客户端。

明文通道（curl + token）适合手工点一下看结果；这个脚本走的是加密通道，
用于脚本化、自动化，以及「不想让同机其他进程从网络层看到明文」的场景。

## 为什么加密

通道是回环（127.0.0.1），链路本身窃听不到。但同一台设备上的其他进程
（尤其是有 root 或有 VPN 权限的）可以直接监听回环流量。明文通道下：
  - 你 POST 的存档名、实例名一览无余；
  - 微软账号的设备码、用户信息都在线上；
  - 请求体里的路径会暴露你的目录结构。

加密通道把这些全罩住。密钥由 token 派生，**token 本身不进报文**。

## 用法

    # 一次性调用
    ./scripts/mizu_api.py --token <token> --port 38471 GET /instances

    # 带 body
    ./scripts/mizu_api.py --token <token> --port 38471 POST /instances \\
        --json '{"versionId":"1.20.1","name":"测试"}'

    # 自动从设备读 token（需要 shell 权限）
    ./scripts/mizu_api.py --auto GET /diagnostics

    # 明文模式对照（调试用）
    ./scripts/mizu_api.py --token <token> --port 38471 --plain GET /health

## 协议（详见 docs/DEBUG_API.md 第 3 章）

    请求头：
      Authorization: Bearer <token>
      X-Mizu-Encrypted: 1
      X-Mizu-Salt: <16 字节 hex>
      X-Mizu-Ts: <毫秒时间戳>
      X-Mizu-Seq: <序号>
    请求体：AES-256-GCM 密文（hex 包装成 {"cipher":"..."} 还是裸字节？见下）

    密钥派生：
      prk     = HMAC-SHA256(key=token, msg=salt || ts_be64)
      encKey  = HMAC-SHA256(key=prk, msg="mizu-enc"   || salt)
      nonceKey= HMAC-SHA256(key=prk, msg="mizu-nonce" || salt)

    nonce = HMAC-SHA256(nonceKey, seq_be64)[:12]
    AAD   = seq_be64 || "<METHOD>|<path>"

    注意 AAD 里序号**只出现一次**（be64 前缀那一次）。服务端的 SecureChannel
    内部会统一加这个前缀，所以任何客户端都只拼 "<METHOD>|<path>"。
    path 不含 query string。

    请求体 = 原始 GCM 密文字节（不是 hex，靠 Content-Length 界定）
    响应体 = JSON: {"cipher":"<hex>"}，带 X-Mizu-Encrypted: 1
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------------------
# 加密原语。用标准库实现，不依赖 cryptography —— 安卓端也是同样算法的原生实现，
# 双方对着同一份规范写，比引一个库更容易核对。
# ---------------------------------------------------------------------------

def be64(v: int) -> bytes:
    """大端 8 字节无符号整数"""
    return struct.pack(">Q", v & 0xFFFFFFFFFFFFFFFF)


def hmac_sha256(key: bytes, msg: bytes) -> bytes:
    return hmac.new(key, msg, hashlib.sha256).digest()


def derive_keys(token: str, salt: bytes, ts: int) -> tuple[bytes, bytes]:
    token_bytes = token.encode("utf-8")
    prk = hmac_sha256(token_bytes, salt + be64(ts))
    enc_key = hmac_sha256(prk, b"mizu-enc" + salt)
    nonce_key = hmac_sha256(prk, b"mizu-nonce" + salt)
    return enc_key, nonce_key


def gcm_encrypt(key: bytes, nonce: bytes, aad: bytes, plaintext: bytes) -> bytes:
    """AES-256-GCM 加密，返回 密文||tag"""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        print(
            "缺少 cryptography 库。安装：pip3 install cryptography\n"
            "（Python 标准库没有 AES-GCM，安卓端用的是 javax.crypto，"
            "这个脚本只能借第三方库）",
            file=sys.stderr,
        )
        sys.exit(2)
    return AESGCM(key).encrypt(nonce, plaintext, aad)


def gcm_decrypt(key: bytes, nonce: bytes, aad: bytes, ciphertext: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(nonce, ciphertext, aad)


# ---------------------------------------------------------------------------
# 客户端
# ---------------------------------------------------------------------------

class MizuClient:
    def __init__(self, host: str, port: int, token: str, plain: bool = False):
        self.host = host
        self.port = port
        self.token = token
        self.plain = plain
        self.seq = 0

    def _url(self, path: str) -> str:
        p = path if path.startswith("/") else "/" + path
        if not p.startswith("/api/"):
            p = "/api/v1" + p
        return f"http://{self.host}:{self.port}{p}"

    def call(
        self,
        method: str,
        path: str,
        body: bytes = b"",
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, bytes]:
        """发一次请求，返回 (状态码, 明文响应体)"""
        url = self._url(path)
        headers = {"Authorization": f"Bearer {self.token}"}
        if extra_headers:
            headers.update(extra_headers)

        if self.plain:
            headers["Content-Type"] = "application/json"
            req = urllib.request.Request(url, data=body or None, method=method, headers=headers)
            return self._send(req, encrypted_response=False)

        # ---- 加密通道 ----
        self.seq += 1
        seq = self.seq
        salt = secrets.token_bytes(16)
        ts = int(time.time() * 1000)
        enc_key, nonce_key = derive_keys(self.token, salt, ts)

        # AAD 绑定的是「HTTP 请求行里的 path」，**不含 query string**。
        # 服务端用的是 target.substringBefore('?')，所以这里也必须切掉，
        # 否则带 ?force=true 的请求会因为 AAD 不一致而解不开。
        #
        # 序号只在 be64(seq) 前缀里出现这一次。服务端 SecureChannel.encrypt()
        # 内部会自动补上前缀，所以业务 AAD 只有 "<METHOD>|<path>"。
        target = url.split(f"{self.host}:{self.port}", 1)[1]
        full_path = target.split("?", 1)[0]
        aad = be64(seq) + f"{method}|{full_path}".encode("utf-8")
        nonce = hmac_sha256(nonce_key, be64(seq))[:12]
        cipher = gcm_encrypt(enc_key, nonce, aad, body)

        headers.update({
            "X-Mizu-Encrypted": "1",
            "X-Mizu-Salt": salt.hex(),
            "X-Mizu-Ts": str(ts),
            "X-Mizu-Seq": str(seq),
            # 请求体是裸密文，Content-Type 用 octet-stream
            "Content-Type": "application/octet-stream",
        })
        req = urllib.request.Request(url, data=cipher or None, method=method, headers=headers)
        status, raw = self._send(req, encrypted_response=True)

        if raw.startswith(b'{"cipher"'):
            # 响应是 {"cipher":"<hex>"}。nonce 与请求同一个（都按 seq 派生），
            # 所以这里直接用上面算好的 nonce —— 重新算一遍等于多一次出错机会。
            hexed = json.loads(raw.decode()).get("cipher", "")
            resp_cipher = bytes.fromhex(hexed)
            return status, gcm_decrypt(enc_key, nonce, aad, resp_cipher)
        return status, raw

    def _send(self, req, encrypted_response: bool) -> tuple[int, bytes]:
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except urllib.error.URLError as e:
            raise SystemExit(f"连接失败：{e.reason}（服务起来了吗？端口对吗？）")


# ---------------------------------------------------------------------------
# 自动发现
# ---------------------------------------------------------------------------

def auto_discover() -> tuple[int, str]:
    """
    从设备上读端点文件。依次尝试 root / run-as。

    路径可能在 /data/data 或 /data/user/0，两种都试。
    """
    candidates = [
        "/data/user/0/com.mizulauncher/files/mizu-debug-api.json",
        "/data/data/com.mizulauncher/files/mizu-debug-api.json",
    ]
    for path in candidates:
        for cmd in (["su", "-c", f"cat {path}"], ["run-as", "com.mizulauncher", "cat", path]):
            try:
                out = subprocess.run(
                    cmd, capture_output=True, text=True, timeout=10
                )
                if out.returncode == 0 and out.stdout.strip().startswith("{"):
                    info = json.loads(out.stdout)
                    return int(info["port"]), info["token"]
            except (FileNotFoundError, subprocess.TimeoutExpired, json.JSONDecodeError, KeyError):
                continue
    raise SystemExit(
        "自动发现失败。请确认：\n"
        "  1. App 已经启动过（服务随 App 启动）；\n"
        "  2. 当前 shell 有 root 或 run-as 权限；\n"
        "  3. 或者手工传 --port 和 --token。"
    )


def main() -> int:
    ap = argparse.ArgumentParser(
        description="MizuLauncher 调试 API 客户端（默认走加密通道）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  %(prog)s --auto GET /diagnostics\n"
               "  %(prog)s --auto POST /instances --json '{\"versionId\":\"1.20.1\"}'\n",
    )
    ap.add_argument("--auto", action="store_true", help="从设备自动读取 port + token")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int)
    ap.add_argument("--token")
    ap.add_argument("--plain", action="store_true", help="走明文通道（仅调试对照用）")
    ap.add_argument("--raw", action="store_true", help="只输出响应体，不美化")
    ap.add_argument("method", help="GET / POST / PATCH / DELETE")
    ap.add_argument("path", help="例如 /instances 或 /instances/abc/prepare")
    ap.add_argument("--json", dest="body", default="", help="请求体 JSON")
    args = ap.parse_args()

    if args.auto:
        port, token = auto_discover()
    else:
        if not args.port or not args.token:
            raise SystemExit("需要 --port 和 --token，或者用 --auto")
        port, token = args.port, args.token

    client = MizuClient(args.host, port, token, plain=args.plain)
    body = args.body.encode("utf-8") if args.body else b""
    status, raw = client.call(args.method.upper(), args.path, body)

    text = raw.decode("utf-8", errors="replace")
    if args.raw:
        sys.stdout.write(text)
    else:
        try:
            print(f"HTTP {status}")
            print(json.dumps(json.loads(text), indent=2, ensure_ascii=False))
        except json.JSONDecodeError:
            print(text)

    return 0 if 200 <= status < 300 else 1


if __name__ == "__main__":
    sys.exit(main())
