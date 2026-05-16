# paper-plugin — MC Java 平台集成插件

Paper API（Kotlin）插件，将 Minecraft Java Edition 服务器接入游戏社区平台（core）。

## 功能

- **长轮询**：持续从平台拉取下行命令（踢人、白名单、广播、玩家通知等），无需 WebSocket
- **心跳上报**：定期上报在线人数与玩家列表，刷新平台侧服务器状态
- **玩家事件**：加入/离开事件实时推送到平台
- **账号绑定**：玩家通过 `/axo bind <CODE>` 将游戏名与平台账号关联
- **排行榜同步**：定期采集服务器内统计数据，批量推送到平台排行榜
- **玩家统计按需查询**：响应平台的 `player.stats.fetch` 命令，返回指定玩家的 stats
- **玩家命令**：`/axo web`（显示社区网址）、`/axo docs`（浏览文档）；所有玩家指令都挂在 `/axo` 主命令下，避免与其它插件冲突

## 环境要求

| 依赖 | 版本 |
|---|---|
| Paper | 1.21.4+ |
| Java | 21+ |
| Kotlin | 2.0（随 jar 打包，无需服务器额外安装） |

## 构建

```bash
./gradlew build
# 产物：build/libs/paper-platform-<version>.jar
```

构建使用 [paperweight-userdev](https://github.com/PaperMC/paperweight) 处理混淆映射，Shadow 将 Kotlin stdlib 打包进 jar。

## 安装

1. 将 `paper-platform-<version>.jar` 放入服务器 `plugins/` 目录
2. 启动服务器，插件自动生成 `plugins/PaperPlatform/config.yml`
3. 编辑配置文件（见下方），重启服务器生效

## 配置

`plugins/PaperPlatform/config.yml`：

```yaml
# 平台后端地址（插件自动在此基础上拼接 /api/srv/* 路径）
base_url: "https://your-platform.example.com"

# 服务器 Token（由管理员后台生成，明文仅在创建/重置时返回一次）
token: "REPLACE_ME"

# 长轮询超时（毫秒）。必须 > 服务端挂起时长（28s）+ 缓冲，推荐 35000
poll_timeout_ms: 35000

# 心跳间隔（ticks，20 ticks = 1s）。默认 600 = 30s
heartbeat_ticks: 600

# 排行榜重算间隔（ticks）。默认 6000 = 5min
leaderboard_ticks: 6000

# /axo web 命令展示的网址（为空时使用 base_url）
web_url: ""

# 是否在玩家加入时显示欢迎横幅
welcome_banner: true
```

## 项目结构

```
src/main/kotlin/net/axogc/paper/
├── PaperPlatformPlugin.kt        # 插件入口，生命周期管理
├── commands/
│   ├── AxoCommand.kt             # /axo 主命令，分发到下面三个子命令
│   ├── BindCommand.kt            # /axo bind <CODE>
│   ├── WebCommand.kt             # /axo web
│   └── DocsCommand.kt            # /axo docs [path]
├── config/
│   └── PluginConfig.kt           # 配置加载与校验
├── handlers/
│   └── CommandRouter.kt          # 下行命令路由（dispatch 到具体处理器）
├── observation/
│   ├── HeartbeatTask.kt          # 定时心跳任务
│   ├── LeaderboardTask.kt        # 定时排行榜同步任务
│   └── PlayerListener.kt         # 玩家加入/离开事件监听
├── stats/
│   └── StatsCollector.kt         # 采集玩家 stats（playtime、kills 等）
└── transport/
    ├── ApiClient.kt              # HTTP 客户端封装（所有请求走 ?token= 鉴权）
    ├── Envelope.kt               # 响应 envelope 反序列化
    ├── Event.kt                  # 下行事件数据类
    └── PollLoop.kt               # 长轮询主循环
```

## 通信协议

插件实现平台服务器接入协议（HTTP 长轮询）：

| 方向 | 端点 | 说明 |
|---|---|---|
| 下行（拉） | `GET /api/srv/poll?token=` | 长轮询，挂起最多 28s，返回一个事件或 204 |
| 下行（回） | `POST /api/srv/reply?token=` | 回复需要响应的命令（如白名单操作结果） |
| 上行 | `POST /api/srv/heartbeat?token=` | 心跳 + 在线人数 |
| 上行 | `POST /api/srv/player.joined?token=` | 玩家加入 |
| 上行 | `POST /api/srv/player.left?token=` | 玩家离开 |
| 上行 | `POST /api/srv/binding.request?token=` | 绑定验证码核验 |
| 上行 | `POST /api/srv/leaderboard.update?token=` | 排行榜批量更新 |
| 查询 | `GET /api/srv/config?token=` | 启动时拉取本服配置 |

### 支持的下行命令

| command | 说明 | 需要 reply |
|---|---|---|
| `player.kick` | 踢出玩家 | 否 |
| `player.notify` | 游戏内私信通知玩家 | 否 |
| `player.whitelist.add` | 加入白名单 | 是 |
| `player.whitelist.remove` | 移出白名单 | 是 |
| `player.stats.fetch` | 查询指定玩家统计数据 | 是 |
| `server.broadcast` | 全服广播 | 否 |
