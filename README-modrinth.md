# PAPIStore

<p align="center">
  <img src="https://github.com/che11111/PAPIStore/blob/main/PAPIStore.png?raw=true" width="128" height="128" alt="PAPIStore logo">
</p>

<p align="center">
  <a href="#requirements"><img src="https://img.shields.io/badge/Java-8%2B-orange" alt="Java 8+"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Minecraft-1.8.8%E2%80%9326.3-3fb950" alt="Minecraft 1.8.8–26.3"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Spigot%20%2F%20Paper-supported-blue" alt="Spigot and Paper supported"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Folia-supported-blueviolet" alt="Folia supported"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/PlaceholderAPI-required-5865F2" alt="PlaceholderAPI required"></a>
  <a href="#configuration"><img src="https://img.shields.io/badge/storage-SQLite%20%2F%20MySQL-E67E22" alt="SQLite and MySQL supported"></a>
</p>

<p align="center">
  <a href="https://github.com/che11111/PAPIStore/blob/main/README-zh.md">简体中文</a> ·
  <b>English</b>
</p>

PAPIStore is a Bukkit server plugin that stores PlaceholderAPI data from online players in SQLite or MySQL. The stored data can be consumed by websites, Discord bots, dashboards, scripts, or other plugins.

## Requirements

- Java 8+
- Spigot, Paper, Purpur, or CraftBukkit 1.8.8–26.3
- Folia, using the player entity scheduler and global region scheduler
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI)

PlaceholderAPI 2.12.x requires Minecraft 1.13+. Servers running 1.8–1.12 must use an older PlaceholderAPI build compatible with that server version. The PlaceholderAPI build used on Folia must also support Folia.

## Installation

1. Install PlaceholderAPI.
2. Place the PAPIStore JAR in the server's `plugins/` directory.
3. Start the server.
4. Edit `plugins/PAPIStore/fields.yml` to configure the values that should be stored.
5. Restart the server. After the initial setup, use `/papistore reload` for supported changes.

SQLite is used by default. Its driver must be provided by the server as `org.sqlite.JDBC`; it is not bundled. MySQL Connector/J is bundled in the plugin JAR.

## Commands

| Command | Purpose | Permission |
|---|---|---|
| `/papistore refresh` | Refresh your own data | `papistore.refresh` |
| `/papistore refresh <player>` | Refresh an online player's data | `papistore.op` |
| `/papistore create <PAPI variable> <column> <display name>` | Create a field and write it to `fields.yml` | `papistore.op` |
| `/papistore generate <column>` | Refresh one field for all online players | `papistore.op` |
| `/papistore generate all` | Refresh every field for all online players | `papistore.op` |
| `/papistore query <player/UUID>` | Query a player's stored data | `papistore.query` |
| `/papistore me` | Query your own stored data | `papistore.query.self` |
| `/mydata` | Shortcut for `/papistore me` | `papistore.query.self` |
| `/papistore help` | Show administrator help | `papistore.op` |
| `/papistore migrate <source> <target> [confirm]` | Migrate between SQLite and MySQL | `papistore.op` |
| `/papistore reload` | Reload fields, languages, and player-query switches | `papistore.op` |

`/papistore reload` does not reload the database connection, queue size, or scheduler settings. Restart the server after changing those values.

Tab completion supports subcommands, online player names, configured columns, `all`, and migration arguments.

Default permissions:

| Permission | Default |
|---|---|
| `papistore.refresh` | All players |
| `papistore.query.self` | All players |
| `papistore.op` | OP |
| `papistore.query` | OP |

`/papistore me` and `/mydata` can be enabled independently:

```yaml
player-query:
  enable-papistore-me: true
  enable-mydata: true
```

## fields.yml

`fields.yml` is the single source of truth for field mappings.

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

- `placeholder` is the PlaceholderAPI expression.
- The default variables above are resolved directly by PAPIStore and require no additional expansion.
- `name.placeholder` is reserved and fixed to `%player_name%`; other values are restored with a warning.
- `display-name` stores query labels by language.
- Label fallback order: player language → `en_us` → database column name.
- The generated defaults include player name, UUID, world, X/Y/Z coordinates, and ping.
- Existing fields are preserved; only missing presets are added.
- Third-party expansion placeholders require the matching installed expansion. Unresolved placeholders may be stored as their original text.
- Run `/papistore reload` after editing fields or display names.

## Configuration

```yaml
# Auto-select the player's client language; fall back to English when unknown.
language: auto

storage:
  # SQLite uses the server-provided org.sqlite.JDBC driver; it is not bundled.
  # Storage type: sqlite or mysql. A server restart is required after changing it.
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
  # Spigot/Paper batch size and the maximum concurrent Folia region tasks.
  players-per-tick: 20
  # Maximum time spent rendering one Spigot/Paper PAPI batch.
  render-time-budget-millis: 10
  # Maximum queued database tasks; new refreshes fail fast when full.
  database-queue-capacity: 512
  # Maximum startup wait for database readiness; clamped to 5–60 seconds.
  startup-timeout-seconds: 20

player-query:
  # Whether players can use /papistore me to query their own data.
  enable-papistore-me: true
  # Whether to enable the /mydata shortcut for /papistore me.
  enable-mydata: true
```

Changing the database connection, storage type, queue size, startup timeout, cooldown, or scheduler settings requires a server restart.

## Data Migration

```text
/papistore migrate sqlite mysql
/papistore migrate mysql sqlite
```

The source backend must be the currently active storage. If the target contains data, PAPIStore displays its row count and requires the administrator to confirm within 60 seconds:

```text
/papistore migrate <source> <target> confirm
```

After confirmation, PAPIStore freezes new writes, waits for queued database tasks, and imports the source data in transactional batches. On success, the plugin remains read-only and updates `storage.type`. Restart the server immediately; writes resume on the target backend after the restart.

## Languages

Supported languages:

`en_us`, `zh_cn`, `zh_tw`, `ja_jp`, `ko_kr`, `de_de`, `fr_fr`, `es_es`, `ru_ru`

`language: auto` follows each player's client locale. Console messages, unknown locales, and unsupported locale codes fall back to English. Language files are stored in `plugins/PAPIStore/lang/`; run `/papistore reload` after editing them.
