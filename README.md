# ScoreboardDB

A Minecraft Paper plugin (1.20.1+) for storing scoreboard data in a local or remote database (SQLite, MySQL, MariaDB). Supports Velocity integration, periodic sync, and configurable YAML.
Full Gemini Pro Vibe-Coded Fork for MariaDB support, full constant sync, and PlaceHolderAPI/Papi support
Tested on 1.21.10

## Features
- Stores scoreboard data in a database (local SQLite, or remote MySQL/MariaDB)
- Auto-creates tables: server name, scoreboard name, string, value
- YAML config (see `config.yml`)
- Commands: save value, get value, sync-now
- Periodic sync (configurable interval)
- Velocity support for server name
- Auto Sync on Join
- Only Sync the player data if the player is online
- placeholder : %scoreboarddb_value_{your_scoreboard}%

## Exemple
- you add 1 to the scoreboard money
- you enter /scoreboarddb save money %player_name% 10
- the player change server
- it's auto sync on joining
- the player as is scoreboard auto set to 10

## Setup
1. Place the plugin JAR in your server's `plugins` folder.
2. Configure `config.yml` in the plugin's data folder.
3. Start the server.

## Building
- Requires Java 17+
- Build with Maven: `gradle build`

## Dependencies
- Paper API 1.20.1+
- HikariCP
- SQLite JDBC
- MySQL JDBC
- MariaDB
- SnakeYAML

## License
MIT
