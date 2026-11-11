<!--suppress HtmlDeprecatedAttribute -->
<div align="center">
    <h1> UniversalPluginUpdater </h1>

[![GitHub release](https://img.shields.io/github/v/release/DreamVoid/UniversalPluginUpdater?style=flat-square
)](https://github.com/DreamVoid/UniversalPluginUpdater/releases/latest)
![GitHub all releases](https://img.shields.io/github/downloads/DreamVoid/UniversalPluginUpdater/total?style=flat-square)
</div>

[简体中文](README.md) | [English](README.en-US.md)

---

## 介绍
UniversalPluginUpdater（UPU）是一个 Minecraft 服务端插件，能够让你在 Minecraft 服务器上快速升级现有的插件，同时提供一些 API 帮助开发者简单的为自己的插件实现自动更新功能。

UniversalPluginUpdater 的开发受到 [APT](https://wiki.debian.org/zh_CN/Apt) 的启发。

## 文档和教程

对于初次使用 UPU 的用户来说，阅读 [UPU 帮助手册](https://docs.upu.dreamvoid.me/)能够快速开始使用 UPU，许多问题也能够通过查看文档得到解答，其中也包含了 UPU 如何检查其他插件的更新。

使用 `updater.strategy: backup` 可以在更新时保留旧版 JAR。参见[备份升级策略](docs/backup-upgrade.md)。

## 下载
* 稳定版本
  * [Modrinth](https://modrinth.com/plugin/upu/versions)
  * [Hangar](https://hangar.papermc.io/DreamVoid/UniversalPluginUpdater/versions)
  * [GitHub 发布页](https://github.com/DreamVoid/UniversalPluginUpdater/releases)

## 命令

此处仅列出了几个常用命令，要查看完整命令列表，请查阅[文档](https://docs.upu.dreamvoid.me/core/commands)。

| 命令 | 描述 |
|-------------------|---------------------------|
| /universalpluginupdater (/upu) | UniversalPluginUpdater 主命令 | 
| /upu update | 更新可用插件列表 |
| /upu list | 列出可用插件 |
| /upu download | 通过 下载 来下载现有插件的更新版本 |
| /upu upgrade | 通过 下载/安装 来升级现有插件到更新版本 |
| /upu repo | 配置仓库子命令 |

## 许可证

[GNU General Public License v3.0](https://github.com/DreamVoid/UniversalPluginUpdater/blob/main/LICENSE)

---

[DreamVoid](https://github.com/DreamVoid)，用 ❤ 制作。
