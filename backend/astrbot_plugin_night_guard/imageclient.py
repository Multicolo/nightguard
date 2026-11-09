"""极简生图客户端：DashScope（千问生图）优先，兼容 OpenAI images/generations。"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import time
from pathlib import Path
from typing import Any, Callable

import httpx

DEFAULT_DASHSCOPE = "https://dashscope.aliyuncs.com"
DEFAULT_MODEL = "qwen-image-2.1-turbo"


class ImageClientError(RuntimeError):
    pass


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception:  # noqa: BLE001
        return None


def _items(raw: Any) -> list[dict]:
    if isinstance(raw, str) and raw.strip():
        try:
            raw = json.loads(raw)
        except Exception:  # noqa: BLE001
            return []
    if isinstance(raw, dict):
        if isinstance(raw.get("providers"), list):
            return [x for x in raw["providers"] if isinstance(x, dict)]
        return [raw]
    if isinstance(raw, list):
        return [x for x in raw if isinstance(x, dict)]
    return []


def load_image_providers(cfg: Callable[[str, Any], Any]) -> list[dict]:
    items = _items(cfg("image_providers_json", ""))
    data_root = Path(os.environ.get("ASTRBOT_DATA_DIR", "/AstrBot/data"))
    if not items:
        # 随便哪个插件把 providers_json / dashscope key 写在 config 里都认（不点名具体插件）
        for path in sorted((data_root / "config").glob("*_config.json")):
            raw = _read_json(path)
            if not isinstance(raw, dict):
                continue
            items = _items(raw.get("providers_json"))
            if not items:
                key = str(raw.get("api_key") or "").strip()
                base = str(raw.get("api_base") or "")
                kind = str(raw.get("provider_type") or "").lower()
                if key.startswith("sk-") and ("dashscope" in base.lower() or "dashscope" in kind):
                    items = [
                        {
                            "name": path.stem,
                            "type": "dashscope",
                            "api_key": key,
                            "api_base": base,
                            "model": raw.get("model") or DEFAULT_MODEL,
                        }
                    ]
            if items:
                break
    if not items:
        raw = _read_json(data_root / "cmd_config.json")
        if isinstance(raw, dict):
            providers = (
                raw.get("providers")
                or raw.get("provider")
                or raw.get("provider_sources")
                or []
            )
            if isinstance(providers, dict):
                providers = list(providers.values())
            for item in providers if isinstance(providers, list) else []:
                if not isinstance(item, dict):
                    continue
                key = str(
                    item.get("api_key")
                    or item.get("embedding_api_key")
                    or ""
                ).strip().lstrip("\ufeff")
                base = str(item.get("api_base") or "")
                kind = str(item.get("type") or "").lower()
                if (
                    key.startswith("sk-")
                    and ("dashscope" in base.lower() or "dashscope" in kind)
                ):
                    items = [
                        {
                            "name": "dashscope",
                            "type": "dashscope",
                            "api_key": key,
                            "api_base": base,
                            "model": str(item.get("model") or DEFAULT_MODEL),
                        }
                    ]
                    break
    if not items:
        key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
        if key:
            items = [{"name": "env", "type": "dashscope", "api_key": key, "model": DEFAULT_MODEL}]
    return items


def _headers(provider: dict) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {provider.get('api_key', '')}",
        "Content-Type": "application/json",
    }


def _base(provider: dict) -> str:
    base = str(provider.get("api_base") or DEFAULT_DASHSCOPE).rstrip("/")
    if base.endswith("/compatible-mode/v1"):
        return base[: -len("/compatible-mode/v1")]
    if base.endswith("/api/v1"):
        return base[: -len("/api/v1")]
    return base


async def _dashscope(provider: dict, prompt: str, size: str, refs: list[str]) -> bytes:
    base = _base(provider)
    url = f"{base}/api/v1/services/aigc/image-generation/generation"
    content = [{"image": ref} for ref in refs if ref]
    content.append({"text": prompt})
    payload = {
        "model": str(provider.get("model") or DEFAULT_MODEL),
        "input": {"messages": [{"role": "user", "content": content}]},
        "parameters": {"size": size, "n": 1, "watermark": False},
    }
    async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=20.0)) as client:
        resp = await client.post(url, headers={**_headers(provider), "X-DashScope-Async": "enable"}, json=payload)
        data = resp.json() if resp.content else {}
        if resp.status_code >= 400 or data.get("code"):
            raise ImageClientError(f"提交失败 {resp.status_code}: {str(data)[:300]}")
        task_id = (data.get("output") or {}).get("task_id") or data.get("task_id")
        if not task_id:
            raise ImageClientError(f"没有 task_id: {str(data)[:300]}")
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            await asyncio.sleep(3)
            r = await client.get(f"{base}/api/v1/tasks/{task_id}", headers=_headers(provider))
            d = r.json() if r.content else {}
            out = d.get("output") or {}
            status = str(out.get("task_status") or "")
            if status == "SUCCEEDED":
                for choice in out.get("choices") or []:
                    for item in (choice.get("message") or {}).get("content") or []:
                        ref = str((item or {}).get("image") or "")
                        if ref.startswith("http"):
                            img = await client.get(ref, timeout=120)
                            return img.content
                        if ref.startswith("data:"):
                            return base64.b64decode(ref.split(",", 1)[-1])
                raise ImageClientError("任务成功但没有图片")
            if status in {"FAILED", "CANCELED", "CANCELLED", "UNKNOWN"}:
                raise ImageClientError(f"任务失败 {status}: {str(d)[:300]}")
    raise ImageClientError("生图超时")


async def _openai(provider: dict, prompt: str, size: str) -> bytes:
    base = str(provider.get("api_base") or "https://api.openai.com/v1").rstrip("/")
    if not base.endswith("/v1"):
        base += "/v1"
    payload = {
        "model": str(provider.get("model") or "gpt-image-1"),
        "prompt": prompt,
        "size": size.replace("*", "x"),
        "n": 1,
    }
    async with httpx.AsyncClient(timeout=180.0) as client:
        resp = await client.post(f"{base}/images/generations", headers=_headers(provider), json=payload)
        data = resp.json() if resp.content else {}
        if resp.status_code >= 400:
            raise ImageClientError(f"生图失败 {resp.status_code}: {str(data)[:300]}")
        item = (data.get("data") or [{}])[0]
        if item.get("b64_json"):
            return base64.b64decode(item["b64_json"])
        if item.get("url"):
            img = await client.get(item["url"], timeout=120)
            return img.content
    raise ImageClientError("生图响应里没有图片")


async def generate_image(
    prompt: str,
    *,
    size: str = "1024*1024",
    refs: list[str] | None = None,
    cfg: Callable[[str, Any], Any] | None = None,
) -> bytes:
    providers = load_image_providers(cfg or (lambda _k, d=None: d))
    if not providers:
        raise ImageClientError("没有可用的生图服务商")
    last: Exception | None = None
    for provider in providers:
        kind = str(provider.get("type") or "dashscope").lower()
        try:
            if kind == "openai":
                return await _openai(provider, prompt, size)
            return await _dashscope(provider, prompt, size, refs or [])
        except Exception as exc:  # noqa: BLE001
            last = exc
    raise ImageClientError(str(last) if last else "生图失败")
