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

### 16.4 后续阶段

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| **S0-a** | 模块契约层 + 单测 | ✅ 已交付 |
| **S0-b** | 仓库 / 安装器 / 加载器 / 平台适配 | ✅ 已交付 |
| **S0-c** | P2 sherpa 接入（`sherpa_onnx.native.path`）+ 模块管理页 + 真机验证 | 待做 |
| **S0-d** | 新仓库 `Silent1566/webhtv-plugins` 骨架 + 打包脚本 + CI | 待做 |
| S1–S5 | P1 mpv / P4 ijk / P3 python / P6 tflite / P7 lut / P8 quickjs | 待做 |

> 每阶段独立 `task_guard.sh` 会话 + 原子提交 + recovery tag。
>
> **S0-c 前置**：`RuntimeAndroid` 的安装路径、`hostLibraries` 解析与 `sherpa_onnx.native.path` 生效性均需真机/模拟器验证（纯 JVM 单测覆盖不到）。
