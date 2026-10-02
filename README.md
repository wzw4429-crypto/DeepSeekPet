<div align="center">

# DeepSeek娘 · 桌面悬浮宠物

把 DeepSeek API 余额做成一只挂在桌面上的女仆鲸鱼娘。

[![Android CI](https://github.com/wzw4429-crypto/DeepSeekPet/actions/workflows/android.yml/badge.svg)](https://github.com/wzw4429-crypto/DeepSeekPet/actions)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7-4285F4?logo=jetpackcompose&logoColor=white)](#)

</div>

---

## 这是什么

一个 Android 悬浮窗应用：桌面上常驻一个**可拖拽的全身立绘**，点开是余额卡片，10 分钟自动同步一次 DeepSeek 的 API 余额。

- **悬浮球** — `WindowManager` + `TYPE_APPLICATION_OVERLAY`，拖动换位、点击展开
- **余额卡片** — 半透明，展示 **总余额 / 赠金余额 / 充值余额**，支持下拉与手动刷新
- **两态立绘** — 待机 = 愤怒叉腰；点击 = 托腮笑，且**余额卡片展开期间一直保持点击形象**
- **状态着色** — 卡片数字、通知文案随余额状态变色：充足 → 绿 / 偏低 → 橙 / 告急 → 红
- **常驻通知** — 可刷新余额、显示/隐藏悬浮窗、一键回到 App
- **前台服务保活** — 退到后台、锁屏后悬浮窗仍在
- **可调大小** — 应用内滑杆 60% ~ 240%，**悬浮服务运行中实时生效**

---

## 下载

打开 [Releases](https://github.com/wzw4429-crypto/DeepSeekPet/releases)，取最新的
`DeepSeekPet-release-v1.0.0.apk`。

所有发布产物都由 CI 用**同一张固定证书**签名，因此可以互相覆盖安装、升级不会报签名不一致。

### 首次使用

1. 安装 APK，打开 App
2. 「悬浮宠物 → 悬浮窗权限」→ 点进系统设置开启
3. 「API Key」填入你的 `sk-...` → 保存并查询
4. 打开「显示桌面悬浮球」开关 → 回桌面
5. 点她展开余额卡片；拖动换位置（位置会被记住）
6. 下拉主界面可手动刷新，不拉也会每 10 分钟自动刷

---

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 调用 DeepSeek 余额接口 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗，需在系统设置中手动授权 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务保活（Android 14 起要求声明具体类型） |
| `POST_NOTIFICATIONS` | Android 13+ 常驻通知，运行时申请 |
| `RECEIVE_BOOT_COMPLETED` | 预留，当前未注册开机自启接收器 |

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose（BOM 2024.09.02，Material 3 1.3.0） |
| 网络 | OkHttp 4.12 |
| 存储 | EncryptedSharedPreferences（security-crypto） |
| 后台 | 前台 Service + `START_STICKY` |
| 构建 | AGP 8.5.2 / Gradle 8.7 / JDK 17 / compileSdk 34 / minSdk 26 |

### 结构

```
app/src/main/java/com/deepseek/pet/
├── DeepSeekPetApp.kt        Application：建通知渠道
├── MainActivity.kt          唯一 Activity，装 Compose 主界面
├── data/
│   ├── DeepSeekApi.kt       OkHttp 调 /user/balance，错误码转中文
│   ├── BalanceRepository.kt 单例仓库：StateFlow + 10 分钟自动刷新
│   ├── BalanceHistory.kt    余额快照流水 + 区间消耗聚合
│   └── SecurePrefs.kt       加密存 API Key / 阈值 / 立绘大小 / 位置
├── model/
│   ├── Mood.kt              余额状态 → 配色与文案
│   └── PetSprite.kt         立绘两态（待机 / 点击）
├── service/
│   ├── FloatingPetService.kt 悬浮球 + 余额卡片 + 拖动 + 缩放 + 保活
│   └── PetNotification.kt    常驻通知与快捷操作
└── ui/
    ├── MainScreen.kt         主界面（余额看板 / 消耗额度 / 设置）
    ├── PetOverlayUi.kt       悬浮球与余额卡片 Compose UI
    ├── Format.kt             金额与时间格式化
    └── theme/Theme.kt        设计 token
```

---

## 构建

```bash
# 本地（需要 JDK 17 + Android SDK，compileSdk 34）
gradle assembleDebug      # app/build/outputs/apk/debug/
gradle assembleRelease    # app/build/outputs/apk/release/
```

仓库**不包含** `gradle-wrapper.jar`，本地直接用系统 `gradle`（8.7）；CI 由
`gradle/actions/setup-gradle` 提供。

每次推送到 `main`，Actions 会自动构建、验签并发布 Release（tag `build-<N>`）。

---

## 消耗额度是怎么算的

**DeepSeek 官方没有用量查询接口。** 已核对其 API 文档，账户类只开放
`GET /user/balance` 这一个；控制台的按日用量只能网页登录看。

所以本应用的「今日 / 近7天 / 本月 / 自选」消耗是**推算**出来的：

- 每次余额刷新成功就落一条快照（`filesDir/balance_history.json`，保留 120 天）
- 区间消耗 = 区间内**相邻快照下降段之和**
- 余额上升判定为充值，**不计入消耗**，并标记「区间内充值」提醒你数字可能偏小
- 基线取区间**之前**最近一次采样，避免漏掉区间内第一段消耗

> ⚠️ 这是估算值（10 分钟粒度），**与控制台账单会有出入**。要对账请以官方控制台为准。

---

## 安全与隐私

### API Key

- 只存在设备本地的 **`EncryptedSharedPreferences`**（AES256-GCM，主密钥由 Android Keystore 托管）
- 已在 `backup_rules.xml` / `data_extraction_rules.xml` 中排除备份与设备迁移
- 网络请求**只有一处**：`https://api.deepseek.com/user/balance`
- 代码里不硬编码、不写日志、不上传第三方

### 本仓库不含任何密钥

签名密钥曾出现在 Git 历史中，现已：

1. 用 `git filter-branch` 从**全部历史提交**中清除 `keystore.properties` 与 `keystore/*.p12`
2. force push 覆盖远端旧对象（复扫远端 16 个提交 = 0 命中）
3. 改由**加密的 GitHub Actions Secrets**（`KEYSTORE_B64` / `KEYSTORE_PROPS`）在构建前还原
4. `.gitignore` 排除，防止再次入库

因此固定签名得以保留（证书指纹 `df20dc97…ff69` 不变），老用户无需重装，
同时私钥不随仓库公开。构建日志中的 `Restore signing keystore` 步骤只校验结构，**不打印任何密码**。

---

## 素材说明

悬浮窗中的女仆鲸鱼娘立绘为**社区二创作品**，版权归原作者所有，此处仅用于个人用途展示。
若原作者希望移除，提交 Issue 即可，我会立刻从仓库中撤下。

---

## 排查

| 现象 | 原因 / 解法 |
|---|---|
| 开了开关但桌面没显示 | `SYSTEM_ALERT_WINDOW` 被重置。`pm install -r` 会把它置回 `ignore`，需重新授权 |
| 点击/滑动偶尔无反应 | 悬浮窗内 Compose 子视图抢触摸事件；已在容器上恒拦截（见 `bubbleTouchListener`） |
| 余额卡片在左上角闪一下 | 窗口以 `(0,0)` 挂载后才定位；已改为 `INVISIBLE` 挂载 + 布局完成后再显示 |
| 立绘大小调到某处不再变化 | 上限为 240%；滑杆刻度文字已改为与轨道两端对齐，中间刻度不再有误导 |
| CI 卡 `queued` 后变 `failure` | GitHub `Actions Job Delays` 事故：job 的 `steps` 数组为空 = 没进执行队列，等几分钟 rerun 即可，**不是代码问题** |
