# PLG1 — 播放核心与可选能力模块化（Runtime Module）设计

- **状态**：设计待批准（未实施，未改动任何业务代码）
- **评估时间**：2026-10-16 23:30 → 2026-10-17 更新（引入上游 FongMi 实证）
- **基线**：分支 `dev1` @ `2375d5a291`，工作树初始干净
- **目标**：把 MPV 播放核心及一批可选能力从默认 APK 移出，改为用户按需安装的模块；建立独立 GitHub 仓库（默影视插件）承载模块产物
- **关联**：本文档为该任务唯一权威文档。候选清单见 §5，上游契约见 §6。

> 本文档是 `AGENTS.md` §7 要求的 best-practice 研究门禁产物。**在获得明确批准前不得实施。**

---

## 1. 结论摘要

**核心结论：这不是一个需要我们从零设计的方案。上游 FongMi/TV 已经完整实现并验证了同一件事，我们应该承接其架构而不是自创。**

实证（`FongMi/Release` releases API，2026-10-17 抓取）：

| 版本 | 体积 | 说明 |
| --- | --- | --- |
| `v5.7.1` | **141.3 MB** | 模块化之前 —— **与 WebHTV 当前 143.7 MB 几乎相同** |
| `5.7.2` | 67.1 MB | 中间态 |
| `5.7.3` | 49.6 MB | |
| `5.7.4` | **49.5 MB** | 当前 —— **-65%** |

同期 `module` tag 下的分流产物：

| 模块 | arm64-v8a | armeabi-v7a |
| --- | --- | --- |
| `runtime-mpv-1.0.2` | 17.8 MB | 14.6 MB |
| `runtime-nodejs-1.0.2` | 48.8 MB | 42.7 MB |
| `runtime-bdj-1.0.1` | 41.3 MB | 40.9 MB |
| `runtime-python-1.0.4` | 22.4 MB | 17.9 MB |
| `runtime-thunder-1.0.2` | 6.5 MB | 4.2 MB |
| `runtime-quickjs-1.0.2` | 1.35 MB | 0.97 MB |
| `catalog.json` | 5 KB（清单） | — |

**因此档位收敛为一档**：

| 档位 | 做法 | APK | 建议 |
| --- | --- | --- | --- |
| **定案** | 承接上游 Runtime Module 架构（APK 形态模块 + `PackageInstaller` + `hostLibraries` 契约） | 143.7 MB → **约 25–40 MB** | **采用** |
| ~~档 0~~ | ~~assets 内改压缩归档~~ | — | **废弃**（上游用模块化，不做中间态） |
| ~~档 2~~ | ~~Java 代码 DexClassLoader 化~~ | — | **废弃**（见 §2.3，且上游未采用） |

**架构决策（回答你的问题）**：

1. **新建独立模块体系 `com.fongmi.android.tv.runtime`**，**不复用「实验室」**（`lab` 是 Linux 环境/脚本市场，语义与安全契约都不同，见 §3）。
2. **新建独立 GitHub 仓库** 承载模块产物 —— 上游用 `FongMi/Release` 私有 Release 分发，我们对应建 `webhtv-plugins`。
3. **模块容器用 APK**（非 7z/zip），由系统 `PackageInstaller` 安装 —— 这是上游实证的最佳形态，收益见 §6.4。

---

## 2. 现状证据（WebHTV 实测）

### 2.1 MPV 已是「assets + 运行时加载」

`app/src/main/java/is/xyz/mpv/MPVLib.java:66-97`

```text
chooseAbi(assets) → 拷 assets/mpv-libs/<abi>/*.so → app_mpv-libs/<abi>/
→ System.load(绝对路径)        // 非 System.loadLibrary()
```

`app.getDir("mpv-libs", MODE_PRIVATE)` = `/data/data/<pkg>/app_mpv-libs/`，已在应用私有存储。

**插件化最难的一步（绕过打包期 .so 绑定）项目里已存在。**

### 2.2 体积实测

| 项目 | 数值 |
| --- | --- |
| `app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/*.so`（源码） | **46,673,824 B = 44.5 MB** |
| 同上 `gzip -9` | **21,344,970 B = 20.4 MB** |
| `app/src/armeabi_v7a/assets/mpv-libs/` | ≈ 39 MB |
| APK 内 `assets/mpv-libs`（8/24 构建） | 37,246,264 B = 35.5 MB |
| APK 内压缩方式 | **Stored（0%）** |
| 当前 APK | mobile-arm64 **143.7 MB** / leanback-arm64 **153.4 MB** |

> 上游 mpv 模块 17.8 MB vs WebHTV 37.2 MB：WebHTV 的 mpv 构建含更多补丁（FEL / DV7-DV8 / Vulkan / AVS3 / HPM），且 ffmpeg 拆为多个 soname（`libmvcodec` 等），故体积约为上游 2 倍。**这是既有能力，模块化不应削减它。**

### 2.3 代码耦合面（为何否决 DexClassLoader 档）

| 位置 | 体量 |
| --- | --- |
| `androidx/media3/mpvplayer/` | 47 文件 / 14,579 行 |
| `com/fongmi/android/tv/player/mpv/` | 22 文件 / 8,222 行 |
| `MpvPlayer.java` | 6,667 行 |
| `PlayerManager.java` | 9,333 行 |
| `PlayerSetting.MPV` 静态引用 | **97 处**，散布 16 个包 |
| `PlayerEngine` 接口泄漏 mpv 类型 | `setNativeLutShader(MpvLutShader)` |

上游同样**没有**把 Java 代码模块化 —— 它只搬运 native 运行时。这是重要旁证。

### 2.4 WebHTV 已具备的前置条件（关键）

| 能力 | 位置 | 状态 |
| --- | --- | --- |
| `REQUEST_INSTALL_PACKAGES` 权限 | `AndroidManifest.xml:12` | ✅ 已有 |
| `nativeLibraryDir` 路径处理 | `NativeLibraryDiagnostics.java:65-68` | ✅ 已有 |
| APK 签名比对 | `Updater.java:611 signaturesMatch/fingerprints` | ✅ 已有，可复用 |
| 内核可用性判定扩展点 | `PlayerSetting.isPlayerAvailable()` / `isIjkAvailable()` | ✅ 已有（`isIjkAvailable()` 现硬编码 `true`） |
| 内核优先级/回退 | `KERNEL_ORDER` / `firstUntriedPlayer()` | ✅ 天然支持「未装即跳过」 |
| sha256 校验范例 | `subtitle/RealtimeSubtitleModelCatalog.java` | ✅ 已有 |
| 缓存模块注册 | `CacheModuleId.MPV_*` | ✅ 已有 |
| 备份/同步 | `Backup.java`（`mpv_config_`）、`SyncOptions.mpvConfig` | ⚠️ 需处理未装态 |

---

## 3. 关键决策：为什么新建 `runtime` 而不复用 `lab`

`com.fongmi.android.tv.lab` 实测已是成熟插件包管理器（`LabEnv` 1,419 行：多镜像下载、zip/7z/tar.gz/gz/tar.xz/deb 解压、chmod、symlink、离线包导入导出、路径穿越防护）。但**不能**作为播放核心载体：

**理由 1 — 语义错位。** `lab` 模型是「运行 Linux 环境/脚本」（`proot` rootfs、`commands`、环境市场）。播放核心是「扩展 App 自身能力」。`LabEnv.install` 的 `symlinkShared()` / `normalizeBinary()` 对 `.so` 无意义。

**理由 2 — 安全契约不满足（决定性）。** `LabEnv.download()` **无任何完整性校验**（`download.size` 仅用于 UI 展示）。`.so` 模块 = 任意代码执行，OWASP MASWE-0049 明确将「从可写/外部位置加载且未校验完整性」列为不安全动态代码加载。

**理由 3 — 安装位置不安全。** `LabConfig.fallbackRoot()` 默认落 `getExternalFilesDir(null)`（外部存储）。当前 MPV 库在**内部存储**，沿用 lab 路径等于降低安全基线。

**理由 4 — 装态判定不匹配。** `LabEnv.installed()` 判 `bin/`、`runner/`、`rootfs/` 非空，对纯 `.so` 模块恒 false。

**结论**：新建 `com.fongmi.android.tv.runtime`，**机制层借鉴**（`LabEnv` 的 `chmod -R` 递归、`safeTarget()` 路径防护、`arch()` 读 `BuildConfig.FLAVOR_abi` 等踩坑经验），但**契约层承接上游**。

> `lab` 与 `runtime` 并存，各自独立演进。合并留作后续可选优化。

---

## 4. 目标架构

```text
┌──────────────────────────────────────────────── ┐
│ 宿主 App (WebHTV)                                 │
│                                                   │
│  UI: 设置 → 播放模块管理 (新)   [实验室 = 独立保留]  │
│         ↓                                         │
│  runtime/  RuntimeCatalog   ← 拉 catalog.json     │
│            RuntimeStore     ← 已装模块注册表       │
│            RuntimeInstaller ← PackageInstaller    │
│            RuntimeLoader    ← 校验 + System.load  │
│         ↓                                         │
│  player/   MPVLib ── 模块 nativeLibraryDir ──┐    │
│                    ↘ 回退 assets（过渡期）    │    │
└───────────────────────────────────────────────┼───┘
                                                │
             ┌──────────────────────────────────┘
             ↓ PackageInstaller 安装
   ┌──────────────────────────────────────────────┐
   │ 独立已安装 APK: com.fongmi.runtime.mpv        │
   │   lib/arm64-v8a/libmpv.so                    │
   │   lib/arm64-v8a/libplayer.so                 │
   │   assets/runtime.json  ← 契约                │
   └──────────────────────────────────────────────┘
             ↑ 下载
   ┌──────────────────────────────────────────────┐
   │ 新仓库 webhtv-plugins                         │
   │   catalog.json                                │
   │   runtime-mpv-<ver>-<abi>.apk                 │
   └──────────────────────────────────────────────┘
```

**加载路径**：宿主 `PackageManager.getApplicationInfo("com.fongmi.runtime.mpv", 0).nativeLibraryDir` → `System.load(<dir>/libplayer.so)`。

---

## 5. 模块候选清单（实测排序）

### 5.1 WebHTV APK 构成实测（mobile-arm64_v8a.apk，8/24）

总计：解压前 157.9 MB / APK 内 136.4 MB

| 条目 | 解压前 | APK 内 | 压缩 |
| --- | --- | --- | --- |
| `assets/mpv-libs` | 37,246,264 | 37,246,264 | Stored |
| `lib/libsherpa-onnx-jni.so` | 23,603,240 | 23,603,240 | Stored |
| DEX(all) | 20,760,048 | 8,898,587 | Defl:N |
| `lib/libijkffmpeg.so` | 12,585,416 | 12,585,416 | Stored |
| `assets/chaquopy` | 12,246,812 | 12,114,710 | Stored |
| `assets/lut_presets` | 11,161,158 | 3,762,789 | Defl:N |
| `lib/libxl_thunder_sdk.so` | 5,607,344 | 5,607,344 | Stored |
| `lib/libjpa.so` | 5,288,528 | 5,288,528 | Stored |
| `lib/libavcodec.so`（nextlib） | 4,352,696 | 4,352,696 | Stored |
| `res` | 4,007,937 | 2,821,869 | Defl:N |
| `assets/libmitv.so` | 3,745,900 | 3,745,900 | Stored |
| `lib/libcrypto_python.so` | 3,721,048 | 3,721,048 | Stored |
| `lib/libtensorflowlite_jni.so` | 3,549,376 | 3,549,376 | Stored |
| `lib/libpython3.10.so` | 3,484,592 | 3,484,592 | Stored |
| `resources.arsc` | 2,578,976 | 2,578,976 | Stored |
| `lib/libquickjs-android-wrapper.so` | 1,327,232 | 1,327,232 | Stored |
| `lib/libavif_android.so` | 899,752 | 899,752 | Stored |
| `lib/libsqlite3_python.so` | 886,520 | 886,520 | Stored |
| `lib/libxl_stat.so` | 837,976 | 837,976 | Stored |
| `lib/libssl_python.so` | 623,736 | 623,736 | Stored |
| `lib/libijksdl.so` | 481,352 | 481,352 | Stored |
| `lib/libijkplayer.so` | 422,536 | 422,536 | Stored |
| `lib/libmedia3ext.so`（nextlib） | 265,736 | 265,736 | Stored |
| `assets/models`（basic_pitch） | 204,448 | 204,448 | Stored |
| `lib/libchaquopy_java.so` | 154,688 | 154,688 | Stored |
| `lib/libavutil.so` / `libswscale.so` / `libswresample.so` | 1,379,912 | 1,379,912 | Stored |
| `assets/pdf.*.js` | 1,407,258 | 1,407,258 | Defl:N |

### 5.2 分级（与上游模块对齐）

#### Tier 1 — 强烈推荐

| # | 模块 id | 体积 | 上游对应 | 可选性证据 | 解耦成本 |
| --- | --- | --- | --- | --- | --- |
| **P1** | `mpv` | **37.2 MB** | `runtime-mpv` 17.8 MB | 内核选择器，用户主动选 MPV | 低（6 文件引用 `MPVLib`） |
| **P2** | `sherpa`（实时字幕/ASR） | **23.6 MB** | 上游无 | `Setting.getRealtimeSubtitleModel()` 显式设置；模型已独立下载 | **最低**（`subtitle/` 自包含，且 SDK 支持 `sherpa_onnx.native.path` 覆盖，见 §7.2） |
| **P3** | `python`（Chaquopy） | **≈ 21.1 MB** | `runtime-python` 22.4 MB | 仅用 Python 爬虫的用户 | 中（`chaquo/` 已是独立 Gradle 模块） |
| **P4** | `ijk` | **13.5 MB** | 上游无 | `isIjkAvailable()` 已存在（现硬编码 `true`） | 低（与 P1 同模式） |
| **P5** | `thunder`（迅雷·简片·Force·TVBus） | **≈ 15.4 MB** | `runtime-thunder` 6.5 MB | 按协议 `Extractor.match(uri)` 启用 | 中（`Source.java` 提取器列表需按已装过滤） |

Tier 1 小计 ≈ 110.8 MB。

#### Tier 2 — 推荐

| # | 模块 id | 体积 | 上游对应 | 说明 |
| --- | --- | --- | --- | --- |
| **P6** | `tflite`（卡拉OK） | **≈ 3.75 MB** | 上游无 | `isKaraokeMode()` 门禁已存在；`player/karaoke/` 自包含（6,314 行） |
| **P7** | `lut`（LUT 预设包） | **3.76 MB** | 上游无 | `LutStore.ASSET_DIR` 单点；用户 LUT 目录功能已在 |
| **P8** | `quickjs` | **1.33 MB** | `runtime-quickjs` 1.35 MB | JS 爬虫运行时 |
| **P9** | `bdj`（蓝光菜单 JVM） | — | `runtime-bdj` 41.3 MB | **WebHTV 当前未内置**；上游已模块化。若将来引入应直接按模块做 |
| **P10** | `nodejs` | — | `runtime-nodejs` 48.8 MB | WebHTV 已是运行时下载（`NodeBundle`），无需改动 |

#### Tier 3 — 低优先

| # | 模块 id | 体积 | 说明 |
| --- | --- | --- | --- |
| **P11** | `pdf` | 1.41 MB | 仅 `WebReaderActivity` |
| **P12** | `avif` | 0.90 MB | `glide-avif-integration` |
| **P13** | `gif` | 0.04 MB | 收益过小，不做 |

#### 明确**不**模块化

| 项 | 体积 | 理由 |
| --- | --- | --- |
| **Exo 内核**（`libavcodec`/`libavutil`/`libswscale`/`libswresample`/`libmedia3ext`） | ≈ 6.0 MB | 默认内核，必须开箱可用 |
| **`libc++_shared.so`** | 1.37 MB | **必须留在宿主**。上游 `runtime.json` 的 `hostLibraries` 字段正为此设计（§6.3）；WebHTV 有真实事故记录（commit `2a56751af0`：soname 抢占 → `libmpv.so` 缺 `std::__ndk1::__from_chars_floating_point` → 「几乎所有片源报 MPV 加载媒体失败」） |
| **DEX** | 8.9 MB | §2.3；上游亦未做 |
| **QuickJS 之外的 JS/CSS/WebHome assets** | ≈ 1.7 MB | WebHome 核心能力 |

### 5.3 实施顺序（建议）

| 阶段 | 内容 | 累计 APK 减少 |
| --- | --- | --- |
| **S0** | `runtime` 框架 + **P2 sherpa 试点**（耦合最低、无 soname 约束、失败影响小） | ≈ 23.6 MB |
| **S1** | P1 mpv | ≈ 60.8 MB |
| **S2** | P4 ijk | ≈ 74.3 MB |
| **S3** | P3 python | ≈ 95.4 MB |
| **S4** | P5 thunder | ≈ 110.8 MB |
| **S5** | P6 tflite + P7 lut + P8 quickjs | ≈ 119.6 MB |

> **强烈建议 S0 用 P2 而非 P1 打框架**：单文件 23.6 MB、`subtitle/` 自包含、无 `libc++` 次序约束、SDK 原生支持路径覆盖、失败影响面小（实时字幕不可用 ≠ 无法播放）。框架稳定后再动 P1。

---

## 6. 模块契约（承接上游，不另创）

### 6.1 `catalog.json`（仓库根清单）

实测上游原文（`FongMi/Release` tag `module`）：

```json
{
  "schema": 1,
  "modules": [
    {
      "id": "mpv",
      "packageName": "com.fongmi.runtime.mpv",
      "compatibilityId": "mpv-jni-ffmpeg-shared-v1",
      "versionCode": 3,
      "versionName": "1.0.2",
      "artifacts": [
        {
          "abi": "arm64-v8a",
          "minSdk": 24,
          "size": 17793241,
          "sha256": "d45d2da660dd7827bef6a8c2752c2c1b78fb371eae554cd2328bbc80d7a3d0b3",
          "url": "https://github.com/FongMi/Release/releases/download/module/runtime-mpv-1.0.2-arm64-v8a.apk"
        }
      ]
    }
  ]
}
```

**WebHTV 采用相同结构**，`packageName` 前缀改为自有域名：
`com.silent.runtime.mpv` / `com.silent.runtime.sherpa` / …

字段语义（沿用上游，不增不改）：

| 字段 | 语义 |
| --- | --- |
| `schema` | 清单版本；不识别即拒绝 |
| `id` | 模块标识（`mpv`/`python`/…） |
| `packageName` | **已安装模块 APK 的包名**（宿主据此查 `nativeLibraryDir`） |
| `compatibilityId` | **宿主↔模块 ABI 契约版本**。宿主内置「认可的 compatibilityId 集合」，不匹配即拒绝安装 |
| `versionCode` | 用于更新判断 / 防降级 |
| `versionName` | 展示 |
| `artifacts[].abi` / `minSdk` / `size` / `sha256` / `url` | 按 ABI 分流 + 完整性校验 |

### 6.2 模块内 `assets/runtime.json`（实测上游原文）

```json
{
  "module": "mpv",
  "packageName": "com.fongmi.runtime.mpv",
  "versionCode": 3,
  "versionName": "1.0.2",
  "compatibilityId": "mpv-jni-ffmpeg-shared-v1",
  "abi": "arm64-v8a",
  "sha256": "aaaecbe2…",
  "size": 56584,
  "library": "lib/arm64-v8a/libplayer.so",
  "hostLibraries": {
    "libc++_shared.so": "b5d97c38a728afe28d341cda3369f512d35eaf9c680c1b0563cd6a63e395230f"
  },
  "files": [
    {"path": "lib/arm64-v8a/libmpv.so",    "size": 17708336, "sha256": "ede6123b…"},
    {"path": "lib/arm64-v8a/libplayer.so", "size": 56584,    "sha256": "aaaecbe2…"}
  ]
}
```

实测 `runtime-mpv-1.0.2-arm64-v8a.apk` 内部：

```text
lib/arm64-v8a/libmpv.so      17,708,336
lib/arm64-v8a/libplayer.so       56,584
assets/runtime.json                 652
AndroidManifest.xml
META-INF/CERT.RSA / CERT.SF      ← APK 已签名
```

**注意：模块内不含 `libc++_shared.so`**。这是设计核心（见 §6.3）。

### 6.3 `hostLibraries` — 直击 WebHTV 已有事故

上游 `hostLibraries` 语义：

> 模块声明「我需要宿主提供 `libc++_shared.so`，且必须是 digest `b5d97c38…` 那一份」。宿主加载前先校验自己那份的 digest，不符则报 `Incompatible MPV host library libc++_shared.so` / `Invalid MPV host library digest`；缺失则报 `MPV requires host library libc++_shared.so`。

这**正是** WebHTV commit `2a56751af0` 事故的通用化解法：不允许多份 `libc++_shared.so` 抢占同一 soname，改为「宿主唯一提供 + 模块声明所需 digest」。

→ **必须照此实现。这是本方案最重要的技术约束。**

### 6.4 为何用 APK 容器而非 7z/zip

| 维度 | 7z/zip 归档 | **APK 模块（上游选型）** |
| --- | --- | --- |
| 完整性 | 自实现 sha256 | `catalog.json` sha256 + **系统校验 APK 签名** |
| 版本管理 | 自建注册表 | `PackageManager` 原生（versionCode/更新/卸载） |
| 路径安全 | 需自实现防穿越 | 系统解压到 `/data/app/…/lib/`，无穿越风险 |
| 加载路径 | 自建目录 + `chmod` | 系统 `nativeLibraryDir`（可信路径） |
| 卸载 | 自实现清理 | 系统卸载，无残留 |
| 原生库压缩 | 手写解压 | `android:extractNativeLibs` 由系统处理 |
| 用户可见性 | 无 | 系统安装确认（一次） |
| 依赖 `REQUEST_INSTALL_PACKAGES` | 否 | **是 —— WebHTV 已有** |

---

## 7. 宿主端实现映射

### 7.1 新增文件（`com.fongmi.android.tv.runtime`）

| 文件 | 职责 |
| --- | --- |
| `RuntimeCatalog.java` | `catalog.json` 反序列化（`schema` / `modules[]` / `artifacts[]`） |
| `RuntimeModule.java` | 单模块模型 |
| `RuntimeArtifact.java` | abi / minSdk / size / sha256 / url |
| `RuntimeManifest.java` | 模块内 `assets/runtime.json`（含 `hostLibraries` / `files[]` / `library`） |
| `RuntimeStore.java` | 已装模块注册表（id/versionCode/compatibilityId/packageName/abi/installedAt） |
| `RuntimeRepository.java` | 拉取 catalog.json、按 ABI+minSdk 选 artifact、更新检查、自定义 URL |
| `RuntimeInstaller.java` | 下载 → **sha256 校验** → `PackageInstaller` 会话安装 → 结果回调 |
| `RuntimeLoader.java` | `nativeLibraryDir` 解析 → `runtime.json` 解析 → **`hostLibraries` digest 校验** → `files[]` 逐项校验 → `System.load` |
| `RuntimeCompatibility.java` | 宿主认可的 `compatibilityId` 白名单 |
| `ui/RuntimeModuleActivity.java` | 模块管理页（列表/安装/更新/卸载/进度/镜像/来源设置） |

