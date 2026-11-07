# VoxCut

声控切片自动合成录音机 —— Skip-Silence / VOX Recorder。

全天候监听环境音，只有当音量越过你设定的阈值时，才把这段声音（含前置预录缓冲与尾音保护）平滑追加进同一个音频文件。停下来时你拿到的是一段剪辑紧凑、没有静音垃圾时间、也没有拼接爆音的完整 WAV。

## 核心机制

**采集**：`AudioRecord` 以 16 kHz / 单声道 / 16-bit PCM 取数，每 20 ms 一帧（16000 Hz 下即 320 个采样）。

**分贝换算**：每帧先算 RMS，再换到 0–100 的显示分贝。

```
RMS      = sqrt( Σ sampleᵢ² / N )
dBFS     = 20 · log₁₀( max(RMS, 1.0) / 32767.0 )
DisplayDB = (dBFS + 95.0).coerceIn(0.0, 100.0)
```

UI 上再叠一层一阶低通（`db = 0.7 · prev + 0.3 · cur`），避免数字狂跳。RMS 为 0 或出现 NaN / Infinity 时统一收敛到 0.0，不会把 UI 打崩。

**三态状态机**：

| 状态 | 行为 | 迁移 |
| --- | --- | --- |
| `IDLE` | 每帧写进预录环形缓冲，不落盘 | 连续 3 帧（60 ms）DisplayDB ≥ 阈值 → `ACTIVE`，触发计数 +1 |
| `ACTIVE` | 当前帧直接追加写盘，累加有效时长 | 单帧 DisplayDB < 阈值 − 3 dB（迟滞）→ `HANGOVER` |
| `HANGOVER` | 继续写盘并累加时长，尾音计时器 +20 ms | 计时器 < 尾音保持时间内音量回升 → 回 `ACTIVE`；超时 → 末帧淡出、补留白、清空预录缓冲、回 `IDLE` |

切入 `ACTIVE` 的那一刻，会把环形缓冲里过去 T<sub>pre</sub> 毫秒的历史样本一次性导出，对其开头一帧施加 0→1 线性淡入后写盘，所以第一个字不会被吞，接缝也不会"啪"一声。收尾同理，末帧 1→0 淡出。

**单文件流式追加**：整个监听过程只有一个目标 `.wav`。启动时先写 44 字节占位头，之后所有有效 PCM 一律 `append` 到文件尾；停止时用 `RandomAccessFile.seek(0)` 回写真实的 RIFF / `fmt ` / `data` 头与总长度。全程不产碎片小文件，也没有二次合并。

**热循环零分配**：帧缓冲、预录环形缓冲、静音缓冲、`ByteBuffer` 全部在录制启动前一次性预分配并复用，`while (running)` 里没有任何 `new`，不触发 GC 抖动，音质就不会被掉帧打断。

## 参数

| 参数 | 范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| 触发阈值 X | 20 – 90 dB | 45 dB | 越过它才开始记录 |
| 前置预录 T<sub>pre</sub> | 0.1 – 2.0 s | 0.5 s | 触发点之前保留多久的声音 |
| 尾音保持 T<sub>hold</sub> | 0.5 – 5.0 s | 1.5 s | 说话中途换气不断段 |
| 段间留白 T<sub>gap</sub> | 0.0 – 1.0 s | 0.2 s | 两段之间补多久静音 |

四个参数都支持监听过程中实时热更新，不打断正在进行的录制。

## 模块

```
app/src/main/java/com/voxcut/
├── engine/
│   ├── PreRollRingBuffer.kt    预录环形缓冲：单块预分配数组 + 写指针循环覆盖
│   ├── WavFileWriter.kt        流式 WAV 写入器 + 44 字节 RIFF 头回写
│   └── VoxAudioEngine.kt       采集循环、三态状态机、淡入淡出、分贝换算
├── service/
│   └── VoxRecordingService.kt  前台服务、WakeLock、通知栏实时状态、状态总线
├── VoxViewModel.kt             参数、历史文件、试听与分享
└── MainActivity.kt             Jetpack Compose 单屏控制台
```

界面：深色 Material 3。顶部是实时分贝大数、状态徽章和一条画着红色触发线的 Canvas 电平条；中间四格统计（监听总时长 / 有效时长 / 触发次数 / 压缩省流比）；下面是四个参数滑块；底部是主控按钮和录音历史（试听、分享、删除）。

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 36，minSdk 26）。

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

若你的检出路径含非 ASCII 字符（例如中文目录），Windows 上 AGP 会拒绝构建，需要在 `gradle.properties` 里打开 `android.overridePathCheck=true`（本仓库默认已开启）。

## 权限

`RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`。

服务以 `foregroundServiceType="microphone"` 常驻，并持有 `PARTIAL_WAKE_LOCK`，熄屏放进兜里也能整夜连续采集。

## 许可

MIT
