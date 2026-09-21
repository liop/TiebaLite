# <p align="center">Tieba Lite</p>
<p align="center">
    <a href="https://github.com/HuanCheng65/TiebaLite/actions/workflows/build.yml">
        <img alt="Build Status" src="https://github.com/HuanCheng65/TiebaLite/actions/workflows/build.yml/badge.svg?branch=4.0-dev">
    </a>
    <a href="https://t.me/tblite_discuss">
        <img alt="Status" src="https://img.shields.io/badge/-Telegram-blue?logo=telegram&style=flat">
    </a>
</p>

贴吧 Lite 是一个**非官方**的贴吧客户端。

## 蒲公英上传

复制环境变量模板并填写自己的 API Key：

```bash
cp .env.example .env.local
```

构建 debug APK 并上传蒲公英：

```bash
./scripts/pgyer.sh pgyer
```

也可以构建 release 包，或直接上传已有 APK：

```bash
./scripts/pgyer.sh pgyer type:release
./scripts/pgyer.sh upload file:/absolute/path/app.apk
```

脚本会把构建产物复制到 `dist/`，并在蒲公英更新说明中附带版本、分支和最近 5 条 Git 提交。需要自定义说明时可设置 `PGYER_UPDATE_DESC`，或传入 `desc:更新说明`。

## 说明

**本软件及源码仅供学习交流使用，严禁用于商业用途。**

## 友情链接

+ [Starry-OvO/aiotieba: Asynchronous I/O Client for Baidu Tieba](https://github.com/Starry-OvO/aiotieba)
+ [n0099/tbclient.protobuf: 百度贴吧客户端 Protocol Buffers 定义文件合集](https://github.com/n0099/tbclient.protobuf)
