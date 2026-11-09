# 后端部署（AstrBot 插件）

NightGuard 的后端是一个普通 AstrBot 插件：它不接管 bot 的消息，只额外开一个 HTTP 服务给手机 App 用。
角色、模型、记忆、生图都复用你 AstrBot 里已有的配置。

---

## 1. 放插件

| 部署方式 | 插件目录 |
| --- | --- |
| Docker（常见） | 宿主机 `.../astrbot-data/plugins/astrbot_plugin_night_guard/`，容器内 `/AstrBot/data/plugins/` |
| 裸机 / 源码 | `AstrBot/data/plugins/astrbot_plugin_night_guard/` |

把 `backend/astrbot_plugin_night_guard/` 下 5 个文件整个拷进去：

```
main.py  imageclient.py  _conf_schema.json  metadata.yaml  __init__.py
```

重启 AstrBot：

```bash
docker restart astrbot          # Docker
# 或：在面板里重载插件 / 重启进程
```

启动日志里应出现：

```
night_guard: 守夜后端已监听 0.0.0.0:6200
night_guard: 未配置 api_token，已自动生成口令 xxxxxxxx
```

## 2. 拿口令

- 配了 `api_token`：就是你填的值；
- 没配：看上面日志，或读容器里的
  `/AstrBot/data/plugin_data/astrbot_plugin_night_guard/token.txt`（宿主机对应 `.../astrbot-data/plugin_data/...`）。

## 3. 端口怎么给手机

后端默认只监听**容器内** 6200。三种接法，任选：

### A. 直接映射端口（最简单，仅局域网）

`docker-compose.yml` / `docker run` 里加：

```yaml
ports:
  - "6200:6200"        # 或 "127.0.0.1:6200:6200" 只给本机
```

手机和服务器同一局域网时，App 里填 `http://<服务器IP>:6200`。
注意：**明文 HTTP 会在公网泄露口令和聊天内容**，不要直接暴露到公网。

### B. nginx 反代到子路径（推荐，可上 HTTPS）

```nginx
location /night/ {
    proxy_pass http://172.18.0.3:6200/;   # 换成你的容器 IP / 127.0.0.1
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;                  # 聊天是即时返回，别攒
    proxy_read_timeout 300s;              # 生图/大模型可能慢
    proxy_send_timeout 300s;
}
```

```bash
nginx -t && systemctl reload nginx
```

App 里填 `https://你的域名/night`。

> 容器 IP 用 `docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' astrbot` 查；
> 也可以直接反代到宿主机映射端口 `http://127.0.0.1:6200/`。

### C. 只在内网用（最安全）

不开公网，手机连家里 Wi-Fi / 内网穿透（Tailscale、ZeroTier）后填内网地址。

## 4. HTTPS 与证书

- 有正规证书（域名 + Let's Encrypt）：App 里**不要**勾「信任自签证书」，走系统校验最安全。
- 自签证书 / 直接连 IP：App 里勾上「信任自签证书」，否则 TLS 会失败。
- 想彻底避免证书问题，也可以在内网用 HTTP（只走 A 或 C，且不要暴露公网）。

## 5. 验证

```bash
TOKEN=你的口令
curl -H "X-Night-Token: $TOKEN" http://127.0.0.1:6200/health
curl -H "X-Night-Token: $TOKEN" https://你的域名/night/config

curl -H "X-Night-Token: $TOKEN" -H "Content-Type: application/json" \
     -d '{"text":"求你了，再让我玩五分钟","history":[]}' \
     https://你的域名/night/chat
```

`/config` 返回里的 `persona_name` 就是 App 锁屏标题会显示的名字。

## 6. 人设怎么来的

优先级：

1. `use_astrbot_persona = true`（默认）→ 取 AstrBot **默认人设**（`persona_manager.get_default_persona_v3`）；
2. 取不到 → 用 `persona_name`（如果填了）+ 内置通用「夜间管家」人设；
3. 都为空 → 显示名「守夜人」。

想固定用某个名字：把 `use_astrbot_persona` 关掉，`persona_name` 填你要的名字。
想让人设更贴角色，直接改 AstrBot 里的人设提示词即可，插件会跟着变。

## 7. 生图（睡照）怎么配

`enable_sleep_photo=true` 时，命中「想看你睡觉」这类话会异步生成一张照片。服务商按下面顺序找：

1. 插件配置 `image_providers_json`（JSON 数组，元素含 `type/api_key/api_base/model`）；
2. `data/config/*_config.json` 里任意带 `providers_json` 或 dashscope `api_key` 的插件配置；
3. `data/cmd_config.json` 里的 dashscope key（`api_key` / `embedding_api_key`）；
4. 环境变量 `DASHSCOPE_API_KEY`。

没有可用服务商时：只回文字，日志里一行警告，不影响守夜。

想让人物长得像你的角色，配 `character_forms_dir`（三视图目录，含
`{mature,chibi,winter}/{front,side,back}.png`）和 `character_look`（外貌描述）。

## 8. 排错

| 现象 | 排查 |
| --- | --- |
| 启动日志没有 `守夜后端已监听` | 端口被占（`ss -ltnp \| grep 6200`）；看是否有 `HTTP 启动失败` |
| `/health` 返回 401 | 口令不对：`X-Night-Token` 或 `?t=` |
| `/health` 502 / 404 | nginx `proxy_pass` 地址或路径不对（注意结尾 `/`） |
| App 报「信号好像断了」 | 手机连不上服务器 / 证书被拒（自签要勾信任）/ 口令错 |
| 聊天有回复但从不放行 | `allow_chance` 太低，或今晚已放行过；看 `/state` 的 `granted` |
| 从没出现照片 | 没配生图 key；`docker logs astrbot \| grep 睡照` 看具体报错 |
| 第二轮开始每个请求都回「不理你了」 | `/state` 里 `ended=true`，等第二天（凌晨 5 点换日）或手动清 `state.json` |

清空今晚状态（调试用）：

```bash
cd /path/to/astrbot-data/plugin_data/astrbot_plugin_night_guard
echo '{"nights":{},"chat":[]}' > state.json && docker restart astrbot
```
