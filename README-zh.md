# PAPIStore

<p align="center">
  <img src="PAPIStore.png" width="200" height="200" alt="PAPIStore logo">
</p>

<p align="center">
  <a href="#环境要求"><img src="https://img.shields.io/badge/Java-8%2B-orange" alt="Java 8+"></a>
  <a href="#环境要求"><img src="https://img.shields.io/badge/Minecraft-1.8.8%E2%80%9326.3-3fb950" alt="Minecraft 1.8.8–26.3"></a>
  <a href="#环境要求"><img src="https://img.shields.io/badge/Spigot%20%2F%20Paper-supported-blue" alt="Spigot and Paper supported"></a>
  <a href="#环境要求"><img src="https://img.shields.io/badge/Folia-supported-blueviolet" alt="Folia supported"></a>
  <a href="#环境要求"><img src="https://img.shields.io/badge/PlaceholderAPI-required-5865F2" alt="PlaceholderAPI required"></a>
  <a href="#配置"><img src="https://img.shields.io/badge/storage-SQLite%20%2F%20MySQL-E67E22" alt="SQLite and MySQL supported"></a>
</p>

<p align="center">
  <b>简体中文</b> ·
  <a href="README.md">English</a>
</p>

PAPIStore 是一个 Bukkit 服务端插件，可将在线玩家的 PlaceholderAPI 数据持续保存到 SQLite 或 MySQL，供网站、机器人或其他插件读取。

将玩家的 PlaceholderAPI 数据保存到 SQLite 或 MySQL。

## 环境要求

- Java 8+
- Spigot / Paper / Purpur / CraftBukkit 1.8.8–26.3
- Folia，使用玩家实体调度器和全局区域调度器
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI)

最新版 PlaceholderAPI 2.12.x 要求 Minecraft 1.13+。要运行在 1.8–1.12，需要使用支持对应服务端的旧版 PlaceholderAPI。Folia 使用的 PlaceholderAPI 构建也必须支持 Folia。

## 安装

1. 安装 PlaceholderAPI。
2. 将 PAPIStore JAR 放入 `plugins/`。
3. 启动服务器。
4. 编辑 `plugins/PAPIStore/fields.yml` 配置需要保存的 PAPI 变量。
5. 重启服务器。

默认使用 SQLite。SQLite 驱动由服务端提供为 `org.sqlite.JDBC`，插件不会打包该驱动。MySQL Connector/J 已包含在插件 JAR 中。

## 指令

| 指令 | 用途 | 权限 |
|---|---|---|
| `/papistore refresh` | 刷新自己的数据 | `papistore.refresh` |
| `/papistore refresh <玩家>` | 刷新在线玩家 | `papistore.op` |
| `/papistore create <PAPI变量> <列名> <备注名>` | 创建字段并写入 `fields.yml` | `papistore.op` |
| `/papistore generate <列名>` | 刷新指定字段的全部在线玩家 | `papistore.op` |
| `/papistore generate all` | 刷新全部字段的全部在线玩家 | `papistore.op` |
| `/papistore query <玩家/UUID>` | 查询任意玩家的已存数据 | `papistore.query` |
| `/papistore me` | 查询自己的数据 | `papistore.query.self` |
| `/mydata` | `/papistore me` 的快捷指令 | `papistore.query.self` |
| `/papistore help` | 显示管理员帮助 | `papistore.op` |
| `/papistore migrate <源> <目标> [confirm]` | 在 SQLite 与 MySQL 间迁移 | `papistore.op` |
| `/papistore reload` | 重载字段、语言和玩家查询开关 | `papistore.op` |

`/papistore reload` 不会重载数据库连接、队列大小或调度参数；修改这些配置后仍需重启服务器。

支持 Tab 补全：子指令、在线玩家名、已配置列名、`all` 和迁移参数。

权限默认值：

| 权限 | 默认 |
|---|---|
| `papistore.refresh` | 所有玩家 |
| `papistore.query.self` | 所有玩家 |
| `papistore.op` | OP |
| `papistore.query` | OP |

`/papistore me` 和 `/mydata` 可以分别关闭：

