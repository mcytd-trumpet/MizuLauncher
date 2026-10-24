#!/usr/bin/env python3
"""
校验 `docs/DEBUG_API.md` 与 `ApiRoutes.kt` 里的接口清单是否一致。

加这个脚本的原因：调试文档最容易烂掉的方式不是写错，而是**只改了一边**。
代码里加了接口忘了写文档，或者文档规划了接口但代码没实现，都很难靠人眼发现。

> 调试文档目前**不入库**（作者自己对接调试用），所以脚本在没有文档时会跳过，
> 而不是判失败。想启用校验就把文档放到 docs/DEBUG_API.md。

用法：
    python3 scripts/check_api_doc.py
退出码 0 = 一致或跳过，1 = 不一致。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "DEBUG_API.md"
ROUTES = ROOT / "core" / "debug-api" / "src" / "main" / "java" / "com" / "mizulauncher" / "debugapi" / "ApiRoutes.kt"

API_PREFIX = "/api/v1"


def code_endpoints() -> set[tuple[str, str]]:
    """从 ApiRoutes.kt 的 ENDPOINTS 列表里提取 (方法, 路径)"""
    text = ROUTES.read_text(encoding="utf-8")
    block = re.search(r"val ENDPOINTS[^=]*=\s*listOf\((.*?)\n\s*\)\n", text, re.S)
    if not block:
        print("❌ 在 ApiRoutes.kt 里找不到 ENDPOINTS 定义", file=sys.stderr)
        sys.exit(1)
    found = re.findall(r'Triple\(\s*"(\w+)"\s*,\s*"\$PREFIX([^"]*)"', block.group(1))
    return {(method, API_PREFIX + path) for method, path in found}


def doc_endpoints() -> set[tuple[str, str]]:
    """从文档里提取所有 (方法, 路径)。

    两个来源：总览表格 和 详解小节标题。两者加起来才算完整覆盖。
    """
    text = DOC.read_text(encoding="utf-8")
    found: set[tuple[str, str]] = set()

    # 总览表格：| GET | `/api/v1/instances` | 说明 |
    for m in re.finditer(r"\|\s*(GET|POST|PATCH|DELETE)\s*\|\s*`(/api/v1[^`]*)`", text):
        found.add((m.group(1), m.group(2)))

    # 详解小节：#### `GET /api/v1/instances`
    for m in re.finditer(r"####\s*`(GET|POST|PATCH|DELETE)\s+(/api/v1[^`]*)`", text):
        found.add((m.group(1), m.group(2)))

    return found


def main() -> int:
    if not DOC.is_file():
        # 文档不入库，CI 上一定没有。跳过而不是失败，否则每次构建都会红。
        print("⏭  未找到 docs/DEBUG_API.md（调试文档不入库），跳过一致性校验")
        return 0
    if not ROUTES.is_file():
        print(f"❌ 找不到路由表：{ROUTES}", file=sys.stderr)
        return 1

    code = code_endpoints()
    doc = doc_endpoints()

    print(f"代码接口 {len(code)} 个，文档接口 {len(doc)} 个")

    only_code = code - doc
    only_doc = doc - code

    ok = True
    if only_code:
        ok = False
        print("\n❌ 这些接口代码里有，但文档没写：")
        for method, path in sorted(only_code):
            print(f"   {method:6} {path}")

    if only_doc:
        ok = False
        print("\n❌ 这些接口文档写了，但代码里没有：")
        for method, path in sorted(only_doc):
            print(f"   {method:6} {path}")

    if ok:
        print("✅ 文档与代码一致")
        return 0
    print("\n提示：改接口时请同时更新 ApiRoutes.kt 的 ENDPOINTS 和 docs/DEBUG_API.md")
    return 1


if __name__ == "__main__":
    sys.exit(main())
