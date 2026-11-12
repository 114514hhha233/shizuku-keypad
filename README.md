<div align="center">

<img src="assets/icon.png" width="128" alt="哈基米虚拟键盘图标">

# 哈基米虚拟键盘

**简体中文** · [English](README.en.md)

**给 Android 手机加一套能拖能改的虚拟键盘、摇杆和鼠标——不用 root，不用改游戏。**

[![Release](https://img.shields.io/github/v/release/114514hhha233/shizuku-keypad?label=release)](https://github.com/114514hhha233/shizuku-keypad/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Shizuku](https://img.shields.io/badge/Shizuku-13.x-6a5acd)](https://shizuku.rikka.app/)

**[⬇ 下载 APK](https://github.com/114514hhha233/shizuku-keypad/releases/download/v1.5/shizuku-keypad-1.5.apk)** ·
[源码](https://github.com/114514hhha233/shizuku-keypad) ·
[问题反馈](https://github.com/114514hhha233/shizuku-keypad/issues)

</div>

在 Android 上给游戏加一套可自由摆放的虚拟键鼠：每个按键都是独立悬浮窗，通过 **Shizuku** 把按键事件注入到系统输入层，因此云游戏、串流、模拟器、原生手游都能用；不需要 root，也不需要改游戏。

- 包名：`com.example.shizukukeypad`
- 版本：1.5（versionCode 6）
- 最低系统：Android 8.0（API 26），目标 API 34
- 依赖：Shizuku 13.x（`dev.rikka.shizuku:api/aidl/provider:13.1.5`）
- 语言：跟随系统，内置简体中文与英文

> 项目为实验性质，尚未在大量机型与游戏上验证，欢迎反馈问题。

---

## 功能

### 按键
- 每个按键一个独立 `TYPE_APPLICATION_OVERLAY` 窗口，位置互不干扰，可自由拖动、改大小（40–120dp）、圆形/圆角方形。
- 两种注入方式：
  - **键盘键**：通过 Shizuku 注入 `KeyEvent`（keyDown / keyUp），云游戏、串流、远程桌面可用。
  - **触摸键**：窗口本身设为 `FLAG_NOT_TOUCHABLE`，手指直接穿透到游戏，由系统提供**原生多点触控**，零注入延迟；同时按多个触摸键就是真正的多指。
- **锁定键**：点一下保持按住，再点一下松开，适合持续前进、持续疾跑。
- **宏**：逗号或空格分隔步骤，步骤内用 `+` 表示同时按下，例如 `Ctrl+Shift+K, A`。
- 自定义键值：不在列表里的键可以直接填 Android `KeyEvent` keyCode（1–400）。
- 一键开关：勾选后，工具栏总开关关闭时该按键整体隐藏。

### 虚拟摇杆
- **键值模式**：8 方向输出，中心 22% 死区；方向变化时只补发“松开/按下”，不会每次移动都重按。默认输出 **W / S / A / D**（51 / 47 / 29 / 32），四个方向键值都可以改成任意键。
- **触摸模式**：在“输出点”注入一根手指来推游戏自己的摇杆，位移被限制在摇杆行程内。输出点可以在编辑模式下用黄色准星拖动，避免压在悬浮按键上。
- 摇杆头跟手显示，可在编辑模式拖动摆放。

### 指托
一块半透明的小区域，**不发送任何按键**，只用来占住手势。

系统对多指有一条硬规则：一根手指按下后，这次手势就归它落到的那个窗口所有；如果第一根手指落在游戏上，后续手指就全部归游戏，悬浮层再也收不到。反过来，只要第一根手指落在我们的窗口上，后续手指就会继续送到我们这里，我们就能按屏幕坐标把它们分给各个按键。

所以：**拇指一直压在指托（或摇杆、任意按键）上，另一只手就能随便滑视角、随便按新键**。这是目前唯一稳定可行的“全屏多指”方案。

### 整块键盘
- 一整块 QWERTY 键盘，所有键在同一个窗口里按 pointerId 分发，多指同时按不会抢键。
- 顶部三条杠整体拖动，双指缩放并记住宽度。
- 字母层 / 符号层切换、Caps、Shift 处理。
- 与散键互斥，避免互相遮挡。

### 鼠标
- 触控板式拖动：在触控板上滑动移动光标，点击触控板即鼠标左键。
- 光标独立悬浮窗，鼠标键（左/中/右、滚轮上/下）注入在光标位置。
- 灵敏度倍率 50%–250%，拖拽锁定（左键按住）可选全屏拖动。

### 编辑与配置
- 工具栏：编辑模式、鼠标、整块键盘、一键开关、宏绑定、透明度。
- 编辑模式下直接拖动按键、点按键改配置、长按删除。
- 布局、透明度、灵敏度、开关状态全部用 `SharedPreferences + JSON` 持久化；按键位置存 0–1 比例，旋转屏幕、换分辨率都不会错位。
- 预设管理与配置导入导出。
- 可选“拦截系统输入法”：临时顶替为空输入法，避免打字时系统键盘弹出来挡住画面。
- 前台服务保活，切到游戏后悬浮层不被回收。
- 界面文案跟随系统语言，内置简体中文与英文。

---

## 工作原理

### 1. 为什么需要 Shizuku
普通应用注入按键只有三条路：无障碍（延迟高、会被游戏识别）、输入法（只能输入文字、拿不到组合键）、root。Shizuku 通过 `adb shell` 或 root 启动一个特权进程，把 `IInputManager` 的 binder 交给应用，于是可以直接调用：

```
IInputManager.injectInputEvent(event, MODE_WAIT_FOR_FINISH)
```

注入的是系统级输入事件，游戏无法区分，也无需任何特殊权限。

### 2. 为什么每个按键要独立成窗口
如果所有按键画在一张大窗口里，窗口会挡住整块屏幕，注入的点击会被自己吃掉；而且一张大窗口会让系统把整片区域的触摸都算给它。独立小窗口的好处：

- 只有按键那一小块区域参与触摸命中，游戏画面完全不被遮挡；
- 每个按键的注入坐标就是该窗口的真实屏幕坐标，不需要手工换算旋转和偏移；
- 窗口加 `FLAG_SPLIT_TOUCH`，多指可以分别落到不同的按键窗口。

### 3. 注入前先让开
注入的触摸和真手指走同一条分发路径，会按坐标命中“最上层可触摸窗口”。如果目标位置正好被我们自己的浮层挡住，注入就会打回自己身上（表现是按键闪一下、游戏没反应）。所以注入前会把挡路的窗口临时设为 `FLAG_NOT_TOUCHABLE`，注入完再恢复。

触摸键则相反：它常态就不可触摸，让手指直接穿透，用系统原生多点触控，没有任何注入延迟。

### 4. 多指路由
`FLAG_SPLIT_TOUCH` 只能保证“第一根手指所在的窗口”能继续收到后续手指。因此每个按键窗口的触摸处理里都做了二次分发：

- 按下：记录 pointer → 按键；
- 落在按键之外的后续手指：按屏幕坐标判断归属，或整根转发给游戏（`forwardDown / forwardMove / forwardUp`）；
- 抬起：释放对应按键或转发触点。

这就是“按住按键时还能去点、去拖屏幕其它地方”的原因，也是指托能生效的机制。

### 5. 坐标与持久化
屏幕尺寸取 `currentWindowMetrics`（当前旋转方向下的真实像素），按键位置一律存比例，旋转、换设备自动跟随。

---

## 已知限制

- **滑视角时按不了键**：如上所述，第一根手指落在游戏上时，这次手势归游戏所有，悬浮层收不到任何后续手指。解决办法是让一根手指一直压着指托/摇杆/按键。
- **无法做全屏接管层**：全屏窗口会挡住注入事件，导致自我循环；设成不可触摸又收不到手指；手势中途改可触摸状态会被系统取消。三件事无法同时成立，这是窗口分发规则决定的，与应用层权限无关。
- **触摸键与摇杆的触摸模式依赖注入**：注入的手指是独立输入流，极少数游戏/应用可能不认，或在系统手势边缘区域表现异常。
- **不支持 iOS/免 Shizuku 模式**：没有 Shizuku 时只能看布局，不能注入。
- 云游戏、串流场景建议用键盘键模式；原生手游建议用触摸键/摇杆触摸模式。

---

## 安装与使用

1. 从 [Releases](https://github.com/114514hhha233/shizuku-keypad/releases) 下载 `shizuku-keypad-1.5.apk` 并安装。
2. 安装 [Shizuku](https://shizuku.rikka.app/)，并按官方说明通过 `adb shell` 或 root 启动服务。
3. 打开应用，授予「悬浮窗」和「通知」权限。
4. 在应用内点「授权 Shizuku」，允许后状态变为已连接。
5. 点「启动悬浮层」，回到桌面/游戏即可看到工具栏与按键。
6. 点工具栏的「编辑」进入编辑模式，点「＋」添加按键，拖到合适位置；退出编辑即可使用。

---

## 构建

需要 JDK 17、Android SDK 34。仓库未附带 Gradle Wrapper，用本地 Gradle 8.x 即可：

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 项目结构

| 文件 | 职责 |
| --- | --- |
| `KeypadOverlay.kt` | 悬浮层主体：按键窗口、触摸分发、注入、摇杆、触控板、编辑模式、整块键盘、工具栏 |
| `KeyModel.kt` | 按键数据模型、键位目录、宏解析、SharedPreferences/JSON 持久化 |
| `JoystickPad.kt` | 摇杆控件：圆盘 + 摇杆头绘制与编辑拖动 |
| `ShizukuInjector.kt` | Shizuku binder 与输入注入：按键、触摸、鼠标、多点触控指针管理 |
| `OverlayService.kt` | 前台服务，承载悬浮层 |
| `MainActivity.kt` | 授权、开关、透明度/灵敏度、预设、导入导出 |
| `AboutActivity.kt` | 关于页 |
| `ImeGuard.kt` / `NoopImeService.kt` | 空输入法，用于拦截系统输入法弹出 |
| `res/layout/dialog_key.xml` | 按键配置对话框（含摇杆专属选项） |

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 绘制悬浮按键与工具栏 |
| `moe.shizuku.manager.permission.API_V23` | 调用 Shizuku 注入输入事件 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务保活悬浮层 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+） |
| `BIND_INPUT_METHOD`（服务声明） | 空输入法，仅在开启“拦截系统输入法”时使用 |

应用不联网、不收集任何数据；配置只保存在本机。

---

## 免责声明

本项目仅供学习与个人使用。请遵守所玩游戏/平台的服务条款，不要在多人在线竞技游戏中使用，由此产生的一切后果由使用者自行承担。
