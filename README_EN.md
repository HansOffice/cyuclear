[简体中文](README.md) · **English**

# CyuClear

[![Paper](https://img.shields.io/badge/Paper-1.13%20~%201.21+-0284c7?style=for-the-badge&logo=paper&logoColor=white)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-Supported-7c3aed?style=for-the-badge)](https://papermc.io/software/folia)
[![Spigot](https://img.shields.io/badge/Spigot-1.8%20~%201.12-ed8b00?style=for-the-badge&logo=spigotmc&logoColor=white)](https://www.spigotmc.org)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-8%20%7C%2017%20%7C%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-CYU%20Custom-2563eb?style=for-the-badge)](LICENSE-CYU.md)

Entity cleanup and void recovery plugin for Minecraft servers, supporting Paper, Folia, and Spigot on 1.8 ~ 1.21+, featuring automatic valuable item protection, chunk overload limits, and a player-accessible void trash can

[Official Wiki](https://nexu.cyucloud.cn) · [Community Group](https://qm.qq.com/q/2sW504fPF2) · [Issue Tracker](https://github.com/HansOffice/cyuclear/issues)

---

## Downloads

| Platform | Jar File | Compatibility |
| --- | --- | --- |
| ![Paper](https://img.shields.io/badge/Paper-0284c7?style=flat-square&logo=paper&logoColor=white) | `cyuclear-paper-1.4.7.jar` | Paper and Purpur 1.13+ |
| ![Folia](https://img.shields.io/badge/Folia-7c3aed?style=flat-square) | `cyuclear-folia-1.4.7.jar` | Folia 1.20+ |
| ![Spigot](https://img.shields.io/badge/Spigot-ed8b00?style=flat-square&logo=spigotmc&logoColor=white) | `cyuclear-legacy-1.4.7.jar` | Spigot and Bukkit 1.8 ~ 1.12 |

---

## Features

### Entity Cleanup & Protection
- **Scheduled Cleanup**: Periodically clears ground clutter and redundant entities in batches
- **Automatic Whitelist**: Automatically preserves shulker boxes, beacons, named mobs, saddled mounts, enchanted gear, and ridden vehicles
- **Target Inspection**: Run `/cc check` while looking at an entity to see whether it will be cleared and the matching rule
- **Dry Run**: Run `/cc preview` to estimate the cleanup count and items without deleting anything

### Void Trash Can
- **Lost Item Retrieval**: Cleared items are stored in a shared void bin for players to search and take back
- **Batch Rollback**: Run `/cc runs` to inspect recent cleanup batches and restore items with one command
- **Inventory Disposal**: Run `/cc bin` to open a personal disposal container for unwanted items

### Lag Prevention & Diagnostics
- **Chunk Entity Limiter**: Enforces entity limits per chunk to suppress farm-induced lag
- **Hotspot Scanner**: Run `/cc hotspots` to locate coordinates of chunks with high entity density
- **Configuration Doctor**: Run `/cc doctor` to inspect configuration syntax errors and conflicting rules

---

## Commands & Permissions

| Command | Description | Permission | Default |
| --- | --- | --- | --- |
| `/cc help` | View available commands | `cyuclear.use` | Everyone |
| `/cc bin` | Open void trash can to retrieve items or discard junk | `cyuclear.use` | Everyone |
| `/cc preview` | Dry-run cleanup to preview items without deletion | `cyuclear.admin` | OP |
| `/cc check` | Check whether the target entity will be cleared and the rule | `cyuclear.admin` | OP |
| `/cc menu` | Open GUI management panel | `cyuclear.admin` | OP |
| `/cc runs` | View cleanup history and restore items by batch | `cyuclear.admin` | OP |
| `/cc hotspots` | List coordinates of chunks with high entity counts | `cyuclear.admin` | OP |
| `/cc status` | View running status and time until next cleanup | `cyuclear.admin` | OP |
| `/cc doctor` | Inspect configuration files for errors and conflicts | `cyuclear.admin` | OP |
| `/cc reload` | Reload configuration files and rules | `cyuclear.admin` | OP |

---

## Configuration

- `config.yml`: Cleanup intervals, broadcast messages, and world toggles
- `rules.yml`: Item and entity whitelists, protection rules, and chunk limits
- `areas.yml`: Per-world and coordinate region rules
- `void-bin.yml`: Void bin capacity and retention duration
- `storage.yml`: Local storage and database connection settings
- `sounds.yml`: Audio effects for cleanups and trash can interactions
- `lang/`: Localization files with built-in `zh_cn.yml` and `en_us.yml`
- `menu/`: Chest GUI layout definitions

---

## Building from Source

```bash
# Build Paper jar
mvn clean package -Ppaper -DskipTests

# Build Folia jar
mvn package -Pfolia -DskipTests

# Build 1.8~1.12 jar
mvn package -Plegacy -DskipTests
```

---

## License

This project is licensed under a custom source license, see [`LICENSE-CYU.md`](LICENSE-CYU.md):
- Personal learning, self-hosted deployment, and non-commercial modifications allowed
- Original author copyright and project source notices must be preserved
- Commercial resale, repackaging for sale, or removal of copyright notices strictly prohibited
