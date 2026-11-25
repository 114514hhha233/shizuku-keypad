# 版本兼容性说明

HJM Keypad 用 Shizuku 把按键 / 触摸 / 鼠标事件注入系统输入层，所以「能不能用」取决于两件事：
**系统允不允许悬浮窗 + 前台服务**，以及**输入分发认不认我们注入的事件**。下面是按版本逐条核对的结果。

## 速查表

| 系统版本 | API | 状态 | 要注意的点 |
| --- | --- | --- | --- |
| Android 8.0 – 10 | 26 – 29 | 可用 | `TYPE_APPLICATION_OVERLAY` 自 8.0 起；Shizuku 需 root 启动 |
| Android 11 | 30 | 可用 | Shizuku 可用无线调试启动，无需 root；包可见性 `<queries>` 已声明 |
| Android 12 / 12L | 31 / 32 | 可用（本次重点验证） | 触摸进行中注入鼠标悬停会 **CANCEL 当前触摸**，本版已改为抬手注入 |
| Android 13 | 33 | 可用 | `POST_NOTIFICATIONS` 需运行时授权（已申请，拒绝也不影响悬浮按键） |
| Android 14 | 34 | 可用 | 前台服务必须声明 `foregroundServiceType`（已声明 `specialUse` + 对应权限 + 子类型属性） |
| Android 15 | 35 | 可用 | 强制边到边只对 targetSdk 35 生效，本应用是 34；鼠标悬停/触摸的判定已变化，抬手注入方案在两版上都安全 |
| Android 16 | 36 | 可用（兼容模式） | 未启用预测性返回；无 native 库，不涉及 16KB 页对齐 |

最低系统 Android 8.0（API 26），targetSdk / compileSdk 34。

## 各版本具体核对项

**Android 8.0 – 11**
- 悬浮窗走 `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`，无需额外适配。
- 前台服务（承载悬浮层）在这些版本上没有类型约束。
- Shizuku 在这些版本上需要 root 或 `adb shell` 拉起服务端。

**Android 12 / 12L（本次实测机型 PDYT20，Android 12）**
- 实测结论：手指按住悬浮按键的过程中，只要再注入鼠标悬停（`ACTION_HOVER_ENTER/MOVE`），系统会立刻给这个按键窗口发 `ACTION_CANCEL`，触摸随后漏到下层应用，下层只收到「按下」收不到「抬起」——表现就是**点一下变成长按**。
- 本版对策：鼠标左 / 右 / 中键改成**手指抬起那一刻**才注入点击，按住期间一个鼠标事件都不发；触摸被 `CANCEL` 时也不补点击。

**Android 13**
- 通知权限：`POST_NOTIFICATIONS` 已在启动时申请；即使拒绝，悬浮按键照常工作，只是前台服务通知不显示。
- 其余输入注入、悬浮窗行为与 12 一致。

**Android 14**
- 前台服务类型：`android:foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 已全部声明，不会出现 `MissingForegroundServiceTypeException`。
- 从后台启动前台服务受限：本应用只在用户点开开关时（应用在前台）启动服务。
- 隐式启动组件受限：所有跨进程启动都是显式 `Intent`。

**Android 15 / 16**
- 边到边（edge-to-edge）强制只作用于 targetSdk 35+，本应用 targetSdk 34，布局不受影响。
- 输入注入接口（`InputManager.injectInputEvent`）签名与权限模型未变，Shizuku 提供的 shell 身份照旧可用。
- 无 native 库（没有 `.so`），不涉及 16KB 页对齐要求。
- 未使用预测性返回、未使用 `SCHEDULE_EXACT_ALARM` 等受新政策约束的能力。

## 串流 / 云游戏（Moonlight、RustDesk、ToDesk 等）

这类客户端有两种常见做法，都会让「注入鼠标按键」失效：

1. **独占指针捕获**（`View.requestPointerCapture()` / `InputCapture`）。捕获后系统把鼠标事件只发给该应用，其他窗口不再收鼠标事件；部分实现还会把绝对坐标事件丢掉。
2. **只认自己的触摸手势**：触摸拖动换算成鼠标位移、**点按换算成左键单击**（Moonlight 的 `RelativeTouchContext` 就是这套：点按 → `sendMouseButtonDown/UP`）。

本版为此做了两件事：

- 注入事件补上 `getActionButton()`（原来只有 `buttonState`）。串流 / 桌面类应用普遍用 `getActionButton()` 判断「哪个键松开」，缺这个字段会以为一直按着。
- **注入失败时自动改发一次触摸点按**：先尝试标准鼠标事件，如果连「按下」都被输入分发拒绝（就是界面弹「注入失败」那种情况），就改为发送一次真正的触摸 `ACTION_DOWN → UP`（左键 60ms、右键 120ms）——串流客户端会把它翻译成左键 / 右键单击。只有鼠标路径连按下都没被接受时才走兜底，避免出现连点两次。

## 真机验证记录（本次）

- Android 12（PDYT20 / ColorOS 12）：点一下、带抖动点、手指滑出按键边缘三种点法，系统日志里都是干净的一对 `ACTION_DOWN → ACTION_UP`（63–66ms），无多余按下、无只按不放。
- 悬浮按键的触摸不再出现 `ACTION_CANCEL`（改版前每一拍都有）。
- 串流客户端未在本机安装验证，触摸兜底路径依据客户端公开的输入处理方式实现，建议在 Moonlight / RustDesk 上实测确认。

## 自测清单

1. 桌面：光标放在图标上，点悬浮左键 → 应用打开，不是「按住不动」。
2. 普通应用：点左键 20 次，不能出现「一次变两次」或「点一下没反应」。
3. 触控板：轻点 = 左键单击；双指点按 = 右键；双指滑动 = 滚轮。
4. 串流（Moonlight 等）：进入串流后点悬浮左 / 右键，远端应产生单击 / 右键菜单；若界面弹「注入失败」，说明走了触摸兜底，请记录客户端名字反馈。
5. 覆盖升级：新包必须能直接覆盖旧版（签名一致、versionCode 递增）。
