"""守夜人 · NightGuard —— 深夜守则 + 关不掉的聊天贴图（AstrBot 后端）。

独立 HTTP 服务（默认 6200），供 NightGuard APK 调用；不依赖面板登录。
角色可选：默认读取 AstrBot 当前默认人设；也可以关掉，用下面通用的「守夜人」人设。
"""

from __future__ import annotations

import asyncio
import base64
import json
import random
import secrets
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from aiohttp import web

from astrbot.api import logger
from astrbot.api.star import Context, Star, StarTools, register

from .imageclient import generate_image

PLUGIN_NAME = "astrbot_plugin_night_guard"
PLUGIN_VERSION = "1.0.0"

# 不带任何特定 IP：谁装都能用。想换成自己的角色，就在插件配置里打开「读取 AstrBot 人设」。
DEFAULT_PERSONA = (
    "你是一位负责督促主人早睡的夜间管家。说话短、口语化，嘴硬心软，"
    "会催他放下手机，但不会真的伤人；被撒娇时会别扭一下，最后还是关心他。"
)

SLEEP_WORDS = (
    "睡觉", "睡姿", "睡着", "晚安", "一起睡", "陪我睡", "想看你睡", "躺下", "床",
)

# 只有真的在求情时才掷骰子放行，避免随便说句话就被放走
PLEA_WORDS = (
    "求", "让我", "再玩", "再待", "再一会", "别锁", "放我", "通融", "好不好",
    "行不行", "就一会", "几分钟", "一分钟", "能不能", "please", "let me", "more minute",
    "stay", "a bit",
)

PHOTO_PROMPT = (
    "【写实生活照】深夜的卧室，暖色床头灯，窗外是深蓝的夜色。"
    "画面里是{who}：{look}。"
    "刚准备睡觉，穿着宽松的浅色睡衣，头发有点乱，抱着被子坐在床边，"
    "或者侧躺在床上看向镜头，眼神困倦又有点不服气。"
    "像手机随手拍的私密照片：略高角度、轻微噪点、光线偏暗、真实皮肤质感，不要动漫感、不要过度磨皮。"
    "{keep}画面里不要出现文字、水印、时间戳。"
)


