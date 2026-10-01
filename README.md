# DeepSeek娘 · 桌面悬浮宠物

一个把 DeepSeek API 余额做成桌面宠物的 Android 应用。悬浮球常驻桌面，点开是余额卡片，
余额三档变化会切换 DeepSeek娘 的表情。

- 悬浮球：`WindowManager` + `TYPE_APPLICATION_OVERLAY`，可拖动、点击展开
- 余额卡片：半透明，展示 **总余额 / 赠金余额 / 充值余额**
- 表情规则：`> 阈值` 开心 · `0 ~ 阈值` 担忧 · `0 或查询失败` 哭泣
- 数据源：`GET https://api.deepseek.com/user/balance`
- API Key：用户手动输入，存 `EncryptedSharedPreferences`（Android Keystore 托管），不写死、不进日志
- 刷新：前台服务每 10 分钟自动拉一次，卡片和主界面上都可以手动刷新
- 保活：前台服务 + 常驻通知，通知栏可刷新 / 显示隐藏悬浮窗 / 回到 App

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose（BOM 2024.09.02, Material 3） |
| 网络 | OkHttp 4.12 |
| 存储 | EncryptedSharedPreferences（security-crypto） |
| 后台 | 前台 Service + START_STICKY |
| 构建 | AGP 8.5.2 / Gradle 8.7 / JDK 17 / compileSdk 34 / minSdk 26 |

## 项目结构

```
DeepSeekPet/
├── .github/workflows/android.yml      # CI：构建 release+debug，校验签名，发 Release
├── keystore.properties                # 固定签名配置（被 gradle 读取）
├── keystore/deepseekpet.p12           # 固定签名密钥库（PKCS12）
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts               # 固定签名配置 + Compose 开关 + 依赖
    └── src/main/
        ├── AndroidManifest.xml        # 权限 + 前台服务声明
        ├── java/com/deepseek/pet/
        │   ├── DeepSeekPetApp.kt      # Application：建通知渠道
        │   ├── MainActivity.kt        # 唯一 Activity，装 Compose 主界面
        │   ├── data/
        │   │   ├── BalanceModels.kt   # BalanceInfo + BalanceState 状态机
        │   │   ├── DeepSeekApi.kt     # OkHttp 调 /user/balance，错误码转中文
        │   │   ├── BalanceRepository.kt # 单例仓库：StateFlow + 10 分钟自动刷新
        │   │   └── SecurePrefs.kt     # 加密存 API Key / 阈值 / 悬浮球位置
        │   ├── model/Mood.kt          # 表情切换逻辑（三档 + 加载中）
        │   ├── service/
        │   │   ├── FloatingPetService.kt # 悬浮球 + 余额卡片 + 拖动 + 前台保活
        │   │   └── PetNotification.kt    # 常驻通知与快捷操作
        │   └── ui/
        │       ├── MainScreen.kt      # 主界面（澎湃 OS 风格）
        │       ├── PetOverlayUi.kt    # 悬浮球 + 余额卡片 Compose UI
        │       ├── Format.kt          # 金额/时间格式化
        │       └── theme/Theme.kt     # 澎湃 OS 设计 token
        └── res/
            ├── drawable/ic_face_*.xml # 开心 / 担忧 / 哭泣 / 加载中（矢量图）
            ├── drawable/ic_stat_pet.xml
            ├── mipmap-anydpi-v26/     # 自适应图标
            ├── values/                # 字符串、颜色、主题
            └── xml/                   # 备份规则（排除加密 prefs）
```

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 调 DeepSeek 余额接口 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗，**需要在系统设置里手动授权** |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务保活（Android 14 要求声明具体类型） |
| `POST_NOTIFICATIONS` | Android 13+ 常驻通知，运行时授权 |
| `RECEIVE_BOOT_COMPLETED` | 预留，当前未注册开机自启接收器 |

悬浮窗权限走 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`，通知权限走
`ActivityResultContracts.RequestPermission`，两者都在主界面「悬浮宠物」分区里可点。

## 固定签名

`debug` 与 `release` **共用同一份签名**（`keystore/deepseekpet.p12`，openssl 生成的 PKCS12），
所以：

- 所有构建产物签名一致，可以互相覆盖安装，升级不会出现「签名不一致」的安装失败；
- CI 每次构建出的 APK 都能直接装到同一台设备上替换旧版本。

要换成你自己的签名，只需要替换 `keystore/deepseekpet.p12` 并改 `keystore.properties` 四个值
（`storePassword` / `keyAlias` / `keyPassword` / `storeType`）。如果把仓库改成公开，请务必先换密钥。

## 本地构建

```bash
# 需要 JDK 17 + Android SDK（compileSdk 34）
gradle assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

仓库没有提交 `gradle-wrapper.jar`，本地用系统 `gradle`（8.7）即可；CI 里由
`gradle/actions/setup-gradle` 提供。

## 使用步骤

1. 装上 APK，打开 App；
2. 「悬浮宠物 → 悬浮窗权限」点进去授权；
3. 在「API Key」填 `sk-...`，点「保存并查询」；
4. 打开「显示桌面悬浮球」开关，回到桌面就能看到 DeepSeek娘；
5. 点悬浮球看余额，拖动可换位置（位置会被记住）；
6. 下拉主界面或点卡片右上角刷新即可手动更新。

## 隐私

API Key 只存在本机 `EncryptedSharedPreferences`（AES256-GCM，主密钥由 Android Keystore 托管），
已被 `backup_rules.xml` / `data_extraction_rules.xml` 排除在备份与设备迁移之外。
网络请求只有一处：`https://api.deepseek.com/user/balance`。
