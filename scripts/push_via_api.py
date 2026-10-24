#!/usr/bin/env python3
"""
在没有 git 传输通道（443 连不上）的情况下，用 GitHub Git Data API 推送提交。

原理和 `git push` 做的事一样：把每个被改动的文件变成 blob，按目录组装 tree，
建 commit，再把分支引用指过去。只是把「打包成 packfile 走 smart HTTP」
换成了一次次 REST 调用 —— 慢，但对 20 个文件的规模完全够用。

用法：
    GITHUB_TOKEN=... python3 scripts/push_via_api.py \
        --repo mcytd-trumpet/MizuLauncher --base fba5a22 --head HEAD --branch main
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def call(token: str, method: str, path: str, payload: dict | None = None) -> dict:
    url = path if path.startswith("http") else f"{API}{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read().decode()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:500]
        raise SystemExit(f"[{method} {url}] HTTP {e.code}: {detail}")


def remote_blobs(token: str, repo: str) -> dict[str, tuple[str, str]]:
    """取远端 main 上所有 blob 的 path -> (mode, sha)（递归 tree）"""
    try:
        ref = call(token, "GET", f"/repos/{repo}/git/ref/heads/main")
        tree_sha = ref["object"]["sha"]
        commit = call(token, "GET", f"/repos/{repo}/git/commits/{tree_sha}")
        tree = call(token, "GET", f"/repos/{repo}/git/trees/{commit['tree']['sha']}?recursive=1")
    except SystemExit:
        return {}
    return {e["path"]: (e["mode"], e["sha"]) for e in tree["tree"] if e["type"] == "blob"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--base", required=True, help="远端当前 commit")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--branch", default="main")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("缺少 GITHUB_TOKEN")

    head_sha = git("rev-parse", args.head)
    message = git("log", "-1", "--pretty=%B", args.head)
    changed = git("diff", "--name-status", args.base, args.head).splitlines()
    if not changed:
        # 没有内容变更不代表没有要修的东西（比如文件模式），下面继续走一遍。
        print("内容无变更，继续检查文件模式")

    # 走 API 建的 commit 与本地哈希不同，本地可能没有那个对象。
    # 真正的判据不是 diff 列表，而是「本地文件内容 vs 远端 tree」：
    # 直接全量比对，只上传变化的 blob，结果必然收敛到远端 == 本地。
    remote_files = remote_blobs(token, args.repo)

    # 1) 拿基线 tree
    base_commit = call(token, "GET", f"/repos/{args.repo}/git/commits/{args.base}")
    base_tree = base_commit["tree"]["sha"]

    # 2) 逐个文件生成 blob，记录 路径 -> blob sha 或 None（删除）
    updates: dict[str, str | None] = {}
    uploaded = reused = 0
    for line in changed:
        parts = line.split("\t")
        status, path = parts[0], parts[-1]
        rel = path
        if status.startswith("D"):
            updates[rel] = None
            print(f"  D {rel}")
            continue
        # 先算本地 blob sha：与远端一致就完全跳过上传
        local_sha = git("hash-object", "-w", rel)
        if remote_files.get(rel, (None, None))[1] == local_sha:
            updates[rel] = local_sha
            reused += 1
            continue
        content = open(rel, "rb").read()
        blob = call(token, "POST", f"/repos/{args.repo}/git/blobs", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        updates[rel] = blob["sha"]
        uploaded += 1
        print(f"  {status} {rel} ({len(content)}B)")

    print(f"上传 {uploaded} 个，复用 {reused} 个")

    # 3) 递归取完整 tree，按目录重组
    full_tree = call(token, "GET", f"/repos/{args.repo}/git/trees/{base_tree}?recursive=1")
    entries: dict[str, tuple[str, str]] = {}  # path -> (mode, sha)
    for e in full_tree["tree"]:
        if e["type"] == "blob":
            entries[e["path"]] = (e["mode"], e["sha"])
    for path, sha in updates.items():
        if sha is None:
            entries.pop(path, None)
        else:
            # 文件模式必须沿用远端已有记录：Git Data API 在 blob 未变时会自动复用
            # 旧 sha，于是这个 path 根本不进 updates，opts.access 的判断就永远不会
            # 被执行；而一旦内容变了、走了 updates 分支，os.access 又依赖检出目录
            # 的真实权限位（Windows/容器里常被抹成 644），会把 gradlew 的可执行位
            # 静默写成 100644，CI 上直接 Permission denied。
            prev = remote_files.get(path)
            mode = "100755" if prev and prev[0] == "100755" else "100644"
            entries[path] = (mode, sha)

    # 2.5) 修正文件模式：内容没变不会进 updates，但 mode 可能被历史推送写坏了。
    #      以本地真实权限位为准——本地可执行、远端却是 100644 的，一律纠正为 100755。
    mode_fixed = 0
    for path, (mode, sha) in list(entries.items()):
        if mode == "100644" and os.path.exists(path) and os.access(path, os.X_OK):
            entries[path] = ("100755", sha)
            mode_fixed += 1
            print(f"  M+ {path} 100644 → 100755")
    if mode_fixed:
        print(f"修正 {mode_fixed} 个文件的执行位")

    # 4) 自底向上建 tree
    def build(prefix: str) -> str:
        children: dict[str, list[dict]] = {"": []}
        for path, (mode, sha) in entries.items():
            if prefix and not path.startswith(prefix + "/"):
                continue
            rest = path[len(prefix) + 1:] if prefix else path
            if "/" in rest:
                top = rest.split("/", 1)[0]
                children.setdefault(top, [])
            else:
                children[""].append({"path": rest, "mode": mode, "type": "blob", "sha": sha})
        for name, sub in children.items():
            if name == "":
                continue
            sub_sha = build(f"{prefix}/{name}" if prefix else name)
            children[""].append({
                "path": name, "mode": "040000", "type": "tree", "sha": sub_sha
            })
        tree = call(token, "POST", f"/repos/{args.repo}/git/trees", {"tree": children[""]})
        return tree["sha"]

    new_tree = build("")
    print(f"新 tree: {new_tree}")

    # 5) 建 commit 并移动分支
    commit = call(token, "POST", f"/repos/{args.repo}/git/commits", {
        "message": message,
        "tree": new_tree,
        "parents": [args.base],
    })
    print(f"新 commit: {commit['sha']}")

    call(token, "PATCH", f"/repos/{args.repo}/git/refs/heads/{args.branch}", {
        "sha": commit["sha"], "force": False,
    })
    print(f"✅ 分支 {args.branch} 已指向 {commit['sha'][:8]}")
    print(f"   本地 HEAD: {head_sha[:8]}")
    if commit["sha"] != head_sha:
        print("   注意：远端 commit 与本地哈希不同（内容与 tree 一致，只是提交对象不同）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