@register(
    PLUGIN_NAME,
    "NightGuard contributors",
    "守夜人：深夜把手机锁成关不掉的聊天贴图，只能跟角色求情；一晚只放行一次，"
    "可发准备睡觉的照片，聊久了会自己结束会话。",
    PLUGIN_VERSION,
)
class NightGuardPlugin(Star):
    def __init__(self, context: Context, config: dict | None = None) -> None:
        super().__init__(context)
        self.config = config or {}
        try:
            self.data_dir = Path(StarTools.get_data_dir(PLUGIN_NAME))
        except Exception:  # noqa: BLE001
            self.data_dir = Path("/AstrBot/data/plugin_data") / PLUGIN_NAME
        self.data_dir.mkdir(parents=True, exist_ok=True)
        (self.data_dir / "photos").mkdir(parents=True, exist_ok=True)
        self.logger = logger
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._lock = asyncio.Lock()
        self._state = self._load_state()
        self._photo_tasks: dict[str, asyncio.Task] = {}
        self._qq_memory: list[dict[str, str]] = []
        self._memory_at = 0.0
        self._token = self._resolve_token()

    # ------------------------------------------------------------------ 配置
    def cfg(self, key: str, default: Any = None) -> Any:
        value = self.config.get(key)
        if value is None or value == "":
            return default
        return value

    def _resolve_token(self) -> str:
        """api_token 为空时自动生成一个，存到 data_dir/token.txt，并打到日志里。"""
        configured = str(self.config.get("api_token") or "").strip()
        if configured:
            return configured
        path = self.data_dir / "token.txt"
        try:
            if path.is_file():
                saved = path.read_text(encoding="utf-8").strip()
                if saved:
                    return saved
        except Exception:  # noqa: BLE001
            pass
        token = secrets.token_hex(8)
        try:
            path.write_text(token, encoding="utf-8")
        except Exception:  # noqa: BLE001
            pass
        self.logger.info(
            "night_guard: 未配置 api_token，已自动生成口令 %s（也可在插件配置里自定义）", token
        )
        return token

    @property
    def token(self) -> str:
        return self._token

    @property
    def port(self) -> int:
        try:
            return int(self.cfg("http_port", 6200))
        except Exception:  # noqa: BLE001
            return 6200

    # ------------------------------------------------------------------ 生命周期
    async def initialize(self) -> None:
        try:
            await self._start_http()
        except Exception as exc:  # noqa: BLE001
            self.logger.error("night_guard: HTTP 启动失败: %s", exc)

    async def terminate(self) -> None:
        for task in list(self._photo_tasks.values()):
            task.cancel()
        if self._runner is not None:
            try:
                await self._runner.cleanup()
            except Exception:  # noqa: BLE001
                pass
            self._runner = None

    async def _start_http(self) -> None:
        app = web.Application(client_max_size=16 * 1024 * 1024)
        app.router.add_get("/health", self.h_health)
        app.router.add_get("/config", self.h_config)
        app.router.add_post("/chat", self.h_chat)
        app.router.add_get("/state", self.h_state)
        app.router.add_get("/bg", self.h_bg)
        app.router.add_get("/photo/{name}", self.h_photo)
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, "0.0.0.0", self.port)
        await self._site.start()
        self.logger.info("night_guard: 守夜后端已监听 0.0.0.0:%s", self.port)

    # ------------------------------------------------------------------ 状态
    def _state_path(self) -> Path:
        return self.data_dir / "state.json"

    def _load_state(self) -> dict[str, Any]:
        try:
            return json.loads(self._state_path().read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            return {"nights": {}, "chat": []}

    def _save(self) -> None:
        try:
            self._state_path().write_text(
                json.dumps(self._state, ensure_ascii=False, indent=1), encoding="utf-8"
            )
        except Exception as exc:  # noqa: BLE001
            self.logger.warning("night_guard: 保存状态失败: %s", exc)

    def _now(self) -> datetime:
        return datetime.now()

    def _night_key(self, now: datetime | None = None) -> str:
        now = now or self._now()
        return (now - timedelta(hours=5)).strftime("%Y-%m-%d")

    def _night(self) -> dict[str, Any]:
        key = self._night_key()
        nights = self._state.setdefault("nights", {})
        item = nights.setdefault(key, {"granted": False, "ended": False, "turns": 0})
        return item

    # ------------------------------------------------------------------ HTTP 鉴权
    def _check_token(self, request: web.Request) -> None:
        given = (
            request.headers.get("X-Night-Token")
            or request.query.get("t")
            or ""
        ).strip()
        if given != self.token:
            raise web.HTTPUnauthorized(text="bad token")

    # ------------------------------------------------------------------ 路由
    async def h_health(self, request: web.Request) -> web.Response:
        self._check_token(request)
        return web.json_response(
            {"ok": True, "plugin": PLUGIN_NAME, "version": PLUGIN_VERSION,
             "time": self._now().strftime("%Y-%m-%d %H:%M:%S"),
             "night": self._night_key()}
        )

    async def h_config(self, request: web.Request) -> web.Response:
        self._check_token(request)
        night = self._night()
        return web.json_response(
            {
                "ok": True,
                "persona_name": await self._persona_name(),
                "bedtime_start": str(self.cfg("bedtime_start", "23:30")),
                "bedtime_end": str(self.cfg("bedtime_end", "06:30")),
                "allow_chance": float(self.cfg("allow_chance", 0.45)),
                "allow_minutes": int(self.cfg("allow_minutes", 3)),
                "night_key": self._night_key(),
                "granted": bool(night.get("granted")),
                "ended": bool(night.get("ended")),
                "bg": "/bg" if str(self.cfg("bg_image", "") or "").strip() else "",
            }
        )

    async def h_state(self, request: web.Request) -> web.Response:
        self._check_token(request)
        night = self._night()
        return web.json_response(
            {
                "ok": True,
                "night_key": self._night_key(),
                "granted": bool(night.get("granted")),
                "ended": bool(night.get("ended")),
                "turns": int(night.get("turns", 0)),
                "chat": list(self._state.get("chat", []))[-40:],
            }
        )

    async def h_bg(self, request: web.Request) -> web.Response:
        self._check_token(request)
        raw = str(self.cfg("bg_image", "") or "").strip()
        if not raw:
            raise web.HTTPNotFound(text="no bg configured")
        try:
            data = Path(raw).read_bytes()
        except Exception:  # noqa: BLE001
            raise web.HTTPNotFound(text="no bg")
        return web.Response(body=data, content_type="image/jpeg",
                            headers={"Cache-Control": "no-store"})

    async def h_photo(self, request: web.Request) -> web.Response:
        self._check_token(request)
        name = Path(str(request.match_info.get("name") or "")).name
        if not name or "/" in name or "\\" in name:
            raise web.HTTPNotFound()
        path = self.data_dir / "photos" / name
        if not path.is_file():
            raise web.HTTPNotFound(text="pending")
        return web.Response(body=path.read_bytes(), content_type="image/jpeg",
                            headers={"Cache-Control": "no-store"})

    async def h_chat(self, request: web.Request) -> web.Response:
        self._check_token(request)
        try:
            body = await request.json()
        except Exception:  # noqa: BLE001
            body = {}
        text = str(body.get("text") or "").strip()[:800]
        history = body.get("history")
        if not text:
            return web.json_response({"ok": False, "say": ""}, status=400)
        async with self._lock:
            result = await self._chat(text, history if isinstance(history, list) else [])
        return web.json_response(result)

    # ------------------------------------------------------------------ 人设 / 记忆
    async def _persona_name(self) -> str:
        name, _prompt = await self._persona(str(self.cfg("owner_umo", "") or ""))
        return name

    async def _persona(self, umo: str) -> tuple[str, str]:
        """返回 (显示名, 人设提示词)。读 AstrBot 人设是可选项。"""
        configured_name = str(self.cfg("persona_name", "") or "").strip()
        use_astrbot = bool(self.cfg("use_astrbot_persona", True))
        if use_astrbot:
            manager = getattr(self.context, "persona_manager", None)
            if manager is not None:
                try:
                    persona = await manager.get_default_persona_v3(umo=umo or None)
                    if persona:
                        prompt = (
                            persona.get("prompt")
                            if isinstance(persona, dict)
                            else getattr(persona, "prompt", "")
                        )
                        pname = (
                            persona.get("name")
                            if isinstance(persona, dict)
                            else getattr(persona, "name", "")
                        )
                        if prompt and str(prompt).strip():
                            return str(pname or configured_name or "守夜人"), str(prompt).strip()
                except Exception as exc:  # noqa: BLE001
                    self.logger.warning("night_guard: 读取 AstrBot 人设失败: %s", exc)
        return configured_name or "守夜人", DEFAULT_PERSONA

    async def _sync_qq_memory(self) -> list[dict[str, str]]:
        if self._qq_memory and time.time() - self._memory_at < 300:
            return self._qq_memory
        umo = str(self.cfg("owner_umo", "") or "").strip()
        manager = getattr(self.context, "conversation_manager", None)
        if not umo or manager is None:
            return self._qq_memory
        try:
            cid = manager.get_curr_conversation_id(umo)
            if asyncio.iscoroutine(cid):
                cid = await cid
            if not cid:
                return self._qq_memory
            conv = manager.get_conversation(umo, cid)
            if asyncio.iscoroutine(conv):
                conv = await conv
            raw = getattr(conv, "history", None) if conv else None
            if isinstance(raw, str):
                raw = json.loads(raw)
            out: list[dict[str, str]] = []
            for item in (raw or [])[-14:]:
                if not isinstance(item, dict):
                    continue
                role = "user" if str(item.get("role")) in {"user", "human"} else "assistant"
                content = item.get("content")
                if isinstance(content, list):
                    txt = " ".join(str(x.get("text") or "") for x in content if isinstance(x, dict))
                else:
                    txt = str(content or "")
                txt = txt.strip()[:300]
                if txt:
                    out.append({"role": role, "text": txt})
            self._qq_memory = out
            self._memory_at = time.time()
        except Exception as exc:  # noqa: BLE001
            self.logger.warning("night_guard: 同步 QQ 记忆失败: %s", exc)
        return self._qq_memory

    def _pick_llm(self):
        provider_id = str(self.cfg("llm_provider_id", "") or "").strip()
        if provider_id:
            try:
                provider = self.context.get_provider_by_id(provider_id)
                if provider is not None:
                    return provider
            except Exception:  # noqa: BLE001
                pass
        try:
            pm = getattr(self.context, "provider_manager", None)
            for inst in getattr(pm, "provider_insts", None) or []:
                conf = getattr(inst, "provider_config", None)
                kind = str(getattr(conf, "provider_type", "") or "")
                if kind and kind != "chat_completion":
                    continue
                if hasattr(inst, "text_chat"):
                    return inst
        except Exception:  # noqa: BLE001
            pass
        try:
            return self.context.get_using_provider()
        except Exception:  # noqa: BLE001
            return None

    @staticmethod
    def _text_of(resp: Any) -> str:
        for attr in ("completion_text", "text", "content"):
            value = getattr(resp, attr, None)
            if isinstance(value, str) and value.strip():
                return value.strip()
        if isinstance(resp, dict):
            for key in ("completion_text", "text", "content"):
                value = resp.get(key)
                if isinstance(value, str) and value.strip():
                    return value.strip()
        return ""

    @staticmethod
    def _parse_json(text: str) -> dict[str, Any]:
        cleaned = (text or "").strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.strip("`")
            if cleaned.lower().startswith("json"):
                cleaned = cleaned[4:]
        start, depth, in_str, esc = cleaned.find("{"), 0, False, False
        if start < 0:
            return {}
        for i in range(start, len(cleaned)):
            char = cleaned[i]
            if in_str:
                if esc:
                    esc = False
                elif char == "\\":
                    esc = True
                elif char == '"':
                    in_str = False
                continue
            if char == '"':
                in_str = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    try:
                        value = json.loads(cleaned[start : i + 1])
                        return value if isinstance(value, dict) else {}
                    except Exception:  # noqa: BLE001
                        return {}
        return {}

    # ------------------------------------------------------------------ 核心对话
    async def _chat(self, text: str, history: list[Any]) -> dict[str, Any]:
        now = self._now()
        night = self._night()
        if night.get("ended"):
            return {
                "ok": True,
                "say": str(self.cfg("ended_line", "……不理你了。睡觉。")),
                "ended": True,
                "allow_minutes": 0,
                "photo": "",
            }

        name, prompt_persona = await self._persona(str(self.cfg("owner_umo", "") or ""))
        memory = await self._sync_qq_memory()
        turns = int(night.get("turns", 0))
        granted_before = bool(night.get("granted"))

        # 先掷骰子决定这一轮放不放，再让模型照着这个结果说话，避免「嘴上放行、状态没放」
        grant_now = False
        grant_min = 0
        plea = any(w in text.lower() for w in PLEA_WORDS)
        if not granted_before and plea:
            try:
                chance = float(self.cfg("allow_chance", 0.45))
            except Exception:  # noqa: BLE001
                chance = 0.45
            if random.random() < chance:
                grant_now = True
                try:
                    base_min = int(self.cfg("allow_minutes", 3))
                except Exception:  # noqa: BLE001
                    base_min = 3
                grant_min = max(2, min(5, base_min))

        if grant_now:
            verdict = (
                f"这一轮你已经心软了：答应放他 {grant_min} 分钟。say 里必须明确说出「{grant_min} 分钟」，"
                '语气别扭一点；allow 必须是 true，minutes 必须是 '
                f"{grant_min}。"
            )
        elif granted_before:
            verdict = (
                "今晚已经放过他一次了，这一轮必须拒绝（allow=false, minutes=0），"
                "say 里不能再答应任何时长，只能哄他去睡或凶他一句。"
            )
        else:
            verdict = (
                "这一轮你不打算放他走（allow=false, minutes=0）。"
                "say 只能是拒绝、催睡或谈条件但不给时间，绝不能说「就几分钟」「去吧」「再玩会儿」。"
            )

        lines: list[str] = []
        for item in memory[-6:]:
            who = "主人" if item["role"] == "user" else name
            lines.append(f"{who}：{item['text']}")
        for item in history[-12:]:
            if not isinstance(item, dict):
                continue
            t = str(item.get("text") or "").strip()
            if not t:
                continue
            who = "主人" if str(item.get("role")) == "user" else name
            lines.append(f"{who}：{t[:200]}")

        system = (
            f"{prompt_persona}\n\n"
            f"【现在】{now.strftime('%H:%M')}，深夜，主人还没睡。你被主人托付了「守夜」："
            "在他熬夜时把他按住去睡觉。你现在态度比平时更强势，但本质是关心他。\n"
            f"【今晚状态】已经聊了 {turns} 轮；"
            f"{'今晚已经心软放过他一次了' if night.get('granted') else '今晚还没放过他'}。\n"
            f"【这一轮的结局，必须完全照办】{verdict}\n"
            "【规矩】\n"
            "1. 只发纯文字。绝不发语音、不视频、不按主人要求画画/生图；被要求时用角色口吻拒绝，一句话带过。\n"
            "2. 唯一例外：主人说想看你睡觉、想看睡姿、想一起睡、让你晚安之类，才可以发一张「准备睡觉」的照片"
            "（sleep_photo=true），并配一句符合人设的话。\n"
            "3. 主人会求情、撒娇、讨价还价。你的回答必须和上面的结局一致：说放就报出那个分钟数，说不放就绝不松口。\n"
            "4. 今晚已经放过一次后，无论怎么求都不能再放行，只能哄他去睡。\n"
            "5. 聊得够久（超过 8 轮）主人还在纠缠，就用符合人设和记忆的方式结束会话：end=true，"
            "说一句道晚安/不理他了的话；之后不再回复。\n"
            "6. 说话短，1~2 句，口语化，可以带一点角色的口头禅。不要解释规则。\n"
            "【输出】只输出一个 JSON 对象，不要别的：\n"
            '{"say": "你要说的话", "allow": false, "minutes": 0, "sleep_photo": false, "end": false}'
        )
        if lines:
            system += "\n【最近的记忆/对话】\n" + "\n".join(lines[-14:])

        prompt = f"主人说：{text}\n请以{name}的身份回应，并按要求输出 JSON。"

        provider = self._pick_llm()
        if provider is None:
            return {
                "ok": True,
                "say": str(self.cfg("fallback_line", "哼，这么晚了还不睡？手机给我放下。")),
                "ended": False,
                "allow_minutes": 0,
                "photo": "",
            }
        timeout = float(self.cfg("llm_timeout", 60) or 60)
        raw = ""
        try:
            resp = await asyncio.wait_for(
                provider.text_chat(
                    prompt=prompt,
                    session_id=f"nightguard-{self._night_key()}",
                    system_prompt=system,
                ),
                timeout=timeout,
            )
            raw = self._text_of(resp)
        except Exception as exc:  # noqa: BLE001
            self.logger.warning("night_guard: LLM 失败: %s", exc)
        obj = self._parse_json(raw)
        say = str(obj.get("say") or "").strip()
        if not say:
            say = raw.strip().splitlines()[0][:120] if raw.strip() else "哼，睡觉。"
        say = say.replace("\n", " ")[:160]

        allow = bool(grant_now) and not granted_before
        minutes = grant_min if allow else 0

        photo = ""
        want_photo = bool(obj.get("sleep_photo")) or any(w in text for w in SLEEP_WORDS)
        if want_photo and bool(self.cfg("enable_sleep_photo", True)):
            photo = self._start_photo(night)

        end = bool(obj.get("end")) and turns >= 8

        night["granted"] = bool(night.get("granted")) or allow
        night["turns"] = turns + 1
        night["ended"] = bool(night.get("ended")) or end
        chat = self._state.setdefault("chat", [])
        chat.append({"role": "user", "text": text, "at": time.time()})
        chat.append({"role": "assistant", "text": say, "at": time.time()})
        self._state["chat"] = chat[-60:]
        self._save()

        return {
            "ok": True,
            "say": say,
            "allow_minutes": minutes,
            "ended": end,
            "photo": photo,
            "night_key": self._night_key(),
            "granted": bool(night.get("granted")),
        }

    # ------------------------------------------------------------------ 生图
    def _start_photo(self, night: dict[str, Any]) -> str:
        prefix = f"sleep-{self._night_key()}-"
        # 一晚只生成一张：已经画好就直接复用，避免重复消耗生图额度
        try:
            for p in sorted((self.data_dir / "photos").glob(prefix + "*.jpg")):
                if p.is_file() and p.stat().st_size > 2000:
                    return f"/photo/{p.name}"
        except Exception:  # noqa: BLE001
            pass
        name = f"{prefix}{uuid.uuid4().hex[:6]}.jpg"
        task = asyncio.create_task(self._photo_worker(name))
        self._photo_tasks[name] = task
        task.add_done_callback(lambda _t: self._photo_tasks.pop(name, None))
        return f"/photo/{name}"

    async def _photo_worker(self, name: str) -> None:
        try:
            prompt, refs = self._photo_prompt()
            data = await generate_image(
                prompt,
                size=str(self.cfg("image_size", "896*1152")),
                refs=refs,
                cfg=self.cfg,
            )
            (self.data_dir / "photos" / name).write_bytes(data)
            self.logger.info("night_guard: 睡照已生成 %s (%d bytes)", name, len(data))
        except Exception as exc:  # noqa: BLE001
            self.logger.warning("night_guard: 睡照生成失败: %s", exc)

    def _photo_prompt(self) -> tuple[str, list[str]]:
        look = str(self.cfg("character_look", "") or "").strip()
        refs: list[str] = []
        forms_raw = str(self.cfg("character_forms_dir", "") or "").strip()
        if forms_raw:
            forms = Path(forms_raw)
            for slot in ("front", "side", "back"):
                for form in ("mature", "chibi", "winter"):
                    path = forms / form / f"{slot}.png"
                    if path.is_file():
                        try:
                            refs.append(
                                "data:image/png;base64,"
                                + base64.b64encode(path.read_bytes()).decode("ascii")
                            )
                        except Exception:  # noqa: BLE001
                            pass
                        break
        if refs:
            who = "参考图里的角色"
            keep = "严格保持参考图里的脸型、发型、发色、耳鳍和体型，不要换成别的角色。"
            if not look:
                look = "外形、发型、服装以参考图为准"
        else:
            who = "一位普通年轻人"
            keep = "不要出现任何已知作品/品牌角色。"
            if not look:
                look = "普通居家打扮，自然长相"
        return PHOTO_PROMPT.format(who=who, look=look, keep=keep), refs
