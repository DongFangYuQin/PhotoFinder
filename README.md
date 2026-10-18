<div align="center">

<img src="docs/images/icon.png" width="120" alt="图标">

# 相册找图（PhotoFinder）

**用一句话描述，从手机相册里把那张照片翻出来。**

文字识别完全在手机本地完成（不联网、不花钱、照片不上传）<br>
只有「按图片内容搜索」才会联网调用 AI

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)

<a href="https://github.com/DongFangYuQin/PhotoFinder/releases/latest">
  <img src="https://img.shields.io/github/v/release/DongFangYuQin/PhotoFinder?label=%E4%B8%8B%E8%BD%BD%E5%AE%89%E8%A3%85%E5%8C%85&color=3E6E96&style=for-the-badge" alt="下载">
</a>

</div>

---

## 下载安装

**👉 [点这里下载最新版 APK](https://github.com/DongFangYuQin/PhotoFinder/releases/latest)**

| 项 | 说明 |
|---|---|
| 系统要求 | **安卓 10（API 29）及以上** |
| 安装包大小 | 约 **49 MB**（文字识别模型占大头） |
| 签名 | 已用**发布密钥**签名（不是调试版） |
| 安装方式 | 下载 APK → 传到手机 → 点开安装 → 首次需在系统里允许「安装未知来源应用」 |

**验证下载是否完整**（可选）：对比 Release 页面上的 SHA-256。
本版本证书指纹 `SHA-256`：`3657076B3B9F39929EDA401F73D46A13E34A504F8DE4784B1D793530065B724B`

> ⚠️ **装过更早的调试版？** 必须先**卸载**旧版再装这个版本 ——
> 调试签名和发布签名不同，不能覆盖安装。

---

## 它解决什么问题

手机相册存了几千张图之后，想找某一张基本靠回忆往下滑。
这个 App 让你不用整理相册，也能按**三条线索**把图翻出来，线索可以叠加，填得越多越准：

| 线索 | 举例 | 靠什么实现 | 花钱吗 |
|---|---|---|---|
| **图里的文字** | 「发票」「318」 | 手机本地文字识别（ML Kit 中文包） | 不花钱 |
| **文件信息** | GIF / 2024 年的 / 有字没字 | 读文件属性 | 不花钱 |
| **图长什么样** | 「一只橘猫趴在键盘上」「某个动漫角色」 | 云端 AI 看图（**要联网**） | 约 1~2 分钱 / 次 |

搜索**不匹配文件名**（没人记得住 `IMG_20240101_120000`），
文字搜索按**空格分词**（`发票` 是一个词；`发 票` 是两个词，都要出现在图里）。

---

## 功能

- **选一个相册** → 扫描建目录（只记"哪张图有什么字、多大、什么时间"，照片本身不动）
- **识别图中文字**：本地免费、可随时暂停继续、进度实时保存；识别错了可以**手动改**
- **按文字搜索**：可叠加「格式 / 年份范围 / 有字无字」排除 OCR 误差
- **浏览**：缩略图网格、点开看大图、双指缩放、双击放大、拖动手动调
- **按图片内容搜索**（联网）：
 1. 先让你用「格式 / 年份 / 文字」把候选缩小（**必须至少选一个**，越少越省越准）
 2. AI 先理解描述（必要时**联网查资料**，所以能认出动漫角色名、作品名）
 3. 把候选图发给 AI 做**比对**（不是识别 —— 这是能认出小众角色的关键）
 4. **再复核一遍**，把不像的剔掉；剔掉的图也能翻看，不会悄悄丢掉
 5. 支持**分批搜索**，已比对过的图不会重复发送，不会重复花钱
- **设置页**：填自己的 API 密钥、切服务商、测试连接、调单次比对张数

---

## 隐私

- **文字识别不出手机**：ML Kit 在本地跑，照片不上传。
- **密钥只存手机**：填在 App 设置里，存在本机 DataStore，**不进代码、不进仓库**。
 所以别人克隆这个仓库，也要填自己的密钥才能用联网功能。
- **只有点「开始搜索」才联网**，且只发送**筛选出来的那几十张候选图**，不是整个相册。
- App **不复制、不修改、不删除**你的照片。

---

## 花钱（以 DeepSeek 为例）

- 文字识别：**免费**
- 让 AI 看一张图：约 **¥0.0005（闲时）~ ¥0.001（高峰）**，一张图按低清计费（最多 1024 单位）
- 一次搜索（默认发 20 张 + 复核）：约 **1~2 分钱**
- 联网查资料（只在需要时）：**¥0.005/次**，没查到结果不收费
- 省钱的窍门：**多设几个条件**把候选缩小；**晚上和周末**是半价

> 费用直接从你填密钥的那个账号扣，App 不碰钱。

---

## 环境要求

| 项 | 版本 |
|---|---|
| Android | **10（API 29）及以上**；编译目标 API 37 |
| AGP / Kotlin / KSP | 9.4.1 / 2.4.10 / 2.3.12 |
| Gradle | 9.6.0 |
| Compose BOM / Room / Coil / OkHttp | 2026.02.01 / 2.8.5 / 3.6.3 / 5.2.1 |
| Java | 17 及以上（本项目用 Java 25 toolchain 编译通过） |

权限只有 5 个：`READ_MEDIA_IMAGES`、`READ_MEDIA_VISUAL_USER_SELECTED`（安卓 14+）、
`READ_EXTERNAL_STORAGE`（安卓 12 及以下）、`INTERNET`、`ACCESS_NETWORK_STATE`。

---

## 怎么编译

**用 Android Studio（推荐）**

```
打开 PhotoFinder
→ 等右下角进度条跑完
→ 手机插上数据线（先开 USB 调试）
→ 点绿色三角
```

**命令行**

```powershell
$env:JAVA_HOME        = '<JDK 目录>'
$env:GRADLE_USER_HOME = '<Gradle 缓存目录>'   # 可选，默认在用户目录下
Set-Location '<项目目录>'

# 调试包（直接能装，用系统自带的调试签名）
.\gradlew.bat --no-daemon --console=plain assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# 正式包（需要自己配签名，见下）
.\gradlew.bat --no-daemon --console=plain assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

> ⚠️ 如果项目路径含中文，`gradle.properties` 里已经开了
> `android.overridePathCheck=true`；挪到纯英文路径后这一行可以删掉。
>
> ⚠️ 另一个开关 `android.disallowKotlinSourceSets=false` 是因为
> AGP 9 内置 Kotlin 支持会和 KSP/Room 的代码生成方式打架，官方文档给的解法。
>
> ⚠️ 调试包约 **80 MB**，正式包约 **49 MB**（正式包会做代码压缩）。

### 怎么打正式签名包

1. 生成一个 keystore（**放在项目目录之外**，别提交）：
   ```powershell
   keytool -genkeypair -v -keystore D:\keystore\photofinder-release.jks `
     -alias photofinder -keyalg RSA -keysize 4096 -validity 10000 `
     -storetype PKCS12 -dname "CN=PhotoFinder, O=PhotoFinder, C=CN"
   ```
2. 在项目根目录新建 `keystore.properties`（已在 `.gitignore` 里，不会上传）：
   ```properties
   storeFile=D:/keystore/photofinder-release.jks
   storePassword=你的密码
   keyAlias=photofinder
   keyPassword=你的密码
   ```
3. `gradlew assembleRelease`

> 🔐 **keystore 和密码一定要备份**。丢了就再也签不出同签名的更新包，
> 用户只能卸载重装。

---

## 文档

- [技术方案.md](docs/技术方案.md) —— 架构、数据表、每处设计的原因
- [环境搭建.md](docs/环境搭建.md) —— 从零装开发工具
- [开发日志.md](docs/开发日志.md) —— 按时间顺序的完整改动记录（很长，含所有踩坑过程）
- [CHANGELOG.md](CHANGELOG.md) —— 每个版本改了什么

---

## 已知限制

- **一次只装一个相册**：换相册会清掉旧目录（本地 OCR 结果有长期缓存，重扫不用重认）。
- **不做后台识别**：国产系统（vivo / OPPO / 小米 / 华为等）会限制后台，
 常驻通知的服务一样会被暂停，绕不过去。想边用手机边识别，把它切成**小窗**。
- **需要自备 API 密钥**才能用「按图片内容搜索」。
- 只支持**中文为主的文字识别**（ML Kit 中文包同时能认英文和数字）。

---

## 参与贡献

欢迎提 Issue 和 PR。开始之前请先看 [贡献指南](CONTRIBUTING.md)（有环境搭建和代码规范），
参与即表示同意遵守 [行为准则](CODE_OF_CONDUCT.md)。

发现安全问题请**不要公开提 Issue**，按 [安全策略](SECURITY.md) 私下报告。

---

## 协议

[MIT](LICENSE) —— 随便用、随便改，保留版权声明即可。
