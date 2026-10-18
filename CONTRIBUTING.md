# 贡献指南

感谢你愿意花时间改进这个项目。下面是最少必要的说明，照着做就能跑起来。

---

## 这个项目是什么

一个安卓 App：**用一句话描述，从相册里把那张照片翻出来**。
三条线索可叠加 —— 图里的文字（本地 OCR）、文件信息（格式/年份/有无文字）、
图长什么样（云端 AI 比对）。

技术栈：Kotlin + Jetpack Compose（Material 3）、Room、Coil、ML Kit、OkHttp。
单模块，没有多模块拆分，也没有依赖注入框架（用 `ViewModelProvider.Factory` 手动构造）。

---

## 搭建开发环境

### 需要的工具

| 工具 | 版本 |
|---|---|
| Android Studio | 最新稳定版即可 |
| Android SDK | API 37（compileSdk / targetSdk 37，minSdk 29） |
| JDK | 21 或更高（项目用 Java 25 toolchain 也能跑） |
| Gradle | 用仓库自带的 `gradlew`，不用另装 |

### 编译

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

第一次编译会下载依赖，需要几分钟。之后走缓存会快很多。

> ⚠️ 如果项目路径里含中文，可能会遇到
> `Your project path contains non-ASCII characters`。
> `gradle.properties` 里已经开了 `android.overridePathCheck=true`，
> 但为了省事，**建议把项目放在纯英文路径下**。

---

## 代码结构

```
app/src/main/java/com/atri/photofinder/
├── MainActivity.kt              入口
├── PhotoFinderApp.kt            Application：注册 Coil 的 GIF 解码器
├── model/SearchModels.kt        枚举（格式分类、清晰度档位）
├── data/
│   ├── PhotoRepository.kt       扫描、识别、状态编排
│   ├── TextMatcher.kt           文字搜索（空格分词）
│   ├── media/MediaStoreReader.kt 读相册（MediaStore 查询）
│   ├── ocr/TextRecognizer.kt    本地文字识别（ML Kit）
│   ├── ai/AiImageSearch.kt      AI 搜索（理解 → 比对 → 复核）
│   ├── db/                      Room：实体、DAO、数据库
│   └── settings/SettingsStore.kt DataStore：密钥、服务商、单次张数
└── ui/
    ├── MainScreen.kt            所有界面（Compose）
    ├── PhotoViewModel.kt        全部界面状态
    ├── MediaPermissions.kt      权限请求
    └── theme/                   配色、字体
```

---

## 代码规范

没有强制检查工具，但请保持这些习惯：

1. **注释写"为什么"，不写"是什么"**。
   `// 加一` 这种没用；`// 加一是因为界面要求从 1 开始计数` 才有价值。
2. **踩过的坑要留在注释里**。这个项目里很多注释在解释"为什么不能这样写"，
   因为那些是真实踩过的 bug，删了之后容易重犯。
3. **不要硬编码密钥**。密钥一律走设置页 → DataStore。
4. **改数据库结构要谨慎**：`PhotoDatabase` 现在 `version = 5` 且是
   `fallbackToDestructiveMigration()` —— **升版本会清空用户目录和识别缓存**，
   用户要重新扫描、重新识别。能复用字段就复用。
5. **界面文字用中文**，且尽量短（这个 App 面向中文用户，文案偏简洁）。

---

## 提交 PR 的流程

1. Fork 仓库
2. 从 `main` 开一个分支：`git checkout -b fix/年份滚轮`
3. 改完先自己编译一遍，确认能过
4. 提交，写清楚**改了什么、为什么**：
   ```
   fix: 年份滚轮滑到顶端选不到最老一年

   原因：居中项计算依赖空白填充，到边界时会算错两格。
   改法：加边界特判（滚不动了就是到头了）。
   ```
5. Push 到你的 fork，然后开 Pull Request

### PR 里请说明

- 改了哪个问题（最好附上复现步骤）
- 有没有在真机上跑过
- 有没有动数据库版本

---

## 提交 Issue

请带上：

- 手机型号 + 安卓版本
- 复现步骤（第几步、点了什么）
- 预期结果 vs 实际结果
- 如果是 AI 搜索的问题，**附上「看 AI 的原始回复」的内容**（App 里有这个按钮）

---

## 不要提交什么

- `local.properties`（本机 SDK 路径）
- `*.jks` / `*.keystore` / 任何密钥文件
- `app/build/` 等编译产物
- 你自己的 API 密钥（哪怕是注释里的示例）

`.gitignore` 已经覆盖了这些，提交前可以自查：

```bash
git status
git ls-files | grep -E 'local.properties|\.jks|secrets'
```

---

## 行为

参与本项目即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)。