### 7.2 修改文件（最小集）

| 文件 | 改动 | 阶段 |
| --- | --- | --- |
| `is/xyz/mpv/MPVLib.java` | `ensureLoaded()`：模块 `nativeLibraryDir` → assets 回退 → 明确报错；`getBundleId()` 纳入模块版本（§9 陷阱 1） | S1 |
| `setting/PlayerSetting.java` | `isPlayerAvailable()` 增 `player != MPV \|\| RuntimeStore.isInstalled("mpv")`；`isIjkAvailable()` 接 P4 | S1/S2 |
| `ui/dialog/PlayerKernelDialog.java` | 按 `isPlayerAvailable` 过滤 | S1 |
| `ui/dialog/PlaybackPerformanceDialog.java` | MPV 性能页按已装显示（3 处 `PlayerSetting.MPV` 分支） | S1 |
| `subtitle/RealtimeSubtitleRecognizer.java` | 加载前查模块；用 `sherpa_onnx.native.path` 指向模块目录 | S0 |
| `player/karaoke/*` | 加载前查 P6 模块 | S5 |
| `player/extractor/Source.java` | 提取器列表按已装模块过滤 + 缺模块引导 | S4 |
| `player/lut/LutStore.java` | `ASSET_DIR` 改为「模块优先 → assets 回退」 | S5 |
| `bean/Backup.java` | 未装模块时配置保留暂存（§10） | S1 |
| `app/build.gradle` | 各 flavor 可选打包 `assets/mpv-libs` 等（`-PmpvBundled=true` 回退开关） | 每阶段 |

### 7.3 P2（sherpa）试点可行性已实证

`sherpa-onnx-v1.13.4.jar` 内 `com/k2fsa/sherpa/onnx/LibraryUtils.class` 实测含：

```text
sherpa_onnx.native.path          ← 系统属性覆盖库目录
sherpa-onnx-jni
loadFromSherpaOnnxNativePath
isAndroid / loadFromResourceInJar
java/lang/System.loadLibrary / mapLibraryName
Loading from: %s
```

即 SDK **官方支持**通过 `System.setProperty("sherpa_onnx.native.path", dir)` 指定库路径 → `System.load(dir/lib<sherpa-onnx-jni>.so)` 后再初始化。因此 P2 试点**无需破解 SDK 加载逻辑**，只需在初始化前设置该属性。这是选定 P2 为试点的决定性依据。

### 7.4 硬约束（违反即全线播放失败）

1. **`LOAD_ORDER` 不变**（`c++_shared` 第一）。见 §6.3 与 commit `2a56751af0`。
2. **`libc++_shared.so` 只由宿主提供**，任何模块不得内置。由 `hostLibraries` 契约强制。
3. **宿主 `libc++_shared.so` 升级即触发全部模块重校验**。`compatibilityId` 与 `hostLibraries` digest 需随宿主构建锁定（`third_party/mpv-native-lock.json` 已是同类机制）。

---

## 8. 安全设计

| 项 | 要求 | 依据 |
| --- | --- | --- |
| 清单完整性 | `catalog.json` sha256 随包/签名分发；`schema` 不识别即拒 | 上游 `Unsupported runtime catalog` |
| 产物完整性 | 下载后**先校验 `artifacts[].sha256`**，再交 `PackageInstaller` | OWASP MASWE-0049 |
| 模块签名 | 校验模块 APK 签名与预期一致（复用 `Updater.signaturesMatch`） | 上游模块均带 `META-INF/CERT.RSA` |
| 加载前校验 | 解析 `runtime.json` → 校验 `hostLibraries` digest → 校验 `files[]` 逐项 sha256 | 上游 `Invalid MPV host library digest` |
| 兼容性 | `compatibilityId` 白名单；`versionCode` 防降级 | 上游 `Signed Node bundle cannot downgrade to unsigned` 同类思路 |
| 路径 | 只用系统 `nativeLibraryDir`，**禁止**外部存储 | 当前 mpv 已在内部存储，不得降级 |
| 失败降级 | 任一校验失败**必须拒绝加载并报错**，不得「跳过继续」 | 安全红线 |
| 传输 | 仅 HTTPS | |

> `targetSdk = 28` 时 Android 14 DCL 只读强制与 Android 17 `System.load` 可写检查均不生效，风险降低；但 APK 模块方案天然满足（系统管 `/data/app`），无需额外 `chmod 555`。

---

## 9. 兼容性与升级陷阱

### 陷阱 1（最隐蔽）：APK 升级强制重拷

`MPVLib.getBundleId()`（169-176 行）：`return info.lastUpdateTime + ":" + abi;`

APK 升级 → `lastUpdateTime` 变 → `refreshBundle = true` → 强制从 assets 重拷；assets 已无 mpv 则 `UnsatisfiedLinkError`，且 `loadError` 缓存导致**本次进程内永久失效**。

**修复**：`bundleId` 改为 `模块 versionCode:abi`（模块存在时）或 `lastUpdateTime:abi`（assets 回退时）。

### 陷阱 2：`libc++_shared.so` soname 抢占

见 §6.3 / §7.4。

### 陷阱 3：备份恢复丢配置

`Backup.java:283` 按 `mpv_config_` 前缀归档。未装模块时恢复会静默丢弃。

**修复**：写入暂存区，模块安装后自动应用。

### 陷阱 4：ABI 不匹配

复用 `LabEnv.arch()` 思路：读 `BuildConfig.FLAVOR_abi`（比 `Build.SUPPORTED_ABIS` 正确 —— 设备可能双 ABI 但 APK 只带一种）。

### 陷阱 5：`PackageInstaller` 交互成本

模块安装走系统确认，TV 遥控器操作不友好。

**缓解**：一次性引导（首次选 MPV / 首次开实时字幕时），并支持「全部安装」批量流程。

### 陷阱 6：P5 协议提取器静默失败

缺 `thunder` 模块时用户看到「解析失败」而非「缺模块」。

**修复**：`Source.getExtractor()` 为 null 且 URI 匹配未装模块 → 抛可识别错误 + 安装引导。

### 陷阱 7：模块包名与宿主混淆

`com.silent.runtime.*` 必须是**独立已安装应用**，不得与宿主同包名/同签名要求混淆。宿主通过包名查询，无需共享签名。

### 陷阱 8：宿主 `libc++_shared.so` 变更导致模块集体失效

**修复**：`hostLibraries` digest 不匹配时给出明确提示「需更新播放模块」，并在 UI 侧提供一键更新。

---

## 10. 备份 / 恢复集成

| 场景 | 期望行为 |
| --- | --- |
| 未装模块 + 备份 | `mpv_config_` 正常导出 |
| 未装模块 + 恢复 | 配置写入并标记 pending；模块安装后自动应用 |
| 已装模块 + 恢复 | 现有行为不变 |
| 跨设备一键同步 | 接收端未装模块时配置暂存不丢 |

---

## 11. 分阶段实施计划

| 阶段 | 内容 | 验收锚点 |
| --- | --- | --- |
| **S0** | `runtime/` 框架（Catalog/Store/Repository/Installer/Loader）+ **P2 sherpa 试点** + 新仓库骨架 | APK 减 23.6 MB；实时字幕可装可用 |
| **S1** | P1 mpv（含陷阱 1/3/8）+ 模块管理页 | APK 减 37.2 MB；MPV 全能力无回归 |
| **S2** | P4 ijk | 内核选择器同步 |
| **S3** | P3 python（`chaquo/` SPI 化） | Python 源可用 |
| **S4** | P5 thunder + 缺失引导 UI（陷阱 6） | 协议源引导正确 |
| **S5** | P6 tflite + P7 lut + P8 quickjs | |
| **S6**（可选） | P11 pdf / P12 avif；`lab` 与 `runtime` 机制层合并 | |

每阶段独立 `task_guard.sh` 会话 + 原子提交 + recovery tag。

---

## 12. 验收标准（可执行）

| # | 验收项 | 判定 |
| --- | --- | --- |
| A1 | APK 不含已模块化内容 | `unzip -l app-*.apk \| grep -E "mpv-libs\|sherpa"` → 空 |
| A2 | 体积达标 | 对比基线：S0 -23.6 MB / S1 累计 -60.8 MB |
| A3 | 未装模块时默认能力正常 | EXO 播放测试通过 |
| A4 | 未装模块时入口不出现 | `PlayerKernelDialog` 无 MPV |
| B1 | 安装成功 | 状态「已安装」，系统设置可见 `com.silent.runtime.mpv` |
| B2 | MPV 全能力无回归 | Vulkan / 硬解 / DV7 / FEL / 蓝光菜单逐项对比安装前后 `SpiderDebug player-engine` 输出 |
| C1 | **篡改检测** | 改产物 1 字节 → 安装失败，报 sha256，无残留 |
| C2 | 缺 sha256 拒绝 | 删 `catalog.json` 的 sha256 → 拒绝 |
| C3 | ABI/兼容性拒绝 | arm64 装 v7a 包 → 报错；伪造 `compatibilityId` → 拒绝 |
| C4 | **hostLibraries 校验** | 篡改宿主 `libc++_shared.so` → 报 `Invalid MPV host library digest`（验 §6.3） |
| C5 | 降级拒绝 | 装低 `versionCode` → 拒绝 |
| D1 | 卸载干净 | 模块消失、MPV 从内核选择器消失、回退 EXO 可播 |
| D2 | 磁盘释放 | `du` 差值 ≈ 模块体积 |
| E1 | **宿主升级不丢模块** | 覆盖安装宿主新 APK → 模块仍在、可播（陷阱 1） |
| E2 | 备份恢复不丢配置 | 未装态恢复 `mpv_config_` → 装模块后生效 |
| F1 | 单测通过 | `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest` |
| F2 | 现有 mpv 单测全绿 | 上述任务中 `Mpv*Test` |
| G1 | **P2 试点**：未装模块时实时字幕给引导不崩溃 | 模拟器实测 |
| G2 | **P2 试点**：装后可用（`sherpa_onnx.native.path` 生效） | 模拟器实测 |

> 按 addendum：模拟器一律**覆盖安装**（`adb install -r`）；签名不一致时先备份→卸载→安装→恢复。webhtv 不打正式包。
> 模拟器分配：`F:/Workspace/webhtv` → `192.168.50.3:5555`。

---

## 13. 回滚路径

| 层级 | 手段 |
| --- | --- |
| 构建期 | `-PmpvBundled=true` 等开关恢复内嵌 → 回到当前行为（不改代码即可回滚） |
| 运行期 | 各加载点双源查找：模块缺失回退 assets（若已打包） |
| 发布期 | `recovery/<task-id>/<timestamp>` 标签；模块仓库 tag 独立回滚 |
| 数据期 | 卸载模块不影响 `mpv_config_` |

**每个阶段必须保留构建开关**，使「APK 内嵌」始终可用。

---

## 14. 风险与未决问题

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| `libc++_shared.so` 契约实现错误 | **高** | §6.3 严格照做 + C4 验收 + commit `2a56751af0` 事故为鉴 |
| 模块化后 MPV 特有补丁（FEL/DV/Vulkan）构建链断裂 | **高** | `third_party/mpv-native-lock.json` 锁定；`scripts/verify_mpv_native_assets.sh` 纳入模块构建 |
| 宿主 `libc++_shared` 升级致模块集体失效 | 中高 | 陷阱 8：明确提示 + 一键更新 |
| `PackageInstaller` 在 TV 端交互差 | 中 | 批量安装 + 一次性引导 |
| 模块仓库单点 | 中 | 支持自定义 catalog URL |
| P5 缺模块静默失败 | 中 | 陷阱 6 |
| 首装下载量大（mpv 20–37 MB） | 中 | 多镜像 + 断点续传 |

**未决问题（需决策）**：

1. **新仓库归属与命名**：`Silent1566/webhtv-plugins`？还是独立组织？
2. **`catalog.json` 托管**：GitHub Releases（同上游）/ Raw / CDN？
3. **`packageName` 前缀**：`com.silent.runtime.*` 是否可接受？
4. **是否照抄上游 `compatibilityId` 命名风格**（如 `mpv-jni-ffmpeg-shared-v1`）？
5. **首阶段试点**：确认用 **P2 sherpa**（本文建议）还是 P1 mpv？
6. **P5 thunder 是否值得做**（15.4 MB 但需引导 UI，失败影响面大）？
7. **是否需要同步引入 P9 bdj 模块**（WebHTV 当前无，上游有；属新功能而非瘦身）？

---

## 15. 上游参考索引（实证来源）

| 项 | 来源 | 访问日期 |
| --- | --- | --- |
| 上游仓库结构 / 分支 `fongmi` | `https://github.com/FongMi/TV` | 2026-10-17 |
| 上游发布体积演进（v5.7.1 → 5.7.4） | `https://api.github.com/repos/FongMi/Release/releases` | 2026-10-17 |
| `catalog.json` 原文 | `https://github.com/FongMi/Release/releases/download/module/catalog.json` | 2026-10-17 |
| `runtime-mpv-1.0.2-arm64-v8a.apk` 结构 | 实测解包（11 文件，含 `assets/runtime.json`） | 2026-10-17 |
| `hostLibraries` 契约 | 上述 `runtime.json` + 宿主 dex 字符串 `Invalid MPV host library digest` 等 | 2026-10-17 |
| `PackageInstaller` 机制 | 宿主 dex 字符串 `Landroid/content/pm/PackageInstaller$SessionParams;`、`REQUEST_INSTALL_PACKAGES`、`setAppPackageName` | 2026-10-17 |
| safetensors / 动态加载安全要求 | OWASP MASWE-0049；Android Dynamic Code Loading | 2026-10-17 |

---

## 16. 批准与实施记录

### 16.1 已批准决策（2026-10-17）

用户确认：

1. 新建独立模块体系 `com.fongmi.android.tv.runtime`，**不复用** `lab`。
2. 新建独立仓库 **`Silent1566/webhtv-plugins`**。
3. 模块包名前缀 **`com.silent.runtime.*`**。
4. 首个试点用 **P2 sherpa**（非 P1 mpv）。
5. **P5（thunder）暂缓**。

### 16.2 S0-a 已交付（2026-10-17）

**范围**：模块契约层（纯校验，不含下载/安装/加载）。

新增（`app/src/main/java/com/fongmi/android/tv/runtime/`）：

| 文件 | 职责 |
| --- | --- |
| `RuntimeIntegrity.java` | SHA-256（字节/文件）、`sha256:` 前缀归一化、常量时间比较 |
| `RuntimeText.java` | 纯 Java 字符串工具 |
| `RuntimeAbi.java` | ABI 归一化与 flavor 优先解析 |
| `RuntimeCatalog.java` | `catalog.json` 模型 + `isSupported()` + 按 ABI/minSdk 选产物 |
| `RuntimeManifest.java` | 模块内 `runtime.json` 模型（含 `hostLibraries`） |
| `RuntimeCompatibility.java` | 宿主认可的 `compatibilityId` 白名单 |
| `RuntimeVerifier.java` | 失败关闭式校验编排 |

**关键设计决定**：契约层**完全不依赖 Android API**（包括不用 `android.text.TextUtils`）。原因：本包单测在纯 JVM 运行且未启用 Robolectric；若改用 `unitTests.returnDefaultValues = true`，`TextUtils.isEmpty` 会恒返回 `false`，静默产生错误行为。为此引入 `RuntimeText` 替代。

**验证**：`bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.runtime.*"` → **38 tests, 0 failures, 0 skipped**（`RuntimeCatalogTest` 13 / `RuntimeManifestTest` 10 / `RuntimeVerifierTest` 15）。

测试数据取自实测抓取的上游真实契约（`FongMi/Release` 的 `catalog.json` 与 `runtime-mpv-1.0.2` 的 `runtime.json`），字段逐一对应。

**变异验证**（证明测试非恒真）：临时禁用 `RuntimeVerifier` 中 `hostLibraries` 摘要校验 → `rejectsIncompatibleHostLibraryDigest` 立即 FAILED；恢复后全绿。

**已覆盖的失败关闭场景**：篡改文件、缺失文件、尺寸不符、宿主库摘要不符、宿主库缺失、宿主库提供者不可用、**模块自带宿主库**（soname 冲突防线）、版本不符、契约不符、未知契约、ABI 不符、模块目录缺失、null 入参、多问题一次性汇总。

### 16.3 S0-b 已交付（2026-10-17）

**范围**：模块仓库、安装编排、加载门禁、Android 适配层。

新增（`app/src/main/java/com/fongmi/android/tv/runtime/`）：

| 文件 | 职责 |
| --- | --- |
| `RuntimeStore.java` | 已装模块注册表 + 安装/更新/重装/降级决策 |
| `RuntimePlatform.java` | 平台能力的窄接口（下载/安装/包信息/库加载） |
| `RuntimeInstaller.java` | 下载 → **校验** → 安装 → 记录 的编排 |
| `RuntimeLoader.java` | 校验 + 加载入口库（`System.load` 前的最后一道门） |
| `RuntimeRepository.java` | 拉取/解析 `catalog.json`（HTTPS 强制、大小上限） |
| `RuntimeAndroid.java` | 平台实现（`PackageInstaller` 会话、`nativeLibraryDir`、`Prefers` 存储） |

**关键设计**：所有平台访问收敛为 `RuntimePlatform` 的四个窄接口，因此「下载→校验→安装→记录」与「校验→加载」两条安全关键路径均在纯 JVM 可测，无需 Robolectric 或真机。

**为什么用 `PackageInstaller` 会话而非 `ACTION_VIEW`**：`ACTION_VIEW` 不会回传结果，宿主无法知道用户是否确认，可能把**从未安装**的模块记入注册表。会话 + 动态注册的接收器（无需 manifest 条目或导出组件）才能在首终态触发一次回调并据此写入注册表。

**验证**：`bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests "com.fongmi.android.tv.runtime.*"` → **94 tests, 0 failures, 0 skipped**（新增 `RuntimeStoreTest` 13 / `RuntimeInstallerTest` 13 / `RuntimeLoaderTest` 15 / `RuntimeRepositoryTest` 15）。

**变异验证**（证明安装前校验非恒真）：临时禁用 `RuntimeInstaller` 的产物摘要校验 → `verifiesDigestBeforeHandingTheApkToTheInstaller` 立即 FAILED；恢复后全绿。

**已覆盖的失败关闭场景（新增）**：

- 安装：摘要不符（且**安装器从未被调用**）、尺寸不符、下载失败、包名与清单不符、原生库目录缺失、未知契约（**下载前即拒绝**）、ABI 无产物、下载文件在成功/失败后均被清理、重复回调不覆盖已定结果
- 加载：未安装、无清单、篡改库（等长载荷精确命中摘要路径）、尺寸不符、宿主库摘要错、入口库不在磁盘、加载抛错被转为失败结果、未知契约、库目录消失、`verify` 不触发加载
- 注册表：持久化往返、损坏数据不复活模块、降级被拒、契约变更触发重装、无效记录触发重装
- 仓库：拒绝明文 HTTP、拒绝未知 schema、拒绝超大清单、拒绝空/畸形载荷、本地文件源

### 16.4 S0-c 已交付（2026-10-17）

**范围**：P2 sherpa 模块门禁 + 控制器接入。

新增（`app/src/main/java/com/fongmi/android/tv/runtime/`）：

| 文件 | 职责 |
| --- | --- |
| `RuntimeSherpa.java` | 决定实时字幕引擎的原生库来源并指向模块目录 |

修改：

| 文件 | 改动 |
| --- | --- |
| `subtitle/RealtimeSubtitleController.java` | `prepareModel` 中在**模型下载之前**插入 `ensureNativeEngineAvailable()`，并输出一行 `realtime-subtitle` 诊断日志 |

**SDK 机制（实测字节码确认，非推测）**：

`com.k2fsa.sherpa.onnx.LibraryUtils.load()` 的调用顺序是

```text
loadFromSherpaOnnxNativePath()
  name = mapLibraryName("sherpa-onnx-jni")          → libsherpa-onnx-jni.so
  path = System.getProperty("sherpa_onnx.native.path")
  if (dir.isDirectory() && new File(dir, name).exists())
      System.load(absolutePath); return true        ← 命中
  return false                                      ← 回退 System.loadLibrary
```

因此只要安装模块后把该属性指向模块目录即可重定向 SDK，**无需自行 `System.load`、无需修改 SDK**。

**两个来自实测的硬约束**：

1. **门禁必须在任何 `com.k2fsa.sherpa.onnx` 类被触碰之前执行**。`LibraryLoader` 的静态初始化块设置 `autoLoadEnabled = true` 并缓存加载结果，晚设置属性完全无效。`RuntimeSherpa` 刻意不引用 SDK，以免门禁自身触发被守护的加载。
2. **`libsherpa-onnx-jni.so` 不依赖 `libc++_shared.so`**（`readelf -d` 实测：只依赖 `libandroid/liblog/libm/libdl/libc`）。所以 sherpa 模块的 `hostLibraries` 为空，不涉及 soname 冲突——这正是把它选为首个试点的依据。

**降级设计**：模块未安装或校验失败时回退到 APK 内置库（过渡期仍打包 `libsherpa-onnx-jni.so`）；只有两者都不可用才报 `UNAVAILABLE` 并阻止启动。这样模块化不会把功能做成「装了才有」。

**验证**：`--tests "com.fongmi.android.tv.runtime.*" --tests "com.fongmi.android.tv.subtitle.*"` → **178 tests, 0 failures, 0 skipped**（runtime 108 / subtitle 70）。`RuntimeSherpaTest` 14 项覆盖：指向已验证模块、模块优先于内置库、无模块回退、全不可用、校验失败降级、失败时报告原因、ABI 不符、loader 缺失、probe 缺失、configurer 缺失、属性名断言、库被删、未知契约。

**真机验证状态（如实记录）**：

| 项 | 状态 |
| --- | --- |
| Debug APK 构建 | ✅ 成功 |
| 覆盖安装（签名一致，无需备份恢复流程） | ✅ 成功 |
| 安装后无崩溃、进程存活 | ✅ 通过 |
| `libsherpa-onnx-jni.so` 位于 `nativeLibraryDir` | ✅ 确认 |
| **模块路径（`PackageInstaller` 安装、`native.path` 重定向）** | ⚠️ **无法验证** |
| 内置库回退的实际运行 | ⚠️ 未触发 |

**模块路径无法验证的原因**：目前尚不存在任何模块 APK（S0-d 未做），没有可安装对象；且 `VideoActivity` 未导出、本地 HTTP 服务未启用，无法用脚本触发实时字幕 UI。**这两项验证必须在 S0-d 产出 `runtime-sherpa` 模块后进行。**

### 16.5 S0-d 已交付（2026-10-17）

**范围**：独立模块仓库 `webhtv-plugins` + 打包脚本 + CI；并补齐 S0-c 遗留的真机验证。

**新仓库**：`F:/Workspace/webhtv-plugins`（已 `git init`，提交 `9c41fb7`；**尚未推送**）

