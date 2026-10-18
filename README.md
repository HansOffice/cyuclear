**简体中文** · [English](README_EN.md)

# CyuClear · 实体清理与虚空垃圾桶

[![Paper](https://img.shields.io/badge/Paper-1.13%20~%201.21+-0284c7?style=for-the-badge&logo=paper&logoColor=white)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-Supported-7c3aed?style=for-the-badge)](https://papermc.io/software/folia)
[![Spigot](https://img.shields.io/badge/Spigot-1.8%20~%201.12-ed8b00?style=for-the-badge&logo=spigotmc&logoColor=white)](https://www.spigotmc.org)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-8%20%7C%2017%20%7C%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-CYU%20Custom-2563eb?style=for-the-badge)](LICENSE-CYU.md)

用于 Minecraft 服务端的实体清理与虚空垃圾桶插件，适用于 1.8 ~ 1.21+，支持 Paper、Folia 与 Spigot，自带贵重物品保护与区块防卡机制，被清理的物品暂存至虚空箱，支持随时翻找找回与批次还原

[官方文档与 Wiki](https://nexu.cyucloud.cn) · [CyuCloud服主技术交流群](https://qm.qq.com/q/2sW504fPF2) · [提交问题](https://github.com/HansOffice/cyuclear/issues)

---

## 运行包

| 服务端核心 | 运行包 | 适用版本 |
| --- | --- | --- |
| ![Paper](https://img.shields.io/badge/Paper-0284c7?style=flat-square&logo=paper&logoColor=white) | `cyuclear-paper-1.4.7.jar` | Paper 及 Purpur 1.13 及以上版本 |
| ![Folia](https://img.shields.io/badge/Folia-7c3aed?style=flat-square) | `cyuclear-folia-1.4.7.jar` | Folia 1.20 及以上版本 |
| ![Spigot](https://img.shields.io/badge/Spigot-ed8b00?style=flat-square&logo=spigotmc&logoColor=white) | `cyuclear-legacy-1.4.7.jar` | Spigot 及 Bukkit 1.8 ~ 1.12 版本 |

---

## 内容介绍

### 扫地与贵重保护
- **自动清理**：定时分批清理地面掉落物与多余实体
- **默认豁免**：潜影盒、信标、命名生物、戴鞍坐骑、高附魔装备与骑乘中的载具默认不被清理
- **准星排查**：对准实体执行 `/cc check`，查看是否会被清理及命中规则
- **安全预演**：执行 `/cc preview` 预先查看清理清单，不删除物品

### 虚空垃圾桶
- **全服后悔药**：清理掉落物自动存入虚空垃圾桶，支持随时翻找取回
- **批次还原**：执行 `/cc runs` 查看清理记录，支持按批次一键还原
- **随身垃圾箱**：输入 `/cc bin` 打开垃圾桶丢弃杂物

### 区块防卡与体检
- **堆叠限流**：单区块实体数量超限时就地清理，压制刷怪场卡顿
- **卡服定位**：执行 `/cc hotspots` 扫描并列出实体密集的区块坐标
- **配置体检**：执行 `/cc doctor` 检查配置文件规则冲突与拼写错误

---

## 指令

| 指令 | 效果 | 权限 |
| --- | --- | --- |
| `/cc help` | 查看可用命令指引 | 全体玩家 |
| `/cc bin` | 打开虚空垃圾桶，翻找找回或丢弃杂物 | 全体玩家 |
| `/cc preview` | 预演清理，查看预估清单，不删除物品 | 管理员 |
| `/cc check` | 查看准星对准的目标是否会被清理及原因 | 管理员 |
| `/cc menu` | 打开管理面板 | 管理员 |
| `/cc runs` | 查看历史清理记录，支持按批次还原物品 | 管理员 |
| `/cc hotspots` | 列出全服实体最多的卡服区块坐标 | 管理员 |
| `/cc status` | 查看运行状态与下次清理倒计时 | 管理员 |
| `/cc doctor` | 检查配置文件规则冲突与拼写错误 | 管理员 |
| `/cc reload` | 重载全部配置文件与规则 | 管理员 |

---

## 配置

- `config.yml`：清理周期、提示消息与世界开关
- `rules.yml`：掉落物与实体保护白名单、单区块实体上限
- `areas.yml`：特定世界与坐标区域的独立规则
- `void-bin.yml`：虚空垃圾桶格子数量与保留时间
- `storage.yml`：数据存储与数据库连接配置
- `sounds.yml`：清理与打开垃圾桶时的音效
- `lang/`：多语言提示文本，默认使用 `zh_cn.yml`
- `menu/`：菜单界面布局配置

---

## 构建

```bash
# 构建 Paper 运行包
mvn clean package -Ppaper -DskipTests

# 构建 Folia 运行包
mvn package -Pfolia -DskipTests

# 构建 1.8~1.12 运行包
mvn package -Plegacy -DskipTests
```

---

## 协议

本项目采用自定义源码许可协议，详情参见 [`LICENSE-CYU.md`](LICENSE-CYU.md)：
- 允许个人学习、自建服务器部署与非商业修改
- 保留原始作者版权声明与开源出处
- 严禁商业转售、倒卖、打包收费或抹除版权信息
