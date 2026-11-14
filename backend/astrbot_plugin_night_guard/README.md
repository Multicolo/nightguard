# 守夜鱼 · NightGuard（AstrBot 插件）

到点把手机盖成**关不掉的聊天框**，只能跟你的 AstrBot 角色求情睡觉。

这是守夜鱼的后端插件：它不接管 bot 的聊天，只额外开一个 HTTP 服务给 Android App 用。
角色、模型、记忆、生图全部复用你 AstrBot 里已有的配置。

> **Android App 下载**：[NightGuard.apk（GitHub Releases）](https://github.com/Multicolo/nightguard/releases/latest/download/NightGuard.apk)
> **主仓库 / 源码**：<https://github.com/Multicolo/nightguard>
> **后端部署长文**：[docs/BACKEND.md](https://github.com/Multicolo/nightguard/blob/main/docs/BACKEND.md)

---

## 它做什么

- 到点后手机被全屏聊天框盖住，按 Home / 打开别的 App 会被拉回来（软锁，不杀进程）。
- 跟你的 AstrBot 角色求情，后端**先掷骰子**再让模型说话：一晚最多放行一次，每次 2–5 分钟。
- 可以说「想看你睡觉」求一张睡照：一晚一张，命中复用，失败不重试。
- 聊够 8 轮后才允许 `end=true`；结束 = 禁言，锁继续到时间窗结束。
- 人设默认读 AstrBot 默认人设；关掉 `use_astrbot_persona` 用内置通用人设。

## 安装

**插件市场（收录后）**：AstrBot WebUI → 插件 → 插件市场，搜索「守夜鱼」或 `astrbot_plugin_night_guard`。
收录有审核延迟；搜不到就直接用手动方式。

**手动**：

```bash
cd <AstrBot>/data/plugins
git clone https://github.com/Multicolo/astrbot_plugin_night_guard
```

Docker 用户放到宿主机 `.../astrbot-data/plugins/astrbot_plugin_night_guard/`（容器内 `/AstrBot/data/plugins/`），然后：

```bash
docker restart astrbot
```

启动日志应出现：

```text
night_guard: 守夜后端已监听 0.0.0.0:6200
night_guard: 未配置 api_token，已自动生成口令 xxxxxxxx
```

## 配置（AstrBot 面板）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `api_token` | 空 | App 口令；留空自动生成，见日志或 `plugin_data/astrbot_plugin_night_guard/token.txt` |
| `http_port` | `6200` | 容器内监听端口 |
| `use_astrbot_persona` | `true` | 读 AstrBot 默认人设 |
| `persona_name` | 空 | 角色显示名；空则用 AstrBot 人设名 |
| `owner_umo` | 空 | 同步 QQ 记忆的会话（可留空） |
| `llm_provider_id` | 空 | 指定对话模型，留空自动选 |
| `bedtime_start` / `bedtime_end` | `23:30` / `06:30` | 默认时间窗 |
| `allow_chance` | `0.45` | 求情放行概率 |
| `allow_minutes` | `3` | 放行分钟数（夹到 2–5） |
| `enable_sleep_photo` | `true` | 是否允许生成睡照 |
| `image_size` | `896*1152` | 睡照尺寸 |
| `character_forms_dir` | 空 | 角色三视图目录（可选） |
| `character_look` | 空 | 角色外貌描述（可选） |
| `bg_image` | 空 | 默认聊天背景（可选） |
| `ended_line` | `……不理你了。睡觉。` | 结束后固定回复 |
| `fallback_line` | `哼，这么晚了还不睡？手机给我放下。` | 模型不可用兜底 |

## 手机怎么连

1. 把 6200 端口映射到宿主机（仅局域网）或用 nginx 反代到 `https://你的域名/night`。
2. App 里填服务器地址 + `api_token`。
3. 自签证书 / 直连 IP 时勾「信任自签证书」。
4. 按 App 里的权限清单开启悬浮窗、通知、电池白名单。

详细步骤（Docker / nginx / HTTPS / 排错）见主仓库的
[docs/BACKEND.md](https://github.com/Multicolo/nightguard/blob/main/docs/BACKEND.md)。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 存活检查 |
| GET | `/config` | 角色名、时间段、放行概率、今晚状态 |
| GET | `/state` | 今晚轮数、是否放行/结束、最近聊天 |
| POST | `/chat` | `{text, history}` → `{say, allow_minutes, ended, photo}` |
| GET | `/bg` | 默认聊天背景（没配置返回 404） |
| GET | `/photo/<name>` | 睡照 |

鉴权：`X-Night-Token: <api_token>`（当前版本也兼容 `?t=`，计划在 M5 删除查询参数）。

## 安全提示

- 明文 HTTP 只适合局域网；公网请用 HTTPS 反代。
- `api_token` 等同于聊天与睡照的访问口令，不要提交到仓库或发到群里。
- 插件只读取 AstrBot 的人设与配置，不额外收集用户数据。

## License

MIT，见 [LICENSE](LICENSE)。

作者：花影落闲庭 · <https://github.com/Multicolo>