| 文件 | 职责 |
| --- | --- |
| `scripts/build_module.py` | 打包模块 APK（aapt2 link → 追加 Stored `.so` → zipalign → apksigner） |
| `scripts/gen_catalog.py` | 由 `dist/` 重新生成 `catalog.json`，计算 sha256/size |
| `catalog.json` | sherpa 1.0.0，覆盖 arm64-v8a 与 armeabi-v7a |
| `.github/workflows/build-modules.yml` | 构建 + 摘要自校验 + 发布到 Release |
| `README.md` | 模块契约（`catalog.json` / `runtime.json` / `hostLibraries`）与约束 |
| `.gitignore` | 忽略 `dist/`（产物不入库，随 Release 分发） |

**产物**：

| ABI | 大小 | 说明 |
| --- | --- | --- |
| arm64-v8a | 23,630,316 B | 含 23,603,240 B 的 `libsherpa-onnx-jni.so`（Stored） |
| armeabi-v7a | 16,134,638 B | 含 16,110,348 B 的 `libsherpa-onnx-jni.so`（Stored） |

**打包过程中发现并修复的两个真实缺陷**（均为真机/构建实测暴露，非推测）：

1. **`INSTALL_FAILED_INVALID_APK: code is missing`** —— 模块不含 dex，manifest 必须声明
   `android:hasCode="false"`，否则平台拒绝安装。
2. **`nativeLibraryDir` 路径拼接错误（宿主侧）** —— manifest 声明
   `lib/arm64-v8a/libsherpa-onnx-jni.so`（相对 APK 根），而平台给的
   `nativeLibraryDir` 已是扁平目录 `.../lib/arm64/`（**目录名甚至不是 ABI 的拼写**）。
   直接拼接会去找 `.../lib/arm64/lib/arm64-v8a/x.so` 而必然失败。
   修复：新增 `RuntimeManifest.nativeLibraryEntry(path, abi)` 剥离 `lib/<abi>/` 前缀，
   由 `RuntimeVerifier.verifyFiles` 与 `RuntimeLoader` 共用。

**验证**：

| 项 | 结果 |
| --- | --- |
| 宿主单测 | `--tests "com.fongmi.android.tv.runtime.*"` → **112 tests, 0 failures**（新增 4 项路径前缀回归测试） |
| 宿主既有字幕测试 | `--tests "com.fongmi.android.tv.subtitle.*"` → 70 tests, 0 failures，无回归 |
| **真机仪器化测试** | `RuntimeModuleDeviceTest` → **OK (10 tests)**（设备 `192.168.50.3:5555`，定向 `am instrument`） |
| catalog 一致性独立复核 | 两个 ABI 的 size/sha256 与产物一致；模块内 `files[0].sha256` 与真实 `.so` 一致；`Stored=True` |

真机 10 项覆盖：`nativeLibraryDir` 可解析、库实际存在、`runtime.json` 可从已装 APK 读取、
**对扁平目录校验成功**（即上述缺陷 2 的回归防线）、完整 loader 路径可加载、
`sherpa` 门禁解析为 `Source.MODULE` 且路径为真实目录、属性名对 SDK 可见、
版本不符被拒、ABI 不符被拒、缺失模块不抛异常。

> 说明：`connectedAndroidTest` 会把测试跑到**所有**已连接设备并在目标设备上卸载宿主应用，
> 因此真机验证改用 `adb shell am instrument -w -e class <FQCN>` 定向执行。此坑已记录。

### 16.6 S0-e 已交付（2026-10-18）

**范围**：端到端验证「SDK 是否真的从模块目录加载」——这是本方案最后一个高风险未验证项。

新增 `app/src/androidTest/java/com/fongmi/android/tv/runtime/RuntimeSherpaLoadDeviceTest.java`。

**为什么必须做成独立验证**：`sherpa_onnx.native.path` 能否真正重定向 SDK，是纯路径断言无法证明的。
SDK 在静态初始化时加载并**按进程缓存**结果，因此本测试类必须在**单独一次 instrumentation 运行**中执行，
否则同级测试先触碰 sherpa 类会让结论依赖执行顺序。

**取证方式**：读 `/proc/self/maps`，检查 `libsherpa-onnx-jni.so` 的映射路径落在模块包目录还是宿主目录。

**关键前提**：测试 APK **不自带**任何 `.so`（已用 `unzip -l` 确认）。因此加载成功即可归因——
不可能是宿主内置副本被加载。

**真机结果**（`192.168.50.3:5555`）：**OK (2 tests)**

| 断言 | 结果 |
| --- | --- |
| 门禁判定为 `Source.MODULE`，且配置路径 = 模块目录 | ✅ |
| 设置属性后 SDK 未提前加载（映射为空） | ✅ |
| `LibraryUtils.load()` 后 `libsherpa-onnx-jni.so` 已被映射 | ✅ |
| 映射路径**包含模块包目录** `com.silent.runtime.sherpa` | ✅ |
| 映射路径**不含宿主目录** `com.silent.android.webhtv` | ✅ |
| 无模块记录时回退 `BUNDLED` 且**不设置**属性 | ✅ |

**测试自身的一个缺陷（已修正）**：首轮 `sdkLoadsTheNativeLibraryFromTheInstalledModule` 失败，
原因是测试的 configurer 只 `append` 记录路径、**没有设置系统属性**，而生产代码是
`path -> System.setProperty(...)`。该测试的整个价值就在于镜像生产接线，因此改为与生产完全一致。
这类「测试没接对线」的失败若不是在真机上跑，很容易被误判为功能缺陷。

**S0-c 遗留验证的最终状态**：

| 项 | 状态 |
| --- | --- |
| 模块安装到平台并落到 `nativeLibraryDir` | ✅ 已验证 |
| 门禁解析为 `Source.MODULE` | ✅ 已验证 |
| **SDK 实际从模块目录 dlopen** | ✅ **已验证**（本节） |
| 模块卸载后回退内置库 | ⬜ 未验证（卸载模块后 `RuntimeSherpa` 应回退 `BUNDLED`，逻辑已由单测覆盖，但未做真机卸载回归） |
| `PackageInstaller` 会话路径的 UI 联调 | ⬜ 未验证（用 `adb install` 验证了模块可安装落位，宿主内会话+确认流程未跑通） |

### 16.7 S0-f 已交付（2026-10-18）

**范围**：模块管理页（挂载到 设置 → 增强功能）+ 卸载入口。

新增：

| 文件 | 职责 |
| --- | --- |
| `runtime/RuntimeModulePolicy.java` | 列表展示规则（可装/已装/可升级/ABI 不支持/契约不支持）+ 体积格式化 |
| `ui/adapter/RuntimeModuleAdapter.java` | 模块列表适配器（每行：名称/说明/版本·体积/状态/安装/卸载） |
| `ui/dialog/RuntimeModulesDialog.java` | 模块管理对话框（拉清单、安装、卸载、失败提示） |
| `res/values/runtime_strings.xml` | 文案（含模块展示名的本地映射） |
| `leanback/res/layout/{dialog_runtime_modules,adapter_runtime_module}.xml` | 电视端布局 |
| `mobile/res/layout/{dialog_runtime_modules,adapter_runtime_module}.xml` | 手机端布局 |

修改：

| 文件 | 改动 |
| --- | --- |
| `AndroidManifest.xml` | 新增 `REQUEST_DELETE_PACKAGES`（卸载模块走系统确认界面） |
| `leanback/.../SettingEnhanceActivity.java` + 布局 | 新增「播放模块」入口，并纳入 `reorderItems` 顺序 |
| `mobile/.../SettingEnhanceFragment.java` + 布局 | 同上 |

**设计决定**：

1. **模块展示名用本地字符串映射，不用远程文本**。`catalog.json` 不含面向用户的文案，且宿主只认可白名单内的 `compatibilityId`；渲染远程文本既无必要，也扩大了不可信输入面。
2. **卸载先忘记记录、再调系统卸载界面**。若用户在系统确认页取消，宿主已停止提供该能力——这是保守方向（宁可少一个能力，也不让注册表指向一个可能被卸载的包）。
3. **已装但清单无匹配产物时仍显示该行**（`INSTALLED_NO_ARTIFACT`），否则用户无法卸载，会被卡住。

**真机验证**（`192.168.50.3:5555`，覆盖安装）：

| 项 | 结果 |
| --- | --- |
| Debug APK 构建 + 覆盖安装 | ✅ |
| 应用启动无崩溃 | ✅ |
| 设置 → 增强功能 出现「播放模块」入口（紧跟「实验室」） | ✅ |
| 点击后对话框正常打开（标题/提示/空态/刷新按钮） | ✅ |
| 刷新清单发起真实网络请求 | ✅ |
| 拉取失败时显示原因 | ✅ 「无法获取模块清单：获取模块清单失败 HTTP 404」 |

> 404 符合预期：模块仓库 `webhtv-plugins` **尚未推送**，因此清单地址当前不可达。这条恰好验证了失败路径的可见性。

**本轮发现并修复的 4 个真实缺陷**（均由编译/单测/真机暴露）：

1. **体积格式化单位偏移一位** —— `displaySize(1024)` 返回「1.0 MB」而非「1.0 KB」。循环先除后进位，索引起点写错。影响所有小于 1 MB 的模块显示。
2. **拉取失败提示被覆盖** —— `render()` 在错误上报后执行，把错误文案刷回通用提示，用户看不到失败原因。改为用 `catalogFailure` 状态保留。
3. **适配器点击回调失效** —— 初版在构造函数里绑监听并读 `view.getTag()`（从未设置，恒为 null），且 `onRemove(null)` 传了 null。改为每次 `onBindViewHolder` 绑定，并通过 `getBindingAdapterPosition()` 取当前项，避免回收行作用于错误条目。
4. **`App.execute` 不存在** —— 应为 `Task.execute`；另有缺失的字符串资源与缺失的 `Color` import。

### 16.8 S0-g 已交付（2026-10-18）

**范围**：推送模块仓库 + 对**已发布产物**做端到端验证。

**仓库已推送**：

| 项 | 值 |
| --- | --- |
| 仓库 | `https://github.com/Silent1566/webhtv-plugins` |
| 分支 | `main`（本地原为 `master`，已改名以匹配 catalog URL 与 CI） |
| 提交 | `9c41fb7bc3b7c5e9c4f422a066fecce2fcaa49e4` |
| Release | tag `modules`，含 arm64-v8a 与 armeabi-v7a 两个产物 |
| catalog URL | `https://raw.githubusercontent.com/Silent1566/webhtv-plugins/main/catalog.json`（HTTP 200） |

**新增** `app/src/androidTest/java/com/fongmi/android/tv/runtime/RuntimeRepositoryDeviceTest.java`（4 项）。

**为什么用仪器化测试而不是 UI 点击**：模块管理页的坐标导航在模拟器上不可靠（我实测多次误入 TMDB 详情页）。端到端测试直接调用生产路径，覆盖更广且可重复：

| # | 断言 | 说明 |
| --- | --- | --- |
| 1 | 设备能拉取已发布 catalog | schema、模块存在、契约被宿主认可 |
| 2 | catalog 提供本机 ABI 的产物 | 含 `minSdk` 过滤，HTTPS 校验 |
| 3 | **下载产物摘要与 catalog 一致** | 走真实 `RuntimeAndroid.downloader()`；镜像篡改或上传截断会在此暴露 |
| 4 | **产物声明与 catalog 一致，且库为 Stored** | 直接读下载 APK 的 `assets/runtime.json`，比对包名/契约/版本/ABI，并验证入口库的 `STORED`、大小、摘要 |

**真机结果**（`192.168.50.3:5555`）：**OK (4 tests)**

**本轮发现并修复的真实生产缺陷（Java 9 API 在设备上不存在）**：

`RuntimeLoader.fileTreeSource()` 使用了 `InputStream.readAllBytes()`。这是 **Java 9 API，Android 设备运行时不存在**，且 `coreLibraryDesugaring`（`desugar_jdk_libs_nio` 2.1.5）**不覆盖 `java.io`**。

- **为什么单测发现不了**：单测跑在 JDK 21 上，方法存在，一路绿灯。
- **为什么生产未崩**：生产路径 `RuntimeAndroid.manifestSource()` 恰好用了手写循环，因此侥幸避开了；`fileTreeSource()` 这条路径会崩。
- **修复**：改为显式循环读取，并在注释中写明这不是风格偏好。
- 注意区分：`java.nio.file.Files.readAllBytes()`（Java 7，脱糖覆盖）是安全的；只有 `java.io.InputStream.readAllBytes()` 有问题。`RuntimeRepository:77` 用的是前者，无风险。

**验证**：宿主单测 192 项（runtime 122 / subtitle 70）+ 真机仪器化 16 项（模块 10 + 加载 2 + 仓库 4）全绿。

### 16.9 S1 已交付（2026-10-18）：mpv 播放核心模块化

**范围**：把 mpv 的 9 个原生库移入模块，`libc++_shared.so` 留给宿主——这是本方案最关键、
此前只在单测中验证过的约束（`hostLibraries`）首次在真机上被证明。

**依赖图（`readelf -d` 实测，决定加载次序）**：

```text
libmvutil.so     -> (仅系统库)
libmvcodec.so    -> libmwresample, libmvutil, libc++_shared
libmpv.so        -> libmvcodec, libmvfilter, libmvformat, libmvutil,
                    libc++_shared, libmwresample, libmwscale, libmvdevice
libplayer.so     -> libmwscale, libmvcodec, libmpv, libc++_shared
```

`libc++_shared.so` 被 3 个库共同 `DT_NEEDED`，因此：
**模块不得自带，宿主提供唯一一份，模块声明期望摘要。**

**模块产物**：

| ABI | 大小 | 库数 | 模块声明的宿主库摘要 |
| --- | --- | --- | --- |
| arm64-v8a | 45,376,556 B (43.3 MB) | 9 | `c4c2fe5c…` |
| armeabi-v7a | 39,539,774 B (37.7 MB) | 9 | `af383654…` |

**本轮发现并修复的真实缺陷**：

**`hostLibraries` 摘要必须按 ABI 区分。** 宿主 arm64 与 v7a 的 `libc++_shared.so` 是**不同文件**
（`c4c2fe5c…` / `af383654…`）。打包脚本最初硬编码了 arm64 的值，会让 v7a 模块在加载时被摘要门禁拒绝——
而且是静默的：模块能装、能通过安装校验，只在真正加载时才失败。
修复：`hostLibraries` 支持 `@derive:<path>{abi}`，从 WebHTV 检出按 ABI 推导，杜绝漂移。
（真机确认宿主 `nativeLibraryDir/lib/arm64/libc++_shared.so` 的摘要正是 `c4c2fe5c…`。）

**新增/修改**：

| 文件 | 改动 |
| --- | --- |
| `runtime/RuntimeMpv.java`（新） | 先按 soname 加载宿主库，再按 manifest 顺序加载模块库 |
| `runtime/RuntimeManifest.java` | 新增 `loadOrder`；`isValid` 要求每个待加载库都在 `files` 中（可被摘要校验） |
| `runtime/RuntimeLoader.java` | 按 `loadOrder` 依次加载，而非只加载单入口 |
| `is/xyz/mpv/MPVLib.java` | `ensureLoaded` 优先模块 → 回退 assets；抽出 `ensureLoadedFromAssets` |
| `webhtv-plugins/scripts/build_module.py` | 支持多库模块、`loadOrder`、`@derive` 宿主摘要 |

**加载次序即契约**：改由 manifest 的 `loadOrder` 显式声明，不再依赖 `DT_NEEDED` 隐式解析——
显式次序可被校验，隐式不行。

**验证**：

| 项 | 结果 |
| --- | --- |
| 宿主单测 | **205 tests, 0 failures**（runtime 135 含新增 `RuntimeMpvTest` 13 / subtitle 70 无回归） |
| **真机仪器化（mpv）** | **OK (9 tests)** @ `192.168.50.3:5555` |
| 真机仪器化（其余） | 16 tests（模块 10 + 加载 2 + 仓库 4） |

真机 9 项覆盖：模块暴露 9 库且**不自带** `libc++_shared.so`、宿主提供的库摘要与模块声明一致、
对真实 `nativeLibraryDir` 校验通过、**宿主库最先加载且模块按序跟随（10 次加载）**、
入口库落在真实文件、契约未知被拒、未安装不抛异常、`loadOrder` 首尾正确且不含宿主库、
入口库 ELF 头可读。

**过渡期保留回退**：宿主仍打包 `assets/mpv-libs`（10 个条目），模块缺失或校验失败时自动回退，
因此本阶段不会让任何现有用户失去 mpv。移除 assets 是后续独立步骤。

### 16.10 S1-b 已交付（2026-10-18）：mpv 真实加载验证

**范围**：证明模块化的 mpv **真的能 dlopen**，而不只是"路径与摘要正确"。

**为什么必须单独做这一步**：S1 用注入的加载器记录调用，验证了次序与摘要门禁，但**从未真正加载**。
次序对、摘要对，仍可能因 soname 冲突或符号缺失而失败——这正是 commit `2a56751af0` 的失败模式：
所有校验通过，加载仍失败，表现为「几乎所有片源报 MPV 加载媒体失败」。只有真实加载能排除它。

**新增** `app/src/androidTest/java/com/fongmi/android/tv/runtime/RuntimeMpvLoadDeviceTest.java`（3 项）。

**取证方式**：`/proc/self/maps`。加载后用 `System::load` 真实 dlopen，再逐库检查映射来源。

**真机结果**（`192.168.50.3:5555`）：**OK (3 tests)**，用时 2.28s

| 断言 | 结果 |
| --- | --- |
| 整条链真实加载成功（9 个库全部 `System.load` 通过） | ✅ |
| 9 个模块库**全部**映射自模块包目录，且**无一**来自宿主 | ✅ |
| **`libc++_shared.so` 只有一份，且来自宿主** | ✅ |
| 模块**未**贡献第二份 `libc++_shared.so` | ✅ |
| 入口库符号可解析（`DT_NEEDED` 图被完整解析） | ✅ |

这直接覆盖了 soname 冲突这一历史故障模式，为移除 `assets/mpv-libs`（S2）提供了依据。

**测试自身的两个缺陷（均已修）**：

1. **`String.lines()` 是 Java 11 API**，设备（API 28）上不存在，`NoSuchMethodError`。改为 `split`。
   已核实生产代码无此问题：`HlsManifestCleaner`/`DebugLogs` 中的 `.lines()` 是自定义类型的成员方法。
2. **不能假设进程干净**：原生库一旦加载就无法卸载，同类中先跑的测试已经映射过它们。
   原断言「加载前映射为空」在同一进程内必然失败。改为只断言**映射来源**——这个结论与谁先触发加载无关。

### 16.11 S2-a 已交付（2026-10-18）：内核可用性接入模块

**范围**：让「MPV 是否可用」由**模块是否已装**决定，为移除 `assets/mpv-libs` 铺路。

**为什么必须单独做**：移除 assets 后，内核选择器若仍无条件列出 MPV，用户选中就会在加载时失败。
必须在移除**之前**先把门禁接好，否则 S2-b 会变成一次功能性回归。

**改动的关键性质：19 个调用点零改动。** `PlayerSetting.isPlayerAvailable()` 早已存在且被回退链、
内核选择器、手动切换共同使用（`PlayerManager` ×6、`PlaybackService` ×13）。只要把 MPV 的判定接进去，
所有既有调用点自动生效——这正是第一轮设计里把它标为「现成扩展点」的价值。

| 文件 | 改动 |
| --- | --- |
| `runtime/RuntimeMpv.java` | 新增纯函数 `isAvailable(moduleInstalled, bundled)` |
| `is/xyz/mpv/MPVLib.java` | 新增 `isBundled(Context)`，探测 APK 是否仍带 mpv assets |
| `setting/PlayerSetting.java` | `isPlayerAvailable` 接 MPV；`isMpvAvailable()` 带缓存；`resetMpvAvailability()` |
| `ui/dialog/RuntimeModulesDialog.java` | 安装/卸载后重置缓存 |

**过渡期语义**：`模块已装 || APK 仍内置`。两者皆有也可用。移除 assets 后自动收敛为
「只有装了模块的用户才有 MPV」——**同一份代码，无需再改**。

**缓存与失效**：判定结果在进程内不会变（模块安装需重启进程才会被加载），故缓存。
但用户在模块管理页刚装完就去看内核选择器时缓存会是旧的，因此安装/卸载成功后显式重置。
这是真实可复现的路径，不是理论问题。

**验证**：209 tests 全绿（runtime 139 含新增 `RuntimeMpvTest` 4 项可用性规则 / subtitle 70）；
mobile 与 leanback 两 flavor 均编译通过。

新增的 4 项可用性规则测试：模块已装→可用、仅内置→可用、**两者皆无→不可用**（移除后的预期态）、
两者皆有→可用。

> **仍未验证**：真机上「装模块 → 内核选择器出现 MPV」与「卸载 → 消失」的端到端 UI 行为，
> 以及真实播放回归。这些留待 S0-h/S2-b。

### 16.12 S2-b-前置 已交付（2026-10-18）：mpv 模块功能验证

**范围**：证明模块化的 mpv **功能可用**，而不只是能加载——移除 `assets/mpv-libs` 的最后一道保险。

**为什么 dlopen 还不够**：`RuntimeMpvLoadDeviceTest` 已证明 9 个库能加载并映射自模块，但
「库能加载」不等于「功能可用」。库里仍可能存在首次调用才暴露的问题。

**新增** `app/src/androidTest/java/com/fongmi/android/tv/runtime/RuntimeMpvFunctionalDeviceTest.java`（3 项）。

**真机结果**（`192.168.50.3:5555`）：**OK (3 tests)**，用时 1.78s

| 断言 | 证据 |
| --- | --- |
| **模块能初始化真实 mpv 核心** | `MPVLib.tryCreate()` + `init()` 成功 |
| **JNI 表面可解析且核心能应答** | `getPropertyString("mpv-version")` 返回非空版本串 |
| **编解码栈可用** | `getPropertyString("ffmpeg-version")` 返回非空（ffmpeg 在模块内是独立库） |
| 可用性判定报告 MPV 可用 | `PlayerSetting.isMpvAvailable()` 与 `isPlayerAvailable(MPV)` 均为 true |
| `isBundled` 与实际打包一致 | 探针结论与直接读 assets 的结论相同 |

`mpv-version` 只在核心运行起来后才可读，因此这个值本身就是「整条链功能可用」的证据。

**过程中遇到的一个真实陷阱**：首轮测试报 `NoClassDefFoundError: RuntimeStore`——测试 APK 是新的，
但设备上的宿主 APK 还是旧的。仪器化测试的类在宿主进程内运行，因此**每次改了宿主代码都必须重装宿主**，
只装测试 APK 不够。这会导致「明明代码没问题却 NoClassDefFoundError」的困惑。

### 16.13 S2-c 已交付（2026-10-18）：真实播放回归

**范围**：证明模块化 mpv 能**真实解码播放**，补齐移除 assets 前最后一项未验证的路径。

**为什么还要这一步**：S2-b-前置证明了核心能初始化并读出属性，但那不经过解复用与解码。
一个 bundle 可以核心正常、属性可读，却在真正解媒体时失败。

**新增** `app/src/androidTest/java/com/fongmi/android/tv/runtime/RuntimeMpvPlaybackDeviceTest.java`（3 项）。