```yaml
player-query:
  enable-papistore-me: true
  enable-mydata: true
```

## fields.yml

`fields.yml` 是字段映射的唯一来源。

```yaml
fields:
  name:
    placeholder: "%player_name%"
    display-name:
      zh_cn: "玩家名称"
      en_us: "Player Name"

  player_uuid:
    placeholder: "%player_uuid%"
    display-name:
      zh_cn: "玩家UUID"
      en_us: "Player UUID"

  world:
    placeholder: "%player_world%"
    display-name:
      zh_cn: "当前世界"
      en_us: "Current World"

  x:
    placeholder: "%player_x%"
    display-name:
      zh_cn: "X坐标"
      en_us: "X Coordinate"

  y:
    placeholder: "%player_y%"
    display-name:
      zh_cn: "Y坐标"
      en_us: "Y Coordinate"

  z:
    placeholder: "%player_z%"
    display-name:
      zh_cn: "Z坐标"
      en_us: "Z Coordinate"

  ping:
    placeholder: "%player_ping%"
    display-name:
      zh_cn: "延迟"
      en_us: "Ping"
```

- `placeholder`：PAPI 变量。
- `name.placeholder` 是保留字段，固定为 `%player_name%`；其他值会被恢复并记录警告。
- `display-name`：查询时显示的名称，可添加任意语言键。
- 显示名依次回退：玩家语言 → `en_us` → 数据库列名。
- 首次生成会预设玩家名称、UUID、世界、X/Y/Z 坐标和延迟。
- 已有字段不会被覆盖，只补充缺失预设。
- 如果填写第三方扩展变量，必须先安装对应扩展；未解析的变量可能按原字符串保存。
- 修改后可执行 `/papistore reload` 使字段和显示名生效。

## 配置

```yaml
# 自动根据玩家客户端语言选择；无法识别时使用英语。
language: auto

storage:
  # SQLite 使用服务端提供的 org.sqlite.JDBC，驱动不随插件打包。
  # 存储类型可选 sqlite 或 mysql；修改后需要重启服务器。
  type: sqlite
  sqlite:
    file: "papi_store.db"
    table: "papi_store"

mysql:
  user: root
  password: "PleaseInput"
  address: 127.0.0.1
  port: 3306
  useSSL: false
  database: "minecraft"
  table: "papi_mysql"
  allowPublicKeyRetrieval: true
  connectTimeout: 5000
  socketTimeout: 10000

settings:
  refresh-cooldown-seconds: 10
  # Spigot/Paper 的单批大小，也是 Folia 单批并发区域任务上限。
  players-per-tick: 20
  # Spigot/Paper 每批 PAPI 渲染最多占用的时间，避免一次采集过多玩家。
  render-time-budget-millis: 10
  # 数据库异步队列上限；满载时新刷新会快速失败，避免无限积压。
  database-queue-capacity: 512
  # 插件启动等待数据库就绪的最长时间，范围 5-60 秒。
  startup-timeout-seconds: 20

player-query:
  # 是否允许玩家使用 /papistore me 查询自己的数据。
  enable-papistore-me: true
  # 是否启用 /mydata 快捷指令；关闭后该指令会提示功能已禁用。
  enable-mydata: true

```

## 数据迁移

```text
/papistore migrate sqlite mysql
/papistore migrate mysql sqlite
```

源存储必须是当前启用的存储。目标库有数据时会显示条数，并要求管理员在 60 秒内执行：

```text
/papistore migrate <源> <目标> confirm
```

确认后会冻结新写入、等待已排队的数据库任务完成，再分批事务性导入源数据。
迁移成功后插件保持只读，`storage.type` 自动更新；请立即重启服务器，重启后写入才会切换到目标存储。

## 多语言

支持：

`en_us`、`zh_cn`、`zh_tw`、`ja_jp`、`ko_kr`、`de_de`、`fr_fr`、`es_es`、`ru_ru`

`language: auto` 根据玩家客户端语言选择；控制台、未知语言和不支持的 locale 使用英语。语言文件位于 `plugins/PAPIStore/lang/`，修改后可执行 `/papistore reload`。