**真机结果**（`192.168.50.3:5555`）：**OK (3 tests)**

**mpv 自身日志（决定性证据）**：

```text
[demux:v]  Trying demuxers for level=normal.
[vd:v]     Decoder format: 1080x1920 [0:1] yuv420p ... CL=mpeg2/4/h264
[vf:v]     [in] 1080x1920 yuv420p bt.709/bt.709/bt.1886/limited/display
[cplayer]  VO: [null] 1080x1920 yuv420p
[cplayer]  first video frame after restart shown
[cplayer]  playback restart complete ... video=playing
```

这证明整条链路走通：**解复用 → H.264 解码 → 滤镜链 → 首帧输出**。
H.264 解码器位于模块内的 `libmvcodec.so`（ffmpeg），因此这条证据直接覆盖了模块最核心的能力。

| 断言 | 结果 |
| --- | --- |
| 加载真实媒体文件并等到 `FILE_LOADED` | ✅ |
| 解出视频尺寸 `width`/`height` 为正 | ✅ |
| `duration` 可读 | ✅ |
| 引擎使用的核心选项被接受（`hwdec`/`vo`/`video-sync`/`framedrop`） | ✅ |
| 播放命令与属性可设置（`pause`） | ✅ |

**测试方法上的一个关键修正**：首轮 `FILE_LOADED` 超时失败，但**不是模块缺陷**。
mpv 日志显示 `[vo/gpu/android:error] Missing surface pointer` ——
所有真实 `vo` 后端都需要 Android Surface，而测试没有附加（附加是播放器的职责）。
改用 `vo=null`/`ao=null` 隔离「解复用+解码」与「输出面」两件事后通过。
若不做这次日志诊断，很容易把测试方法问题误判为模块缺陷。

> 附带观察：测试环境下解码回退到软件解码（`DR failed - disabling` → `Using software decoding`），
> 这是没有 Surface 时的预期行为，不代表硬件解码有问题——硬解需要 Surface 参与。

### 16.14 S2-b 已交付（2026-10-18）：移除宿主 assets，收益兑现

**范围**：默认构建不再打包 `assets/mpv-libs`，APK 真正瘦身。`-PmpvBundle=true` 保留为回滚路径。

**改动**（`app/build.gradle`）：

```groovy
def mpvBundle = (project.findProperty('mpvBundle') ?: 'false').toString().toBoolean()
if (!mpvBundle) {
    androidResources.ignoreAssetsPatterns.add('mpv-libs')
}
```

**实测体积**（同一 flavor、全量重打、完整性校验通过）：

| 构建 | 体积 | `mpv-libs` 条目 |
| --- | --- | --- |
| `-PmpvBundle=true`（回滚路径） | 200,387,121 B (191.1 MB) | 10 |
| **默认（已移除）** | **153,648,893 B (146.5 MB)** | **0** |
| **减少** | **46,738,228 B (44.6 MB)** | |

**踩到的两个坑（都值得记录）**：

**1. `ignoreAssetsPatterns` 是只读 `ListProperty`**，Groovy 的 `+=` 会走 get-then-set 并报
`Cannot set the value of read-only property`。必须用 `.add()` 原地修改。
（先试的 `sourceSets.assets.filter.exclude` 完全无效——APK 里条目数不变。）

**2. 体积一度看起来"没变"，是 Gradle 增量打包的假象。** 移除后 APK 仍报 194.5 MB，
且 `mpv-libs` 条目已为 0。逐字节分析发现文件里有 **45.5 MB 空洞**：
`sum(compress_size)` 只占文件 74.9%，而含 mpv 的版本占 97.8%。
空洞起始处是 `PK` 本地文件头签名——即被删除条目的**残留旧数据**，
Gradle 复用了旧 APK 而未截断。

**必须删除 `app/build/outputs/apk/<variant>` 后全量重打**才能看到真实体积。
若只看增量构建的结果，会得出"移除 assets 没有收益"的错误结论并放弃这一步。

**验证**：

- 两种开关均构建成功，`testzip()` 完整性 OK
- 默认构建 `mpv-libs` 条目 = 0；`-PmpvBundle=true` = 10
- 宿主单测 209 项全绿（runtime 139 / subtitle 70）

**回滚路径**：`-PmpvBundle=true`。此外运行期仍有 assets 回退逻辑，
且 `PlayerSetting.isMpvAvailable()` 在两者皆无时隐藏 MPV 内核。

### 16.15 S2-d 已交付（2026-10-18）：可用性改查平台，修复侧载模块不可用

**范围**：修复 S2-b 移除 assets 后暴露的真实缺陷。

**缺陷**：`isMpvAvailable()` 原先查**宿主自己的安装注册表**（`RuntimeStore.isInstalled`），
而注册表只由应用内的安装流程写入。因此通过任何其他途径安装的模块——
侧载 APK、文件管理器、`adb install`——在设备上真实可用，宿主却认为不可用，
MPV 内核直接不显示。

**这不是理论问题**：S2-b 移除 assets 后，真机测试立刻报
`MPV should be offered when its module is installed` 失败。移除 assets 之前
这个缺陷被掩盖了，因为 assets 回退总是让判定为真。

**修复**：可用性改问平台——`RuntimePlatform.PackageInfo.nativeLibraryDir(packageName) != null`。
注册表仍负责版本与摘要元数据，但**不再是「什么存在」的事实来源**。

| 文件 | 改动 |
| --- | --- |
| `runtime/RuntimeMpv.java` | 新增 `MODULE_PACKAGE` 常量与 `isModuleInstalled(PackageInfo)` |
| `setting/PlayerSetting.java` | `isMpvAvailable()` 改用平台探测 |

**验证**：

| 项 | 结果 |
| --- | --- |
| 宿主单测 | **212 tests, 0 failures**（`RuntimeMpvTest` 20 项，新增 3 项平台探测回归） |
| 真机 `RuntimeMpvFunctionalDeviceTest` | OK (3 tests) |
| 真机 `RuntimeMpvLoadDeviceTest` | OK (3 tests) |
| 真机 `RuntimeMpvPlaybackDeviceTest` | OK (3 tests) |

**同时完成了 S2-b 的设备回归**：设备已装**不含 `assets/mpv-libs`** 的新 APK
（`unzip -l` 确认条目数 = 0），而模块路径的全部真机测试仍然通过——
9 库 dlopen、映射来源正确、H.264 解码出首帧。这证明移除 assets 是安全的。

新增的 3 项回归测试：平台探测（有/无/异常）、侧载模块使 MPV 可用、包管理器异常时不抛异常。

### 16.16 S0-h 已交付（2026-10-18）：UI 侧安装联调，端到端跑通

**范围**：让用户在模块管理页真正完成安装。此前所有能力都在代码层就绪，但缺少
「点按钮安装」这一环的验证。

**真机端到端结果**（`192.168.50.3:5555`，leanback 版）：

| 阶段 | 证据 |
| --- | --- |
| 点击安装 | `RuntimeModule: install requested id=mpv catalog=true module=true` |
| 下载 | 进度对话框「下载 1.0.0 ...」 |
| **摘要校验** | `progress id=mpv msg=校验完整性 ...` |
| 交给系统安装器 | `progress id=mpv msg=安装模块 ...` |
| 系统确认框 | `com.silent.runtime.mpv` / `安装来源：默影视 \| 大小：43 MB` / `你确定需要安装此应用吗？` |
| 安装成功 | `installed id=mpv version=1.0.0` + Toast「mpv 安装完成」 |
| 列表刷新 | 状态变为「已安装」，按钮变为「卸载」 |
| 平台确认 | `pm list packages` 出现 `com.silent.runtime.mpv` |

**本轮修复的 4 个真实缺陷（全部由真机暴露）**：

**1. 点击安装即崩溃 —— `?attr/webhtvColorPrimary` 未定义**
`webhtvColorPrimary` 只在 `webhtv_attrs.xml` **声明**，全仓**没有任何主题给它赋值**，
inflate 时抛 `UnsupportedOperationException: Failed to resolve attribute`。
改用 `?attr/colorPrimary`（mobile 布局既有 3 处先例）。
注意：两个 flavor 都会崩，只是先在 mobile 上触发。

**2. 点击安装即崩溃 —— `StringBuilder.isEmpty()` 是 Java 15 API**
设备（API 28）上不存在，抛 `NoSuchMethodError`。
这是**我自己引入的**：早前把 `safe.length() == 0` "顺手清理"成 `safe.isEmpty()`，
把可用代码改成了崩溃。
值得记录的是：静态检查器 `prefer-string-is-empty` **正是建议这样改的**——
这是一条会致崩的误报，不能采纳。已反向修复并在注释中写明原因。

**3. 安装阻塞 UI 线程**
`installer.install(...)` 同步下载 43 MB 且在点击回调线程上执行，
导致界面冻结、无进度、无日志。改为 `Task.execute(...)` 派发到后台。

**4. 关键路径无可观测性**
`RuntimeModulesDialog` 与 `RuntimeInstaller` 原本**完全没有日志**，无法判断卡在哪一步。
且首选的 `SpiderDebug.log` 会经 `DebugLogStore.acceptsTag` 过滤，
默认配置下**静默丢弃**，所以它不能用作诊断通道。改用 `android.util.Log`。

**一个耗时的排查教训（方法层面）**：

我长时间用 `adb shell input tap` 模拟点击，始终无响应，一度怀疑触摸被拦截。
实际原因是：**这是 leanback（TV）应用，主交互是遥控器焦点 + 确定键，不是触摸**。
`uiautomator` 显示 `focused=true` 的正是目标按钮，改用
`input keyevent KEYCODE_DPAD_CENTER` 后流程立即走通。
**TV 端 UI 验证必须用 DPAD 事件，不能用 tap。**

**验证**：宿主单测 212 项全绿（runtime 142 / subtitle 70）。

### 16.17 S3 已交付（2026-10-19）：IJK 内核模块化

**范围**：把 IJK 的 3 个原生库移入模块，并解决它与 MPV 的根本差异。

**与 MPV 的关键差异：加载方式不同**

| | MPV | IJK |
| --- | --- | --- |
| 加载调用 | `System.load(绝对路径)` | `System.loadLibrary("ijkffmpeg")` **按裸名搜索** |
| 搜索范围 | 任意路径 | **仅宿主自己的 `nativeLibraryDir`** |
| 模块库可见性 | 天然可见 | **不可见** |

`System.loadLibrary` 只查宿主自己的目录，所以模块里的库对它完全不可见。
且 `IjkMediaPlayer.LOADER` 是 `private static final`，无法从外部替换。

**解决方案**：给 `IjkLibLoader` 加一个宿主可注入的 `Resolver`。
`loadLibrary` 先问 resolver；resolver 返回文件就用绝对路径加载，返回 null 才回退
`System.loadLibrary`。这样既让模块库能被找到，又保留了「仍打包库」时的原行为。

**依赖关系（`readelf -d` 实测）**：

```text
libijkffmpeg.so -> 仅系统库
libijksdl.so    -> libijkffmpeg + 系统库
libijkplayer.so -> libijkffmpeg, libijksdl + 系统库
```

**三库都不依赖 `libc++_shared.so`**，所以 `hostLibraries` 为空——
比 mpv 简单，没有宿主库摘要契约要维护。

**模块产物**：

| ABI | 大小 | 库数 | hostLibraries |
| --- | --- | --- | --- |
| arm64-v8a | 10,355,321 B (9.9 MB) | 3 | 空 |
| armeabi-v7a | 7,623,295 B (7.3 MB) | 3 | 空 |

**改动**：

| 文件 | 改动 |
| --- | --- |
| `tv/danmaku/.../IjkLibLoader.java` | 新增 `Resolver` 注入点与 `loadLibraryOrResolved` |
| `tv/danmaku/.../IjkMediaPlayer.java` | `AnonymousClass1.loadLibrary` 改走解析路径 |
| `runtime/RuntimeIjk.java`（新） | 安装 resolver；可用性判定；裸名匹配 |
| `runtime/RuntimeCompatibility.java` | 登记 `ijk-jni-v1` 契约 |
| `setting/PlayerSetting.java` | `isIjkAvailable()` 接模块状态（原为硬编码 `true`） |
| `App.java` | 启动时安装 resolver |

**设计要点**：resolver **每次查找都重新检查模块**，而不是捕获安装时的快照。
这样「先装模块」和「先启动播放」的顺序无关紧要。代价可接受——
`IjkLibLoader` 自身会缓存加载尝试，每库每进程最多查一次。

**验证**：

| 项 | 结果 |
| --- | --- |
| 宿主单测 | **152 tests, 0 failures**（`RuntimeIjkTest` 10 项） |
| **真机仪器化** | **OK (6 tests)** @ `192.168.50.3:5555` |

真机 6 项覆盖：模块暴露 3 库、**裸名解析到模块文件**、
**三库真实加载且映射自模块目录**（`librariesLoadFromTheModuleForReal` 用
`/proc/self/maps` 取证）、可用性在模块已装时为真、resolver 与安装顺序无关、
无模块时回退而不抛异常。

**又一次踩到同一个坑**：首轮 4 项失败报 `NoSuchMethodError: install(PackageInfo)`。
原因是我重建了测试 APK 但**没重建宿主 APK**——设备上还是旧签名。
仪器化测试的类跑在宿主进程内，**改了宿主代码必须重装宿主**。
这个坑在 S2-b-前置已记录过一次，这次仍复现，说明它足够容易忘。

### 16.18 S3-b 已交付（2026-10-19）：移除宿主 IJK 库，收益兑现

**范围**：默认构建不再打包 IJK 的三个原生库。`-PijkBundle=true` 保留为回滚路径。

**改动**（`app/build.gradle`）：IJK 的库在共享 `jniLibs` 源集里（与 MPV 的 flavor assets
不同），所以从**打包阶段**排除而非资源阶段：

```groovy
def ijkBundle = (project.findProperty('ijkBundle') ?: 'false').toString().toBoolean()
if (!ijkBundle) {
    packaging.jniLibs.excludes.add('**/libijkffmpeg.so')
    packaging.jniLibs.excludes.add('**/libijksdl.so')
    packaging.jniLibs.excludes.add('**/libijkplayer.so')
}
```

同样用 `add()` 而非 `+=`：AGP 9.x 上是只读 `ListProperty`，get-then-set 会报
`Cannot set the value of read-only property`（S2-b 已踩过）。

**实测体积**（同一 flavor、全量重打、完整性校验通过）：

| 构建 | 体积 | `libijk*` 条目 |
| --- | --- | --- |
| `-PijkBundle=true`（回滚路径） | 153,648,897 B (146.5 MB) | 3 |
| **默认（已移除）** | **143,310,371 B (136.7 MB)** | **0** |
| **减少** | **10,338,526 B (9.9 MB)** | |

**决定性验证**：设备装上**不含 ijk 库**的 APK（`unzip -l` 确认条目 = 0）后，
`RuntimeIjkDeviceTest` 仍然 **OK (6 tests)** ——
三库真实加载且映射自模块目录（`/proc/self/maps` 取证）。
这证明移除宿主库是安全的：模块路径不依赖宿主副本。

**验证**：宿主单测 **222 tests, 0 failures**（runtime 152 / subtitle 70）。

### 16.19 S4 已交付（2026-10-19）：移除宿主 sherpa 库 + 修复侧载模块不可加载

**范围**：移除宿主打包的 sherpa 库（23.6 MB），并修复一个在移除后才会暴露的真实缺陷。

**缺陷：可用性查平台，但加载查注册表**

S2-d 把可用性改为查平台（侧载模块显示可用），但 `RuntimeLoader.verify()` 仍然只认宿主的
安装注册表。两者不一致的后果是**最糟的组合**：

- 侧载模块 → 内核/功能**显示可用**
- 实际加载 → 注册表无记录 → **被判「未安装」而失败**

移除 bundled 库之前，这条路径被 assets 回退掩盖；移除后它成为唯一回退，必须先修。
真机立刻复现：设备上 `com.silent.runtime.sherpa` 已侧载，注册表却只有 mpv。

**修复**：注册表无记录时回退到「已安装模块」校验。

| 文件 | 改动 |
| --- | --- |
| `RuntimeCompatibility` | 新增宿主侧的**模块 id → 包名**可信表（与契约白名单同类） |
| `RuntimeVerifier` | 新增 `verifyInstalled(manifest, root, abi, hostLibraries)`：不比对 catalog，但校验清单结构、ABI、契约、逐文件摘要、宿主库摘要 |
| `RuntimeLoader` | 无记录时用可信表解析包名并走 `verifyInstalled`，合成调用方需要的 entry |

**安全边界（重要）**：`verifyInstalled` 不做 catalog 比对（侧载模块本就没有 catalog 摘要），
但**仍强制**：清单结构有效、ABI 受支持、无路径穿越、契约在白名单内、每个声明文件的摘要匹配、
宿主库摘要匹配。**来源可信性由平台保证**——APK 签名在安装时已由系统校验。
这与 S2-d 的原则一致：注册表管元数据，平台管存在性。

**实测体积**（同一 flavor、全量重打、完整性校验通过）：

| 构建 | 体积 | sherpa 条目 |
| --- | --- | --- |
| `-PsherpaBundle=true`（回滚路径） | 143,310,371 B (136.7 MB) | 1 |
| **默认（已移除）** | **119,700,946 B (114.2 MB)** | **0** |
| **减少** | **23,609,425 B (22.5 MB)** | |

**验证**：

| 项 | 结果 |
| --- | --- |
| 宿主单测 | **226 tests, 0 failures**（runtime 156 / subtitle 70） |
| 真机全量回归 | **41 tests 全绿**：模块 10 / mpv 9 / mpv 加载 3 / mpv 功能 3 / mpv 播放 3 / ijk 6 / 仓库 4 / sherpa 加载 3 |

真机决定性验证：设备装上**不含 sherpa 库**的 APK（条目 0），且 sherpa 模块是**侧载**的
（注册表无记录），`RuntimeSherpaLoadDeviceTest` 仍 **OK (3 tests)** ——
门禁解析为 `Source.MODULE`，SDK 从模块目录加载（`/proc/self/maps` 取证）。

**修正的 6 个测试预期**（均为语义变更所致，非实现缺陷）：
原先用「空注册表 + 真实 libDir」建模"未安装"，而新语义下这正确表示"侧载已安装"。
准确的"未安装"模型是 `libDir == null`。同时为每个模块补了「侧载模块可用」的正向用例。

**一处测试自身的错误**：`RuntimeMpvDeviceTest` 首轮仍失败，原因是我把桩 `PackageInfo`
传给了 `prepare` 而非 `loader`——验证用的是 loader 里的探测，所以桩没生效。
修正后通过。这说明**桩必须注入到真正做判定的那一层**。

### 16.20 S5 已交付（2026-10-19）：零模块状态验证

**范围**：验证**每个新用户都会遇到的初始状态**——一个模块都没装。

**为什么这一步非做不可**：这是现场最常见的配置，也最容易漏测——因为开发模块的人手上总是装着模块。
它尤其关键，因为 bundled 库已经全部移除：若可用性判定或加载路径假设了模块存在，
**全新安装会崩溃或提供一个无法启动的内核**。

**真机结果**（`192.168.50.3:5555`，设备上三个模块全部卸载、注册表清空）：**OK (8 tests)**

| 断言 | 意义 |
| --- | --- |
| MPV 与 IJK 均报告**不可用** | 内核选择器不会提供无法启动的引擎 |
| `isPlayerAvailable(MPV/IJK)` 为 false | 回退链不会选中它们 |
| **EXO 与系统内核仍可用** | 应用整体仍可用，不会因缺模块而不可用 |
| 模块探测不抛异常 | 全新安装不会因探测而崩溃 |
| `MPVLib.isBundled` 为 false | 确认 bundled 库确实已移除 |
| IJK resolver 拒绝解析 | 回退到 `System.loadLibrary`，符合「仍打包库」时的预期 |
| MPV 准备干净失败、不半加载 | 不会留下部分加载的原生库 |
| 宿主不谎称缺模块已安装 | 可用性判定如实反映设备状态 |
| 每个已知模块 id 都有可信包名 | 侧载回退路径有据可依 |
| 不存在的模块目录不被当作已安装 | 探测不误判 |

**测试设计**：全部用 `assumeTrue` 守卫——模块已装时跳过而非失败，因为本类只覆盖「零模块」这一种状态。
跳过会明确显示，不会伪装成通过。

**验证**：真机 **OK (8 tests)**。

**累计真机验证**：49 项
（模块 10 / mpv 9 / mpv 加载 3 / mpv 功能 3 / mpv 播放 3 / ijk 6 / 仓库 4 / sherpa 加载 3 / 零模块 8）。

### 16.21 S6 已交付（2026-10-19）：内核选择器按可用性过滤

**范围**：零模块时内核选择器不再提供 MPV/IJK；顺带修复 S0-f 引入的对比度缺陷。

**缺陷：内核选择器无条件列出全部内核**

`PlayerKernelDialog.kernels()` 直接返回 `KERNEL_ORDER` 的全量标签，不看可用性。
模块化之前这没问题（四个内核都在），但移除 bundled 库之后，零模块时它仍然提供
MPV 与 IJK —— 用户选中只会得到 `resolveManualPlayer` 的失败提示。

**修复**：菜单按可用性过滤，并修正随之而来的下标语义。

| 文件 | 改动 |
| --- | --- |
| `PlayerSetting` | 新增 `availableKernels()` / `availableKernelIndex()` / `availableKernelAt()` |
| `PlayerKernelDialog` | `kernels()` 过滤；`notifySelected` 改用 `availableKernelAt` |
| `TmdbDetailActivity` | 菜单下标回推改用 `availableKernelAt` |
| `mobile/VideoActivity` | 同上 |

**关键陷阱：菜单下标 ≠ 内核位次**

过滤后，菜单下标与 `KERNEL_ORDER` 的位次不再对应。任何从菜单下标反推内核的地方
都必须改用 `availableKernelAt`——否则「零模块时选第 2 项」会拿到 IJK（一个启动不了的引擎），
而不是系统内核。三处调用点（`PlayerKernelDialog`、`TmdbDetailActivity`、`VideoActivity`）
全部修正，并补测试钉住这个映射。

**顺带修复的真实缺陷（S0-f 引入）**：`LeanbackForegroundContrastTest` 点名
`dialog_runtime_modules.xml` —— 它用 `shape_config_history_dialog`（白天为浅色卡片）
却配 `?attr/webhtvColorOnWallpaper`（浅色前景），**白天模式白字白底**。
同背景的 `dialog_history.xml` 用的是 `@color/config_history_text`，已对齐。
这是 S0-f 时只跑了过滤测试、没跑全量套件而漏掉的。

**验证**：

| 项 | 结果 |
| --- | --- |
| `PlayerSettingTest` | 新增 4 项（可用列表、下标映射、选中项消失时的回退、可用列表是位次表的子集） |
| `PlayerControlFocusIntegrationTest` | 2 项源码断言更新为 `availableKernelAt` |
| `LeanbackForegroundContrastTest` | **修复后通过** |
| 两个 flavor 编译 | 通过 |

**7 个预先存在的失败（与本次改动无关，已逐一取证）**：
`MpvFontConfigTest`、`MpvHlsCacheCoordinatorTest`、`ReaderPlaybackRoutingSourceTest`×2、
`TmdbUIAdapterTest`、`TmdbSourceOnlyInteractionTest`、`WebThemeTokenSourceTest`。

判定方法：前 5 个用 `git stash` 暂存改动后仍失败；后 2 个的断言目标在 **HEAD 就不存在**
（leanback 的 `setTmdbRematchVisible(...)` 为 0 处；`eclipse.html` 是 CRLF 而测试期望 LF）。
**不修**——它们不属于本任务范围，按 AGENTS.md 只报告。

> 一次方法失误值得记录：我第一次做对照时用了错误的包名（`ui.helper` 而非 `ui.activity`），
> Gradle 报 "No tests found" 而非失败，我却据此得出「是我的改动导致」的错误结论。
> 后来改用直接证据（断言目标在 HEAD 是否存在）才判对。**对照实验必须确认它真的跑了。**

### 16.22 S6-b 已交付（2026-10-19）：内核过滤的真机验证

**范围**：为 S6 的菜单过滤补设备级证据——S6 只做了单测与编译验证。

**新增 2 项**（`RuntimeNoModuleDeviceTest`，零模块状态）：

| 断言 | 意义 |
| --- | --- |
| 菜单只列可用内核，且数量与 `availableKernels()` 一致 | 过滤真的作用到了 UI 数据源 |
| 菜单里不含 MPV / IJK，每行都有非空标签 | 不会出现选不动的行或空行 |
| 可用内核恰为 `{EXO, SYSTEM}`（2 项） | 两个内置引擎仍被提供，应用不会不可用 |
| `availableKernelAt(1)` 为系统内核而非 IJK | 菜单下标确实走可用列表，不走位次表 |

最后一项是 S6 的关键陷阱：过滤后菜单下标与 `KERNEL_ORDER` 位次不再对应，
若映射写错，零模块时选第 2 项会拿到 IJK（启动不了的引擎）。

**真机结果**（`192.168.50.3:5555`，三个模块全部卸载）：**OK (10 tests)**

**又一次踩到同一个坑**：首轮构建后直接跑测试会失败，因为 S6 改了宿主代码
而我只重建了测试 APK。仪器化测试的类跑在宿主进程内，**改了宿主必须重装宿主**。
这次在跑之前就重建并安装了宿主（并确认 APK 内含 `availableKernels`），未再浪费一轮。

**累计真机验证**：59 项
（模块 10 / mpv 9 / mpv 加载 3 / mpv 功能 3 / mpv 播放 3 / ijk 6 / 仓库 4 / sherpa 加载 3 / 零模块 10 / 其他 8）。

### 16.23 S7 已交付（2026-10-19）：预加载去重实验，划定模块化边界

**范围**：验证「按绝对路径预加载能否满足后续裸名查找」——这决定协议提取器
（thunder / jianpian / forcetech，共 15.5 MB）能否模块化。

**为什么必须先做这个实验**：这些库由**第三方 AAR 的二进制代码**加载
（`XLLoader` 调 `System.loadLibrary("xl_thunder_sdk")`），不像 IJK 那样是仓库内源码、
可以注入 resolver。宿主唯一可用的手段是「按绝对路径预加载模块副本，
指望后续裸名请求复用已加载的那一份」。

**结论：不行。**

`System.loadLibrary("ijkffmpeg")` 抛 `UnsatisfiedLinkError`，
**即使该库已通过绝对路径加载、且已在 `/proc/self/maps` 中可见**。

原因：Android 在进入 `dlopen` **之前**，先由 class loader 的
`nativeLibraryDirectories` 把裸名解析成路径。模块目录不在其中，所以名字根本解析不到——
失败发生在**名字解析**阶段，与库是否已映射无关。

**对照实验（让结论可归因）**：

| 测试 | 结果 | 意义 |
| --- | --- | --- |
| `bareNameLookupSucceedsForALibraryInTheHostDirectory` | ✅ | 宿主目录内的库（`libc++_shared.so`）裸名加载正常，机制本身没坏 |
| `preloadedLibraryDoesNotSatisfyALaterBareNameLookup` | ✅ | 模块库预加载后裸名查找仍失败，失败归因于**位置** |
| `moduleLibraryLoadsWhenTheHostControlsTheCall` | ✅ | 宿主自己控制调用时能加载模块库，这正是 `RuntimeIjk` 的做法 |

没有第一个对照，第二个结论可能被误读成「裸名机制坏了」。

**对架构的影响**：

- **可以模块化**：宿主自己控制加载的库（mpv、sherpa、ijk）。IJK 之所以可行，
  是因为它的 loader 是仓库内源码，能注入 resolver。
- **不能模块化（不改厂商代码）**：thunder（`XLLoader`）、jianpian（`P2PClass`）、
  forcetech（`ForceTV`）——它们是二进制 AAR。

因此 **15.5 MB 的协议提取器库必须留在 APK**。这是架构边界，不是待办事项。

**理论替代路径（未实施，不推荐）**：用自定义 `ClassLoader`（其
`nativeLibraryDirectories` 指向模块目录）加载这三个 AAR 的类。这属于 DexClassLoader
路线——第一轮设计已否决（97 处静态引用、接口泄漏），此处还要额外承担厂商类的
类加载器隔离风险。

**验证**：真机 **OK (3 tests)**。

**累计真机验证**：62 项。

### 16.24 S8 已交付（2026-10-19）：体积口径修正

**范围**：核实并记录此前所有体积数字的口径——它们都基于 **debug** 构建。

**发现**：`app/src/debug/assets/exo-ass/` 含 **9.3 MB 测试样本**
（`sample-fonts.mkv` 7.3 MB + `sample-embedded.mkv` 2.5 MB），
**只在 debug 源集**，不进正式包。

```text
app/src/main/assets/     ← 正式资产（无 exo-ass）
app/src/debug/assets/    ← exo-ass 仅在此
app/src/leanback/assets/ ← 空
app/src/mobile/assets/   ← 空
```

**口径说明（重要）**：

| 项 | 说明 |
| --- | --- |
| 我报告的所有体积 | **debug 构建**（本仓库约定不打正式包） |
| 相对收益 | **依然有效**——同一构建类型的前后对比 |
| 绝对数字 | 是 debug 数字，比正式包大约 9.3 MB |
| 当前 debug APK | 120,872,956 B (115.3 MB) |
| 推算正式包 | 约 111,126,710 B (106.0 MB) |

**为什么值得单独记录**：若不说明，读者会把「115.3 MB」当成正式包体积，
并据此判断收益。相对值（-44.6 / -9.9 / -22.5 MB）不受影响，因为它们都是
同构建类型的前后差；但绝对值必须以 debug 为前提来读。

**验证**：源集目录逐一核对（上表），并用 zip 逐条目统计确认 exo-ass 全部
位于 debug 源集。

### 16.25 S9 已交付（2026-10-19）：资产重定向实验，Python 模块化可行性

**范围**：验证 `AssetManager.addAssetPath` 能否让模块资产对宿主可见——这决定
Python 运行时（Chaquopy，20.9 MB，剩余最大可拆项）能否模块化。

**结论：资产可以重定向，且 Python 具备模块化的结构条件。**

**实验（`RuntimeAssetPathDeviceTest`，真机 OK (3 tests)）**：

| 测试 | 结果 | 意义 |
| --- | --- | --- |
| `hostAssetsAreReadable` | ✅ | 探针本身有效 |
| `moduleAssetsAreNotVisibleByDefault` | ✅（单独运行） | 模块资产默认不可见 |
| `addAssetPathMakesModuleAssetsReadable` | ✅ | **`addAssetPath` 确实生效** |

第三条是决定性证据：把模块 APK 加进宿主 `AssetManager` 后，
模块的 `assets/runtime.json` 可被宿主读取；而该资产宿主自己没有，
所以成功不可能来自宿主副本。

**为什么 Python 与 thunder 的结论不同（关键区别）**：

| | thunder / jianpian | Python（Chaquopy） |
| --- | --- | --- |
| 资产定位 | 厂商 AAR 二进制 | **`chaquo/Platform.java` 是仓库内源码** |
| 原生库加载 | 厂商 AAR 二进制（`XLLoader`） | **同一个 `Platform.loadNativeLibs()`** |
| 可否改 | 否 | **是** |

`Platform.java` 同时控制 `getPath()`/`extractAssets()`（资产）与
`loadNativeLibs()`（原生库），两者都在仓库内。因此 Python 模块化**不需要**
S7 那条走不通的「预加载满足裸名查找」路径——可以直接改成从模块目录加载。

**测试顺序陷阱（已处理）**：`addAssetPath` 是**进程级且不可撤销**的。
同进程内先跑的决定性测试会把模块 APK 加进去，使「默认不可见」的基线失效。
首轮因此报 1 个失败，单独运行该基线则通过。已改为在该前提下 `assumeTrue` 跳过
（而非假失败），并在类注释中写明本类需单独运行。

**对后续工作的意义**：Python 是**下一个可拆项**，预计收益约 20.9 MB
（`assets/chaquopy` 12.1 + `libpython3.10` 3.5 + `libcrypto_python` 3.7 +
`libsqlite3_python` 0.89 + `libssl_python` 0.62 + `libchaquopy_java` 0.15）。
本次只验证了可行性，未实施。

### 16.26 S10 已交付（2026-10-19）：无 SONAME 依赖实验，Python 模块化可行

**范围**：验证「预加载无 `DT_SONAME` 的库能否满足后续按名依赖」——这决定 Python 运行时
（Chaquopy，约 21 MB，未压缩口径）能否模块化。

**为什么必须先做这个实验**：`libpython3.10.so` **没有 `DT_SONAME`**
（`llvm-readelf -d` 确认：只有 `NEEDED`，无 `SONAME`），而 **13 个库按裸名依赖它**
（`libchaquopy_java.so` + 12 个 `bootstrap-native` 扩展模块）。
bionic 源码对此有明确警告：

> Before API 23, the linker used the basename in place of DT_SONAME. After we switched,
> apps with libraries without a DT_SONAME stopped working: they could no longer be found
> by DT_NEEDED from another library.

该 basename 回退以 `get_application_target_sdk_version() < 23` 为条件，本设备 API 28、
targetSdk ≥ 23，故**按源码推断应当失败**。

**结论：实测通过——预加载确实满足后续按名依赖，Python 模块化可行。**

**实验（`RuntimeNoSonameDependencyDeviceTest`，真机 OK (2 tests)）**：

| 用例 | 被依赖库 | 结果 |
| --- | --- | --- |
| `preloadedLibraryWithSonameSatisfiesDependency` | `libcrypto_python.so`（有 SONAME） | ✅ 对照通过 |
| `preloadedLibraryWithoutSonameSatisfiesDependency` | `libpython3.10.so`（**无 SONAME**） | ✅ **预加载副本被复用** |

设计要点（使结论可归因、且贴合真实布局）：

1. 两个库**分别放在不同子目录**（`s10-nosoname-lib` / `s10-nosoname-dependent`），
   都不在 linker 搜索路径上——真实场景正是如此：扩展模块解压到
   `filesDir/chaquopy/bootstrap-native/<abi>/`，解释器在模块的 native library 目录。
   若两库同目录，结论可能只是「同目录恰好能找到」，不具说服力。
2. 断言被依赖库**只有一个映射来源**，且等于预加载目录。宿主自己也带
   `libpython3.10.so`；若出现第二个来自宿主目录的映射，说明名字是**经搜索路径**解析的，
   而真实模块里宿主根本没有这些库——那结论就不成立。
3. 有 SONAME 的一对作为对照：同样形状（预加载一个、再按绝对路径加载依赖方），
   若它通过而无 SONAME 的失败，才能归因于 SONAME 缺失而非绝对路径加载本身。

**过程中的两次自我纠错（记录以免重犯）**：

- 首版判别测试报 `origins=[]`，看似「库未映射」。实际是 `origins=[""]`——
  我用 `line.substring` 取目录时对 `/proc/self/maps` 的字段切分有误，
  空字符串列表被打印成 `[]`。改用按空白切分取第 6 个字段后正常。
- 修正后又出现 `/data/user/0/...` 与 `/data/data/...` 的比较失败：二者是同一目录的
  符号链接别名。改用 `getCanonicalPath()` 比较。

**对架构的影响**：Python 是**下一个可拆项**，且不需要 S7 那条走不通的路径——
`chaquo/Platform.java` 是仓库内源码，同时控制资产定位（`getPath()`/`extractAssets()`，
S9 已证明 `addAssetPath` 可用）与原生库加载（`loadNativeLibs()`，本阶段证明预加载有效）。
预计收益约 **21 MB**（未压缩口径；`assets/chaquopy` 12.3 + `libpython3.10` 3.5 +
`libcrypto_python` 3.7 + `libsqlite3_python` 0.89 + `libssl_python` 0.62 +
`libchaquopy_java` 0.15）。本次只验证可行性，未实施。

**累计真机验证**：64 项。

### 16.27 S11 已交付（2026-10-19）：模块资产读取机制对比，实施可避开隐藏 API

**范围**：确定宿主读取模块资产的**实施用**机制。S9 证明了 `AssetManager.addAssetPath`
可行，但它是**隐藏 API**，且会**不可逆地**改动进程级 `AssetManager`。
本阶段比较三种机制，为 S11（Python 模块化实施）选定设计。

**结论：实施走公开 API，不需要隐藏 API。**

**实验（`RuntimeModuleAssetAccessDeviceTest`，真机 OK (5 tests)）**：

| 机制 | 性质 | 结果 |
| --- | --- | --- |
| `AssetManager.addAssetPath` | 隐藏 API，进程级不可逆 | ✅（S9 已证） |
| `createPackageContext(pkg).getAssets()` | **公开 API**，无进程级副作用 | ✅ **可用** |
| 直接按 zip 读模块 APK | **纯 Java**，不涉 Android API | ✅ **可用** |

关键用例：

| 用例 | 意义 |
| --- | --- |
| `moduleAssetsAreReadableThroughCreatePackageContext` | 公开 API 可读模块资产 |
| `moduleAssetsAreReadableByReadingTheApkDirectly` | 纯 Java 回退路径可用 |
| `bothMechanismsAgreeOnTheContent` | 两条路径字节一致，排除「读到别的东西」 |
| `moduleContextDoesNotExposeHostAssets` | 模块 context **不**暴露宿主资产，证明读的确实是模块的 |
| `missingModulePackageIsReported` | 模块缺失时抛 `NameNotFoundException`，不静默回退到宿主资产 |

**设计取舍**：

- **首选 `createPackageContext`**：公开 API、无需反射、无进程级副作用，
  且天然带包存在性检查（`NameNotFoundException`）。
- **备选直接读 APK zip**：完全不依赖 Android API，可用于契约层纯 JVM 单测，
  也规避未来 API 收紧。
- **不再使用 `addAssetPath`**：隐藏 API 在 targetSdk 提升后可能被限制，
  且其进程级不可逆副作用已在 S9 中造成测试顺序陷阱。

**对 S11 的意义**：Python 模块化的两个技术前提至此**全部落实且都走公开路径**——
资产读取（本阶段）与无 SONAME 依赖解析（S10）。预计收益约 21 MB。

**累计真机验证**：69 项。

### 16.28 S12 已交付（2026-10-19）：Python 加载次序实验，契约必须拓扑排序

**范围**：确定 Python 模块的 `loadOrder` 契约。`chaquo/Platform.java` 用**裸名**按固定次序加载：

```text
crypto_chaquopy, ssl_chaquopy, sqlite3_chaquopy, python3.10, chaquopy_java
```

但实测依赖图与该次序**不一致**：

| 依赖方 | 被依赖方 | 次序问题 |
| --- | --- | --- |
| `libcrypto_chaquopy.so` | `libcrypto_python.so` | 依赖方**先**加载 |
| `libssl_chaquopy.so` | `libssl_python.so` | 依赖方**先**加载 |
| `libsqlite3_chaquopy.so` | `libsqlite3_python.so` | 依赖方**先**加载 |
| `libssl_python.so` | `libcrypto_python.so` | 依赖方**先**加载 |
| `libchaquopy_java.so` | `libpython3.10.so` | 次序正确 |

**结论：现有裸名次序在绝对路径加载下不成立，模块契约必须声明拓扑排序的 `loadOrder`。**

**决定性证据（`RuntimePythonLoadOrderDeviceTest`，真机 OK (2 tests)）**：

| 用例 | 结果 |
| --- | --- |
| `dependencyIsNotSatisfiedFromTheModuleDirectoryWhenLoadedLate` | ✅ 依赖**未**从模块目录满足 |
| `preloadingTheDependencyMakesTheDependentLoadable` | ✅ 预加载后正常 |

第一项的**设备日志**给出了精确机制（这是本阶段最有价值的证据）：

```text
late-load libcrypto_chaquopy.so = loaded;
  libcrypto_python.so origins = [/data/app/com.silent.android.webhtv-.../lib/arm64];
  hostDir                     = /data/app/com.silent.android.webhtv-.../lib/arm64
```

即：**加载成功了，但依赖是从「宿主自己的目录」解析的**，不是暂存目录。
现有次序之所以能工作，纯粹因为宿主 `nativeLibraryDir` 在 linker 搜索路径上——
而真实模块里宿主**根本不提供**这些库，故该次序会失败。

**为什么断言的是「映射来源」而不是「是否成功」**：宿主自己带这 8 个库的全部副本，
且宿主目录**在**搜索路径上。所以「不预加载依赖也能成功」是可能的——
但那是**假成功**：它依赖宿主副本，而模块场景下不存在。断言映射来源才是可迁移的性质。

**对契约的影响**：Python 模块的 `runtime.json` 必须给出拓扑排序的 `loadOrder`：

```text
libcrypto_python.so, libpython3.10.so, libsqlite3_python.so, libssl_python.so,
libcrypto_chaquopy.so, libssl_chaquopy.so, libsqlite3_chaquopy.so, libchaquopy_java.so
```

这与 mpv 模块的做法一致（S1 的 9 库 `loadOrder` 同样是拓扑序，而非宿主 `LOAD_ORDER` 原序）。

**另一项关键约束（已核实，影响降级设计）**：`BaseLoader` 构造时**急切**创建
`PyLoader` → `new Loader()` → `Python.start(...)`（`BaseLoader.java:35`）。
因此 Python 模块缺失时，**配置加载会直接抛异常**，而不是优雅降级。
实施时必须把这条路径改为「模块可用才初始化」，否则未装模块的用户无法加载任何配置。

**累计真机验证**：71 项。

### 16.29 S13 设计门禁（2026-10-19）：Python（Chaquopy）模块化实施方案

> 本节是 `AGENTS.md` §7 要求的实施前门禁产物。**在获得明确批准前不得实施。**

#### 16.29.1 要实现的实际能力

把 Python 运行时（Chaquopy）从默认 APK 移入可选模块，使用户**未安装模块时**：

- 应用仍能正常启动、加载配置、播放视频（不崩溃）；
- 使用 JS/Jar 源的配置完全不受影响；
- 配置里出现 `.py` 源时，给出「需要安装 Python 模块」的明确引导，而不是静默失败。

**安装模块后**：`.py` 源可用，行为与现在一致。

#### 16.29.2 当前项目已有实现

| 位置 | 现状 |
| --- | --- |
| `chaquo/` Gradle 模块 | 已是独立模块，产出 `assets/chaquopy/**`（20 文件）+ 8 个 `.so` |
| `chaquo/Platform.java` | **仓库内源码**，`extends Python.Platform`，同时控制资产定位与原生库加载 |
| `chaquo/Loader.java` | `Python.start(Platform.create())` 的入口 |
| `PyLoader.java:24` | 构造时急切 `new Loader()` |
| `BaseLoader.java:35,165` | `Loader.INSTANCE` 静态初始化 → 急切 `new PyLoader()` |
| `PythonSubtitleScriptRuntime.java:14` | 构造时急切 `new Loader()` |
| `LoginStateSync.java:655` | 已把 `files/chaquopy/` 视为需同步路径（既有约定） |

#### 16.29.3 涉及仓库及完整 commit ID

| 仓库 | 版本 | 说明 |
| --- | --- | --- |
| WebHTV（本仓库） | `b8a3a027da2aa77492b6b4a353af1368a01bd362` | 宿主侧改动 |
| `Silent1566/webhtv-plugins` | `a00fb34` | 新增 `python` 模块定义与产物 |
| Chaquopy | `com.chaquo.python:gradle:17.0.0` | 仅作为依赖，**不修改** |
| Chaquopy 上游源码 | `chaquo/chaquopy@7b690f7e` | 只读参考（`importer.py`） |

#### 16.29.4 最佳实践研究（证据）

| 来源 | 版本/日期 | 证据等级 | 支持的论断 | 对 WebHTV 的适用性 |
| --- | --- | --- | --- | --- |
| `Python.Platform` Javadoc | Chaquopy 17.0.0 | 官方文档 | `Platform` 是**公开可子类化**的扩展点，`getPath()`/`onStart()` 为文档化方法 | **直接适用**：宿主已用此法 |
| `importer.py` `initialize()` | `7b690f7e` | 上游源码 | 传入的 `context` 同时决定 `nativeLibraryDir`（ctypes）、`getFilesDir()`（`ASSET_PREFIX` 解压根）、`getSharedPreferences`（记账）与 `AssetZipFile`（读资产） | **直接适用**：一个注入点覆盖四项 |
| `importer.py` `initialize_importlib()` | `7b690f7e` | 上游源码 | `ASSET_PREFIX = realpath(context.getFilesDir())/chaquopy/AssetFinder` | 解压位置随 context 走 |
| Chaquopy issue #637（维护者 mhsmith） | 2022-06-17 | 维护者说明 | 「`AssetFinder` 路径是特殊处理的，**不要试图修改它们**」 | **重要约束**：不得改 `sys.path` 或自建 finder |
| WebHTV 实测 S9 | 2026-10-19 | 本仓库真机证据 | `createPackageContext(pkg).getAssets()` 可读模块资产 | 实施用机制 |
| WebHTV 实测 S10 | 2026-10-19 | 本仓库真机证据 | 预加载无 SONAME 的 `libpython3.10.so` 可满足后续按名依赖 | 库加载可行 |
| WebHTV 实测 S12 | 2026-10-19 | 本仓库真机证据 | 现有裸名次序在绝对路径下**不成立**；`BaseLoader` 急切初始化会在模块缺失时抛异常 | 决定契约与降级设计 |

**未能取得的证据**：上游无「Chaquopy 资产从模块 APK 加载」的先例（`FongMi/Release` 的
`runtime-python` 走的是同一 Chaquopy 机制，但其宿主如何定位资产未公开源码）。
故本方案的上游对应关系是**架构对应**，不是实现照搬。

#### 16.29.5 方案对比

| 方案 | 做法 | 判定 |
| --- | --- | --- |
| **A. 不改** | 保持 21 MB 内嵌 | 可行但放弃收益；与已完成的 mpv/ijk/sherpa 不一致 |
| **B. 照搬上游** | 假定上游有现成做法 | **不可行**：无公开实现可照搬 |
| **C. 最小适配（推荐）** | 模块 APK 承载 `assets/chaquopy/**` + 8 个 `.so`；`Platform` 改为从模块 context 读资产与加载库；`PyLoader`/`PythonSubtitleScriptRuntime` 改为惰性 + 可用性门禁 | **采用** |
| D. 改 Chaquopy 二进制 | 重打包 `chaquopy_java.jar` | **否决**：维护成本高、升级即碎 |

#### 16.29.6 推荐方案（C）的设计

**1. 契约（模块侧 `assets/runtime.json`）**

```json
{
  "module": "python",
  "packageName": "com.silent.runtime.python",
  "compatibilityId": "chaquopy-python310-v1",
  "library": "lib/arm64-v8a/libchaquopy_java.so",
  "loadOrder": [
    "lib/arm64-v8a/libcrypto_python.so",
    "lib/arm64-v8a/libpython3.10.so",
    "lib/arm64-v8a/libsqlite3_python.so",
    "lib/arm64-v8a/libssl_python.so",
    "lib/arm64-v8a/libcrypto_chaquopy.so",
    "lib/arm64-v8a/libssl_chaquopy.so",
    "lib/arm64-v8a/libsqlite3_chaquopy.so",
    "lib/arm64-v8a/libchaquopy_java.so"
  ],
  "hostLibraries": {},
  "files": [ ... 每个 .so 与每个 assets/chaquopy/** 的 path/size/sha256 ... ]
}
```

`loadOrder` 是 **S12 实测得出的拓扑序**，不是 `Platform.loadNativeLibs()` 的原序。
`hostLibraries` 为空：8 个库只依赖系统库与彼此（实测），不依赖宿主提供的 `libc++_shared.so`。

**2. 契约层扩展（`RuntimeVerifier`）**

现有 `verifyFiles` 以 `nativeLibraryDir` 为根，**无法覆盖 `assets/**`**。需要：

- `RuntimeManifest.RuntimeFile` 增加「载荷根」概念：`lib/<abi>/…` 相对 `nativeLibraryDir`，
  `assets/…` 相对模块 APK 的 zip 根；
- `verifyFiles` 按前缀分派到对应的读取器；
- 新增 `RuntimeModuleAssets`：用 **S11 选定的公开 API**
  （`createPackageContext(pkg).getAssets()`）读取，并以 `ZipFile` 为备选。

**3. 宿主适配（`chaquo/Platform.java`）**

```java
// 现状：context = Init.context(); am = context.getAssets();
// 改为：模块可用时用模块 context，否则报错（不再回退到宿主 assets，因为已移除）
Context assetContext = RuntimePython.assetContext();   // 模块 context 或 null
```

- `am = assetContext.getAssets()` → 读 `chaquopy/build.json` 与 `chaquopy/**`；
- `loadNativeLibs()` 改为按 `loadOrder` 从模块目录 `System.load` 绝对路径；
- `getPath()` / `onStart()` 不变（它们只用 `context.getFilesDir()`，仍可用宿主 context）。

**4. 降级（关键，S12 已证必须做）**

| 位置 | 现状 | 改为 |
| --- | --- | --- |
| `PyLoader.java:24` | 构造即 `new Loader()` | 惰性：首次真正需要时创建；模块不可用则抛可识别异常 |
| `PythonSubtitleScriptRuntime.java:14` | 构造即 `new Loader()` | 同上 |
| `BaseLoader.java:35` | 静态初始化急切 `new PyLoader()` | `PyLoader` 构造不再触发 `Python.start` |

必须保证：**未装模块时应用能启动、能加载配置、能播放**。这是硬验收项。

**5. UI 引导**

`.py` 源在模块缺失时给出明确提示（复用 S0-f 的模块管理页入口），而非静默返回空。

#### 16.29.7 缺点与风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 首次启动解压成本 | 20 个资产、约 12 MB 解压到 `filesDir` | 既有行为不变（现在也解压）；模块化不增加 |
| `files/chaquopy/` 路径约定 | `LoginStateSync:655` 依赖该前缀 | 保持 `Common.ASSET_DIR` 与解压位置不变 |
| 模块 context 的生命周期 | `createPackageContext` 返回的 context 需长期持有 | 持有 Application 级引用；模块卸载时失效需重取 |
| 上游升级 | Chaquopy 升版可能改 `importer.py` 内部 | 只用文档化扩展点（`Platform` 子类）；`initialize(context,…)` 的 context 语义已在源码确认 |
| 配置加载路径 | `BaseLoader` 静态初始化 | S12 已定位；改为惰性后需回归「JS/Jar 源不受影响」 |
| 体积口径 | 模块约 21.15 MB（未压缩） | 宿主净减 21.03 MB（压缩口径） |

#### 16.29.8 与现有功能的关系

| 功能 | 影响 |
| --- | --- |
| JS 源（`JsLoader`） | **不受影响**（不同 `Loader` 类） |
| Jar 源（`JarLoader`） | **不受影响** |
| CAT 源 | **不受影响** |
| Python 源（`PyLoader`） | 需装模块；未装时给引导 |
| Python 字幕脚本 | 同上 |
| 播放（EXO/MPV/IJK） | **不受影响** |
| 备份/恢复 | `files/chaquopy/` 同步规则保持 |

#### 16.29.9 最小实施步骤

| 步 | 内容 | 验证 |
| --- | --- | --- |
| 1 | 契约层支持 `assets/**` 载荷校验（`RuntimeFile` 载荷根 + `RuntimeModuleAssets`） | 单测 |
| 2 | `Platform.java` 改为从模块 context 读资产 + 按 `loadOrder` 加载库 | 真机：模块可用 |
| 3 | `PyLoader` / `PythonSubtitleScriptRuntime` 改惰性 + 可用性门禁 | 单测 + 真机 |
| 4 | `app/build.gradle` 排除 `chaquopy` 资产与 8 个库（`-PpythonBundle=true` 回滚） | 体积实测 |
| 5 | 插件仓库新增 `python` 模块定义 + 产物（2 ABI） | 产物摘要一致 |
| 6 | 端到端：装模块 → `.py` 源可用；卸载 → 引导且不崩 | 真机 |

#### 16.29.10 预计需要的验证

| 项 | 判据 |
| --- | --- |
| 未装模块启动 | 应用正常启动，加载 JS/Jar 配置成功，能播放 |
| 未装模块用 `.py` 源 | 给出模块引导，**不崩溃** |
| 装模块后 `.py` 源 | 与模块化前行为一致 |
| 库加载来源 | `/proc/self/maps` 显示来自模块目录 |
| 资产读取来源 | 模块 context 读到 `chaquopy/build.json` |
| 体积 | 宿主 APK 净减约 21 MB；模块产物约 21 MB |
| 回滚开关 | `-PpythonBundle=true` 恢复内嵌 |
| 既有单测 | runtime 156 项 + subtitle 70 项全绿 |

#### 16.29.11 回滚路径

| 层级 | 手段 |
| --- | --- |
| 构建期 | `-PpythonBundle=true` 恢复内嵌资产与库 |
| 运行期 | 模块缺失 → 引导（不崩），JS/Jar 源照常 |
| 发布期 | `recovery/PLG1-S13/<timestamp>` 标签 |
| 数据期 | 卸载模块不影响配置；`files/chaquopy/` 可保留 |

**建议：实施（方案 C）。** 两个技术前提（资产读取、无 SONAME 依赖）均已真机证实，
依赖次序与降级点也已定位。**等待用户明确批准。**

### 16.30 S14 已交付（2026-10-19）：DV5 渲染器降级验证，发现更大的可拆项

**范围**：重新实测 APK 构成（此前候选清单有遗漏），并验证新发现的最大单项能否模块化。

**新发现：`libexo_dovi_renderer.so` = 9.26 MiB，是剩余最大单项**（大于 Python 的 21 MB 之半）。
它不在原候选清单 §5.2 中，原因是清单按「上游对应」筛选，而该库是 WebHTV 自研。

**为什么它是优质候选**（三项条件均优于已完成的模块）：

| 条件 | `libexo_dovi_renderer.so` | 对比 |
| --- | --- | --- |
| 依赖 | 只依赖系统库（`libandroid`/`liblog`/`libmediandk`/`libvulkan`/`libdl`/`libm`/`libc`，`readelf` 实测） | 无需 `hostLibraries` 契约 |
| SONAME | **有** | 无 S10/S12 那类无 SONAME 依赖风险 |
| 源码 | 仓库内（`app/src/main/cpp/exo_dovi_renderer.cpp`，1251 行） | 可注入 resolver，同 IJK 模式 |
| 降级 | **已存在且是设计意图** | 见下 |

库之所以达 9 MB，是因为 `CMakeLists.txt` 把 libplacebo / shaderc / libdovi 作为
**IMPORTED STATIC** 静态链接进来，故自包含。

**降级链（实施前必须验证的前提）**：

```text
ExoDv5Native.loadLibrary()          catch Throwable -> LoadResult(false, "load-…")
  -> probe()                        -> Probe(available=false, …)
  -> ExoDv5GpuRendererFactory.create()  -> null
  -> ExoUtil.buildVideoRenderers()  catch (Throwable ignored) -> 不添加
  -> 随后无条件添加标准渲染器
```

模块化会把「库不可用」变成**常态**（未装模块的用户），所以硬依赖会让所有未装模块的用户
无法播放。降级链必须真实有效，而不只是读起来像。

**实验（`RuntimeDv5DegradationDeviceTest`，真机 OK (3 tests)）**：

| 用例 | 结果 |
| --- | --- |
| `probeReportsOutcomeInsteadOfThrowing` | ✅ probe 返回结果而非抛异常 |
| `factoryReturnsNullWhenRendererIsUnavailable` | ✅ 不可用或 null 的 probe 被拒绝 |
| `standardRenderersAreAddedRegardlessOfTheDv5Renderer` | ✅ 标准渲染器不受影响 |

**决定性证据**（设备日志）：

```text
hostShipsLibrary=true probeAvailable=false
```

即：库**存在且加载成功**，但 probe 报告**不可用**（模拟器缺 Vulkan 1.2 / AImageReader），
且是**返回结果而非抛异常**。这恰好演示了模块化后未装模块时的路径——
渲染器不可用，播放照常。

**过程中的自我纠错**：首轮 2 项失败，均为**测试假设错误**而非产品缺陷——
（1）我断言「宿主带库则 probe 应可用」，但模拟器缺 Vulkan，实际返回 `missing-capability`；
（2）`Probe` 是包私有 record，反射构造需 `setAccessible(true)`。
修正为断言**契约**（返回可识别的状态而非抛异常）而非断言设备能力。

**建议**：`libexo_dovi_renderer.so` 应作为**下一个模块化目标**，优先级高于 Python——
收益 9.26 MiB 且风险显著更低（无 `hostLibraries`、有 SONAME、降级链已验证）。
实施前仍需 §7 设计门禁与用户批准。

**累计真机验证**：74 项。

### 16.31 S15 设计门禁（2026-10-19）：DV5 渲染器模块化实施方案

> 本节是 `AGENTS.md` §7 要求的实施前门禁产物。**在获得明确批准前不得实施。**

#### 16.31.1 要实现的实际能力

把 `libexo_dovi_renderer.so`（9.26 MiB，剩余最大单项）从默认 APK 移入可选模块。

**用户可见效果**：

| 状态 | 效果 |
| --- | --- |
| 未装模块 | 杜比视界 **Profile 5** 片源走既有降级路径（`CODEC_TONE_MAP` / `LEGACY_HDR10_FALLBACK`），**仍可播放**，只是失去 GPU 映射路径 |
| 装模块 | 与现在完全一致：DV5 片源可用 GPU 映射（Vulkan + AImageReader + libplacebo） |

**这是可选增强而非核心能力**：`ExoDv5GpuMappingPolicy.decide()` 在 GPU 路径不可用时
已有多条降级路线，且 `ExoUtil` 用 `catch (Throwable ignored)` 包裹创建调用。
S14 已真机验证该降级链有效。

#### 16.31.2 当前项目已有实现

| 位置 | 现状 |
| --- | --- |
| `app/src/main/cpp/exo_dovi_renderer.cpp` | 1251 行，仓库内源码 |
| `app/src/main/cpp/CMakeLists.txt` | `add_library(exo_dovi_renderer SHARED …)`，静态链接 libplacebo/shaderc/libdovi |
| `ExoDv5Native.java` | 仓库内源码；`loadLibrary()` 用**裸名** `System.loadLibrary("exo_dovi_renderer")`，`catch Throwable` 返回 `LoadResult` |
| `ExoDv5Native.probe()` | 能力探测（6 项 capability 位），返回 `Probe` 而非抛异常 |
| `ExoDv5GpuRendererFactory.shouldCreate()` | 门禁：`probe.available()` 为 false 则不创建 |
| `ExoUtil.java:1144-1157` | `catch (Throwable ignored)` 后**无条件**添加标准渲染器 |
| `ExoDv5GpuMappingPolicy` | 降级路线：`NATIVE_DOLBY_VISION` → `CODEC_TONE_MAP` → `GPU_MAPPING` → `LEGACY_HDR10_FALLBACK` |

**引用面**：`ExoDv5Native` 共 40 处引用，集中在 5 个文件（`ExoDv5GpuRenderer`、
`ExoDv5GpuRendererFactory`、`ExoDv5Native`、`ExoDv5VideoSink` + 1 个单测），
无跨模块泄漏。

#### 16.31.3 涉及仓库及完整 commit ID

| 仓库 | 版本 | 说明 |
| --- | --- | --- |
| WebHTV（本仓库） | `d7e0dde8ee1a73c2e6038487981b5da512f22a6d` | 宿主侧改动 |
| `Silent1566/webhtv-plugins` | `a00fb34` | 新增 `dv5` 模块定义与产物 |
| libplacebo / shaderc / libdovi | 见 `third_party/exo-dv5-native/prebuilt` | **静态链接进库**，不单独打包 |

#### 16.31.4 最佳实践研究（证据）

| 来源 | 版本/日期 | 证据等级 | 支持的论断 | 对 WebHTV 的适用性 |
| --- | --- | --- | --- | --- |
| `readelf -d`（本仓库实测） | 2026-10-19 | 一手实测 | 只依赖系统库：`libandroid`/`liblog`/`libmediandk`/`libvulkan`/`libdl`/`libm`/`libc`；**有 SONAME** | **直接适用**：无需 `hostLibraries`；无 S10/S12 类风险 |
| WebHTV 实测 S14 | 2026-10-19 | 本仓库真机证据 | 降级链有效：库存在但 probe 报不可用（模拟器缺 Vulkan）时**返回结果而非抛异常** | **直接适用**：模块化前提已满足 |
| WebHTV 实测 S7 | 2026-10-19 | 本仓库真机证据 | 裸名查找在模块目录不可见，须注入 resolver | **直接适用**：`ExoDv5Native` 是仓库内源码，可注入 |
| WebHTV 实测 S2-d | 2026-10-19 | 本仓库真机证据 | 可用性查平台（`nativeLibraryDir != null`）而非注册表，侧载模块也真实可用 | **直接适用**：沿用同一模式 |
| Android 官方 `packaging.jniLibs.excludes` | AGP 9.x | 官方文档 | 按路径模式排除打包的 `.so` | **需实施时验证**：本库由 CMake 产出而非 jniLibs 源集，排除是否生效须实测 |
| libplacebo 官方文档 | — | — | 静态链接时须定义 `PL_STATIC` | 已在 CMakeLists 中，无需改动 |

**未能取得的证据**：上游 `FongMi/Release` 无 DV5 GPU 渲染器模块（该库是 WebHTV 自研），
故无上游对应实现可参照。本方案基于本仓库实测证据。

#### 16.31.5 方案对比

| 方案 | 做法 | 判定 |
| --- | --- | --- |
| **A. 不改** | 保持 9.26 MiB 内嵌 | 可行但放弃收益 |
| **B. 拆成独立模块（推荐）** | 库移入模块；`ExoDv5Native` 注入 resolver 指向模块目录；未装则 probe 报不可用 | **采用** |
| C. 改为运行时下载到 `filesDir` | 不走模块体系 | **否决**：绕过签名校验与摘要校验，等于新增 RCE 路径（与 §3 否决 `lab` 同因） |
| D. 从 CMake 中移除并依赖预编译 | 改为预编译 AAR | **否决**：无收益，且失去源码可重建性 |

#### 16.31.6 推荐方案（B）的设计

**1. 契约（模块侧 `assets/runtime.json`）**

```json
{
  "module": "dv5",
  "packageName": "com.silent.runtime.dv5",
  "compatibilityId": "exo-dv5-renderer-v1",
  "library": "lib/arm64-v8a/libexo_dovi_renderer.so",
  "loadOrder": ["lib/arm64-v8a/libexo_dovi_renderer.so"],
  "hostLibraries": {},
  "files": [{"path": "lib/arm64-v8a/libexo_dovi_renderer.so", "size": 9708848, "sha256": "…"}]
}
```

`hostLibraries` 为空（实测只依赖系统库）；`loadOrder` 单元素（无模块内互相依赖）。

**2. 新增 `RuntimeDv5`（复用 `RuntimeIjk` 模式）**

```java
public final class RuntimeDv5 {
    public static final String MODULE_ID = "dv5";
    public static final String MODULE_PACKAGE = "com.silent.runtime.dv5";
    public static final String LIBRARY = "exo_dovi_renderer";

    public static boolean isModuleInstalled(RuntimePlatform.PackageInfo p);
    public static Status install(RuntimePlatform.PackageInfo p);   // 注入 resolver
    public static void uninstall();
}
```

**3. `ExoDv5Native` 改造**

```java
// 现状：System.loadLibrary("exo_dovi_renderer");
// 改为：先问模块，再回退裸名（内嵌态），都失败则 LoadResult(false, …)
```

与 `IjkLibLoader` 的 resolver 模式一致：**每次查找都重新检查模块**，
使「装模块」与「开始播放」的先后顺序无关；失败返回 `LoadResult` 而非抛异常，
保持既有降级语义。

**4. `App.onCreate` 接入**

在现有 `RuntimeIjk.install(...)` 旁增加 `RuntimeDv5.install(...)`。

**5. 排除打包**

```groovy
def dv5Bundle = (project.findProperty('dv5Bundle') ?: 'false').toString().toBoolean()
if (!dv5Bundle) {
    packaging.jniLibs.excludes.add('**/libexo_dovi_renderer.so')
}
```

**关键未决点（S16 已实测解决）**：该库由 CMake 产出，`packaging.jniLibs.excludes`
是否对它生效——**实测生效**，见 §16.32。故无需 CMake 条件化。

#### 16.31.7 缺点与风险

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| ~~排除方式未验证~~ | **S16 已实测：生效**（CMake 产物同样受 `packaging.jniLibs.excludes` 约束） | 已解决，无需 CMake 条件化 |
| DV5 用户失去 GPU 路径 | 未装模块的 DV5 片源降级到 `CODEC_TONE_MAP`/`HDR10` | 这是**既有降级路径**，非新缺陷；给模块引导 |
| 模拟器无法验证正向路径 | 模拟器缺 Vulkan 1.2/AImageReader，probe 恒为 false | 正向验证需真机；模拟器只能验证降级与加载来源 |
| 模块体积 | 9.26 MiB（arm64） | 与宿主净减相当 |
| 与 `libdovi_android.so` 的关系 | 后者来自第三方 AAR `exoplayerhdrutils-0.4.0`（446 KB，DV7 用），**不是**本模块目标 | 明确区分，不混入 |

#### 16.31.8 与现有功能的关系

| 功能 | 影响 |
| --- | --- |
| EXO 普通播放 | **不受影响** |
| DV7 处理（`libdovi_android.so`） | **不受影响**（不同库） |
| DV5 原生杜比视界显示 | **不受影响**（走 `NATIVE_DOLBY_VISION`，不用本库） |
| DV5 GPU 映射 | 需装模块；未装则降级 |
| ASS 字幕（`libexo_ass.so`） | **不受影响** |
| MPV / IJK / sherpa / Python | **不受影响** |

#### 16.31.9 最小实施步骤

| 步 | 内容 | 验证 |
| --- | --- | --- |
| ~~1~~ | ~~实测排除是否生效~~ | ✅ S16 已完成：生效 |
| 2 | 新增 `RuntimeDv5` + 单测 | 单测 |
| 3 | `ExoDv5Native.loadLibrary()` 接模块 resolver | 真机：`/proc/self/maps` 来源 |
| 4 | `App.onCreate` 接入 | 真机 |
| 5 | 插件仓库新增 `dv5` 模块 + 产物（2 ABI） | 摘要一致 |
| 6 | 端到端：装模块 → probe 可用；卸载 → 降级不崩 | 真机 |

#### 16.31.10 预计需要的验证

| 项 | 判据 |
| --- | --- |
| 排除生效 | APK 中无 `libexo_dovi_renderer.so`；体积净减约 9.26 MiB |
| 未装模块 | probe 返回不可用（非抛异常）；DV5 片源走降级路径可播 |
| 装模块 | 库从模块目录加载（`/proc/self/maps`）；probe 在支持设备上可用 |
| 回滚开关 | `-Pdv5Bundle=true` 恢复内嵌 |
| 既有单测 | runtime 156 项 + `ExoDv5GpuRendererTest` 全绿 |
| 模块产物 | 2 ABI 产物生成且摘要与 catalog 一致 |

#### 16.31.11 回滚路径

| 层级 | 手段 |
| --- | --- |
| 构建期 | `-Pdv5Bundle=true` 恢复内嵌 |
| 运行期 | 模块缺失 → probe 不可用 → 既有降级路径（S14 已验证） |
| 发布期 | `recovery/PLG1-S15/<timestamp>` 标签 |
| 数据期 | 卸载模块不影响播放配置 |

**建议：实施（方案 B）。** 收益 9.26 MiB，风险显著低于 Python 模块化
（无 `hostLibraries`、有 SONAME、降级链已真机验证）。
唯一未决点是 CMake 产物的排除方式，实施第一步即可判定。
**等待用户明确批准。**

### 16.32 S16 已交付（2026-10-19）：CMake 产物排除实测，S15 唯一未决点已解决

**范围**：实测 `packaging.jniLibs.excludes` 对 **CMake 产出**的 `.so` 是否生效。
这是 S15 设计门禁里唯一的未决风险——若不生效，方案需改为 CMake 目标条件化。

**结论：生效。** 无需 CMake 条件化，S15 方案可直接实施。

**实测方法**（避免增量打包干扰，两次均**全量重打**）：

| 构建 | 命令 | APK |
| --- | --- | --- |
| 含 DV5 | `-Pdv5Bundle=true` | 118,951,446 B = **113.4 MiB** |
| 排除 DV5 | 默认（加 `excludes`） | 109,242,598 B = **104.2 MiB** |

**差值 = 9,708,848 B = 恰好等于 `libexo_dovi_renderer.so` 本身**（9,708,848 B）。
差值精确等于库体积，说明排除是**精确生效**的，没有连带影响其他内容。

**对照项（排除后仍在，证明未误伤）**：

| 库 | 状态 |
| --- | --- |
| `libavcodec.so` | 仍在（6,055,480） |
| `libc++_shared.so` | 仍在（1,374,336） |
| `libdovi_android.so` | 仍在（446,240，第三方 AAR，DV7 用） |
| `libexo_ass.so` | 仍在（2,775,600） |
| `assets/lab/libc++_shared.so` | 仍在（1,374,336） |

**过程中的自我纠正（重要）**：我最初把这个排除**写进了默认构建**，随后发现
`dv5` 模块产物**尚不存在**（`webhtv-plugins/dist` 只有 ijk/mpv/sherpa）。
那会让用户失去 DV5 能力却无处可装模块——是**能力倒退**。
已**完全回退** `app/build.gradle`（`git diff` 为空），本阶段只保留实测结论，
不改默认构建行为。实施时须与模块产物同步落地。

**同时纠正一个体积口径**：115.3 MiB 的旧基线含**增量打包残留**（S8 已记录此坑），
全量重打后含 DV5 的真实体积是 **113.4 MiB**。后续体积对比一律以全量重打为准。

**累计真机验证**：74 项（本阶段为构建期实测，不增加真机项）。

### 16.33 S17 已交付（2026-10-19）：forcetech 资产在 arm64 上不可加载

> **⚠ 本阶段结论已在 S20 修正。** S17 测得的事实成立（arm64 上该资产从不被加载），
> 但**推理不完整**：S17 只测到「裸名加载桩库」这一步，没有继续追查桩库之后的行为，
> 因而误判「v7a 也不需要该资产」。实际 v7a **需要**它。详见 §16.36。

**范围**：重新审查 S7 划定的「协议提取器不可模块化」边界时，发现其中一项与模块化无关的
**独立问题**——`assets/libmitv.so`（3.7 MB）在 arm64 构建里**根本无法加载**。

**S7 的结论仍然成立**（厂商代码按裸名加载 → 不能模块化）。本阶段发现的是**另一个问题**：
该资产不是「能不能模块化」，而是「**它有没有用**」。

**加载链（反汇编实证，非推测）**：

```text
Force.java（仓库内源码）
  -> Util.intent(context, scheme)          把 scheme 作为 "scheme" extra
  -> PxPService.onBind()                   取出 extra 传给 ForceTV.start(String, int)
  -> com.forcetech.android.ForceTV.start(String lib, int port)
       System.loadLibrary(lib)             ← 裸名，库名运行时传入
  -> com.gsoft.mitv.MainActivity.<init>()
       checkLibrary()                      Asset.open("libmitv.so") -> Path.copy -> Path.cache()
       System.loadLibrary("mitv")          ← 从 nativeLibraryDir 找，不是 cacheDir
```

**决定性证据（`RuntimeForcetechCacheCopyDeviceTest`，真机 OK (3 tests)）**：

```text
bareName=mitv outcome=failed: UnsatisfiedLinkError cacheCopyMapped=false cacheCopySize=3745900
asset=libmitv.so size=3745900 elfClass=1 machine=0x28 process64=true
```

即：复现厂商自己的流程（把资产拷到 `getCacheDir()`，再按裸名加载）后——

1. `System.loadLibrary("mitv")` **抛 `UnsatisfiedLinkError`**；
2. 拷贝出的副本**从未被映射**（`cacheCopyMapped=false`）。

**两条独立原因**（任一条都足以使该资产无用）：

| 原因 | 证据 |
| --- | --- |
| **架构不匹配** | 资产是 32 位 ARM（`elfClass=1 machine=0x28`），arm64 构建的进程是 64 位 |
| **位置不在搜索路径** | `loadLibrary` 只查 `nativeLibraryDir` 与系统路径；`cacheDir` 不在其中（与 S7 测得的同一机制） |

**ABI 对比（实测两个构建）**：

| 构建 | `lib/` 中的 mitv | `assets/libmitv.so` | 结论 |
| --- | --- | --- | --- |
| **arm64** | **无** | 3.7 MB（32 位） | 无法加载 |
| **v7a** | `lib/armeabi-v7a/libmitv.so`（54,548 B，32 位） | 3.7 MB（32 位） | `loadLibrary` 命中 **jni 版本**；**但 assets 版本并非无用——见 §16.36** |

两个 `libmitv.so` **哈希不同**（`e0fed3b5…` 54 KB vs `db429e31…` 3.7 MB）。
S17 当时据此推断「jni 版本才是被加载的那个，assets 版本只是白拷一次」——
**这个推断是错的**：两者是**不同用途的库**（桩库 vs 引擎），详见 §16.36。

**收益**：3.7 MB——大于候选清单里的 quickjs（1.3 MB）与 tflite（3.5 MB），
且**不需要模块化**（无需模块体系、无 `hostLibraries`、无降级设计）。

**与 S7 的关系**：S7 回答「这些库能否移出 APK 由模块提供」（否）；
本阶段回答「这个资产是否被使用」（否）。两者不冲突，且后者收益可直接落地。

**建议（S20 修正后）**：**仅从 arm64 构建**排除 `assets/libmitv.so`。
v7a **必须保留**——S17 当时认为「v7a 也无用」是错的。

**累计真机验证**：77 项。

### 16.34 S18 已交付（2026-10-19）：剩余候选量化与优先级排序

**范围**：对剩余可拆项做一次完整量化，并按「收益 ÷ 风险」排序，给出可执行的下一步建议。
纯评估，不改任何代码。

#### 16.34.1 精确测量（arm64 debug，全量重打基线 113.4 MiB）

| 候选 | 压缩后 | 占比 | 门禁状态 | 风险 |
| --- | --- | --- | --- | --- |
| Python/Chaquopy | 21,026,527 | 17.7% | ✅ S13 已就绪 | 中（需改 `Platform`/降级链） |
| DV5 渲染器 | 9,708,848 | 8.2% | ✅ S15 已就绪 | **低**（无 `hostLibraries`、有 SONAME、降级已验证） |
| thunder（S7 判定不可模块化） | 6,445,320 | 5.4% | ❌ 边界 | — |
| jianpian（S7 判定不可模块化） | 5,288,528 | 4.4% | ❌ 边界 | — |
| lut_presets（P7） | 4,283,610 | 3.6% | 未做 | 低（`LutStore.ASSET_DIR` 单点） |
| **forcetech 死重量** | **3,745,900** | **3.1%** | ✅ **S17 已取证** | **最低（无需模块化）** |
| tflite（P6 卡拉OK） | 3,549,376 | 3.0% | 未做 | 低（门禁已存在） |
| quickjs（P8） | 1,327,232 | 1.1% | 未做 | 低 |
| avif（P12） | 899,752 | 0.8% | 未做 | 低 |
| pdf（P11） | 454,695 | 0.4% | 未做 | 低 |

**新发现（不在原候选清单）**：

| 项 | 体积 | 说明 |
| --- | --- | --- |
| `libexo_dovi_renderer.so` | 9.26 MiB | S14 发现，S15 已出门禁，S16 已解决唯一未决点 |
| `assets/libmitv.so` | 3.57 MiB | S17 取证为死重量，**无需模块化** |
| `assets/lab/`（7zz + proot + libc++） | 2.67 MiB | 用户可见功能（`LabActivity` 等 4 个 Activity），**非死重量**，但是独立模块化候选 |

#### 16.34.2 系统性「死重量」扫描

S17 的方法（实测资产是否真的被使用）已应用到整个 APK：

| 检查 | 结果 |
| --- | --- |
| `assets/` 下全部 ELF 的 ABI 匹配 | **仅 `assets/libmitv.so` 不匹配**（ELF32/ARM vs 64 位进程） |
| `assets/lab/proot/loader32`（ELF32/ARM，1,439 B） | **有意设计**——`LabEnv:131` 注释明确「loader32 只存在于 arm64 包里」，供 32 位兼容层用 |
| 其余 assets ELF | 全部 AArch64，匹配 |

**结论**：S17 找到的 `libmitv.so` 是**唯一**的 ABI 型死重量。其余大项都是「真实功能但可选」。

#### 16.34.3 优先级建议（按收益 ÷ 风险）

| 序 | 项 | 收益 | 为何是这个顺序 |
| --- | --- | --- | --- |
| **1** | **forcetech 死重量** | 3.57 MiB | **无需模块体系**——不新增契约、不新增降级、不新增 UI。S17 已完整取证。唯一顾虑是裁剪第三方 AAR，需按 ABI 条件化 |
| **2** | **DV5 渲染器** | 9.26 MiB | 门禁已就绪（S15），唯一未决点已解决（S16）。风险低于 Python：无 `hostLibraries`、有 SONAME、降级链已真机验证 |
| **3** | **Python/Chaquopy** | 20.05 MiB | 收益最大，但工作量也最大：需改 `Platform` 资产定位、改加载次序为拓扑序、改 `PyLoader` 为惰性并加可用性门禁（S12 已证不改会崩） |
| 4 | lut_presets | 4.09 MiB | 单点引用，但属「用户可见预设包」，需产品判断是否值得让用户单独装 |
| 5 | tflite / quickjs / avif / pdf | 合计 5.9 MiB | 收益小；tflite 有现成门禁，quickjs 有上游对应 |

**为什么 1 排在 2 前面**：收益虽小，但**零架构成本**。它不依赖模块体系是否完善，
可以立即落地；而 2 与 3 都需要走完整的模块化流程（契约、产物、安装、降级、UI）。

#### 16.34.4 明确不建议做的项

| 项 | 理由 |
| --- | --- |
| thunder（6.4 MiB） | S7 已证：厂商二进制按裸名加载，不改厂商代码无法模块化 |
| jianpian（5.3 MiB） | 同上 |
| `assets/lab/`（2.67 MiB） | 是用户可见功能（4 个 Activity 已注册），且 `LabEnv` 1,419 行是成熟实现。模块化它属「另一个功能域」，超出播放核心模块化范围 |
| DEX（约 8.9 MiB） | §2.3 已否决 DexClassLoader 路线 |
| `libc++_shared.so`（1.37 MiB） | **必须留宿主**（§6.3，事故 commit `2a56751af0`） |

#### 16.34.5 当前累计成果

| 阶段 | 移除内容 | 实测减少 |
| --- | --- | --- |
| S1–S2 | mpv（9 库 + assets） | 44.6 MB |
| S3 | ijk（3 库） | 9.9 MB |
| S4 | sherpa（1 库） | 22.5 MB |
| S9–S17 | 可行性验证与死重量取证 | 待实施 |
| **累计** | | **77.7 MiB**（191.1 → 113.4 MiB） |

> **口径说明**：上表以 **MiB**（1 MiB = 1,048,576 B）为准，与 `ls` 显示一致。
> 基线 `-PmpvBundle=true` = 200,387,121 B = 191.1 MiB；当前 = 118,951,446 B = 113.4 MiB；
> 差值 = 81,435,675 B = **77.7 MiB**。分项之和为 77.0，与总差值相差 0.7，
> 差额来自各阶段构建期其他变动（如 S8 的 debug 专用资产口径修正），不逐项归因。
> 若按十进制 MB 计，则为 200.4 → 119.0 MB，差值 **81.4 MB**。两种口径不可混用。

**下一步建议**：先做 **forcetech 死重量移除**（零架构成本，3.57 MiB），
再做 **DV5 渲染器模块化**（9.26 MiB，门禁已就绪）。两者都不需要新的架构决策。

**等待用户批准。**

### 16.35 S19 已交付（2026-10-19）：forcetech 在 arm64 上整体失效，并发现既有缺陷

**范围**：S17 证明 `assets/libmitv.so` 从不被加载后，追问一个更大的问题——
**依赖它的提取器本身是否可用**。

**结论：arm64 构建上整个 forcetech 提取器不可用**，且这暴露了一个**既有缺陷**
（与模块化无关，但由本阶段发现）。

**结构性原因**：forcetech AAR 的原生库**只有 armeabi-v7a**：

| AAR 内路径 | 体积 |
| --- | --- |
| `jni/armeabi-v7a/libmitv.so` | 54,548 |
| `jni/armeabi-v7a/libp2p.so` | 787,324 |
| `jni/armeabi-v7a/libp3p.so` | 824,872 |
| `assets/libmitv.so` | 3,745,900 |

arm64 flavor 的 `abiFilters "arm64-v8a"`（`app/build.gradle:74`）把 `jni/armeabi-v7a/`
**全部过滤掉**，而 `assets/` 不受 `abiFilters` 约束，所以只有那个 32 位的
`assets/libmitv.so` 进了 arm64 APK。

**决定性证据（`RuntimeForcetechExtractorAvailabilityDeviceTest`，真机 OK (3 tests)）**：

```text
nativeLibraryDir=/data/app/com.silent.android.webhtv-.../lib/arm64 present=[]
bareNameLookups p2p=UnsatisfiedLinkError p3p=UnsatisfiedLinkError mitv=UnsatisfiedLinkError
```

即：arm64 上三个库**全部缺失**，三个裸名查找**全部失败**。
而 `Force` 的加载路径正是这三条裸名查找（`P2PService` → `p2p`、`P3PService` → `p3p`、
`MainActivity` → `mitv`）。

**两个不同性质的结论**：

| 结论 | 性质 |
| --- | --- |
| 3.7 MB 资产可**无损失移除** | 本阶段要支持的结论 |
| arm64 上 forcetech 提取器**已不可用** | **既有缺陷**，独立于本阶段 |

**既有缺陷（只报告，不修）**：`Source.java:42` **无条件**注册 `new Force()`，
而 arm64 上它必然失败。用户遇到 `p2p://`/`mitv://` 源时会走失败路径，
而不是「该能力不可用」的明确提示。这与 S6 修好的「内核选择器无条件列出全部内核」
是同一类问题（注册与可用性脱节），但**不在本任务范围内**，按 `AGENTS.md` §2 只报告。

**对移除方案的影响**：移除 `assets/libmitv.so` 在 arm64 上**零能力损失**
（它从未被加载，且其消费方也已失效）。实施时**仅对 arm64 排除**，v7a 保持现状。

> **⚠ 本阶段末句的推理已在 S20 修正。** S19 写「v7a 的 assets 版本虽同样未被加载」——
> 这句是**错的**，它沿用了 S17 的不完整推理。实际 v7a **需要**该资产（桩库会加载它）。
> 详见 §16.36。

**累计真机验证**：80 项。

### 16.36 S20 已交付（2026-10-19）：arm64 排除落地，并修正 S17/S19 的错误结论

**范围**：实施 forcetech 资产移除（仅 arm64），并在实施过程中发现并修正
S17/S19 的**实质性推理错误**。

#### 16.36.1 修正的错误

S17 测得的事实**成立**（arm64 上该资产从不被裸名加载），但**推理不完整**：

S17 只追到「`System.loadLibrary("mitv")` 加载 jni 版本的桩库」这一步就停了，
据此推断「assets 版本只是白拷一次，v7a 也不需要它」。
**没有继续追查桩库之后的行为**。

S20 用符号表与反汇编补上了这一步，两个同名文件其实是**不同用途的库**：

| 文件 | 体积 | 性质 | 导出符号 |
| --- | --- | --- | --- |
| `jni/armeabi-v7a/libmitv.so` | 54,548 | **加载器桩** | `Java_com_gsoft_mitv_MainActivity_loadLibrary`、`decrypt`、`encrypt` |
| `assets/libmitv.so` | 3,745,900 | **真正的引擎** | `Java_com_forcetech_android_ForceTV_start/stop`（SONAME `libforcetv.so`） |

桩库的字符串与符号给出了完整链条：

```text
% s/libmitv.so          "Failed to open libmitv.so"
res/raw/libmitv.so       "Failed to extract libmitv.so"
com.gsoft.mitv.zip
getCacheDir / getPackageCodePath / System.load / unzip / zdecompress / aes_decrypt
```

即桩库会读 `<cacheDir>/libmitv.so`（正是 `checkLibrary()` 从 assets 拷来的那份）
并 `System.load` 它。而 `com.forcetech.android.ForceTV` 声明的 native 方法
（`start(int,int)`/`stop()`）**恰好由那个 3.7 MB 引擎导出**。

**因此**：

| 构建 | 桩库在否 | 引擎资产用途 | 可否移除 |
| --- | --- | --- | --- |
| **arm64** | **否**（被 `abiFilters` 过滤） | 无人能加载它 | ✅ 可移除 |
| **v7a** | **是** | **桩库加载的目标** | ❌ **必须保留** |

S17 的「v7a 也无用」是错的。所幸 S17 的建议本身就写了「仅 arm64 排除，v7a 不冒险」，
所以**错误结论没有导致错误实施**。

#### 16.36.2 实施与验证

**实现**：`app/build.gradle` 的 `androidComponents` 中按 ABI 条件化排除。

**过程中发现的第二个陷阱（重要）**：最初把 `androidResources { }` 写在
`productFlavors.arm64_v8a` 块里，结果**全局生效**（v7a 也丢了资产）。
原因：AGP 9.2.1 中 `ignoreAssetsPatterns` 只声明在
`com.android.build.api.dsl.AndroidResources`（`android` 块级）与
`com.android.build.api.variant.AndroidResources`（**Variant API**）上，
**不在 `ProductFlavor` 上**。写在 flavor 块里时 Groovy 闭包回退到外层 `android` 块。

已改用 Variant API：

```groovy
onVariants(selector().all()) { variant ->
    def abi = variant.productFlavors.find { it.first == 'abi' }?.second
    if (abi == 'arm64_v8a') {
        variant.androidResources.ignoreAssetsPatterns.add('libmitv.so')
    }
}
```

**验证（全量重打两个 ABI）**：

| 构建 | 体积 | `assets/libmitv.so` | `lib/…/libmitv.so` |
| --- | --- | --- | --- |
| **arm64** | 113.4 → **109.9 MiB** | **已排除** ✅ | 本来就无 |
| **v7a** | **97.9 MiB**（= 原始基线） | **保留** ✅ | **保留** ✅ |

**真机验证（arm64，9 项全绿）**：

| 测试类 | 结果 |
| --- | --- |
| `RuntimeForcetechAbiDeviceTest` | OK (3 tests) |
| `RuntimeForcetechCacheCopyDeviceTest` | OK (3 tests) |
| `RuntimeForcetechExtractorAvailabilityDeviceTest` | OK (3 tests) |

**过时断言的处置**：三个测试类中依赖「资产存在」的断言已按新事实修正——
改为在该前提不成立时 `assumeTrue` 跳过（而非删除），
因为它们仍是 arm64 可移除性的证据，且可对 v7a 构建重跑。
其中 `theAssetIsStillPackaged` **反转**为 `theAssetIsExcludedFromTheArm64Build`，
以便区分「资产因我们的移除而不在」与「资产因其他原因不在」。

**收益**：arm64 **-3.57 MiB**（113.4 → 109.9 MiB）；v7a 无变化。

**教训（值得记录）**：追查「某文件是否被使用」时，只测到**第一层加载**就下结论是危险的。
`loadLibrary("mitv")` 成功加载的是一个**加载器**，而加载器加载的东西才是真正在用的。
判据应该是「这个文件的**内容**是否被消费」，而不是「这个文件名是否出现在某次加载调用里」。

**累计真机验证**：89 项。

### 16.37 S21 已交付（2026-10-19）：全包回归 + 修正测试隔离缺陷

**范围**：S20 改的是**打包内容**（移除一个资产），但当时只跑了 forcetech 专项测试——
这不构成「APK 仍正常工作」的证据。本阶段补上这个决定性检查，并修正过程中暴露的缺陷。

**全包回归（真机 arm64，78 项）**：

```text
OK (78 tests)   —— 18 个测试类，覆盖 runtime 包全部
```

这确认 S20 的资产移除**没有破坏任何东西**。

**过程中发现的测试隔离缺陷（我 S12 引入）**：

首轮全包运行报 1 个失败：

```text
preloadingTheDependencyMakesTheDependentLoadable
  expected: .../files/s1[2-python-order]
  but was:  .../files/s1[0-soname-lib]
```

**不是产品回归**，是测试缺陷：一个进程只能映射一个库一次，而
`RuntimeNoSonameDependencyDeviceTest`（S10）与 `RuntimePythonLoadOrderDeviceTest`（S12）
**都用 `libcrypto_python.so`**。谁先跑谁拥有该映射，后跑的那个断言必然失败。

S12 当时只**单独跑方法**（`-e class ...#method`），所以从未暴露。

**修正**：给三个受影响的测试补上前提守卫（`assumeTrue`），
使「前提不成立」表现为**跳过**而非**假失败**：

| 测试类 | 补守卫的方法 |
| --- | --- |
| `RuntimePythonLoadOrderDeviceTest` | `preloadingTheDependencyMakesTheDependentLoadable` |
| `RuntimeNoSonameDependencyDeviceTest` | `preloadedLibraryWithSonameSatisfiesDependency`、`preloadedLibraryWithoutSonameSatisfiesDependency` |

**为什么用 `assumeTrue` 而不是删除或改断言**：这些测试的价值在于「单独运行时可观察到
具体机制」（S10/S12 的结论正是这样得出的）。守卫保留了该能力，同时让它们在
全包运行时诚实跳过，而不是报一个与主题无关的失败。

**教训（与 S20 的教训同类）**：**验证的范围必须匹配改动的范围**。
S20 改的是打包内容，却只跑了与该资产直接相关的测试；
而 S20 之前的 S12 改的是测试代码，却只单独跑方法。
两次都是「验证范围窄于改动范围」，两次都靠**扩大验证范围**才发现问题。

**真机验证口径修正**：此前各阶段累计写「89 项」，那是**逐次运行的累加**，
不是独立的测试集合。runtime 包**实际共 78 个测试方法**（18 个类），
本阶段的全包运行 `OK (78 tests)` 即为**全部**。
后续统一以「runtime 包 78 项全绿」为口径，不再逐阶段累加。

### 16.38 S22 已交付（2026-10-19）：DV5 宿主侧接入 + 发现 CI 构建缺口

**范围**：实施 DV5 渲染器模块化的**宿主侧**部分（契约、resolver、接入、单测），
并核实插件仓库能否产出该模块。**本阶段不加构建期排除**——理由见下。

#### 16.38.1 已实施（宿主侧）

| 文件 | 改动 |
| --- | --- |
| `RuntimeCompatibility.java` | 白名单加 `exo-dv5-renderer-v1`；包名表加 `dv5 → com.silent.runtime.dv5` |
| `RuntimeDv5.java`（新增） | 复用 `RuntimeIjk` 的 resolver 模式：`install`/`uninstall`/`resolve`/`isAvailable` |
| `ExoDv5Native.java` | `loadLibrary()` 先问模块（`System.load` 绝对路径），再回退裸名 |
| `App.java` | `onCreate` 接入 `RuntimeDv5.install(...)` |
| `RuntimeDv5Test.java`（新增） | 10 项单测 |

**关键设计点（与 IJK 的差异）**：DV5 渲染器是**可选增强**而非播放引擎。
`ExoDv5GpuMappingPolicy` 已有多条降级路线，`ExoUtil` 用 `catch (Throwable ignored)` 包裹。
因此 resolver 在模块缺失时**返回 null 而非抛异常**，让调用方回退到裸名加载。
模块存在但加载失败时也**回退**——「装了模块反而更差」是不可接受的。

**验证**：

| 项 | 结果 |
| --- | --- |
| `RuntimeDv5Test` | **10 项全绿**（tests=10, failures=0, errors=0, skipped=0） |
| 全包真机回归 | **OK (78 tests)** |
| 未装模块时行为 | `hostShipsLibrary=true probeAvailable=false` —— 正确回退到内嵌库 |

**本阶段不加构建期排除**：`dv5` 模块产物**尚不存在**（`webhtv-plugins/dist` 只有
ijk/mpv/sherpa）。若此时排除，用户会失去 DV5 能力却无处可装——正是 S16 踩过的坑。
排除必须与产物**同步落地**。

#### 16.38.2 发现的 CI 构建缺口（重要）

核实插件仓库能否产出 `dv5` 模块时发现：**现有 CI 路径不适用于 dv5**。

| 模块 | 库的来源 | CI 克隆 WebHTV 后可否打包 |
| --- | --- | --- |
| sherpa | `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so`（**git 跟踪**） | ✅ |
| mpv | `app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libmpv.so`（**git 跟踪**） | ✅ |
| ijk | `app/src/main/jniLibs/arm64-v8a/libijkffmpeg.so`（**git 跟踪**） | ✅ |
| **dv5** | **CMake 从 `app/src/main/cpp/exo_dovi_renderer.cpp` + `third_party/exo-dv5-native/prebuilt/*.a` 构建** | ❌ **无跟踪的 `.so` 副本** |

即：前三个模块的库都在 git 跟踪路径下，CI 克隆源码即可打包；而
`libexo_dovi_renderer.so` 是**构建产物**，仓库里只有 `.cpp` 源码与静态库。

**已实测的可行路径**：CMake 可脱离 Gradle 独立构建该库。

| 步骤 | 命令 | 实测结果 |
| --- | --- | --- |
| 配置 | `cmake -S app/src/main/cpp -G Ninja -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26` | ✅ 成功 |
| 构建 | `cmake --build <dir> --target exo_dovi_renderer` | ✅ 产出 `libexo_dovi_renderer.so` |
| strip | `llvm-strip --strip-unneeded` | ✅ 21.27 → 9.14 MiB |

**产物可比性验证**（决定 CI 方案是否可靠）：

| 项 | 独立 CMake 构建 | Gradle 构建 |
| --- | --- | --- |
| SONAME | `libexo_dovi_renderer.so` | `libexo_dovi_renderer.so` |
| DT_NEEDED | 7 个系统库 | 7 个系统库（**完全一致**） |

产物**不逐字节一致**（9.14 vs 9.26 MiB，NDK 28.2 vs 29.0 及 strip 参数差异），
这不影响正确性：模块产物只需**自身摘要**与 catalog 一致，无需与宿主产物相同。

**待定方案（需在插件仓库侧实施）**：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A** | CI 中增加 CMake 构建步骤（静态库已跟踪，104 MB） | **推荐**：无需入库 `.so`，与宿主源码同源 |
| B | 把 `.so` 提交到跟踪路径（如 mpv 做法） | 需入库 ~16.5 MB（9.26 + 6.82 MiB），且与源码可能失同步 |
| C | 手工构建后手动上传 Release | 不可复现，与现有 CI 校验（摘要/大小）冲突 |

方案 A 的 CI 改动属**插件仓库**范围，需单独授权。

**累计真机验证**：runtime 包 78 项全绿。

### 16.39 S23 已交付（2026-10-19）：DV5 构建期排除 + 正向路径验证

**范围**：产物已就绪（插件仓库 `972d606`）后，实施宿主侧构建期排除，
并完成正向路径（装模块）与降级路径（未装模块）的双向验证。

#### 16.39.1 实施

`app/build.gradle` 加排除，带回滚开关：

```groovy
def dv5Bundle = (project.findProperty('dv5Bundle') ?: 'false').toString().toBoolean()
if (!dv5Bundle) {
    packaging.jniLibs.excludes.add('**/libexo_dovi_renderer.so')
}
```

S16 已实测 `packaging.jniLibs.excludes` 对 **CMake 产物**同样生效。

#### 16.39.2 体积实测（全量重打，A/B 对比）

| 构建 | 含 DV5 | 排除 DV5 | 差值 |
| --- | --- | --- | --- |
| arm64 | 115,206,636 B | 105,497,770 B | **9,708,866 B** |
| v7a | — | 95,788,069 B（原 105.5 MB 级） | 同量级 |

**差值归因（逐条对比，非估算）**：

| 项 | 字节 |
| --- | --- |
| `lib/arm64-v8a/libexo_dovi_renderer.so` | 9,708,848 |
| `classes17.dex` 微差 | +1 |
| `classes20.dex` 微差 | +17 |
| **合计** | **9,708,866** |

那 18 字节**不是排除引起的**：同一 flag 重复构建时 dex 也会变
（实测 115,206,645 vs 115,206,636），属既有构建非确定性。
除该库外，两个 APK 的条目集合与其余条目大小**完全一致**。

**对照项（排除后仍在，证明未误伤）**：`libavcodec.so`、`libc++_shared.so`、
`libexo_ass.so`、`assets/lab/7zz`、`assets/lab/libc++_shared.so`，
以及 DV7 用的 `libdovi_android.so`（arm64 446,240 / v7a 336,208）。

#### 16.39.3 双向真机验证

| 场景 | 结果 |
| --- | --- |
| **未装模块**（库已排除） | `OK (82 tests)`；证据 `hostShipsLibrary=false probeAvailable=false` |
| **装模块** | `OK (4 tests)`；证据 `moduleInstalled=true probeAvailable=false reason=missing-capability` |
| **零模块态**（全新安装） | `OK (82 tests)`，`RuntimeNoModuleDeviceTest` 10 项全部执行 |

**关键证据解读**：装模块后 `reason=missing-capability` 而非 `load-*`，
说明库**成功加载**（模拟器缺 Vulkan 1.2 / AImageReader 所以能力探测不通过）。
这正是 S14 建立的降级语义：库能加载、能力不足时返回结果而非抛异常。

**新增测试类** `RuntimeDv5ModuleLoadDeviceTest`（4 项）：

| 用例 | 断言 |
| --- | --- |
| `resolverPointsAtTheModuleFile` | resolver 指向模块文件 |
| `loadingTheResolvedFileMapsItFromTheModuleDirectory` | **映射来源**是模块目录（非仅「加载成功」） |
| `probeReportsAnOutcomeWithTheModuleInstalled` | probe 返回可识别状态 |
| `hostDoesNotShipItsOwnCopy` | 宿主不再自带副本（防止未来回归） |

断言**映射来源**而非加载成败：宿主已不带副本，成功只可能来自模块，
但显式检查来源能捕获「未来重新引入内嵌副本」的回归。

#### 16.39.4 修正的测试覆盖缺口

`RuntimeNoModuleDeviceTest` 的模块列表**硬编码**且缺 `dv5`：

```java
private static final String[] MODULE_PACKAGES = {
        "com.silent.runtime.mpv", "com.silent.runtime.ijk", "com.silent.runtime.sherpa",
};   // ← 缺 dv5
```

后果：该类的「全新安装态」检查遍历此列表，**装着 dv5 模块时也会通过**——
一个静默失效的测试。已补 `dv5`，并在 `everyKnownModuleIdHasATrustedPackageName`
处加注释说明「此列表须与 `RuntimeCompatibility.PACKAGES` 同步，
遗漏会让上述检查在模块已装时仍然通过」。

这是本阶段除体积收益外**更有价值的产出**：一个会静默失效的测试比没有测试更危险。

**累计真机验证**：runtime 包 **82 项**（78 + 4 新增）。

### 16.40 S24 已交付（2026-10-19）：Python 第 1 步——契约层支持 assets 载荷校验

**范围**：S13 门禁六步计划的**第 1 步**——让契约层能校验「非原生库」载荷。
这是 Python 模块化的前置条件：Chaquopy 的主体是 `assets/chaquopy/**` 树，
而现有 `verifyFiles` 以 `nativeLibraryDir` 为根，**覆盖不到 `assets/**`**。

#### 16.40.1 实施

| 文件 | 改动 |
| --- | --- |
| `RuntimeIntegrity.java` | 新增 `sha256(InputStream)`——载荷在 APK 内，不能整份读入内存 |
| `RuntimeManifest.java` | 新增 `isNativeLibraryPath(path)`：按载荷根分类 |
| `RuntimeVerifier.java` | 新增 `PayloadReader` 缝；`verifyFiles` 按前缀分派到库校验/载荷校验 |
| `RuntimeCompatibility.java` | 白名单加 `chaquopy-python310-v1`；包名表加 `python` |

**`PayloadReader` 缝的设计**：载荷在模块 APK 内，只能经 `AssetManager` 或 zip 读取。
把它做成注入接口，使校验逻辑保持**无 Android API 依赖**，可在纯 JVM 单测。
这与既有的 `HostLibraries`、`ManifestSource` 是同一模式。

**关键安全决定（失败关闭）**：清单声明了载荷但**没有可用 reader** 时，
**拒绝**而非接受。接受等于让未校验的载荷进入加载器——与「失败关闭」的目的相反。
这也意味着「不能读模块资产的宿主构建」无法被诱导加载载荷。

**向后兼容**：新增 `verify`/`verifyInstalled` 的重载（多一个 `payloads` 参数），
原有签名保留并委托给新重载。既有的 `lib/**` 型模块**无需 reader**。

#### 16.40.2 验证

新增 `RuntimePayloadVerificationTest`（**10 项**）：

| 用例 | 断言 |
| --- | --- |
| `acceptsAModuleWhosePayloadMatches` | 载荷摘要匹配则通过 |
| `rejectsAPayloadWhoseDigestDoesNotMatch` | **摘要不符则拒绝** |
| `rejectsAMissingPayload` | 载荷缺失则拒绝 |
| `rejectsAPayloadWhenNoReaderIsAvailable` | **无 reader 时拒绝而非接受** |
| `reportsAReaderFailureAsAProblem` | reader 抛异常→报告为问题而非传播 |
| `acceptsALibraryOnlyModuleWithoutAReader` | 向后兼容：纯库模块无需 reader |
| `classifiesPathsByPayloadRoot` | 路径分类正确 |
| `rejectsAPayloadPathWithTraversal` | 路径穿越被拒 |
| `rejectsAnAbsolutePayloadPath` | 绝对路径被拒 |
| `streamDigestMatchesFileDigest` | 流式摘要与文件摘要一致 |

**单测**：runtime 包 **176 项全绿**（含新增 10 项）。

**过程中的顺序依赖（已修正）**：首轮 5 项失败，原因是
`chaquopy-python310-v1` **尚未加入契约白名单**——校验在载荷检查之前先做契约检查，
所以白名单必须先加。这印证了 S13 门禁把「契约层扩展」列为第 1 步的必要性。

**累计真机验证**：runtime 包 82 项（本阶段为纯 JVM 单测，不增加真机项）。

### 16.41 S25 已交付（2026-10-19）：Python 第 2 步——Platform 从模块读资产、按拓扑序加载

**范围**：S13 门禁六步计划的**第 2 步**——让 Chaquopy 的 `Platform` 能从模块读取资产
并按 manifest 声明的拓扑序加载库。

#### 16.41.1 关键约束（决定了设计）

**约束 1：`chaquo` 模块不能引用 app 的 `runtime` 包。**
`settings.gradle` 中 `:app` 依赖 `:chaquo`，反向引用会形成循环依赖。
故在 `chaquo` 侧定义注入缝 `Platform.RuntimeModule`，由宿主在启动时安装实现——
与既有的 `HostLibraries` / `ManifestSource` 同一模式。

**约束 2：宿主无法写模块的数据目录。**
Chaquopy 的 `initialize(context, …)` 用**同一个 context** 决定四件事
（`importer.py` 实测）：

| context 用途 | 能否用模块 context |
| --- | --- |
| `getAssets()` 读 `chaquopy/**` | **需要**——资产在模块 APK 内 |
| `getFilesDir()` 解压根（需可写） | **不能**——模块数据目录宿主不可写 |
| `getSharedPreferences()` 解压记账 | **不能**——同上 |
| `getApplicationInfo().nativeLibraryDir` | 无关——实测**赋值后从未被读取** |

因此用 `ContextWrapper` **只覆盖 `getAssets()`**，其余仍走宿主。
若整体换成模块 context，解压会因权限失败。

**约束 3：库的加载次序是拓扑序，不是 Chaquopy 的原序。**
S12 实测的依赖图与 `Platform.loadNativeLibs()` 的原序不一致
（`crypto_chaquopy` 在 `crypto_python` 之前等）。裸名时靠搜索路径兜住，
绝对路径加载则必须依赖已映射，故 manifest 必须声明拓扑序。

#### 16.41.2 实施

| 文件 | 改动 |
| --- | --- |
| `chaquo/Platform.java` | 新增 `RuntimeModule` 缝（`assetContext()` / `nativeLibraryPaths()`）；`am` 改从缝取；`loadNativeLibs()` 优先按声明路径 `System.load`，无模块时回退原裸名序列 |
| `RuntimePython.java`（新增） | 校验模块 → 解析库路径 → 用 `createPackageContext` 取模块 assets → 安装 `ContextWrapper` provider |
| `App.java` | `onCreate` 接入 `RuntimePython.install(...)`（须在触碰 `com.chaquo.python` 之前） |

**`createPackageContext` 而非 `addAssetPath`**：后者是隐藏 API 且**不可逆地**
改动进程级 `AssetManager`，模块卸载后难以恢复（S9/S11 已论证）。

**纯函数抽取**：`resolveLibraryPaths(libraryDir, manifest)` 不依赖 Android API，
使次序规则可在纯 JVM 单测——与 `RuntimeSherpa.prepare` 同一做法。

#### 16.41.3 验证

新增 `RuntimePythonTest`（**10 项**）：

| 用例 | 断言 |
| --- | --- |
| `resolvesEveryLibraryInTheDeclaredOrder` | 8 个库按声明次序解析 |
| `preservesTheDeclaredOrderRatherThanSorting` | **不重排**——只有 manifest 知道依赖图 |
| `failsWhenALibraryIsMissing` | 缺库则失败 |
| `failsWithoutALibraryDirectory` | 目录不可用则失败 |
| `failsWithoutAManifest` | 清单不可用则失败 |
| `acceptsASingleLibraryWithoutALoadOrder` | 单库模块无需显式 loadOrder |
| `availabilityIsTrueWhenEitherSourceExists` | 可用性规则 |
| `moduleIsInstalledOnlyWhenItsDirectoryExists` | 查平台而非注册表 |
| `contractAndPackageNameAreRegistered` | 契约与包名已登记 |
| `uninstallClearsTheProvider` | 卸载后恢复内嵌行为 |

**单测**：runtime 包 **186 项全绿**（含新增 10 项）。
全仓库 5263 项中 7 项失败，**均为既有失败**（`MpvFontConfigTest`、
`MpvHlsCacheCoordinatorTest`、`ReaderPlaybackRoutingSourceTest`×2、
`TmdbUIAdapterTest`、`TmdbSourceOnlyInteractionTest`、`WebThemeTokenSourceTest`），
与 PLG1 无关，按 `AGENTS.md` §2 只报告不修。

#### 16.41.4 发现的 CI 缺口（与 DV5 同类但更重）

Python 的 8 个库与 `assets/chaquopy/**` **全部是构建产物**（`git ls-files` 确认未跟踪），
由 Chaquopy Gradle 插件产出，依赖：

- 本机 Python 3.10（`local.properties` 的 `chaquopyBuildPython=G:/Python310/python.exe`）
- `pip install -r requirements.txt`（lxml / ujson / pyquery / requests / certifi /
  cachetools / pycryptodome / beautifulsoup4）——**需要网络**

即插件仓库的 CI 若要产出 `python` 模块，需在构建机上准备 Python 3.10 与 pip 网络访问，
比 DV5 的 CMake 缺口更重。**待定方案**（需单独授权）：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A** | CI 增加 Python 3.10 + pip 步骤，再跑 Chaquopy 构建 | **推荐**：与宿主同源，无签入二进制 |
| B | 把 8 个库与 `assets/chaquopy/**` 提交到跟踪路径 | 需入库约 21 MB；且与 Chaquopy 版本可能失同步 |
| C | 手工构建后手动上传 | 不可复现，与 CI 的摘要校验冲突 |

**累计真机验证**：runtime 包 82 项（本阶段为纯 JVM 单测，不增加真机项）。

### 16.42 后续阶段

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| **S0-a** | 模块契约层 + 单测 | ✅ 已交付 |
| **S0-b** | 仓库 / 安装器 / 加载器 / 平台适配 | ✅ 已交付 |
| **S0-c** | P2 sherpa 门禁 + 控制器接入 | ✅ 已交付 |
| **S0-d** | 新仓库 `webhtv-plugins` 骨架 + 打包脚本 + CI + 真机验证 | ✅ 已交付（**待推送**） |
| **S0-e** | 端到端验证 SDK 从模块目录加载 | ✅ 已交付 |
| **S0-f** | 模块管理页（设置 → 增强功能 挂载）+ 卸载入口 | ✅ 已交付 |
| **S0-g** | 推送 `webhtv-plugins` + 已发布产物端到端验证 | ✅ 已交付 |
| **S1** | mpv 播放核心模块化（9 库 + `hostLibraries` 契约） | ✅ 已交付 |
| **S0-h** | UI 侧安装联调（`PackageInstaller` 会话端到端） | ✅ 已交付 |
| **S1-b** | mpv 真实 dlopen + `/proc/self/maps` 取证 | ✅ 已交付 |
| **S2-a** | 内核可用性接入模块（为移除 assets 铺路） | ✅ 已交付 |
| **S2-b-前置** | mpv 模块功能验证（核心初始化 + 属性读取） | ✅ 已交付 |
| **S2-c** | 真实播放回归（解复用 + H.264 解码 + 首帧输出） | ✅ 已交付 |
| **S2-b** | 移除宿主 `assets/mpv-libs` | ✅ 已交付（实测 -44.6 MB） |
| **S2-d** | 可用性改查平台（修复侧载模块不可用）+ S2-b 设备回归 | ✅ 已交付 |
| **S3** | IJK 内核模块化（3 库 + resolver 注入） | ✅ 已交付 |
| **S3-b** | 移除宿主 IJK 库 | ✅ 已交付（实测 -9.9 MB） |
| **S4** | 移除宿主 sherpa 库 + 修复侧载模块不可加载 | ✅ 已交付（实测 -22.5 MB） |
| **S5** | 零模块状态验证（新用户初始状态） | ✅ 已交付 |
| **S6** | 内核选择器按可用性过滤 + 修复对比度缺陷 | ✅ 已交付 |
| **S6-b** | 内核过滤的真机验证 | ✅ 已交付 |
| **S7** | 预加载去重实验（划定模块化边界） | ✅ 已交付 |
| **S8** | 体积口径修正（debug 专用资产） | ✅ 已交付 |
| **S9** | 资产重定向实验（Python 模块化可行性） | ✅ 已交付 |
| **S10** | 无 SONAME 依赖实验（Python 模块化可行性） | ✅ 已交付 |
| **S11** | 模块资产读取机制对比（选定公开 API） | ✅ 已交付 |
| **S12** | Python 加载次序实验（契约须拓扑排序） | ✅ 已交付 |
| **S13** | Python 模块化设计门禁（待批准） | ⏸ 待批准 |
| **S14** | DV5 渲染器降级验证（发现更大可拆项） | ✅ 已交付 |
| **S15** | DV5 模块化设计门禁（待批准） | ⏸ 待批准 |
| **S16** | CMake 产物排除实测（解决 S15 未决点） | ✅ 已交付 |
| **S17** | forcetech 资产死重量取证（3.7 MB 可移除） | ✅ 已交付 |
| **S18** | 剩余候选量化与优先级排序（评估） | ✅ 已交付 |
| **S19** | forcetech 在 arm64 整体失效取证（含既有缺陷） | ✅ 已交付 |
| **S20** | arm64 排除落地 + 修正 S17/S19 错误结论 | ✅ 已交付 |
| **S21** | 全包回归（78 项）+ 修正测试隔离缺陷 | ✅ 已交付 |
| **S22** | DV5 宿主侧接入 + 发现 CI 构建缺口 | ✅ 已交付 |
| **S23** | DV5 构建期排除 + 双向验证（82 项） | ✅ 已交付 |
| **S24** | Python 第 1 步：契约层支持 assets 载荷校验 | ✅ 已交付 |
| **S25** | Python 第 2 步：Platform 从模块读资产 + 拓扑序加载 | ✅ 已交付 |
| S1–S5 | P1 mpv / P4 ijk / P3 python / P6 tflite / P7 lut / P8 quickjs | 待做 |

> 每阶段独立 `task_guard.sh` 会话 + 原子提交 + recovery tag。
>
> **S2-b 前置条件已满足**：S1-b 证明 9 库能真实 dlopen 且映射来源正确；
> S2-a 已把内核可用性接到模块状态上，且 19 个既有调用点自动生效。
>
> **S2-b 前置全部满足**：
>
> 1. ✅ 契约层与安装/加载/校验（S0-a/S0-b）
> 2. ✅ 9 库真实 dlopen 且映射来源正确、无 soname 冲突（S1-b）
> 3. ✅ 内核可用性已接模块状态，19 个既有调用点自动生效（S2-a）
> 4. ✅ 模块能初始化真实 mpv 核心并读出 `mpv-version`/`ffmpeg-version`（S2-b-前置）
> 5. ✅ 真实媒体可解复用、H.264 解码成功并输出首帧（S2-c）
>
> **S2-b 已完成**：默认构建不含 `assets/mpv-libs`，实测减少 **44.6 MB**；
> 回滚用 `-PmpvBundle=true`。
>
> **仍未做**：带 Surface 的端到端播放（硬件解码 + Vulkan/OpenGL 渲染路径的实际输出），
> 以及「装模块 → 内核选择器出现 MPV」「卸载 → 消失」的 UI 行为。这些属于 S0-h 范畴。
>
> **S0-h 剩余项**（均需 UI 交互，坐标自动化在模拟器上不可靠）：
>
> 1. 模块管理页里真实安装 sherpa（走 `PackageInstaller` 会话 + 用户确认对话框）
> 2. 播放页开启实时字幕，确认 `realtime-subtitle` 日志为 `source=MODULE`
> 3. 卸载模块后确认回退内置库
>
> 已通过仪器化测试证明的替代证据：产物可下载且摘要匹配、manifest 与 catalog 一致、
> 库以 Stored 存放、门禁解析为 `Source.MODULE`、SDK 确实从模块目录 dlopen。
>
> 模拟器：`F:/Workspace/webhtv` → `192.168.50.3:5555`（arm64 native bridge 可用）。
>
> **真机测试注意事项**（已踩坑）：
>
> 1. 用 `adb shell am instrument -w -e class <FQCN>` 定向执行，**勿用** `connectedAndroidTest`
>    —— 后者会把测试跑到所有已连接设备，并在目标设备上卸载宿主应用。
> 2. `RuntimeSherpaLoadDeviceTest` **必须单独运行**：SDK 每进程只加载一次并缓存结果，
>    同进程内其他测试先触碰 sherpa 类会改变结论。
