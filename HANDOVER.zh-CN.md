# Smile's Radio Mod — 项目交接总结

[English](HANDOVER.md) | **简体中文**

> 最近更新：2026-11-26 23:05（**第二十四轮**完成；含第十九轮**网络电台架构改造：客户端直连**）
> 项目路径：`C:\Projects\Smile's Radio mod`
> 构建产物：`build/libs/radio-smile-1.0.0.jar`（约 **357 KB**，BUILD SUCCESSFUL）
> 版本控制：已初始化 git，**每轮修复一个提交**
> 使用说明：面向服主/玩家的完整文档见 [README.md](README.md)（本文件是开发交接）

## 阅读指引（新会话从这里开始）

| 你想做什么 | 先读 |
|---|---|
| 了解项目是什么、怎么配 | [README.md](README.md) |
| 快速上手改代码 | 本文「十、快速恢复工作的方法」 |
| **动手前必读** | 「七、关键技术坑」+「十二、给下一个会话的提醒」 |
| 查某个 bug 的历史根因 | 「八、当前状态」按轮次排列（**A~AE 共 21 轮**，越靠后越新） |
| 查配置在哪、目录结构 | 本文「三、配置文件」或 README |

> ⚠️ **最容易踩的坑**：这套代码里**多数严重 bug 都是"一个函数/字段承担两种语义"**
> （见十二节表格，已出现 **10 次**，其中第十次是拿「增益 1.0（合法业务值）」
> 当「没有增益（清理信号）」，把本地台的 `outputGain` 抹成了 100%）。
> 改任何函数前先问：**它被几个语义不同的地方调用？**

---

## 一、项目概况

Fabric 1.20.1 模组，添加可播放广播的「收音机」方块。支持服务端配置的电台列表、
玩家自定义网络电台、多人同步播放、空间化音效。

| 项 | 值 |
|---|---|
| mod id | `radio-smile` |
| 方块/物品 id | `radio-smile:radio` |
| Minecraft | 1.20.1 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.92.12+1.20.1 |
| Gradle | 9.8.2（曾被误升级到 9.9.1，已还原） |
| 前置库 | smile_libbox 1.0.26+1.20.1（mavenLocal） |
| 可选依赖 | ModMenu 7.2.2 |
| 内置依赖 | JLayer 1.0.1（MP3 解码，已 include 进 jar） |
| 外部依赖 | **ffmpeg**（可选，用于 OGG→MP3 转码预热） |

---

## 二、方块特性

`RadioBlock` 继承 `BaseEntityBlock`，构造特性仿原版火把：

- `.noCollission()` — 无碰撞箱，不产生方块阴影
- `.noOcclusion()` — 不遮挡光照（放在草方块上不会使其变泥土）
- `.pushReaction(PushReaction.DESTROY)` — 活塞推动直接破坏
- `canSurvive` / `updateShape` — 需下方完整实心面支撑，失去支撑掉落
- 沙子落在上方会变成掉落物（因无完整碰撞面，与火把一致）
- `SoundType.STONE`
- 水平朝向状态 `facing`（north/east/south/west），放置时朝向玩家

`RadioBlockEntity` 存 NBT：
- `station`（当前频道 id）
- `volume`（0.0~1.0，本方块独立音量）

---

## 三、配置文件（服务端）

> **路径已迁移**（第十轮）：旧 `<存档>/serverconfig/radio-smile/` → 新 **`<游戏根目录>/config/radio-smile/`**
> 旧位置会自动迁移（见 T 节）。迁移原因：旧路径按存档隔离，换存档就要重配几十 MB 音频。

```
config/radio-smile/
├── stations.json        电台列表（+ 顶层 ffmpeg 路径）
├── audio/               原始音频 / 文件夹播放列表 / .txt 重定向列表
├── icons/               电台图标
└── cache/               可安全删除，自动重建
    ├── manifest.json    音频哈希清单（quickHash：前64KB+大小）
    ├── baked/           转码后的 MP3（ffmpeg 产物，启动时预热）
    └── remote/          网络音频缓存 + .proof 校验包
```

### stations.json 格式

```json
{
  "ffmpeg": "",
  "stations": [
    { "id": "tiehen", "name": "铁痕电台 #02", "icon": "fm907.png",
      "file": "qtfm_tiehen.ogg", "mode": "block", "rate": "FM105.7" }
  ]
}
```

**顶层字段**（频道列表之外）：

| 字段 | 说明 |
|---|---|
| `ffmpeg` | ffmpeg 可执行文件路径。留空 = 用 PATH 里的 `ffmpeg`（X 节） |

**频道字段**：

| 字段 | 说明 |
|---|---|
| `id` | 唯一标识。**`example` 为保留值，该 id 的条目不加载** |
| `name` | 显示名称 |
| `icon` | 图标文件名（放 icons/） |
| `file` | 单文件 / 文件夹 / **`.txt` 重定向列表** / `http(s)` 直播流 |
| `mode` | `block`/`single`/`false`/`0`=单曲循环；`sequence`/`order`/`loop`/`true`/`1`=列表循环；`random`/`shuffle`=打乱（第十二轮放宽） |
| `rate` | 液晶屏与列表右侧显示的频率/档位（如 `FM90.7`）。不填显示 `NETWORK`（第六轮新增） |
| `seed` | 可选，打乱模式用。不填则按 id 派生（保证多客户端一致） |

**首次启动自动生成**示范条目（id=example，不加载），带 `_说明_*` 字段解释各配置项。
服主改掉 id 即启用。

**配置校验**（第六轮）：音频不可用的频道会**从列表中隐藏**，但玩家方块上记着它时
仍显示 `NOSIGNAL` + 底噪——详见 P 节。

### txt 重定向播放列表（第十轮）

`file` 指向 `.txt`/`.list`/`.m3u`/`.m3u8` 即可混合本地与网络来源：

```text
false                 # 第一行：打乱顺序开关（true/false，可省略）
song_a.mp3
folder/song_b.ogg
https://example.com/song.mp3
```

- **播放顺序 = 文件里从上到下**
- 网络地址会下载到 `cache/remote/`、转 MP3 后参与播放（见 T/U 节）
- 直播流（无 `Content-Length`）会被**自动注释掉**并注明原因

---

## 四、GUI

`RadioScreen`（客户端）：

| 区域 | 内容 |
|---|---|
| 顶部 | 搜索框 |
| 中间 | 可滚动电台列表（含图标、选中高亮） |
| 右侧 | 音量滑块（**5% 一档**，第十三轮） |
| 底部 | 退出 / 扫描 / 确认 |

交互：
- 单击选中，**再次点击已选中项 = 取消选择**（空置）
- 滚轮切换选中，↑↓ 方向键切换，Enter 确认
- 确认后自动关闭界面，并开始拉流
- 进入界面时默认高亮该方块自己的频道（无频道则空置）

### ModMenu 配置界面

基于 smile_libbox 的 `ConfigSpec`，数值项用 `page.number()`（boxOnly 纯输入框）：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `enableStreaming` | true | 是否接收音频流 |
| `maxBandwidthKbps` | 64 | 带宽期望 |
| `maxChunkBytes` | 8192 | 单块上限 |
| `bufferMillis` | 1000 | 缓冲时长 |
| `showNetworkStats` | false | 显示网络状态 |

保存到 `run/config/radio-smile-client.json`。

---

## 五、网络协议

`RadioNetworking` 定义的通道：

**C2S（客户端→服务端）**
| 通道 | 载荷 |
|---|---|
| `c2s_request_stations` | 空 |
| `c2s_select_station` | stationId + BlockPos |
| `c2s_request_icon` | stationId |
| `c2s_audio_subscribe` | BlockPos + stationId + bwKbps + maxChunk |
| `c2s_audio_unsubscribe` | BlockPos |
| `c2s_set_volume` | BlockPos + volume |

**S2C（服务端→客户端）**
| 通道 | 载荷 |
|---|---|
| `s2c_stations` | epoch + JSON 列表 |
| `s2c_select_ack` | stationId + hash + epoch + volume + formatId + skipMillis |
| `s2c_icon` | stationId + byte[]（服务端已缩到 32×32，Z 节） |
| `s2c_audio_chunk` | BlockPos + stationId + trackIndex + sessionId + formatId + byte[] |
| `s2c_audio_status` | BlockPos + statusCode |
| `s2c_reloaded` | 空（配置重载通知，客户端播调台噪 + 声明过渡静音窗） |
| `s2c_audio_silence` | BlockPos + stationId + trackIndex + millis（**空音量包**：声明换曲预载静音窗，见 AC 节） |
| `s2c_output_gain` | BlockPos + stationId + gain（推流增益**带频道归属**，AE 节；网络电台在订阅 OK 时随回复下发） |

`AudioStatus`：0=OK, 1=UNKNOWN_STATION, 2=NO_AUDIO, 3=TOO_FAR, 4=UNSUPPORTED, 5=STOPPED, 6=TIMEOUT（408，**由客户端本地判定**，服务端不宣告）

---

## 六、音频架构

### 数据流（第十九轮改造后）

```
自定义电台（服务端推流）：
FileAudioSource  ──┐
  (本地音频/MP3)    │
                   ├→ AudioSubscription ──S2C_AUDIO_CHUNK──→ ClientAudioPlayer
PlaylistAudioSource┘   (读线程+配速)                          (AudioIngest 线程)
                                                              │
网络电台（客户端直连，第十九轮起）：                            ↓
上游 HTTP 流 ──→ 客户端 StreamFeed 拉流线程            Mp3StreamDecoder (JLayer)
              （复用 StreamAudioSource：                  │
                ICY 剥离 + 指数退避重连）                 ↓ PCM
              ──→ handleUpstreamChunk ─────────→ PcmSink（混音器）
                                                       │
                                                       ↓
                                            SourceDataLine 48kHz/2ch/16bit
```

> 服务端对网络电台只维护**订阅状态机**（`STREAMING_SUBS`：是否在播/走远/拆除），
> 音频字节完全不经过服务端（详见 AC 节，推翻 Z.3 的服务端重分发设计）。

### 服务端核心类

| 类 | 职责 |
|---|---|
| `RadioStationRegistry` | 配置加载、`isPlayable` 校验、quickHash 缓存校验、**预热转码**（同一趟遍历）、旧位置自动迁移 |
| `AudioTranscoder` | 按 quickHash 预热转 MP3（128kbps）；**ffmpeg 路径可配置**（X 节） |
| `AudioSource` | 音频源接口（read/durationMillis/format/skipMillis/positionMillis/seekTo/totalBytes） |
| `FileAudioSource` | 本地文件，MP3 可按帧定位 |
| `StreamAudioSource` | 网络直播流客户端，含 ICY 元数据剥离；**指数退避自动重连 1s→2s→4s→8s 封顶、无限重试**（第十九轮恢复并改进，O 节的「不重连」已推翻）；**被客户端 StreamFeed 直接复用** |
| `RemoteAudioCache` | 网络音频下载、`.proof` 校验包、断链时沿用缓存（T/U 节） |
| `TxtPlaylist` | txt 重定向列表解析、打乱开关、BOM/分隔符容错 |
| `PlaylistTimeline` | 按轮播放时间轴；**只接受 MP3**（V 节） |
| `AudioSubscription` | 订阅状态 + 读线程 + **闭环配速**（锚点按真实时长累加，W 节）；仅自定义电台使用 |
| `AudioStreamManager` | 自定义电台订阅管理、推送线程；**网络电台只登记订阅状态机**（订阅 OK 时**随回复下发增益 1.0**，AD 节）、tick 巡检走远/拆除；**心跳机制已整体删除**（AC 节） |

### 客户端核心类

| 类 | 职责 |
|---|---|
| `ClientAudioPlayer` | 按方块维护播放流，AudioIngest 线程消费；内含 `StreamFeed` 拉流线程（网络电台直连上游，4 秒无数据判停滞） |
| `Mp3StreamDecoder` | JLayer 流式 MP3 解码（**唯一的解码路径**；OggDecoder 已删除） |
| `PcmSink` | **单一声卡混音器**，固定 48kHz/2ch，设备常开；含调台噪、网络底噪、抖动缓冲、过渡静音窗、故障锁存 |
| `TuningNoise` | 调台噪（**点台瞬间触发**、HOLD 0.25 与节目同档、跟随空窗，AD 节定型） |
| `NetworkNoise` | 弱网嘶声（PEAK 0.12 与节目同档、起音 250ms / 落音 1.5s、故障锁存期间恒满档） |
| `RadioBlockEntityRenderer` | 液晶屏显示 rate/NOSIGNAL、红灯闪烁（G/J 节） |
| `AutoSubscriber` | 每 10 tick 扫描半径 52 格，自动订阅/退订；订阅 OK 才起拉流 |
| `BgmSuppressor` | 播放时抑制原版 BGM |
| `AudioLifecycleHooks` | 断开连接时释放声卡；JOIN 时请求电台列表 |
| `VanillaVolumeTracker` | 主线程跟踪 BLOCKS + MASTER 音量 |

### 音量与空间化

最终增益链：
```
方块独立音量 × 原版BLOCKS × 原版MASTER × 距离衰减 × 推流增益 × 左右声像
```

空间化处理：
1. 立体声下混为**单声道点声源**
2. **距离衰减**：≤4格满音量，4~48格线性衰减，≥48格静音
3. **恒功率声像**：按方位角映射回左右声道（总功率恒为 1）

**可听范围 48 格**（类似唱片机）。

---

## 七、关键技术坑（务必遵守）

按重要性排序，这些都是实际踩过的：

### 1. OGG 已从模组中彻底移除（不要加回来）
Vorbis 的 MDCT 重叠相加依赖前序页连续状态，页序号跳变会报
`Failed to read Ogg file`。**任何"从中间开始解 OGG"的方案都不可行**。

更关键的是第二条：客户端摄入队列是**断头丢弃**策略
（`ClientAudioPlayer.enqueue` 满了就 `poll()` 掉最旧的），
而 OGG **丢任意一块就永久失步**；MP3 帧自包含、JLayer 能向后重新搜到同步字。

→ 因此模组内**不再存在任何 OGG 解码路径**（2026-11-23 移除）：
`OggDuration` / `OggHeader` / `OggDecoder` 三个类已删除，
`AudioFormat` 只剩 `MP3` / `UNKNOWN`，`AudioDuration` 只做 MP3 逐帧扫描。

**但这不代表服主不能用 .ogg**：所有源文件在预热阶段经 ffmpeg 转成 MP3 后再推流。
「能不能用」的唯一标准是 **ffmpeg 能否转码**，与扩展名无关
（白名单见 `AudioTranscoder.SOURCE_EXTENSIONS`，14 种格式，单文件与目录两条路径共用）。

### 2. MP3 帧可从任意位置解码
这是网络电台一直正常的原因。实测从第 50 帧开始可连续解出 100 帧。

### 3. 格式必须由服务端下发
客户端早期靠猜首块字节判格式，转码/seek 后首块不在文件头，**必然误判**。

### 4. JLayer 的缓冲区是复用的
`SampleBuffer.getBuffer()` 返回内部缓冲，下一帧解码会覆盖它。
**必须复制**后再交给混音器，否则音频顺序彻底错乱。

### 5. 严禁播放中途 seek
seek 会**直接丢弃**偏差对应的全部音频（实测一次丢 13 秒）。
正确做法：订阅时定位一次，之后**纯顺序播放**，绝不中途跳转。

### 6. 文件读写必须线程安全
`FileAudioSource.read()` 与 `seekTo()` 必须都 `synchronized`，
否则读线程与主线程争抢文件指针，导致没声音或噪声。

### 7. 缓冲溢出不能腰斩
`compact(len/2)` 会把音频从中间切断导致错乱。
必须**按整帧边界丢弃队首**。

### 8. MP3 时长必须自己解析
JDK / 原版都没有可用的 MP3 时长解析，会导致定位逻辑整段失效。
`AudioDuration` 逐帧扫描解决（误差 <50ms，1343万字节用时 48ms）。

### 9. 声像右向量方向
`right = (-forwardZ, forwardX)`。写成 `(forwardZ, -forwardX)` 会左右颠倒。
（MC 中 +X=东、+Z=南，yaw=0 面朝南，此时西在右手边）

### 10. 发包必须回主线程
`ServerPlayNetworking.send()` 在自建线程调用会被丢弃。
必须 `server.execute(() -> {...})`。

### 11. 混音器锁范围要小
锁内做全部混音会与 GUI 线程、解码线程争抢，导致声卡断供。
锁只包数据拷贝与混合，平滑放锁外。

### 12. 贴图目录必须是 textures/block/
原版 `blocks.json` 图集只扫 `block/` 和 `item/`，复数 `blocks/` 永远进不了图集。

### 13. 一个函数不要承担两种语义（本轮反复踩坑的元凶）
本项目三次重大故障都源于此，详见 **第十二节**。改动任何函数前先确认：
**它是不是被语义不同的地方共用？** 是就拆入口，不要在函数里加 if。

### 14. 效果音不要进音源缓冲区
尝试过 premix，连踩三坑（reserve 衰减误判、any 误判、+= 累加导致音量增长）。
详见 **第八节 B**。效果音走独立叠加层，但**复用主链的增益/声像函数**。

---

## 八、当前状态

### 第一轮已验证通过
- ✅ 构建成功，jar 约 303 KB（最新）
- ✅ 方块注册、物品、创造标签页
- ✅ 配置加载、哈希校验、示例配置自动生成
- ✅ 自然序播放列表、固定 seed 随机
- ✅ 网络电台（MP3 流）播放
- ✅ 本地音频转码预热
- ✅ 左右声道方位正确（已数值验证）
- ✅ 音量受控（方块独立音量）
- ✅ 空选后停止播放
- ✅ 拆方块停止播放
- ✅ BGM 抑制
- ✅ 自动重订阅（按实际播放状态判断）

### 本轮修复（2026-11-23）
三处用户实测反馈的问题，根因都已定位并用真实音频数据复现验证：

| 现象 | 根因 | 修复 |
|---|---|---|
| 加载频道时「突然来声音 → 沉寂数秒 → 才进入主推流」 | `AudioSubscription.markSent()` 用 `source.positionMillis()`（读线程**预读**位置）当配速锚点。读线程在首包前已把队列灌到 64KB≈4 秒，锚点被直接推到 4263ms，首包后 4 秒不再发包 | 锚点改为 `sentBytesFromStart` 换算的「已发送毫秒」，首包回推 `streamStartAt`；另修 `updateBitrateEstimate` 用预读速度覆盖码率（16000→106666 B/s）的缺陷（`bitrateFixed`） |
| 声音朝向/声像自动切换有延迟 | 设备缓冲用 `bufferMillis`（1000ms）抗抖动，声卡里积压的是「已乘好 pan」的音频，pan 一进设备就改不了 | 设备缓冲固定 150ms；抖动缓冲下移到混音**前**的 `SourceBuffer`（`primed` 攒够才出声）；`SMOOTH` 0.39→0.55。声像延迟 1.05s → 约 0.22s |
| 播放列表播到末尾不重头/不继续随机 | `openSource()` 永远只取 `tracks.get(0)`；EOF 时只会 `reopen()`（在同一文件内重定位） | 新增 `PlaylistTimeline`（整条列表拼成连续时间轴，`(now-epoch)%总时长` 定位曲目+偏移）与 `PlaylistAudioSource`（EOF 自动接下一首、末尾回 0）；`trackIndex` 真实下发 |

补充修复（评审中发现）：
- **trackIndex 提前约 4 秒**：队列积压导致「发包时才读当前序号」比字节流早一个队列深度，
  客户端提前重建解码器 + flush 声卡 → 换曲约 1 秒静音。改为入队时冻结序号（`Chunk` 记录）。
- `PlaylistAudioSource.skipMillis()` 转发内层值（降级路径才不失同步）。
- `PlaylistTimeline` 加缓存（键含文件大小/修改时间），避免每次订阅在主线程整文件扫时长。
- 落后重锚改锚到本包末尾（`now+interval`），不再多塞一块。
- `PcmSink` 的 `stop()/releaseDevice()` 清空音源移入锁内，统一持锁纪律。

### OGG 链路彻底移除（用户决定，2026-11-23）
起因：tiehen 报 `Failed to read Ogg file 21` 完全放不出声。

排查中发现一个**重构回归**：`AudioStreamManager.openTrack()` 里查
`AudioTranscoder.mp3For()` 的代码被误删，导致单文件频道直接把原始 OGG 推给客户端。
先修回归，随后用户进一步决定「既然这条链路根本不可能可靠，干脆彻底删掉」——判断正确。

删除与改动：
- 删 `OggDuration.java` / `OggHeader.java` / `client/audio/OggDecoder.java`
- `AudioFormat` 枚举只剩 `MP3(0)` / `UNKNOWN(1)`；`.ogg/.oga` → `UNKNOWN`
- `AudioDuration` 只做 MP3 逐帧扫描；`AudioDurationEstimator` 删 `estimateOgg`
- 客户端 `ClientAudioPlayer` 只建 `Mp3StreamDecoder`；新增 `looksLikeOgg()` 按字节魔数
  主动拦截并点名原因（否则 OGG 数据喂给 JLayer 表现为「完全没声音且无任何提示」）

**改为「不静默降级」**（关键设计变更）：
- 转码产物缺失 → `openTrack()` 返回 null → 发 `NO_AUDIO` 拒绝播放，**不再**退回推原始文件
- 网络流不是 MP3 → `openSource()` ERROR 拒绝（网络流原样透传、不转码，
  填 .ogg 地址本来就不该被支持）
- 理由：静默降级产出的是「看起来能响、实则不同步且随时崩」的音频，比明确失败难查得多

**切网络电台延迟优化**（用户反馈「切换到网络电台会有些许延迟」）：
实测上游握手要 **1013ms**，而这段阻塞原本发生在**服务端主线程**
（\`subscribe()\` 由 \`server.execute()\` 调度，构造函数里同步建连）——不只慢，还卡住整个世界。
两处修复：
- \`StreamAudioSource\` 改为**异步建连**：构造函数立即返回（实测 1013ms → 10ms），
  建连期间 \`read()\` 返回**空数组**表示「暂无数据」。
  配套修了读线程：原来把「空数组」也当 EOF，会让订阅在建连完成前就作废
  （这也是「有时干脆没声」的来源）。
- 抖动缓冲增加**起播门槛** \`startupTargetFrames()\`（约 300ms），与稳态目标
  （\`bufferMillis\`，默认 1000ms）分离：先出声，再补到稳态。稳态抗抖能力不变。
  首音延迟 1000ms → 300ms。

**两个掩盖类音效**（用户要求）：
- \`TuningNoise\`「调台噪」：换台/换曲/重载/关闭最后一台时播放约 220ms 的调谐噪声，
  盖住流重连的跳变。**在混音器里合成**而非播原版音效——
  原版音效走 OpenAL，与本模组的 \`SourceDataLine\` 是两条独立时钟，盖不准。
  声音设计：白噪 → 低通 → 高通 → 5ms 淡入 + 平方衰减包络（纯白噪像爆音）。
- \`NetworkNoise\`「网络差底噪」：由**抖动缓冲剩余深度**驱动的轻微嘶声。
  用「缓冲变薄（即将出事）」而非「已经卡了」作信号，玩家能提前察觉。
  带迟滞：起音 1 块(~20ms)、落音 34 块(~680ms)，避免阈值附近「噗噗」抖。
  **正常播放时完全静音**——它是故障提示，不是效果音。

**播放列表改为「按轮播放」**（用户要求）：
- 每轮是曲池的一个排列，**全部播完后生成新一轮**；随机模式每轮重新洗牌，
  顺序模式每轮仍自然序（等价于原来的循环）
- **跨轮冷却**：随机模式生成新轮时避开上一轮**最后 5 首**，两轮交界不会「刚播完又播」。
  曲池太小（`g ≤ n/3`）时自动收窄——「5 首不够的排除在外」
- **不内存泄漏**：轮顺序是 `(seed, 轮次)` 的**纯函数**，需要时现算；
  只有最近 4 轮进小 LRU。实测访问 20 万轮，堆仅增 63 KB
- 关键实现约束（踩坑记录）：冷却修正只在「新轮前 g 位」与「`[g, n-g)`」间交换，
  且要求 `n ≥ 3g`。早期用 `g ≤ (n-1)/2`，n=10 时伙伴区只有 2 个槽位，
  40 轮里撞出 19 次重复；同时伙伴槽位**不能重复使用**，否则交换会把自己换回去

**「ffmpeg 能转码即可用」**（用户提出的原则，已落实）：
- `AudioTranscoder.SOURCE_EXTENSIONS`（14 种）是全模组唯一白名单
- `RadioPlaylist` 直接引用它，不再自存一份
- 修复了「单文件频道能放 .flac，放进目录就静默消失」的不一致

### 第二轮修复（同日，用户连续实测反馈）

这一轮几乎全部围绕**「效果音」与「重连」**两条线，踩坑记录如下——
**这些都是已经修好并验证过的，但很容易被后续改动破坏，务必先读完再动手**。

#### A. 网络电台断线重连（用户反馈：断网崩溃 / 恢复极慢 / 声音「卡回」）

日志真相：4 秒内 **57 次订阅、44 次建连**，而**会话号始终是 1**——
说明每次都是全新实例，重连机制根本没机会工作。三个 bug 互相放大：

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | 客户端超时判定 **2.5 秒** < 建连 1 秒 + 缓冲，必然误判 | `STALL_TIMEOUT_MILLIS` → **8 秒** |
| 2 | 服务端 `subscribe()` **无条件先退订**，杀掉正在建连的实例 | 改为**幂等**：同频道同方块复用现有订阅 |
| 3 | 客户端**无退避**，每 0.5 秒重发订阅 | `RESUBSCRIBE_INTERVAL_MS = 6 秒` |

配套：`StreamAudioSource` 加 **`sessionId`**（每次成功连接递增，随音频块下发）。
客户端发现会话号变化即**重建解码器**——因为重连后的字节与断线前**不连续**，
但 MP3 帧自包含，旧解码器会当成同一条流继续解，听感是声音突然**「卡回」**。

#### B. 调台噪（换台噪声）—— 反复出问题，最终定型

演进：固定 220ms（盖不住 600ms 空窗）→ 跟随空窗 + 连续稳定判定 →
发现淡出**单向不可撤回** → **最终改为独立叠加层**。

> ⚠️ **绝对不要把效果音 premix 进 `SourceBuffer`**
> 试过，连踩三坑（全部实测复现）：
> 1. `reserveFadeGain` 把 20ms 空块误判成「缓冲见底」，压到 **2.8% 音量**
> 2. 纯噪声块被 `any` 判成「节目回来了」，噪声自我淡出
> 3. `premix` 用 `+=` 而清空只置 `length=0`，旧样本残留导致音量**逐块增长**（0.05→0.64，很快削波）
>
> 根因：效果音**没有真实数据流的生命周期**，却被迫服从缓冲区的预热/丢弃/清零规则。
>
> 现行：`overlayEffects()` 独立叠加，但**复用主链的 `sourceGain()` 与 `panForPosition()`**，
> 所以音量/距离/声像天然一致。

当前行为：换台时旧音频**淡出 120ms**、噪声**淡入**，两者重叠无断点。
触发点：`S2C_SELECT_ACK`（含方块坐标）、`handleChunk` 换台分支、`S2C_RELOADED`。
**去重窗口 400ms** —— 一次换台会从多处触发，不去重会**放两遍**。

#### C. 网络差底噪 —— 语义改了三轮，最终定型

| 版本 | 判据 | 问题 |
|---|---|---|
| v1 | 抖动缓冲剩余深度 | 直播流缓冲恒定在起播水位 → **永久 40%，不消失** |
| v2 | 单块欠载就响 | 直播流正常抖动频繁误报 → **一直断噪** |
| v3 | 连续 8 块无数据 | 卡顿间隙的静音**没人填**，其余时间无提示 |
| **v4（现行）** | **两档 + 粘性** | — |

现行逻辑（`PcmSink.updateNetworkNoise()`）：
- **卡住**（这一块完全没数据）→ **满强度**，填满那段死寂
- **不卡但缓冲未恢复** → **0.4 弱底噪**持续提示
- 粘性：保持到缓冲**连续健康 25 块**（约 0.5 秒）才撤
- 停止：网络恢复 / 手动切台 / 空选

**三个导致「底噪又没了」的陷阱**：
1. **不能每收到一块数据就清底噪** —— 块约 250ms 一个，卡顿一阵一阵，
   「卡一下→底噪起→收到一块→被清→又卡」，底噪永远起不来
2. **「没有音源」不能算健康** —— 早期 `isJitterBufferThinned()` 在 sources 为空时
   返回「健康」，于是 `stop()` 清空音源后连续 25 块判健康 → 撤销底噪
3. **「用户关台」≠「播放故障」** —— 见下面 D

#### D. stop() 语义拆分（本会话最隐蔽的一处污染）

`ClientAudioPlayer.stop()` 原本被两类场景共用：

| 场景 | 玩家意图 | 该响底噪吗 |
|---|---|---|
| 用户空选/拆方块/走远 | 不听了 | **不该** |
| 服务端报故障（无音频/超时） | **还想听**，暂时失败 | **该** |

故障借用 `stop()` 会撤销播放意图 → 底噪不响。修复：拆成两个入口，
共用 `stopInternal()`（只拆播放，不碰意图）：
- `stop(pos)` → 撤销 `playbackWanted`
- `stopForFailure(pos)` → **保留** `playbackWanted`
- 新增 `PcmSink.playbackWanted`，区分「异常断流」与「正常空选」

#### E. 刷新按钮不再重订阅

曾把「重连」并进「扫描」，理由是「都是重新问一遍状态」——**这个判断是错的**。
网络直播流每次重连都是**全新 HTTP 连接**，上游从自己的编码缓冲处开始发，
重连后播放的是**几秒前的旧内容** → **割裂 + 卡顿**。

现在 `onScan()` 只做 `requestStations()`。
重订阅只在**确认失败**时由 `AutoSubscriber` 触发。

#### F. 音量相关（用户多次反馈）

| 现象 | 根因 | 修复 |
|---|---|---|
| 不开 UI 音量就不受控 | `applyBlockVolume()` 只在订阅时调一次 | `AutoSubscriber` **每次扫描都同步**（0.5 秒） |
| 效果音不受音量控制 | 未乘方块独立音量 | 统一走 `sourceGain()` |
| 效果音无距离衰减 | 刻意省掉了（错误判断） | 补 `distanceGainAt()` |
| **点扫描音量条跳变** | `stop()` 的 `clearVolumeFor()` 删记录，GUI 反查 `volumeFor()` 回退 1.0 | GUI 用自维护的 `currentVolume`；扫描后补回 |

#### G. 故障显示渲染（`RadioBlockEntityRenderer`）

用户原型：明日方舟的收音机，**红灯就是 POWER 指示灯**，在液晶屏右上角。

**设计要点：红灯最大亮度，液晶屏与错误码跟随环境光照**
- 红灯 `LightTexture.FULL_BRIGHT` —— 自发光指示灯，暗处必须看得见
- 液晶屏底色 + 错误码 → `packedLight` —— 被照亮的显示面，不是光源

**四个已踩的坑（全部实测）**：
1. `IllegalStateException: Not filled all elements of the vertex` ——
   `RenderType.translucent()` 格式含 **normal**，漏了必崩。
   该渲染器平时完全不画，所以直到断网触发故障才第一次暴露
2. **画在背面** —— 模型主箱体 `from [3,0,7] to [13,6,9]`，
   `north` 面 uv `[0,0,5,3]` 才是正面图案；MC 规范里 north 面在
   **`z = from[2] = 7`、朝 -Z**。误用 `to[2]=9` 并按 +Z 画 → 全在背面
3. **文字上下颠倒 + 左右镜像** —— `pxToY` 把「贴图向下」转「方块向上」，
   `yTop`/`yBottom` 传反；且字体默认朝 +Z，需绕 Y 轴转 180° 才面向 -Z 观察者
4. 法线写 `(0,0,1)` + +Z 绕序 → 从正面看背对观察者，**被背面剔除吞掉**（灯不亮）

错误码格式 `ERRnnn`，借用 HTTP 语义：403 距离过远 / 404 频道不存在 /
408 数据超时 / 410 已停止推送 / 415 格式不支持 / 422 无可用音频 / 500 未知。
**只显示在液晶屏，不输出到聊天栏**。

#### H. 重载与命令

- `/radio reload`（权限 2）：重载配置 + **重建所有订阅** + 通知客户端播调台噪
- 原版 `/reload` 也生效（`RadioReloadListener` 挂资源重载）
- **只改 `startEpochMillis` 不够**：已建立的订阅持有固定文件指针，
  必须 `AudioStreamManager.rebuildAll()` 才真正从头播

#### I. HLS / HE-AAC 技术评估（已评估，用户决定**不做**）

用户要求支持喜马拉雅 HLS 流，且**必须客户端独立完成**（不是所有玩家都装 ffmpeg）。

| 项 | 结果 |
|---|---|
| 流格式 | HLS，每 7 秒一个 `.aac` 分片，**HE-AAC**（SBR+PS），44.1kHz/2ch/65kbps |
| `de.sfuhrm:jaad 0.8.7` | **0% 成功率**（SBR 不完整，151 帧全挂） |
| `com.tianscar.javasound:jaad 0.9.4` | **100% 成功**（151/151，44100Hz/2ch/16bit），jar 643 KB |
| JAAD 性能 | 7 秒音频 **101.9 ms**（69x 实时，1.45% 单核） |
| JLayer 性能 | 7 秒音频 **36.2 ms**（193x 实时，0.52% 单核） |

**结论：用户选择 MP3，不换 HE-AAC** —— 解码慢 2.8 倍，唯一优势是带宽减半，不值得。
服务端 ffmpeg 版的 `HlsAudioSource.java` 已删除。
若以后要做：jaad 0.9.4 作为**可选依赖**，按 `.m3u8` 后缀分流到独立客户端播放路径
（拉列表 → 取最新分片 → JAAD 解码 → 喂 PcmSink），注意直播延迟控制与分片预取。

### 第三轮修复（同日 17:40，用户实测截图 + 断网反馈）

#### J. 故障显示渲染：位置全错（本轮最大一块）

用户截图证据：**红灯和液晶屏底色完全不可见**，而 `ERR408` 文字**上下颠倒、左右镜像**，且浮在屏幕上方的键盘区。三个独立缺陷叠加，全部已定位并由反编译原版字节码证实：

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | 红灯/底色四边形**完全看不见** | 朝向旋转写成 `-facing.toYRot()`，与正确值差 **180°**，整个 overlay 落到方块**背面**；法线/绕序背对观察者 → 被背面剔除吞掉 | `180.0F - facing.toYRot()` |
| 2 | 即使朝向对，图案仍**左右镜像** | `pxToX` 写成 `FACE_X_LOW + ...`。反编译 `FaceInfo.NORTH`：顶点为 (MAX_X,MAX_Y),(MAX_X,MIN_Y),(MIN_X,MIN_Y),(MIN_X,MAX_Y)，配合 `BlockFaceUV#getU` 的索引规则（顶点0/1取uvs[0]，2/3取uvs[2]）⇒ **X 越小 u 越大**，u=0 在 +X 侧 | `FACE_X_HIGH - ...` |
| 3 | 文字**上下颠倒** | `pose.scale(s, s, s)` 少了 y 的负号。字体空间 +y 向下、方块 +Y 向上 | `pose.scale(s, -s, s)`（原版 `SignRenderer#translateSignText` 同款） |

补充修正（**都是评审中发现的**，前两条是"改完仍然看不见"的隐藏坑）：

- **镜像与顶点绕序必须成对处理**——`pxToX` 改成镜像后，`pxToX(x0) > pxToX(x1)`，
  而 `drawQuad` 的顶点序 `(x0,yB),(x0,yT),(x1,yT),(x1,yB)` 只有在 **x0 < x1** 时
  叉积才给出 -Z（朝观察者）。直接传镜像后的原值会让法线翻成 +Z ⇒ 被 CULL 剔除 ⇒ **仍然完全不可见**。
  这两种"看不见"现象一模一样、极难区分，所以 `drawPixelQuad()` 内部统一做
  `Math.min/Math.max` 归一化，**不允许**调用方直接给 `drawQuad` 传未排序的 x。
- **纯色四边形不能写 `uv(0,0)..(1,1)`**——`RenderType.translucent()` 的纹理状态是
  `BLOCK_SHEET_MIPPED`，即**整张方块图集**；`uv 0..1` 在 GL 里就是"整张贴图"，
  四边形会把整个图集缩放铺出来，而顶点色只是**乘**在上面、抹不平。
  改为取图集里均匀白色的 sprite（选了 `minecraft:block/snow`，实测均值 (249,254,254)，
  比 white_wool 的 (234,236,237) 更接近纯白），只采样其中心 16% 区域。
  图集未就绪时**跳过绘制**而不是退化到 uv 0..1。
- 文字显示模式 `SEE_THROUGH` → **`POLYGON_OFFSET`**（原版告示牌也用这个）。SEE_THROUGH 不测深度，正是"文字能穿过整块方块从背面看见"的来源。
- 新增 `drawPixelQuad()`：调用方只给**贴图像素矩形**，绕序与 y 翻转在函数内统一处理，杜绝再次把 yTop/yBottom 传反。
- 字号 0.0085 → 0.009，并按液晶屏**绿区**（不是含黑边）限制宽度——黑字压到黑边上就消失了。

> ⚠️ **液晶屏不重绘底色**（用户明确要求）
> 早期故障时会把屏幕刷成暗红底（`LCD_BG_FAULT`）。用户否定：**显示屏背景保持贴图原样，只有文字变黑**。
> 现在 `LED_COLOR_FAULT` 是唯一绘制的四边形，文字色 `0xFF000000`。
> 这也更符合真实设备——液晶底色由偏光片物理决定，变的只有笔画。

**验证方式**（可复现）：把四个朝向、贴图 UV 与文字变换一次性数值化，
确认「u=0→屏幕左、LED 在右上、文字可正读、法线朝观察者」四个朝向全部成立；
并用用户截图反推：贴图左侧黑色面板在 facing=south 时应落在观众左手边，与实际截图一致。

#### K. 「断网无噪音」

两条**互补**路径，任一条成立就完全没声：

1. **纯链路断（服务端不发状态包）**：`overlayEffects()` 的 `if (anchor < 0) return;` ——
   调台噪与网络底噪**共用同一个 `tuningNoiseBlock` 锚点**，而该锚点只由换台路径设置。
   断网没换过台 ⇒ 锚点 -1 ⇒ 整层直接 return，底噪连生成都不做。
2. **服务端报故障**：`stopForFailure()` 最终仍调 `PcmSink.stop()` ⇒ `silent = true` ⇒
   混音线程走静音分支，而 `updateNetworkNoise()`/`overlayEffects()` 都在 `continue` 之后，
   且该分支每 20ms `networkNoise.reset()` ⇒ 底噪永远不可能响。

> 这也解释了为什么表现为「时有时无」：**换过台**的场景本来就有锚点，是能响的。

修复（沿用「一个函数不承担两种语义」）：
- 新增独立的 `networkNoiseBlock` 锚点：`tuningNoiseBlock` = **事件**归属（换台拟音），
  `networkNoiseBlock` = **状态**归属（链路坏了）。两者并列，不复用。
- 抽出 `overlayNetworkNoise()`，让静音分支**只**能出声底噪。
  **调台噪绝不能进静音分支**——它的时长上限靠正常路径的 `followSilence()` 推进，
  静音分支不推进，跟进去会卡在 active 上无限响。
- **无锚点时不 return**：调台噪是效果音（没方位就不放，合理），
  网络底噪是故障提示（定位不到就退化为居中 + 只乘原版音量，**牺牲方位保住提示**）。
- `PcmSink` 新增 `silenceForFailure()`：静音 + flush，但**不**清锚点、**不** reset 底噪。
  这正是第八节 D 那次语义拆分**没做完的下半截**——`ClientAudioPlayer` 早就拆了
  `stop()`/`stopForFailure()`，但两者最终都调 `PcmSink.stop()`，在混音器层又被合并回一个函数。
- 静音分支按 `playbackWanted` 分流：true → 跑 `updateNetworkNoise()` + `overlayNetworkNoise()`；
  false → reset + 纯静音（用户空选必须绝对安静）。
- 新增「本流首次出声」门（`streamFlowing`）与「明确上报故障」门（`faultReported`），
  由 `faultSignalAllowed()` 统一判定。**没有这道门，正常起播会先沙约 1 秒假底噪。**

#### L. 「恢复了 ERR 也不消除」

主因：**本地 TIMEOUT 只点灯、没有灭灯边沿**。
`AutoSubscriber` 只在 `stale` 时 `markLocalFault(TIMEOUT)`，而 `stale` 依赖
`isStalled()` 读 `lastChunkAt`（每收到一块就刷新）。数据一恢复 `stale` 立刻变 false，
于是**再没有任何代码清 `clientFaultStatus`**。注释里写的「恢复后 markFault(OK) 会自动熄灭」
在代码里根本不存在（全工程只有 `AudioStreamManager:136` 发 OK）。

次因：`AudioStreamManager.subscribe()` 的**幂等早退**（同频道同方块直接复用）**不发 `sendStatus(OK)`**。
而客户端重订阅恰恰是在确认故障之后发起的 —— 闷声复用 = ERR408 永久留在屏上。

修复：
- 幂等早退分支补 `sendStatus(player, pos, AudioStatus.OK)`。
- `handleChunk`（**AudioIngest 线程**）检测到数据恢复后，经
  `Minecraft.getInstance().execute(...)` **跳回主线程**清显示。
  直接写 `setClientFaultStatus` 会在 ingest 线程读 `client.level` —— 必须跳线程。
  带**代际检查**（`FAULT_GENERATION`）：排队期间若来了新故障，这次清除作废，
  避免「故障一闪就灭」。
- `AutoSubscriber` 保持**只点灯**，灭灯责任唯一交给上面那条恢复边沿
  （避免"这一 tick 没判超时"导致显示来回抖）。
- 用户清台（`stop`）也清显示，不留孤儿 ERR。

#### M. 本轮新增的语义拆分（第十二节同一形状，第四次）

| 函数 | 被混淆的语义 | 后果 |
|---|---|---|
| `overlayEffects()` 的锚点 | 「换台拟音归属」vs「链路故障归属」 | 断网无噪音 |
| `PcmSink.stop()` | 「用户不要了」vs「故障静音」 | 底噪被当成用户主动关台（D 节没做完的下半截） |
| 静音分支 `networkNoise.reset()` | 「空选静止」vs「故障拿不到数据」 | 底噪永远起不来 |
| `ClientAudioPlayer` 的 `STREAMS` | 「真的在收流」vs「玩家点过确认还没撤销」 | 首块前失败不响 / 关台后迟到包又点亮 |

**结论同第八节 D**：`STREAMS`（事实）与 `REQUESTED`（意图）必须分开，
意图只能有一个判据，否则两种相反误判必然同时存在。

#### N. 第四轮微调（用户实测「渲染没问题」之后）

渲染定位已确认正确，本轮只调整观感与日志噪音：

| 项 | 改动 |
|---|---|
| 红灯 | **快闪**，半周期 250ms（2Hz，每 0.5 秒一个明暗周期）。灭相时**整个四边形不画**，露出贴图原本的暗红"未点亮"状态。用墙钟而不是 `getGameTime()`——后者在暂停时会让灯冻在半亮 |
| 屏幕文字 | `ERRnnn` → **`NOSIGNAL`**，且**常显不闪**（文字要能读；闪的是红灯，两者职责不同） |
| 音量滑块 | 行程 **10 档 → 5 档**（0/25/50/75/100%）。18px 宽的滑块上 10 档每档不到 2 像素，根本停不准 |
| 自动重试 | 服务端重连**不再放弃**（`MAX_RECONNECT_ATTEMPTS` → 无上限）。原先 120 次后彻底放弃，长断网需靠客户端重订阅重建整条订阅、丢掉抖动缓冲 |
| **日志刷屏** | 见下 |

##### 日志刷屏的四个来源（全部已修）

用户实测反馈「失败只要在开始和结束的时候 log 一下就行，现在会刷屏」。
逐一找出并压成**每次故障两行**（开始 / 结束）：

| 位置 | 原来每多久一条 | 现在 |
|---|---|---|
| `StreamAudioSource.scheduleReconnect()` | 每次重试都 WARN（断网时每 3 秒一条） | 新增 `episodeLogged`：本次故障**只报一次**"失联"，恢复时报一次"已恢复（重试 N 次，失联 X ms）"。逐次尝试降为 `debug` |
| `StreamAudioSource.connect()` | 每次（重）连成功都 INFO「已连接」 | 只在**首次**连接（`sessionId==1`）打 INFO；重连成功交给上面那条"已恢复"统一报 |
| `AutoSubscriber` | 每次重订阅都 INFO「数据超时，重新拉流」（每 6 秒一条） | 新增 `retryLogged` 集合：每次故障只报一次「开始自动重试」。恢复正常收流后移除，下次故障还能再报 |
| `ClientRadioNetwork` 状态包 | 每次收到非 OK 都 WARN | 新增 `ClientAudioPlayer.logFaultOnce()`：**方块 + 状态码**都没变就不重复；恢复时 `resetFaultLog()` |

> 设计取向：**开始与结束各一行，中间完全静默**。想看重试细节把日志级别调到 DEBUG。
> 这与第八节「no silent degradation」不冲突——静默的是**重试的重复过程**，
> 不是"出了什么问题"这件事本身。

#### O. 第五轮：职责重新划分（用户明确的架构要求）

用户原话：
> 重连是客户端的事情，断网了服务端就标记为已下线（包括退出游戏），重新连接回来算已上线，
> 服务端不管网络电台的死活，只管自定义电台和方块数据，玩家掉线/断网就清理连接状态，
> 客户端在连接不通畅的情况下不与服务器彻底断开连接就只是底噪比较大，不会直接重连，
> 但服务器判定玩家断开了连接就重置该玩家的订阅

##### 新的职责边界（这是本轮最重要的设计约束）

| 层 | 负责 | 明确<b>不</b>负责 |
|---|---|---|
| **服务端** | 自定义电台（本地文件/播放列表）、方块数据、<b>连接</b>生命周期 | 网络电台的死活（上游断了<b>不修、不重试</b>） |
| **客户端** | 显示状态、底噪提示 | 弱网时重建订阅/重连（只在<b>本地没有订阅</b>时补订阅） |

##### 落地改动

1. **删掉服务端的网络流重连状态机**（`StreamAudioSource`）
   - 移除 `scheduleReconnect` / `maintainReconnect` / 退避 / `MAX_RECONNECT_ATTEMPTS`
   - 建连失败、读到 EOF、读异常 → **标记 `finished` 并就此结束**，只打一条日志
   - `read()` 在结束/建连中返回<b>空数组</b>（不能返回 null，那会让读线程直接退出、订阅作废）
   - 保留 `sessionId` 协议字段（客户端仍需判断字节流连续性），但一条实例恒为 1

2. **读循环要能退出**（`AudioSubscription.readLoop`）
   - 新增：`source.isFinished()` 时退出循环并 `notifyAll`
   - <b>为什么必须加</b>：服务端不再重连后，死掉的流会永远返回空数组；
     不加这个判断，读线程会每 50ms 空转、订阅一直挂着占名额。
   - 退出后订阅仍 active（服务端不主动踢），只是不再产数据——符合"不管死活"

3. **连接生命周期显式化**（`SmileSRadio`）
   - `DISCONNECT` → `unsubscribeAll`（清空该玩家全部订阅，含关闭 HTTP 连接）
   - `JOIN` → 同样 `unsubscribeAll`（幂等保险：崩溃恢复/异常时不留残留订阅）
   - JOIN 时清理可避免"订阅数超限"误判与向失效连接发包

4. **客户端不再因弱网重订阅**（`AutoSubscriber`）
   - 语义拆成三态，<b>只有第一、二种才订阅</b>：

     | 状态 | 判据 | 动作 |
     |---|---|---|
     | 新增/换台 | `isNew` | 订阅 |
     | **本地没有订阅对象** | `!isStreaming()` | 订阅（带 6 秒退避） |
     | 订阅在但没数据 | `isStalled()` | **只显示故障，绝不订阅** |

   - 这正是 HANDOVER 第十二节那个模式的又一例：把"该修"与"该显示"混成一个判据，
     就会在断网期间反复重建订阅，把本来只是抖的音频抖得更厉害。

5. **客户端 JOIN 时清空本地流表**（`AudioLifecycleHooks`）
   - 服务端下线时已清订阅，客户端不清 `STREAMS` 的话 `isStreaming()` 仍为 true，
     AutoSubscriber 认为"已在播"，<b>重连后永久沉默</b>

##### 无信号显示收窄（用户补充要求）

> 但客户端的无信号是针对网络电台的，而非自定义电台

`NOSIGNAL` 只表示"上游网络断了"，因此两处入口都加了电台类型判断：

- **本地判定**（`AutoSubscriber`）：`stale && isStreamingStation(stationId)` 才点亮
- **服务端状态**（`ClientRadioNetwork.displayStatusFor`）：新建一层翻译，
  把传输层状态码映射成"该不该上屏幕"：

  | 状态码 | 上屏幕 | 理由 |
  |---|---|---|
  | `TIMEOUT` + 网络电台 | ✅ NOSIGNAL | 真正的"没信号" |
  | `TIMEOUT` + 本地电台 | ❌ | 本地文件没有"信号"这回事 |
  | `TOO_FAR` | ❌ | 玩家自己走远了，走进范围就恢复 |
  | `STOPPED` / `NO_AUDIO` / `UNSUPPORTED_FORMAT` / `UNKNOWN_STATION` | ❌ | 服主配置或转码问题，与信号无关 |

> 这些状态<b>仍然照常打日志</b>（排查需要），只是不上屏幕。
> 这样 `NOSIGNAL` 的含义就是单一且准确的：**这台网络电台的上游断了**。

#### P. 第六轮：配置校验 / 未知频道报警 / rate 字段

##### 1. 配置失败的频道<b>不显示</b>

新增 `RadioStationRegistry#isPlayable()`，在 `load()` 时逐条校验：

- <b>网络电台</b>：合法 http(s) 即有效。上游此刻通不通<b>不算配置错误</b>——
  断流时显示 NOSIGNAL，而不是把它从列表里删掉。
- <b>本地电台</b>：`resolveTracks()` 非空，且至少一首是 MP3 或可转码格式。

无效的频道<b>不进入 `stations`</b>（客户端列表里看不到），
同时把 id 记进 `invalidStationIds()`，供"方块上还记着它"的场景报警。

##### 2. 已配置却被重载搞坏 / 未知频道 → <b>持续噪音 + NOSIGNAL</b>

关键在于分辨两种"放不出来"：

| 情形 | 列表 | 方块上的表现 |
|---|---|---|
| 配置就写错了（文件不存在） | <b>隐藏</b> | — |
| 配好过，重载后变无效/被删 | 隐藏 | <b>持续噪音 + NOSIGNAL</b> |
| 方块记着完全不存在的 id | — | <b>持续噪音 + NOSIGNAL</b> |

从客户端角度看，后两者是同一件事：<b>方块 NBT 里的 id 在 `ClientRadioData` 里找不到</b>。

实现要点：
- `ClientRadioData.findStation()`：判"配置里到底有没有这个频道"
- `ClientRadioNetwork.displayStatusFor()`：`UNKNOWN_STATION` <b>一律</b>上屏幕；
  `TIMEOUT` 时若方块 id 已不在配置中，也升级成 `UNKNOWN_STATION`
- `AutoSubscriber`：`unknownStation` 分支<b>不等 8 秒超时窗口</b>就点亮
  （它注定不会好，等窗口没意义），并且<b>不再发起订阅</b>
- `ClientAudioPlayer.markUnknownStationFailure()`：<b>新入口，不复用 stopForFailure</b>。
  原因：`stopForFailure` 依赖 `REQUESTED`（玩家点过确认），
  而未知频道常见于"进服时方块已经坏了"，本机从没发过订阅请求、`REQUESTED` 为空，
  复用会导致底噪不响，正好违反要求。

> 注意"持续"两个字：底噪由混音器按缓冲状态维持，不会被一次状态包清掉；
> NOSIGNAL 由方块实体状态维持，直到数据真的回来或玩家处理。

##### 3. `rate` 字段（列表右侧）

`RadioStation` 新增 `rate`；`displayRate()` 未配置时返回 <b>`NETWORK`</b>。

界面：`RadioScreen.renderList()` 先算 rate 宽度并右对齐，
<b>再用它反推名字可用宽度</b>（`nameBudget`）——否则长频道名会盖住 rate，两者都读不清。
rate 用较暗的颜色，不抢频道名的注意力。

示例配置与自动生成的 `stations.json` 都带上了 `rate` 键（空串 = NETWORK）。

##### 4. 测试配置更新

`run/saves/新的世界/serverconfig/radio-smile/stations.json` 与 `run/world/...`：

| id | 内容 | rate |
|---|---|---|
| `tiehen` | 本地 OGG | FM105.7 |
| `testaudio` | 本地 OGG | （空 → NETWORK）|
| `qtfm` | 蜻蜓 FM 网络台 | （空 → NETWORK）|
| **`fm907`** | **杭州潮流音乐电台**，`https://lhttp-hw.qtfm.cn/live/15318146/64k.mp3`，图标 `fm907.png` | **FM90.7** |
| `broken` | 故意写一个不存在的文件 → 应被隐藏 | — |

新流已实测可用：HTTP 200、`audio/mpeg`、`icy-metaint=16000`。

#### Q. 第七轮：文字闪烁根因 / rate 被滚动条遮挡 / 右键交互

##### 1. NOSIGNAL 文字闪烁（**根因已定位，不是偶发**）

用户反馈"文字还是会闪"。查 `run/logs/latest.log` 得到决定性证据：
**「音频已恢复」打印了 119 次，而「音频订阅失败」是 0 次** —— 故障是在<b>本地</b>被反复点亮/清除的。

两个独立缺陷叠在一起：

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | `clearClientFaultOnResume` 里清完故障后**没把 `faultShown` 置回 false** | 那道"要不要排队"的门永远开着 → **每来一个音频块就重复清一次、重复打一条日志**（实测每 0.25s 一条）|
| 2 | 客户端进世界时**从不请求电台列表**（只在打开 GUI 时请求） | 不碰界面的玩家 `ClientRadioData.stations()` 永远为空 → 我上一轮加的"未知频道"判定把<b>所有</b>频道都判成未知 → 每 0.5s 点亮一次，数据到了又清 → 闪 |

修复：
- `clearClientFaultOnResume` 显式 `faultShown = false`
- `AudioLifecycleHooks` JOIN 时主动 `requestStations()`
- 两道保险：`AutoSubscriber` 与 `ClientRadioNetwork.displayStatusFor` 都加
  **`ClientRadioData.isLoaded()` 前置判断**，列表未到绝不判"未知"

> 教训与第十二节同型：**「未知」和「还不知道」是两件事**。
> 把"列表还没加载"当成"频道不存在"，就会在每次轮询里制造一次假故障。

##### 2. rate 文字不显示

右边界原本写成 `listRight - 8`，而滚动条区间是 `[listRight-5, listRight]`——
短文案（如 `NETWORK`）的右端正好被压在滚动条下面。
改为 `listRight - SCROLLBAR_WIDTH - 4`，并保证即使名字预算被压到 0 也照画 rate。

##### 3. 右键交互：手持其它物品不触发

`RadioBlock#use` 新增 `isInteractionItem()`：只有**空手**或**拿着收音机物品**才打开界面，
其余一律返回 `PASS` —— **拿方块右键能正常放置**、拿工具/食物正常作用，不被收音机截走。

同时修了一个只在服务端发生的隐藏 bug：原实现服务端返回 `PASS`，
于是服务端会继续走 `ItemStack#useOn`，**拿着收音机物品右键收音机时会在服务端凭空放置一个方块**，
而客户端已经弹出了界面。改为服务端返回 `SUCCESS` 吃掉交互（界面仍只在客户端打开）。

> 交互矩阵：
>
> | 手持 | 客户端 | 服务端 | 结果 |
> |---|---|---|---|
> | 空手 | SUCCESS | SUCCESS | 打开界面 |
> | 方块 / 工具 / 食物 | PASS | PASS | **正常走原版** |
> | 收音机物品 | SUCCESS | SUCCESS | 打开界面，且不偷放方块 |

#### R. 第八轮：液晶屏显示 rate / 拆方块噪音 / 缓冲变薄预警

##### 1. `rate` 显示在<b>方块液晶屏</b>上（此前一直理解错位置）

前几轮把 `rate` 做到了**选台 GUI 的列表右侧**，用户反馈"不显示"——
实际是**位置理解错了**：要求的是**方块液晶屏**上显示。

- 正常播放：液晶屏写该频道的 `displayRate()`（如 `FM90.7`，未配置则 `NETWORK`）
- 故障：仍写 `NOSIGNAL`
- 两者共用新抽出的 `drawLcdText()`，避免各写一份又只修好一边
- `shouldRender()` 原来 `return fault != OK`，正常播放时**完全不画**——
  必须改成 `return true`，否则屏幕上的频率永远看不到
- 字号改为**按宽度自适应**：短文案放大、长文案缩小，上下限夹住

##### 2. 拆方块有噪音

两个叠加缺陷：

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | `silenceIfNoSources()` 只清 `tuningNoiseBlock`，**漏了网络底噪的锚点与增益**。拆方块时若 `STREAMS` 已空，会走这条路径 → 方块没了底噪还在响 | 一并清 `networkNoiseBlock`、复位 `networkNoise` 与全部计数 |
| 2 | 拆方块的几个 tick 里，服务端先停推送 → `stale` 变 true，但方块实体还没移除 → 误判链路故障并点亮底噪 | 故障判定加「方块实体是否还在」前置检查 |

##### 3. 缓冲层薄时的底噪（用户要求：含切台、断网恢复）

新增 `isBufferThinning()`。**关键是不能重蹈 HANDOVER 第八节 C 的 v1 覆辙**：
v1 用「相对配置目标的水位」判断，而直播流稳态固定在起播水位（300ms vs 目标 1000ms），
于是永久 40% 不消失。

现行判据（两者取或）：

| 判据 | 含义 | 阈值 |
|---|---|---|
| **相对自己的稳态变薄** | "网络在变差"的定义，与绝对水位无关 | 低于稳态 55% |
| **绝对深度过薄** | 一层随时会断的薄缓冲 | 低于起播门槛 70% |

稳态基线的更新规则：**变厚立即跟随、变薄缓慢下移（1/1024）**。
衰减必须比真实下降慢得多，否则基线会"吸收"掉下降、永不报警
（实测 1/256 的半衰期 3.5 秒，比一次真实掉水还快，已改成约 14 秒）。

模拟验证（600 块）：

| 场景 | 触发块数 |
|---|---|
| 直播稳态 300ms | **0** |
| 直播 300ms + 抖动 ±25% | **0** |
| 本地稳态 1000ms | **0** |
| 本地 1000ms 骤降到 400ms | 329 ✅ |
| 匀速 300ms 降到 120ms | 455 ✅ |

> 正常场景全部零误报，异常场景都能检出。

#### S. 第九轮：选择框（不是碰撞箱）

用户要求"调节下碰撞箱"，澄清后确认是**选择框**（射线高亮的轮廓），**不是实体碰撞**，
且**天线可以不加**。

1.20.1 里两者是独立的：

| 方法 | 作用 | 本轮处理 |
|---|---|---|
| `getShape` | **选择框**：鼠标指上去的轮廓、射线命中范围 | **新增**，取机身几何 |
| `getCollisionShape` | 实体碰撞：玩家/生物/掉落物能否穿过 | **保持空**（与火把同类，可穿过） |

- 形状 = 模型主箱体 `x=3..13, y=0..6, z=7..9`，**不含天线**
- 按 `180 - toYRot()` 旋转（与渲染层同一套角度，否则会"看得见点不到"）
- **必须显式保留 `getCollisionShape = Shapes.empty()`**：
  1.20.1 默认实现回落到 `getShape`，不写就会让玩家撞在薄片上走不过去

> 坑：1.20.1 的 `Shapes` **没有**公开的旋转方法（只有 box/or/join/getFaceShape），
> `VoxelShape` 也只有 `move`。这里手写绕 Y 轴 90° 倍数换轴：`(x,z) -> (z, 1-x)`。

### 配置文件位置（常被问）

| 文件 | 路径 | 说明 |
|---|---|---|
| **电台配置** | `<世界存档>/serverconfig/radio-smile/stations.json` | 开发环境：`run/saves/<存档名>/serverconfig/radio-smile/` |
| 音频文件 | 同上 `audio/` | 单文件或目录（播放列表） |
| 电台图标 | 同上 `icons/` | |
| 转码缓存 | 同上 `cache/baked/` | ffmpeg 产物，可安全删除 |
| **客户端配置** | `run/config/radio-smile-client.json` | 只在客户端；也可用 ModMenu 界面改 |

> 注意 `stations.json` 是**按存档**存的（不是全局 `run/config/`），
> 换存档就要在新存档的 `serverconfig` 下重新配置。

#### T. 第十轮：配置迁到 config / txt 重定向播放列表 / 轻量哈希

##### 1. 电台配置迁移到全局 `config/`

| | 旧路径 | 新路径 |
|---|---|---|
| 电台配置 | `<存档>/serverconfig/radio-smile/` | **`config/radio-smile/`** |

理由：旧路径**按存档隔离**，换存档/开新世界就要把几十 MB 音频重配一遍。
新位置全局唯一，与 `config/radio-smile-client.json` 同级。

**旧位置自动迁移**：新位置还没有 `stations.json` 而旧位置有时，
首次加载整棵目录复制过来（含 audio/ icons/，因为配置里都是相对路径）。

##### 2. txt 重定向播放列表

`file` 字段可以指向一个 `.txt`（也支持 `.list` / `.m3u` / `.m3u8`）：

```
# 第一行是模式（可省略，默认 sequence）
sequence
song_a.mp3
folder/song_b.ogg
https://example.com/song.mp3
```

- 模式：`sequence`/`order`/`loop`（顺序）、`random`/`shuffle`（固定 seed 打乱）
- **播放顺序 = txt 行从上到下**（用户明确要求）
- 空行与 `#` 开头的行忽略；支持行尾注释（`#` 前有空白才算，避免误伤 URL 里的 `#`）

##### 3. 播放顺序 = txt 行从上到下（**不要做分桶/排序**）

未缓存好的网络条目处于**挂起**状态：它们**不进入**可播列表，
所以「播放到那一行就自动跳过」是天然成立的，播放层不需要任何额外判断。
后台下载完成后，它们会自己并入可播列表。

> ⚠️ **不要重新引入"优先级分桶"**。曾实现过「本地 > 已缓存网络 > 未缓存网络」
> 的三级优先级，意图是"先播能播的"，但它会**打乱按行顺序**——
> 而用户明确要求"顺序播放就是按 txt 行从上到下"，混播（本地/网络交替）时尤其不能乱。
> 挂起跳过本身已解决"未缓存不卡住播放"，排序是多余的且有害。
>
> 实测（A/网络/B/网络/C 混排）：可播条目 = A、B、C，顺序保持原样。

##### 3b. 网络地址：缓存到本地 + 转 MP3

`RemoteAudioCache` 负责：下载 → 存到 `cache/remote/` → 交给 `AudioTranscoder` 转 MP3。
之后与普通本地音频**完全一样**地参与时间轴——这样架构里不需要第二条播放路径
（直播流不可 seek、没有时长，混进列表会破坏多人同步）。

**校验包（proof）**：另存一份 `<id>.proof`，内容是**前 8KB + 文件总大小**。
下次校验只需 Range 请求取头部几 KB 比对，不必重新下载整个文件。

##### 4. 挂起机制 + 流式自动注释

- **未缓存网络条目 → 挂起**：不在可播列表里，播放到那行**直接跳到下一首**
- 后台单线程串行下载，下完清解析缓存，下一轮自然并入
- **流式传输（无限流）自动注释**：检测到没有 `Content-Length`（或超过 512MB 上限）时，
  直接在 txt 里把那一行改成
  `# [已禁用：流式传输，无法缓存] https://...`
  —— 服主打开文件就能看到原因，不用翻日志

> ⚠️ 直播流（如 `lhttp-hw.qtfm.cn/live/...`）**不能**写进 txt：
> 它没有文件长度、永远下载不完。直播流应当直接配成频道的 `file`（走 `StreamAudioSource`）。

##### 5. 轻量哈希（节省算力）

缓存校验从「整文件 SHA-256」改为 `quickHash()` = **前 64KB + 文件大小**：

- 20MB 文件从 ~50ms 降到 ~0.1ms
- 每次 `/reload` 都要对所有文件重算，累积起来很可观
- `AudioTranscoder.cacheKey()` **同步改用同一函数**——两处不一致会导致
  "校验认为没变、但缓存键变了"，表现为每次启动都重新转码却始终不命中缓存

#### U. 第十一轮：真实网络链接适配（B 站 .m4s / 网易云 .mp3）

用用户提供的真实链接实测，发现并修掉 5 个缺陷：

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | `.m4s` 不在转码白名单 → B 站链接直接被跳过 | 白名单加入 `m4s/mp4/mkv/webm`。**实测 .m4s 是 MP4/ISO-BMFF 容器**（magic `ftypiso5`，音轨 AAC 48kHz 立体声），ffmpeg 能正常提取 |
| 2 | Content-Type 不可信：B 站代理把 .m4s 标成 `audio/mpeg` | 不依赖响应头判格式，只看扩展名 + 交给 ffmpeg |
| 3 | 无扩展名 URL 下载成 `.bin` 后永远转不了码 | 新增**魔数嗅探** `sniffAndRename`：按文件头识别 mp3/ogg/flac/wav/m4a 并改名 |
| 4 | **临时签名链接过期后缓存被误丢** | 关键取舍：**拿不到校验证明 ≠ 文件变了**。请求失败/不返回 206 时**继续信任已有缓存**（本地文件是完整的），只有"拿到新头部却对不上"才判失效 |
| 5 | 解析阶段每条网络地址等 1 秒做 Range 校验 | 解析只查本地存在性（`verify=false`），有效性校验带 **10 分钟 TTL 缓存**。实测 **4477ms → 216ms（20 倍）** |

##### 实测结果（真实链接）

| 链接 | 结果 |
|---|---|
| 网易云 .mp3（6.8MB） | 下载 1.8s，原生 MP3 无需转码，二次命中 **24ms** |
| B 站 .m4s（4.5MB） | 下载 12.4s，识别为 `.m4s`，转出 **3分11秒** MP3 |
| 校验包 | 每个 8200 字节（8KB 头部 + 8 字节长度） |

##### 测试配置

`run/config/radio-smile/audio/realtest.txt`：

```
sequence
test_station.ogg          # 本地
https://.../xxx.mp3       # 网易云
https://.../xxx.m4s       # B站（m4s）
https://.../yyy.mp3
https://.../yyy.m4s
```

调 `realtest` 频道即可验证：前 4 个已缓存立即可播，未缓存的挂起并后台下载。

> ⚠️ **B 站链接带 `deadline` 参数，约 2 小时后失效**。失效后再校验会走"拿不到证明 → 沿用缓存"分支，
> 已下载的文件仍能正常播放。但要重新下载就必须换新链接。

#### V. 第十二轮：修复「时序完全乱」+ 模式写法放宽

##### 1. 时序错乱的根因（**这是本轮最重要的一条**）

现象：接入网络链接后，播放列表的切换时机与界面/时间轴完全对不上。

根因是一条**三段式连锁**，每一段单独看都"合理"：

| 阶段 | 发生了什么 |
|---|---|
| ① | `load()` 时 txt 里的网络条目处于**挂起**（还没下载），预热只处理了本地文件 |
| ② | 后台下载完成后**只清了 txt 解析缓存，没有触发转码** —— 没有任何东西会再跑一次预热 |
| ③ | 于是 `resolveTracks()` 拿到的是未转码的 `.m4s`。而 `AudioDuration` **只认 MP3 帧** → 时长 -1 → 兜底成 **1ms** |

**1ms 的曲目会把它之后所有曲目的起点全部往前拉**，于是"第几轮第几首第几毫秒"的定位与真实播放彻底脱节。
同时 `PlaylistAudioSource.openTrack()` 拒绝非 MP3，运行时又反复跳曲——两个错误叠加，表现就是"时序完全乱"。

修复（三处，缺一不可）：

1. **下载完成后立即转码**（`RemoteAudioCache`）：`AudioTranscoder.preheat(List.of(local))`
2. **txt 解析优先返回转码产物**（`TxtPlaylist`）：`mp3For(cached)`
3. **时间轴只接受 MP3**（`PlaylistTimeline.of`）：非 MP3 直接剔除并告警，**绝不让它进时间轴**。
   这是防线的最后一层——即使前两步失败，也不会再产出错位的时间轴。

> `durationOf()` 的告警文案也从"将按 1ms 处理"改成明确的错误，
> 因为这个兜底本身就是错乱源，不该被当成正常路径。

##### 实测验证

| 项 | 修复前 | 修复后 |
|---|---|---|
| `.m4s` 转码 | 未转码，被时间轴吞掉 | 2 个全部转出 MP3 |
| 各曲时长 | `.m4s` = **1ms** | 139920 / 191088 / 839664 / 1272744 ms |
| 时间轴 | 起点全部错位 | 5 首 / 每轮 **1527.1 秒** |
| 定位抽查 | 对不上 | 时刻 1526149ms → 第 5 首 138920ms，**恰好等于该曲真实时长** |

##### 2. mode 写法放宽（用户要求）

`mode` 现在接受多组同义写法：

| 语义 | 可用写法 |
|---|---|
| **单曲循环** | `block` / `single` / `false` / `0` |
| **列表循环** | `sequence` / `order` / `loop` / `true` / `1` |
| **随机播放** | `random` / `shuffle` |

大小写不敏感、忽略首尾空白；**空值等同未配置**（单曲循环）。

> `true`/`1` 映射到**列表循环**而不是随机——读 `true` 时最自然的理解是"按列表循环"。
> 随机是不可猜的语义，必须写 `random`。

##### 3. 默认示例配置重写

生成的 `stations.json` 现在自带 `_说明_*` 字段（播放模式/rate/txt/示例），
5 个示例条目分别标注：单曲循环、列表循环、随机、txt 播放列表、网络直播流。
同时生成 `example/mylist.txt` 作为 txt 格式参考。

另外关闭了 Gson 的 HTML 转义（`disableHtmlEscaping`）：
否则配置文件里会出现 `mode\u003dblock` 这种转义，完全没法一眼看懂。

#### W. 第十三轮：配速根因修复（丢一半包）+ 音量步进 + txt 布尔开关

##### 1. 「丢一半包、剩下的是噪音」——配速换算根因

用户描述「一长串音频有规律地少了一半，后面的接上来」「丢的那一半变成噪音」。
从录屏音频频谱量化出决定性证据：

| 测量项 | 数值 | 对应 |
|---|---|---|
| 周期性断续 | **3.01 秒** | 客户端缓冲上限 **3.000 秒** |
| 高频爆发占空比 | **49.6%** | 约一半数据被丢弃 |
| 波形自相关 | ≈0.003 | 拼接而非重复播放 |
| 数字全零段 | **0** | 不是断流，是 `dropFront` 丢最旧数据 |

**根因**：`AudioSubscription.markSent()` 用
`audioSentMillis = sentBytesFromStart * 1000 / bytesPerSecond` 做配速锚点，
而播放列表的 `bytesPerSecond()` 返回的是**整条列表平均码率**（172.8 kbps），
但列表里混着 128 kbps 与 320 kbps：

| 曲目类型 | 配速误差 | 后果 |
|---|---|---|
| 320 kbps（网易云） | **+85%** | 发送≈1.85×实时 → 缓冲堆到 3 秒上限 → **丢弃最旧音频** |
| 128 kbps（本地转码/B站 m4s） | **−26%** | 只发出 74% 数据 → 缓冲饿死 → **那段时间只剩噪音** |

修复（三处）：
1. **锚点改为累加本块真实时长**：`audioSentMillis += intervalMs`。
   `intervalMs` 由 `AudioDurationEstimator` 逐帧解析 MP3 帧头得出，
   与码率字段是否准确**完全无关**。实测 4 个 mp3 的估算与真实时长偏差 **< 0.1%**。
2. 首包锚点同步改用累加值，不再走字节换算。
3. `PlaylistAudioSource.bytesPerSecond()` 改为返回**当前曲目**码率
   （旧注释担心"换曲跳变"是对的，但解法错了：用平均值回避跳变，
   代价是每首都配错速）。
4. 客户端缓冲溢出时**打告警日志**（限流 5 秒一条）——
   这个症状历史上完全没有日志，才会被反复误判成解码/网络问题。

##### 2. 音量步进改为 5%

`VOLUME_STEP_PERCENT = 5`（20 格），而不是之前的 `VOLUME_STEPS = 5`（5 格 = 每格 20%）。
两个值含义完全不同，常量改名以避免再次误会。

##### 3. txt 第一行改为布尔开关（「打乱顺序」）

```
# 第一行：打乱顺序
false          # 不打乱，严格按下面从上到下（默认，可整行省略）
true           # 打乱顺序，所有玩家顺序一致
```

- 接受 `true/false`、`1/0`，大小写不敏感
- **兼容旧写法**：`random/shuffle` → 打乱，`sequence/order/loop` → 不打乱
- 第一行不是开关时按普通条目处理

> 用户措辞：文档里叫「**打乱顺序**」，不叫"随机播放/列表播放"。

#### X. 第十四轮：ffmpeg 路径可配置

`stations.json` **顶层**（频道列表之外）新增 `ffmpeg` 字段：

```json
{
  "ffmpeg": "C:\\tools\\ffmpeg\\bin\\ffmpeg.exe",
  "stations": [ ... ]
}
```

用于 ffmpeg 不在 PATH 的服务器（自建容器、精简系统、多版本共存需指定某一份构建）。

| 配置值 | 行为 |
|---|---|
| 字段缺失 / 空串 | 用 PATH 里的 `ffmpeg`（默认，不打扰既有部署） |
| 有效路径 | 采用，日志确认「已按配置指定 ffmpeg」 |
| 路径不存在 | WARN 并回退 PATH，**明确说明**若 PATH 也没有则无法转码 |

> 关键实现约束：探测（`ffmpegAvailable`）与实际转码（`runFfmpeg`）**必须走同一个
> `ffmpegCommand()`**。否则会出现"探测说可用、转码却找不到"这种自相矛盾的状态——
> 正是 HANDOVER 第十二节那个「一个语义两处实现」的老毛病。

实测四种配置组合均正确；并用真实 .ogg 验证**按配置路径转码成功**。

#### Y. 第十五轮：Linux 兼容性审计（在 WSL 里实测）

用 WSL2 Ubuntu 做了实际验证（静态审计 + Linux 语义下的行为测试）。

##### 发现并修复的两个真实缺陷

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| 1 | **配置里的 `\\` 路径在 Linux 上失效** | Windows 上配好再上传到 Linux 服务器，`audio\\song.mp3` 会去找一个名叫 `audio\song.mp3` 的单层文件 → 永远"频道没有可用音频"，而目录里明明有文件 | 新增 `normalizeSeparators()`，把 `\\` 统一成 `/`。覆盖音频路径、图标路径、txt 条目 |
| 2 | **UTF-8 BOM 让 txt 首行开关失效** | Windows 记事本"另存为 UTF-8"会写 BOM，首行变成 `"\uFEFFfalse"`；`trim()` 不去 U+FEFF → 开关静默失效 | 新增 `stripBom()` |

第 2 条特别隐蔽：BOM 是不可见字符，服主会困惑"我明明写了 true 怎么没打乱"。

##### WSL 实测结果

```
=== 反斜杠路径（Linux 语义）===
修复前: audio\song.mp3 -> exists=False
修复后: audio\song.mp3 -> exists=True
        sub\b.mp3     -> exists=True
=== txt 解析 ===
t_bom        打乱=true   条目=1   ← BOM 下开关正确生效
t_backslash  打乱=false  条目=1
t_crlf       打乱=true   条目=1   ← CRLF 正常
```

##### 审计通过项（无需改动）

| 项 | 结论 |
|---|---|
| 资源路径大小写 | 11 个资源文件，**0 个大小写冲突**，代码引用 0 处缺失 |
| 传给 ffmpeg 的路径 | 全部由 `getConfigDir()` 派生，**均为绝对路径**，不受 cwd 影响 |
| `ffmpeg` 可执行名 | 用裸名走 PATH；另有 `ffmpeg` 配置项可指定绝对路径 |
| CRLF / LF 行尾 | `readAllLines` + `trim()` 双向兼容 |
| 文件内容编码 | 所有读写显式 `StandardCharsets.UTF_8` |
| `RandomAccessFile` / `Path` / `ProcessBuilder` | 均为跨平台 API |

> **已知限制**（非本项目可控）：中文**文件名**的解析依赖 JVM 的
> `sun.jnu.encoding`。Linux 默认 UTF-8 正常；Windows 中文版若为 GBK
> 且存档路径含中文，属于 JVM 层面问题。

#### Z. 第十六轮：换曲心跳包 / 图标自动降分辨率 / 架构答疑

##### 1. 换曲间隙误报警（发心跳空包）

**根因**：`AudioStreamManager.pump()` 在 `pollChunk() == null` 时**直接 return，什么都不发**。
换曲/轮转/上游短暂卡顿时，读线程有几百毫秒拿不到数据，客户端那边就是"服务端没消息"——
而客户端判断「流僵住」「该不该响底噪」都看 `lastChunkAt`，
积累到 8 秒就判超时 → **点亮 NOSIGNAL + 响底噪**，可实际上服务一切正常，只是中间换了首歌。

**方案选择**：用户给了两条路——「做到无缝」或「发空包」。选了**发空包**：
- 无缝需要客户端预解码下一首并拼接 PCM，改动面大，且换曲处容易出爆音；
- 发空包精确表达了"我还活着，只是这段没数据"，十几字节。

实现要点：

| 位置 | 作用 |
|---|---|
| `sendHeartbeat()` | 复用 `S2C_AUDIO_CHUNK` 通道，**空载荷**；字段顺序必须与音频块完全一致 |
| `pump()` 无数据分支 | `isDue()==true` 但没数据 → 发心跳（`isFinished()` 时不发） |
| `pumpAll()` 未到节拍分支 | `isDue()==false` 时也要能发——**这条最容易漏**，配速可能把 `nextSendAt` 推到很远的未来 |
| `ClientAudioPlayer.handleChunk` | 空数组 → **只刷 `lastChunkAt` 就 return**，不喂解码器、不清故障显示 |

关键不变量：
1. 心跳**不调 `markSent()`** → 不参与配速，不影响音频节奏
2. 心跳**立即 `markHeartbeatSent()`** → 否则推送线程每 5ms 转一圈就会刷屏
3. 间隔 1000ms：正常播放（块间隔 ~250ms）时**心跳 0 次**；远小于客户端 8 秒超时
4. `source.isFinished()` 时**不发** → 上游真死仍能正常报故障

##### 2. 图标服务端自动降分辨率

服主常放 500×500 PNG，而界面只画 **16×16**。服务端下发前用 JDK 自带 `ImageIO`
缩到 **32×32**（留 2 倍余量防高 DPI 糊）：

| 项 | 数值 |
|---|---|
| fm907.png | 500×500 / 36639 B → **32×32 / 566 B**（省 **98%**） |

- 用 `ImageIO` + `Graphics2D` 高质量插值，**不引入新依赖**
- 缩放失败（headless/格式罕见）→ **退回原图**，不让图标消失
- **必须设 `java.awt.headless=true`**：专用服务器没有图形环境
- 带缓存（键含文件修改时间）；`/radio reload` 时清空

##### 3. 架构答疑：网络电台是服务端重分发

> ⚠️ **本节已被 AC 节（第十九轮，2026-11-24）整体推翻**：
> 用户澄清设计后改为**客户端直连**——音频字节不经过服务端，
> 服务端只维护订阅状态机。三个"硬理由"当时在旧架构内成立，
> 其中「客户端无需外网」被用户明确接受为新的前置条件（玩家需能访问上游流）。
> 保留本节仅作历史沿革，**不要再按它写代码**。

用户问"为什么感觉网络电台是服务端重分发而不是客户端直连"——**感觉是对的**。

```
上游 HTTP 流 → 服务端 StreamAudioSource(读线程) → AudioSubscription(配速)
             → S2C_AUDIO_CHUNK → 客户端 Mp3StreamDecoder → PcmSink → 声卡
```

三个硬理由：
1. **多人同步**：各自连流缓冲深度不同 → 内容错开数秒，直播流不可 seek 没法事后再对齐
2. **ICY 剥离**：上游每 N 字节插元数据，服务端剔一次即可
3. **客户端无需外网**：受限网络里玩家直连上游会失败

代价：服务端上行 = 码率 × 听众人数；上游断则所有玩家一起没声音（这是 O 节的既定职责划分）。

#### AA. 第十七轮：打乱模式「第一首永远相同」

**现象**：用户反馈「随机打乱播放，每次播放的第一个都是同一个音频」。

**根因**：轮次 = `elapsed / roundTotalMillis`，而 `elapsed` 从服务端启动/重载那一刻算起
→ **每次都从第 0 轮开始**。而第 0 轮的顺序由 `mixSeed(seed, 0)` 唯一决定
（seed 不填时按频道 id 派生，更是恒定），于是首曲永远是同一首。

> 这不是"打乱没生效"——**每一轮内部确实是打乱的**，只是每次都从同一轮开始。

**修复**：新增 `roundOffset`（起点轮次随机偏移），仅在 `random` 模式生效：

```java
this.roundOffset = random
    ? (int) Math.floorMod((System.currentTimeMillis() / 3_600_000L) * 2_654_435_761L, 1_000_003L)
    : 0;
```

- 乘大奇数散开：直接用 `now/3600000` 时相邻小时只差 1，实测 12 次开服仅 4 种首曲；
  乘后 **5 首池全部覆盖**
- **同一进程内稳定**（构造时算一次，`final` 字段），不会每次订阅都跳歌
- **同步性不受影响**：时间轴只在**服务端**构造（客户端零引用），
  所有玩家共用同一实例、同一 `startEpochMillis`，因此同一时刻算出的轮次/位置仍完全一致

**配套修复**：`PlaylistTimeline.clearCache()`，并在配置重载时调用。
时间轴实例固化了 `roundOffset`，复用旧实例会导致「重载了但首曲还是那首」。

> **后续修复（同日）**：上面这个偏移只有「小时」精度，**同一小时内连续两次 `/radio reload`**
> 抽到的是同一个起点轮次——顺序没变、第一首还是刚才那首，实测反馈正是如此。
> 现在 `roundOffset` 改为**每次构建现抽**（`pickFreshRoundOffset`），并且候选轮次的首曲
> 必须与上一次构建不同才接受，因此每次重载都换一轮、首曲必换。
> 同步性不受影响：时间轴仍只在服务端存在，偏移在实例内固化。

##### 顺带核实的架构问题

用户问「网络电台为什么感觉是服务端重分发而非客户端直连」——**感觉是对的**，
详见 Z 节第 3 小节（三个硬理由：多人同步 / ICY 剥离 / 客户端无需外网）。

#### AB. 第十八轮：音量滑条底部遮挡

**现象**：用户反馈「音量滑条下段往上一点，有点遮挡」。

**根因**：`volumeBottom = height - 50`，而底部按钮从 `height - 30` 开始、高 20。
滑条面板背景还向外扩 2px，于是面板底边到 `height - 48`，
与按钮区（`height - 30`）重叠约 18px——
**百分比文字画在 `volumeBottom - 10`，正好被按钮压住**。

**修复**：`volumeBottom` 改为 `height - 38`，并按自下而上的布局需求推导：

```
按钮底边           = height - 10
按钮顶边           = height - 30
留 6px 间隙        -> 面板底边上限 = height - 36
面板背景外扩 2px   -> volumeBottom 上限 = height - 38
```

实测四种窗口高度（240 / 300 / 480 / 720）均满足：
- 面板与按钮间隙恒为 **6px**（无重叠）
- 百分比文字底边距面板底 **2px**（不贴边）
- 滑条轨道底始终在文字上方

点击/拖动命中区（`mouseY <= volumeBottom`）与音量映射（`volumeBottom - 12`）
用的都是同一个字段，随之自动一致，无需另行修改。

#### AC. 第十九轮：网络电台架构改造 —— 客户端直连（2026-11-24，本轮最重要）

##### 用户澄清的完整链路设计（这是本轮所有改动的依据）

> 进游戏 → 连服务器 → 换台到网络电台 → **断网**（上游互联网断，
> 与服务器的连接全程正常）→ 客户端缓冲变薄同时底噪抬高 →
> 4 秒后 NOSIGNAL + 闪灯 + 持续底噪 → 自动重连上游流 →
> 网络恢复 → 客户端重连音频流 → 缓存变厚过程中底噪渐弱 → 一切如常。

关键约束（用户逐条纠正，务必遵守）：
- **服务端不知道也不控制客户端的灯/NOSIGNAL**——服务端不检查网络、不宣告 TIMEOUT
- **网络电台对服务端只是一个订阅状态机**——音频字节完全不经过服务端
- **没有心跳包**；**缓存在客户端**
- 空音量包（S2C_AUDIO_SILENCE）的职责是**防止客户端把预载空窗误判成故障**，
  **绝不触发调台噪**（早期误接过调台噪，已纠正并回退）

##### 落地改动

| 项 | 改动 |
|---|---|
| 服务端 | 网络电台 subscribe 只登记 `STREAMING_SUBS` 回 OK（幂等复用同语义）；tick 巡检走远→TOO_FAR、拆除→STOPPED；**心跳机制整体删除**（Z.1 的心跳空包方案被 S2C_AUDIO_SILENCE 空音量包取代，200ms 节流） |
| 客户端拉流 | 新增 `ClientAudioPlayer.StreamFeed` 拉流线程：订阅 OK 后直连上游，**复用 `StreamAudioSource`**（ICY 剥离 + 指数退避重连 1s→2s→4s→**8s 封顶**，无限重试，`sessionId++`） |
| 解码器重建 | 会话号变化 = 上游断过又接回：字节流不连续，必须重建解码器；恢复入口走 `onLinkRecovered()`（底噪按落音速率淡出），**不再** `beginTransitionSilence()`（否则"噪声硬切→干等静音窗→才有声音"） |
| 超时 | 客户端停滞判定 `STALL_TIMEOUT_MILLIS` 8s → **4s**（用户定值） |
| 水位 | `DEPTH_SMOOTH_ALPHA` 0.02→**0.1**（TC 1s→200ms，否则断供检出永远在静音之后）；`BUFFER_THIN_PERMILLE` 550→**650** |
| 弱底噪 | **粘性三件套整体拆除**（weakNoiseHeld 等）+ **饥饿闸门**：弱档只在「最近 3 秒真饿过或绝对见底」时允许——根因见下方"第八次语义混淆" |
| 故障锁存 | `faultLatched`：宽限过后仍连续无数据 → 电平**锁死 1.0**（修"NOSIGNAL 期间 0.4-0.6-1.0-0.3 摆动"）；解锁=持续 25 块健康或显式恢复入口 |
| 饥饿宽限 | 从「归零」改为「**冻结当前增益**」（0.4→保持→平滑爬 1.0，不再 0.4→0→1.0 跳变） |
| 恢复灭灯 | `clearClientFaultOnResume` 补挂 `handleUpstreamChunk`（网络台此前从不清屏幕故障）；AutoSubscriber stale 点灯路径补 PcmSink 故障登记 |
| 噪声响度 | `NetworkNoise` PEAK **0.12**（用户否决 0.25：「抬高响度会盖住其他电台，至少降到电台同档」） |

##### 第八次「一种语义两处实现」：弱底噪粘性

用户实测「1.0 之后一直 0.3，你完全修错了」——真根因是**稳态基线棘轮永久误判**：
客户端直连是 1:1 实时流，建连时 TCP 一口气灌满、棘轮基线抬到峰值，
之后实时 pacing 下缓冲长期停在远低于峰值的水平 →「相对变薄」判据**永久为真**，
粘性把 0.4 永久按住。修法：相对变薄本身不报警，**只有它真的导致了饥饿才报警**。

#### AD. 第二十轮：订阅下发增益 + 调台噪/断网噪时机与响度（2026-11-24 晚）

##### 1. 网络电台声音小 → streamGains 残留（第九次语义混淆）

**现象**：网络电台声音小（系统音量+收音机音量全开）。
排查：`outputGain` 配置不作用于网络电台；`convert()` 无衰减——增益链干净。
**真凶**：方块先放本地电台时服务端下发 `outputGain=0.25` 存入客户端 `streamGains`，
换成网络电台后**服务端不再发任何增益包**（网络台没有音频块，
pump 的「变了才补发」永远不会触发）→ 0.25 永久残留，整路被压到 25%。

修复（两层）：
- **服务端随订阅回复下发增益**（用户提议，协议级根治）：`subscribe()` 网络电台分支
  （新建 + 幂等复用两条路径）在 OK 后发 `S2C_OUTPUT_GAIN`（`STREAM_GAIN=1.0`，透传流不压）
- 客户端兜底：`handleChunk`/`handleUpstreamChunk` 建流时、`stopInternal` 停流时
  `setStreamGain(key, 1.0)` 清残留（满增益触发 remove）

##### 2. 调台噪定型（用户实测「声音没了噪音才起」「峰值响度太低」「淡入时机太晚」）

| 项 | 改动 |
|---|---|
| 触发点 | 从 `S2C_SELECT_ACK` 提前到**玩家点台瞬间**（`selectStation` 发包前）：拧旋钮那一刻噪声就起，与仍在放的旧台重叠；选择失败时旧音频继续供数、噪声经「连续稳定」判定自然淡出 |
| 响度 | `TuningNoise.HOLD_LEVEL` 0.075 → **0.25**（节目同档）：旧参数比节目轻一档以上，只会填补空白盖不住任何声音 |
| 去重 | 窗内重复触发从「直接忽略」改为 **`retrigger()`**（只清稳定计数与观察期、刷新 startedAt，**不重置增益/滤波器**）：高 ping 下回执迟到超过稳定判定窗（约 300ms）时，旧台残余音频会把「接上了」喂满导致噪声提前退场——正是「声音没了噪音才起」的另一半根因 |

##### 3. 断网底噪提前（用户实测「断网的噪声起的也太晚了」）

旧时序：节目停 → 宽限冻结 600ms → 锁存满档 → 起音爬 600ms，静音后近 1.2s 噪声才到位。

- `STARVE_GRACE_MILLIS` 600 → **300**：起播/换曲的正常空窗已有显式过渡静音窗挡在前头，
  走不到宽限；剩下的真饥饿没有理由再让玩家等 600ms
- `NetworkNoise.ATTACK` 0.033 → **0.08**（0→满约 250ms）：仍是可感知渐变
  （不是瞬时跳变），静音到满档的总延迟压进半秒左右；落音（恢复淡出 1.5s）不动
- 缓冲排空前最后约 125ms（绝对见底线）弱底噪就开始渗，
  配合更快的起音，节目还没停干净时噪声已经上来

#### AE. 第二十一轮：推流增益按频道归属隔离（2026-11-25）

##### 用户提问与核对结论

用户提出「本地电台的增益会污染网络电台的增益」。**核对整条链路后，方向相反但问题更严重**：
不是本地台污染网络台，而是**本地台自己的 `outputGain` 被客户端抹掉了**。

完整证据链：
1. 服务端 `pump()` 按「值变才发」（`sub.noteSentGain(gain)`）下发本地台增益 `0.25`；
2. 客户端收到后写入 `streamGains` 表；
3. 但客户端在 `handleChunk` 建流时、`handleUpstreamChunk` **每块**、`stopInternal` 停流时
   都调 `setStreamGain(key, 1.0F)` 想「清掉残留的推流增益」；
4. `1.0` 在 `setStreamGain` 里触发「满增益不留表项」→ 直接 `remove`；
5. 服务端 `lastSentGain` 已是 0.25，判定「没变」不再重发；
6. 结果：**本地台整场按 100% 电平播放，`outputGain` 配置完全失效**。

##### 根因（第十次「一个值承担两种语义」）

`1.0` 同时被当作「不衰减」这个**合法业务值**和「没有增益记录」这个**清理信号**。
两者语义完全不同，混用之后清理动作等价于取消压制。叠加第二个缺陷：
`S2C_OUTPUT_GAIN` 载荷只有 `(BlockPos, float)`，**不带 stationId**，客户端无从判断
收到的增益包属于哪个频道，只能按方块盲写——这才是所有换台时序竞态的根源。

##### 修法

| 层 | 改动 |
|---|---|
| 协议 | `S2C_OUTPUT_GAIN` 载荷由 `(BlockPos, float)` 改为 `(BlockPos, stationId, float)`，带频道归属 |
| 版本兼容 | **不需要兼容层**：本模组新旧客户端/服务端本就无法混用（Fabric 的通道注册与 registry 握手会挡掉版本不一致的一方），因此载荷格式可以直接演进，无需保留旧布局的降级分支 |
| 数据结构 | `PcmSink.streamGains` 由 `Map<Long, Float>` 改为 `Map<Long, StreamGain>`（`record(stationId, gain)`） |
| 校验 | `streamGainFor(blockPos, stationId)` 做频道比对，记录属于别的频道时返回 `1.0`（旧台迟到包自动失效） |
| 归属登记 | 新增 `blockStations` 映射（各方块当前频道），由 `ClientAudioPlayer` 建流 / 换台时写入 |
| 清理 | **移除全部 `setStreamGain(key, 1.0F)`**，改由频道校验自动失效；新增 `clearStreamGain(key)` 把「移除记录」与「增益为 1.0」严格分开 |

> ⚠️ **不要在任何地方用 `setStreamGain(key, 1.0F)` 表达"清理"**。
> 需要清理就调 `clearStreamGain(key)`。这是本轮的核心教训。

> ⚠️ **不要在 `handleChunk` / `stopInternal` 里清增益记录**：服务端发包顺序是
> 「增益包 → 音频块」，走到这些分支时新台的增益包**早已落地**，清表抹掉的
> 正是刚收到的新台增益。旧记录交给频道校验失效即可。

#### AF. 第二十二轮：按实测基准校准 `outputGain`（2026-11-26）

##### 用户提问

用户给了两段素材——一段《明日方舟：终末地》实机录像（34s）与一份 320 kbps MP3（254s）——
要求「让收音机 100% 时的增益做成视频 BGM 那样的音量」，并明确要求**用频谱对照**。

##### 测量方法（可复现）

1. 两轨统一解为 48 kHz / 立体声 / s16le PCM；
2. 用 ffmpeg 的 `highpass + lowpass`（poles=2）切成 8 段：
   60-120 / 120-250 / 250-500 / 500-1k / 1k-2k / 2k-4k / 4k-8k / 8k-16k Hz；
3. 逐段按 100 ms 帧算 RMS（dB），视频侧取**整体电平最低的 25% 帧**作中位数——
   这些帧里音效最少，近似「只剩 BGM」；
4. 源曲侧取全曲各段中位数，逐段相减。

**关键提醒**：高频段不能降到 8 kHz 采样，否则混叠会污染 4k-8k / 8k-16k 两段。
第一版就踩了这个坑，8k-16k 段的差值因此虚高（-14.8 dB），修正重采样后才对齐。

##### 测量结果

| 频段 | 60-120 | 120-250 | 250-500 | 500-1k | 1k-2k | 2k-4k | 4k-8k | 8k-16k |
|---|---|---|---|---|---|---|---|---|
| 源曲 dBFS | -19.4 | -20.1 | -19.5 | -19.8 | -22.2 | -26.6 | -31.1 | -34.0 |
| 视频 BGM dBFS | -41.6 | -39.7 | -39.1 | -41.5 | -43.4 | -45.9 | -51.4 | -48.8 |
| 差值 dB | -22.3 | -19.7 | -19.6 | -21.7 | -21.2 | -19.3 | -20.3 | -14.8 |

- 源曲积分响度 **-9.9 LUFS**（真峰 **+0.9 dBFS**，已削波）；视频整轨 **-25.4 LUFS**（峰值 -11.0）。
- 八个频段几乎平行地落在 -20 dB 附近；把「低电平帧」的门槛从 25 分位收紧到 10 / 5 分位，
  差值从 -20.1 / -21.1 收敛到 **-21.8 dB**（音效污染越少，越接近真实 BGM 电平）。
- 结论：**游戏混音里 BGM 相对母带被整体压低约 20 dB**，不是某一段单独做的手脚。

##### 顺带排除的一个错误前提

视频里的 BGM 与这份 MP3 **不是同一录音**：三频段（150-600 / 600-1500 / 1500-3200 Hz）
包络全时长互相关峰值仅 **0.17 / 0.26 / 0.28**（同一录音应在 0.9 以上）。
所以只能做**电平对齐**，不能做逐样本相减。这不影响结论——用户要的是响度参照，不是内容匹配。

##### 落地

| 项 | 值 |
|---|---|
| `DEFAULT_OUTPUT_GAIN` | `0.25` → **`0.10`**（-20 dB） |
| 目标电平 | 100% 音量时 **-30 LUFS** |
| 验证 | 源曲乘 0.10 实测 **-29.9 LUFS**，真峰 -19.1 dBFS（余量 19 dB，底噪层不会削波） |
| 配置文件 | `run/config/radio-smile/stations.json` 与 `serverconfig/…` 的示例值同步改为 0.10；README 中英文示例与表格同步 |

##### 用户决策留下的已知取舍

用户选择**先只为本地台校准**（保持「网络电台原样透传」的旧约定），目标 -30 LUFS。
由此产生的偏差已经写进 `RadioStationRegistry#DEFAULT_TARGET_LOUDNESS` 的注释：

> 本地压到 0.10 后约 -30 LUFS，而网络流常见 -15 LUFS，**从本地台切到网络台会响约 15 dB**。

要消除这个跳变只有两条路，都需要单独决策：
1. **整机主增益**——让 `outputGainFor` 对 `isStreaming()` 也生效，两路一起压到 -30 LUFS
   （推翻「不改别人的流」的约定，改动只有一行，但语义变了）；
2. **逐曲响度归一化**——预热转码时用 ffmpeg 实测每首的积分响度，
   按「目标 − 实测」算增益，母带差异和网络台差异一并消失（要改缓存格式与增益链路，改动最大）。

> ⚠️ **固定增益无法适配母带差异**：-9.9 LUFS 的曲子压 20 dB 正好 -30，
> -15 LUFS 的曲子同样压 20 dB 就只有 -35。多首混播时响度仍然会不齐——这是 0.10 这个数值的固有限制。

#### AG. 第二十三轮：逐曲响度归一化（2026-11-26 晚）

##### 用户要求

「每首歌的减益写在缓存元数据里，做对应的归一化处理」，
并明确**否掉了另一种做法**：客户端自动识别当前频道音量再归一化（不采纳）。

即：**服务端测、服务端存、服务端算、服务端下发**，客户端只做乘算。

##### 数据流

```
预热阶段（后台线程，一次性）
  源文件 ──ffmpeg loudnorm──→ input_i（积分响度 LUFS）+ input_tp（真峰）
        └─→ cache/loudness/<quickHash 前24位>.lufs   （lufs=… / tp=…）

播放阶段（每个音频块，O(1)）
  当前曲目 ──实测LUFS──→ 增益 = 10^((target − lufs)/20) ──S2C_OUTPUT_GAIN──→ 客户端
                                                                  （乘以混音增益）
```

##### 关键实现点

| 位置 | 作用 |
|---|---|
| `LoudnessNormalizer`（新增） | 实测、元数据读写、增益换算。**只衰减不放大** |
| `AudioSource.currentFile()`（新增） | 由音源回答「此刻放的是哪个文件」，不按 `trackIndex` 查列表 |
| `AudioTranscoder.sourceOf()`（新增） | 转码产物 → 源文件的反查 |
| `AudioStreamManager.outputGainFor()` | 先问归一化器；算不出来时分两种：归一化被关闭 → 1.0 原样播放，未实测 → `UNMEASURED_GAIN` 过渡 |
| `RadioStationRegistry.targetLoudness` | 配置项，`0` = 关闭归一化（默认值见 AI 节：**-19**，本节拍摄时是 -30） |
| `S2C_OUTPUT_GAIN` | **协议不变**，仍按 `(BlockPos, stationId, float)` 下发；换曲时值变了就补发 |

##### 两个必须记住的坑

> ⚠️ **非 MP3 源播放的是「转码产物」，不是源文件。**
> `PlaylistTimeline.streamFileOf()` 在产物存在时返回 `<base>-<hash12>.mp3`，
> 元数据按**源文件**哈希命名，直接拿产物路径查哈希永远查不到——
> 而且失效是静默的（增益一直是兜底值，不报错）。
> 现在由 `AudioTranscoder.BAKED_SOURCE` 登记映射，`sourceOf()` 负责还原。
> **任何按文件取元数据的代码都必须先过 `sourceOf()`。**

> ⚠️ **增益被夹在 0.00~1.00，所以不要试图放大于 1。**
> 客户端 `PcmSink.setStreamGain` 按 `0~1` 夹取，并把「满增益」当"不衰减"直接删表项。
> 服务端返回 1.7 会被静默夹成 1.0：看起来归一化生效了，实际没抬起来。
> 用户已明确「本身偏轻不用抬」，因此本类只做衰减。
> 真峰仍写进元数据——将来若要放开放大，不必重新解码整个曲库。

##### 端到端验证（2026-11-26 21:04，真实服务端）

`gradlew runServer` → 配置加载 → 后台实测 → 5 首全部成功（耗时 84.8 s，一次性）。
4 个元数据文件（其中两首内容相同，共享同一份，符合「按内容哈希命名」）：

| 元数据 | 实测 LUFS | 归一化增益 | 旧固定 0.10 时的输出 | 偏差 |
|---|---|---|---|---|
| `17cffbb8…` | -12.98 | 0.141 | -32.98 | -3.0 |
| `616410df…` | -11.37 | 0.117 | -31.37 | -1.4 |
| `c8e22d78…` | -6.64 | 0.068 | -26.64 | +3.4 |
| `e388c53e…` | -6.31（真峰 +2.24） | 0.065 | -26.31 | +3.7 |

固定增益下这四首的输出跨度 **6.7 dB**（-33.0 ~ -26.3）；归一化后全部落在 -30。
（本节数据拍摄于目标 -30 时期；现行目标 -19，同一批曲目的增益整体上移 11 dB，仍然全部对齐。）
`e388c53e` 的源文件真峰 +2.24 dBFS，本身已经削波——这也说明「按曲实测」是必要的。

##### 已知边界

- **网络电台不参与**：直播流是原样透传，且客户端直连上游，服务端拿不到可分析的整体内容。
- **首次启动会慢一点**：每首一次完整解码（约 17 s/首，5 首 85 s），在后台线程串行，不阻塞开服。
- **换曲瞬间可能有一块错位**：增益按 `currentFile()` 实时算，而音频块在队列里，
  极端情况下换曲的第一块可能用上一首的增益（约 20 ms，不可闻）。
- **没有 ffmpeg 时回落到过渡电平**（`LoudnessNormalizer.UNMEASURED_GAIN`，当时 0.12、现行 0.40），日志会有一条明确的 WARN。
- 元数据只跟文件内容有关：**改名不重测，改内容必重测**（哈希含文件大小 + 头部 64 KB）。

#### AH. 第二十四轮：删除 `outputGain`，实测网络电台的真实电平（2026-11-26 深夜）

##### 用户要求

1. 「outputGain 可以删了」——逐曲归一化接管之后，这个固定旋钮已无独立作用；
2. 顺带核对「一个块里应该包括声音增益和音频数据」，并「拉一段真实案例看看网络电台的声音大小」。

##### 链路核对：增益<b>不在</b>音频块里

真实发包顺序（`AudioStreamManager.pump`）：

| 包 | 载荷 | 何时发 |
|---|---|---|
| `S2C_OUTPUT_GAIN` | `BlockPos + stationId + float` | **值变才发**（`AudioSubscription.noteSentGain`） |
| `S2C_AUDIO_CHUNK` | `BlockPos + stationId + trackIndex + sessionId + formatId + byte[]` | 每块 |

两者在同一个 `server.execute` 里「先增益包、后音频块」，Fabric 自定义载荷走可靠有序通道，
所以客户端一定先拿到新增益、再处理对应音频。**增益没有塞进音频块**——塞进去会让解码器
拿到非音频字节流，且 `byte[]` 头部的偏移一旦算错就整条流错位。

##### 实测：真实网络电台的电平

`FM90.7`（`https://lhttp-hw.qtfm.cn/live/15318146/64k.mp3`，64 kbps MP3 / 48 kHz / 立体声），
抓 60 秒：

| 指标 | 值 |
|---|---|
| 积分响度 | **-18.5 LUFS** |
| 真峰 | -9.2 dBFS |
| 响度范围 LRA | 1.7 LU |

再抓 120 秒切成 4 段：-19.1 / -19.6 / -18.2 / -18.3 LUFS。
**电平几乎恒定**——电台侧有广播压限（AGC + 限制器），节目与广告之间不做电平跳变。

与本地曲目（已归一化到 -30 LUFS）逐频段对比：

| 频段 | 60-120 | 120-250 | 250-500 | 500-1k | 1k-2k | 2k-4k | 4k-8k | 8k-16k |
|---|---|---|---|---|---|---|---|---|
| 网络台比本地台响 | **+7.2** | **+11.9** | **+14.1** | **+13.9** | **+12.6** | **+11.4** | **+10.1** | **+7.0** |

中位 **+11.9 dB**。不是平坦的偏移：中低频差得最多（14 dB）、两端差得少（7 dB），
这是电台侧压限与音色处理的形状，也说明「给网络台乘一个固定系数」对不齐音色。

##### 改动

| 项目 | 处理 |
|---|---|
| 配置项 `outputGain` | **删除**（读取函数、默认配置生成、README 中英文表格、示例值全部移除） |
| `LoudnessNormalizer.UNMEASURED_GAIN`（新增） | 当时定为 `0.12`（≈ -18.4 dB），**还没实测**时的过渡电平；现行 `0.40`（见 AI 节） |
| `targetLoudness >= 0`（关闭归一化） | 改为**原样播放（1.0）**，不再偷偷压缩 |
| `AudioStreamManager.outputGainFor()` | 两种「算不出来」严格分开：关掉 → 1.0；未实测 → 0.12 |

> ⚠️ **为什么不能把「算不出来」一律当 1.0**：本地母带集中在 -11 ~ -13 LUFS，
> 1.0 等于让它在满电平炸出来。必须先压到目标附近，等实测值到位再换成精确值。
> 反过来，用户显式关闭归一化时又不能偷偷压——那等于配置项没生效。
> 两种语义混在一起，就是本项目已经踩过十次的那个坑。

##### 当时仍未解决

网络台恒 1.0、本地台 -30 LUFS，**相差约 11.5 dB**（实测确认）。
直播流既无法逐曲实测（没有"整首"这个概念），也不能逐块估算。
→ **已在第二十五轮解决，但方向与最初的设想相反：不是压网络台，而是把本地台抬到网络台的电平。**

#### AI. 第二十五轮：本地对齐网络，网络不动（2026-11-27 凌晨）

##### 用户要求（两次澄清，第一次我理解反了）

1. 「你对齐下网络台的响度标准吧」——我按字面做成了「给网络台乘 0.282 压到 -30」；
2. 立刻纠正：**「我说的是本地对齐网络，网络不变！！！」**
3. 补充边界：**「本地暂时不向上提增益超过 1」**（只衰减，不放大）。

所以方向是单向的：**网络直播流一个字节都不动，改的是本地曲目的目标电平。**

##### 改法：只动一个数

| 项 | 旧 | 新 |
|---|---|---|
| `DEFAULT_TARGET_LOUDNESS` | `-30.0` | **`-19.0`** |
| `UNMEASURED_GAIN`（未实测过渡电平） | `0.12` | **`0.40`** |
| 网络台增益 `STREAM_GAIN` | `1.0` | **`1.0`（不变）** |
| 客户端 `PcmSink.setStreamGain` 的 0~1 夹取 | 夹取 | **不变**（正好等于"不向上提"的边界） |

-19.0 的依据是 AH 节那五段实测：-19.1 / -19.6 / -18.2 / -18.3 / -19.07，
均值 -18.9、中位 -19.07，取整到 **-19.0**（误差 ≤0.4 dB，低于可辨阈值）。
网络台实测中位 -19.07，本地落在 -19.0，切台时两者差 **0.07 dB**，听不出跳变。

##### 为什么"抬到 -19"不等于"放大"

之前定的 -30 是拿游戏录像的 BGM 做基准，比网络台低 11 dB，
混着听就是"本地台明显偏小"。换成 -19 之后，绝大多数曲子仍然是**衰减**：

| 实测源 | 目标 -19 所需增益 | 实际效果 |
|---|---|---|
| -6.31 | 0.232 | 衰减 12.7 dB |
| -6.64 | 0.241 | 衰减 12.4 dB |
| -11.37 | 0.415 | 衰减 7.6 dB |
| -12.98 | 0.500 | 衰减 6.0 dB |
| -25（偏轻素材） | 1.0（夹住） | **原样播放，不抬** |

只有比 -19 更轻的素材会停在 1.0 —— 这正是用户要的「不向上提增益超过 1」。
`LoudnessNormalizer.gainFrom()` 的 `db > 0 → 0` 与客户端的 0~1 夹取形成了双重保险，
两个方向都不会意外放大。

##### 撤掉的改动（曾短暂实现"压网络台"）

理解反的那一版加了 `streamLoudness` / `streamGain(RadioStation)` /
`RadioStation.loudness` / `AudioStreamManager.refreshStreamingGains()`，
并按 `10^((target − 源响度)/20)` 给网络台下发 0.282。用户澄清后**全部回滚**：

- `RadioStation` 恢复 7 个组件（没有 `loudness`）；
- `RadioStationRegistry` 不再有网络台相关常量/字段/读取函数；
- `AudioStreamManager.STREAM_GAIN = 1.0F` 恢复原语义（订阅时下发只为清掉本地台残留值）。

> ⚠️ **别再往网络台方向改**。用户对"网络台电平"的约定是「原样透传」，
> 11 dB 的差距要在本地侧消化。若哪天觉得本地太响，改 `targetLoudness`
> （往 -22 ~ -24 调），不要动网络台。

##### 这一轮的代价

本地台整体比上一轮**响约 11 dB**。这是目标值变化的必然结果，
不是 bug；若实际听感偏吵（例如盖住了游戏音效），把 `targetLoudness`
调到 -22 ~ -24 即可，不需要改代码、也不用重测（实测电平与目标无关）。

#### AJ. 第二十六轮：启动检查自动禁用失效源（2026-11-27 中午）

##### 用户要求（两次补充，把范围收敛到「播放列表里的源」）

1. 「如果源文件在启动检查发现返回 404 或者失效了就注释掉这一行」；
2. 补充：**「我指的是网络源文件进入播放列表」**——不是频道的 `file` 直播流；
3. 再补充：**「再加上本地源文件，支持相对路径（基于列表文件位置）」**。

所以作用域是 **txt 播放列表（`file` 指向 .txt/.list/.m3u）里的每一条来源**，
两类都管：网络地址与本地文件。

##### 写法：不删、只注释，原因写在行首

JSON 没法注释，但 txt 天然支持 `#`。沿用第二十轮就有的「流式地址自动注释」做法：

```text
# [已禁用：文件不存在] gone.mp3
# [已禁用：HTTP 403，源文件已失效] https://.../expired.mp3
# [已禁用：流式传输，无法缓存] https://.../live.mp3
```

- 原文<b>完整保留</b>：失效的签名直链改好参数就能用，本地文件放回去就能播；
- 恢复方式 = 删掉行首那段 `# [已禁用…] ` 前缀（比"从备份里翻"实际得多）；
- 重复执行是幂等的：已注释的行不会被再次加前缀（实测第二次 `commented=0`）。

##### 判据一：网络源——只认「确定性 4xx」

| 状态码 | 处理 | 理由 |
|---|---|---|
| `400 / 401 / 403 / 404 / 410` | **注释掉** | 客户端侧被拒绝，重发一万次结果相同。过期签名直链就是 403 |
| `408 / 429` | 只记 WARN | 明确表示「过会儿再来」，与链接是否有效无关 |
| `5xx` / 超时 / 连接重置 | 只记 WARN | 上游故障、限流、本机网络抖动，一次失败不足以改配置文件 |

判据收在 `RemoteAudioCache.isPermanentFailure(int)` 一个方法里，
实测 13 个状态码：`200/206/301=false`，`400/401/403/404/410=true`，
`408/429/500/502/503=false`。

> 这一条是<b>实测出来的</b>：用户 `realtest.txt` 里的网易云直链带时间戳，
> 过期后返回 **403** 而不是 404（12:20 用 curl 逐个验证：11-23 的两条 403，
> 当天 12:25 的一条 206）。所以判据不能只写 404，否则对真实场景完全失效。

##### 判据二：本地源——文件不存在

`TxtPlaylist.sanitizeLocalMissing(base, audioDir)` 在启动预热线程里跑一趟，
逐个 txt 解析本地行：能解析到文件就保留，解析不到就注释成
`# [已禁用：文件不存在]`。

**只在启动时做，不在播放路径做**。理由：`parse()` 每次订阅都要跑，
一次磁盘抖动就可能把一行好条目注释掉；而且服主可能正在上传大文件。
启动检查是一次性的、边界清晰的时机，误判代价最小。

##### 相对路径：以列表文件所在目录为第一基准

`resolveEntry()` 的顺序是 **txt 所在目录 → `audio/` → 绝对路径**，
所以 `audio/lists/rock.txt` 里的 `../hit.mp3` 命中 `audio/hit.mp3`，
而直接写 `hit.mp3` 则命中 `audio/hit.mp3`（第二基准）。启动检查复用同一个函数，
因此"检查"和"播放"用的是同一套路径语义——不会出现"检查说不存在、播放却能放"。

##### 两条必须记住的边界

1. **本地已有完整缓存的网络条目不会被注释。**
   `download()` 遇到 4xx 时先 `findAnyCachedFile()`：`cache/remote/` 里已有非空文件
   就直接返回它、继续播放。上游失效 ≠ 这条不能播——大概率只是签名过期导致
   校验必然失败，而音频本体早就落地了。注释掉它等于亲手删掉一个能播的曲目。
2. **写文件是原子替换**（`TxtPlaylist.writeAtomically`：同目录 `.tmp` + `ATOMIC_MOVE`）。
   这份文本正被播放线程读，原地覆写会让它读到半截内容（表现为"列表突然变空"）。

##### 并发与顺序

`preheatTxtNetworkEntries()` 的线程里**先**跑本地检查、**后**解析下载，
两步串行在同一线程：本地注释不会与网络 4xx 触发的注释同时写同一个文件。

##### 端到端验证（2026-11-27 12:2x，本地夹具）

用一个临时目录 `config/audio/lists/rock.txt` 覆盖全部情形，直接调 `sanitizeLocalMissing`：

```text
qtfm_tiehen.ogg                → 保留（相对 audio/）
../qtfm_tiehen.ogg             → 保留（相对 txt 所在目录）
gone.mp3                       → # [已禁用：文件不存在]
also_gone.ogg   # 备注         → # [已禁用：文件不存在] also_gone.ogg   # 备注
https://...（网络）             → 不动；再由 commentOutInTxt 注释成 HTTP 403
```

第二次执行 `commented=0`，幂等成立。

#### AK. 第二十七轮：切曲不再「先切断再等」+ 打乱语义统一（2026-11-27 中午）

##### 用户反馈（三句，同一轮）

1. 「切曲的响应速度不够快，然后切的途中还有噪音」；
2. 「空音量包应该没有声音的」；
3. 「列表播放的随机播放又失效了…」。

第 1、2 句是同一件事的两面，第 3 句另起一条线。

##### 噪音的根因：`handleChunk` 把「换曲」当成了「换台」

客户端收到块时，三种情况走的是<b>同一段代码</b>：

```java
if (stationChanged || trackChanged || streamRestarted) {
    stream.close();
    playTuningNoise(pos);   // ← 换曲也响了调台噪
    flush();                // ← 换曲也清了抖动缓冲
    clearNetworkNoise();
    beginTransitionSilence();
}
```

用户先怀疑「空音量包在放噪声」。**它不是**——`S2C_AUDIO_SILENCE` 的接收器只调
`beginTransitionSilence(millis)`，一条注释明确写着「这里绝不触发调台噪」。
真正在响的是上面那行 `playTuningNoise()`：它是 0.25 幅度、跟随空窗的**调谐嘶声**，
设计目的是盖住「换台」的跳变，却被自动换曲一并触发。

##### 慢的根因：换曲时 `flush()` 清掉了还没播的音频

抖动缓冲里当时存的是<b>上一首还没播到的那 200~400ms</b>，那是节目的一部分，不是垃圾。
`flush()` 把它清掉，`primed` 随之失效，必须重新攒满 `jitterTarget`（≥200ms）才再次出声。
于是每次换曲都要经历：掐掉上一首结尾 → 300ms 数字静音 → 噪声盖着 → 新曲接上。
「慢半拍」与「有噪音」是同一段代码产生的两个症状。

##### 改法：换台与换曲彻底分流

| | 换台（`stationChanged`） | 台内换曲 / 上游重连 |
|---|---|---|
| 解码器 | 重建 | 重建 |
| 调台噪 | **响**（盖住跳变） | **不响** |
| `flush()` | **清**（旧台不能继续播） | **不清**（尾巴要播完，新曲接在后面） |
| 底噪 | 清掉并重新登记归属 | 不动（链路没断） |
| 过渡静音窗 | 900ms（默认） | **400ms** 兜底 |

不 flush 之所以成立：服务端推给客户端的是**连续字节流**（换曲只是换文件，
中间没有空隙），客户端缓冲里的旧尾巴播完，正好接上新曲开头。
缓冲深度不增加、也不丢数据——这是真正的无缝衔接，而不是"用噪声盖住断点"。

服务端的 `TRACK_SWITCH_SILENCE_MILLIS` 也顺势从 **900 → 500**：
空窗既然基本消失，过长的声明只会让真断流的底噪被压住近一秒才响。

##### 顺带修掉的一个跨曲污染

`pendingSkipMillis` 是握手包（订阅确认）下发的「客户端要丢弃的前段」，
早期<b>用完从不清零</b>：一次定位之后，每次换曲都沿用同一个跳过量，
每首歌开头都被吞掉几百毫秒。现在改为**创建解码器时消费一次并清零**，
且换曲分支显式清零。

##### 随机播放「失效」的根因：两个开关、两套语义

打乱在配置里有<b>两个入口</b>，此前行为完全不同：

| 入口 | 旧行为 | 结果 |
|---|---|---|
| `mode: random` | 交给 `PlaylistTimeline`，每轮重新洗牌 | ✅ 正常 |
| txt 首行 `true` | 解析时用「文件名派生的固定 seed」洗**一次** | ❌ 每轮、每次重启都是同一份顺序 |

第二种看起来就是「打了乱，但顺序永远不变」。现在两者合并：

- `isRandomEffective(station)`：任一处要求打乱 → 时间轴逐轮随机；
- `isPlaylistStation(station)`：「这是不是列表」与「要不要打乱」彻底分开。
  早期 `openSource()` 要求 `station.isPlaylist()`，于是
  **「写了 `file: xxx.txt` 但没写 `mode`」（默认 block）的频道永远只播第一首**——
  这是「列表播放失效」的另一个来源。

##### 两个新增的安全阀（本地失效源判定）

第二十六轮引入的「本地文件不存在就注释」，有两个必须防的误伤：

1. **路径第三基准**：`resolveEntry()` 现在按
   **txt 所在目录 → `audio/` → 配置根目录 → 绝对路径** 依次尝试。
   少掉「配置根目录」这一档时，写 `audio/01.mp3` 的条目会被判成不存在；
2. **一条都解析不出来就不动手**：全是缺失只可能是「文件真被删」或「路径基准不对」，
   文件系统上无法区分。此时<b>一条都不注释</b>，只记 WARN 让服主判断。
   否则整个列表会被注释干净、频道随之消失（或只剩一两首 → 连打乱也无从谈起）。

##### 验证（本地夹具）

```text
时间轴（8 首，random=true）：连续 6 轮顺序各不相同        ✅
txt 首行 true + mode=sequence → isRandomEffective = true   ✅
txt 首行 true + mode 缺省     → isRandomEffective = true   ✅
单文件 block                  → random=false, playlist=false ✅
全部缺失的 txt                → commented=0，文件未改动      ✅
部分缺失的 txt                → 只注释缺失那条，原文保留     ✅
```

#### AL. 第二十八轮：ID3 标签被当成音频——「本地文件播放只有噪音」（2026-11-27 13:0x）

##### 现象

用户新放了一个本地测试文件 `tangtang.mp3`（与 txt 同目录，路径没问题），播出来是**持续噪音**，
而且日志里还夹着「文件不存在，已跳过：/tangtang.mp3」。

##### 根因：帧对齐把 ID3 标签里的 UTF-16 BOM 当成了 MP3 帧头

日志只有一行可疑：

```text
MP3 定位到 4 ms（字节偏移 21/9954897）
```

`tangtang.mp3` 头部是 `49 44 33 04 ...`（ID3v2.4），标签长度 **599,687 字节**（占文件 6%，
里面塞了封面图）。音频数据从偏移 **599,697** 才开始，可定位结果却是 **21**。

看 `xxd -s 21`：

```text
00000015: fffe 004e fa8b 3a4e b65b 5450 4531 0000
          ^^^^                        TPE1（艺术家帧）
```

`FF FE` 是 **UTF-16LE 的 BOM**。旧判据是

```java
buf[i] == 0xFF && (buf[i + 1] & 0xE0) == 0xE0
```

`0xFE & 0xE0 == 0xE0` → 命中。于是「对齐到帧边界」把读起点放进了**标签文本中间**，
客户端 JLayer 从垃圾字节开始解码，产出就是噪音。

##### 同一个 bug 的另外两个副作用

| 副作用 | 原因 |
|---|---|
| **多人不同步，整体偏前约 14 秒** | 字节偏移按「整文件大小」比例换算：`fileSize × target / duration`。599,687 字节 ≈ 14 秒（42563 B/s），所有定位都少算这 14 秒 |
| **配速偏快 6.6%，缓冲持续变薄 → 弱底噪** | `bytesPerSecond = 整文件字节 / 时长` = 341 kbps，而音频区实际是 **320 kbps**（`(9954897-599687)×8/233.88s`）。服务端按 341 配速，等于每天多推 6.6% 的字节，客户端缓冲被慢慢吃空，周期性欠载 → 弱底噪一直挂着 |

所以「听起来是噪音」有两个叠加来源：**开头从标签里解码**（刺啦），以及**配速偏快导致的持续欠载底噪**。

##### 改法

1. **先解析 ID3v2 长度**（`detectId3v2Size`）：`"ID3" + 版本2 + 标志1 + 同步安全整数4`，
   每字节只用低 7 位；v2.4 若置 footer 位再 +10。得到 `audioDataStart`；
2. **帧头严格校验**（`isMp3FrameHeader`）：版本 `01`、层 `00`、码率索引 `0000`/`1111`、
   采样率索引 `11`、emphasis `10` 一律拒绝。`FF FE` 因码率索引为 0 被挡掉；
3. **搜索下限 = `audioDataStart`**，向前只回退 8KB，绝不退回标签里；分块扫描到文件尾，
   一个合法帧都没有时返回 `-1`，调用方退化为「从头播 + 客户端丢前段」；
4. **比例换算基准改为音频区**：`audioStart + (fileSize-audioStart) × target / duration`；
5. `bytesPerSecond` 与 `positionMillis()` 同样改用音频区。

##### 实测（`build-verify/Mp3AlignCheck.java`，直接读 tangtang.mp3）

```text
ID3v2 总长度  = 599697（音频从此处开始）

目标      4 ms   旧：偏移 170    → 对齐     21（标签内，必然噪音）
                 新：偏移 599857 → 对齐 599697（FF FB E4 = MPEG1 L3 320kbps，合法帧）
目标   1000 ms   旧：偏移 42564  → 对齐  38479（标签内）
                 新：偏移 639697 → 对齐 632126（音频区）
```

其余本地 / 缓存文件实测标签只有 **34 ~ 193 字节**（ffmpeg 产物、网易云直链），
影响可忽略——所以这个 bug 只在「用户自己放的大封面文件」上暴露，一直没被发现。

##### 顺带：`/tangtang.mp3` 这种写法不再被判「不存在」

日志里那几次「文件不存在：/tangtang.mp3」是另一件事：服主把「音频根」写成前导 `/`。
`Path.resolve("/x")` 在 Windows 上是 `C:\x`、Linux 上是 `/x`，都不是 `audio/x`。
现在对以 `/` 开头的条目**先去掉斜杠按三档基准重试**，最后才当绝对路径，
既照顾这种写法，也不影响真正的绝对路径。

### 当前构建状态
- **`build/libs/radio-smile-1.0.0.jar`，约 359 KB**
- `gradlew build` → BUILD SUCCESSFUL
- **git 已初始化**，每轮一个提交；最新 `main` 与 origin/main 同步（第二十八轮）

### 待用户实测确认（下次会话优先问这些）

> 本清单只保留**尚未验证**的项。已验证通过的移到下方「历史验证」。

**第二十八轮（2026-11-27 13:0x，ID3 标签被当成音频）**：
- ⚠️ **`tangtang.mp3` 现在是否正常出声** — 本轮核心。它的 ID3 标签有 586 KB
  （塞了封面图），以前 4 ms 定位落在偏移 21（标签内的 `FF FE`），解出来是噪音。
  现在日志应为「MP3 定位到 x ms（字节偏移 599697/9954897，音频起点 599697）」，
  且开头就是音乐，没有刺啦声。
- ⚠️ **多人是否还对得上** — 以前按整文件换算，所有定位都偏前约 14 秒。
  用两个客户端同时听同一首，同一时刻应是同一句。
- ⚠️ **弱底噪是否消失** — 以前码率按 341 kbps 配速（实际 320），会持续欠载。
  现在日志/行为上不应再有周期性「沙」一下。

**第二十七轮（2026-11-27 中午，切曲分流 + 打乱语义）**：
- ⚠️ **换曲是否变"跟手"且没有嘶声** — 本轮核心。以前每换一首都会先掐断上一首结尾、
  响一段调谐嘶声、再等新曲接上。现在应当是「上一首正常播完 → 下一首直接开始」。
  判断方法：连听两首换曲处，若还能听到「沙」一下，说明调台噪仍被触发
  （检查日志有没有换曲时的 `playTuningNoise`）。
- ⚠️ **打乱模式每轮顺序是否不同** — 用 `mode: random` 或 txt 首行 `true` 的列表，
  连着听满两轮（列表长度 × 平均时长 × 2），两轮顺序不应相同；
  重启服务端后第一首也不应总是同一首。
- ⚠️ **列表是否真的在轮播** — 若某个频道永远只播第一首，检查它的 `file` 是否指向 txt
  （现在 txt 不再需要写 `mode`）；日志里应有「播放列表定位：第 x 轮 第 y/N 首」。
- ⚠️ **本地条目有没有被误注释** — 若发现 txt 里出现 `# [已禁用：文件不存在]` 但文件明明在，
  回看 `resolveEntry()` 的三级基准；全部条目都缺失时本轮<b>不会</b>注释（只有 WARN）。

**第二十六轮（2026-11-27 中午，失效源自动注释）**：
- ⚠️ **启动后 txt 是否被按预期改写** — 用 `realtest.txt` 验证：那两条 11-23 的
  网易云直链已返回 403，启动后应变成 `# [已禁用：HTTP 403，源文件已失效] https://...`。
  判断方法：开服后直接看 `config/radio-smile/audio/realtest.txt`，
  并在日志里搜「已在 txt 中注释掉一条失效源」。
- ⚠️ **能播的条目有没有被误伤** — 重点是"本地已有缓存但上游 403"的那几条：
  它们<b>不应</b>被注释，日志里是「但本地已有缓存，继续使用」。
  若发现能播的曲子被注释了，说明缓存判据失效，需回看 `findAnyCachedFile`。

**第二十五轮（2026-11-27 凌晨，本地对齐网络，当前优先）**：
- ⚠️ **本地台与网络台切换是否齐平** — 本轮核心。网络台实测中位 -19.07，
  本地目标 -19，理论上只差 0.07 dB。判断方法：同一位置来回切 `FM90.7` 与铁痕电台，
  音量应几乎不变；若本地仍明显偏小，检查日志「响度归一化：目标 -19」是否出现，
  以及 `cache/loudness/` 是否已有实测。
- ⚠️ **本地台整体是否偏吵** — 比上一轮响约 11 dB（目标 -30 → -19），这是预期。
  若盖住游戏音效，把 `targetLoudness` 调到 `-22` ~ `-24` 并重载，不用重测。
- ⚠️ **偏轻素材有没有被抬起来** — 实测低于 -19 的曲子应原样播放（增益 1.0），
  不应比源文件更响。听不出「变响」就是对的。

**第二十四轮（2026-11-26 深夜，删除 `outputGain`）**：
- ⚠️ **`stations.json` 里残留的 `outputGain` 是否被安静忽略** — 该字段已删除，
  旧配置里留着它不应报错、也不应生效。验证方法：故意把它写成 `0.9`，响度不应变化。
- ⚠️ **未实测曲目的过渡电平是否可接受** — 刚加进曲库、预热还没轮到的曲子
  会先按 0.40 播（≈ -8 dB，现行值），实测完成后自动换成精确值。若听出「同一首先轻后变」，
  说明预热还没跑完就被点播了，属预期。
- ⚠️ **关闭归一化是否真的变成原样播放** — 把 `targetLoudness` 设成 `0`，
  本地台应回到母带原电平（明显变响），而不是继续被压。

**第二十三轮（2026-11-26 晚，逐曲响度归一化）**：
- ⚠️ **多首混播的响度是否真的对齐了** — 本轮核心。以前固定 0.10 时，
  四首测试曲的输出跨度 6.7 dB（-33 ~ -26.3），现在应全部落在目标附近
  （拍摄时 -30，现行 -19，见 AI 节）。
  判断方法：含多首不同来源的列表连续播放，切换时不应再有明显的「这首突然变响/变轻」。
- ⚠️ **`cache/loudness/` 是否按预期生成** — 首次启动后该目录下应出现
  `<哈希>.lufs` 文件（内容两行：`lufs=` / `tp=`），日志有一行「实测完成 N/N」。
- ✅ **本地台 → 网络台跳变** — 已由第二十五轮解决：目标改为 -19（对齐网络台实测电平），
  网络台仍为 1.0。两者理论差 0.07 dB。
- ⚠️ **换曲瞬间有无异响** — 增益随换曲变化，理论上第一块可能带上一首的增益
  （约 20 ms，应不可闻）。若听得出「换曲瞬间音量一顿」，说明要改成随块携带增益。

**第二十二轮（2026-11-26 晚，`outputGain` 校准）** —— 历史存档：该配置项已在第二十四轮删除，
以下内容仅记录当时的校准过程，不再可操作：
- ⚠️ **本地台 100% 时是否与视频 BGM 齐平** — 本轮核心。默认已改为 0.10（-20 dB），
  实测 -29.9 LUFS。判断方法：游戏内音量全开、站在收音机 4 格内，本地台响度
  应接近那段终末地录像里 BGM 的听感（明显低于普通曲目原始电平，但仍清晰可辨）。
  偏轻就往上调（0.13 ≈ -27.7 LUFS），偏响就往下调（0.08 ≈ -31.9 LUFS）。
- ⚠️ **本地台 → 网络台切换的音量跳变** — 已知取舍：本地压到 0.10 后，
  网络台会显得响约 15 dB。若跳变难以接受，需要在「整机主增益」与
  「逐曲响度归一化」之间选一条（见 AF 节末尾）。
- ⚠️ **多首混播的响度一致性** — 固定增益无法适配母带差异：-9.9 LUFS 的曲子
  压 20 dB 正好 -30，-15 LUFS 的曲子只有 -35。若某几首明显偏轻，
  说明需要归一化而不是继续调这个固定值。

**第二十一轮（2026-11-25 上午）**：
- ⚠️ **本地台的 `outputGain` 是否真的生效** — 本轮修的核心。把 `stations.json` 的
  `outputGain` 设成 0.25，本地台应明显比网络台低一档；改回 1.0 应立刻变响。
  重点验证：连续听本地台超过 10 秒，响度应保持被压制状态（旧版会在第一块音频
  到达后被抹成 100%）。
- ⚠️ **换台后本地台增益是否正确** — 网络台 → 本地台、本地台 → 本地台（不同频道），
  新台都应拿到自己的增益，不应继承上一台的值。

**第二十轮（2026-11-24 晚）**：
- ⚠️ **网络电台音量是否恢复满档** — 之前被残留的 0.25 推流增益压到 25%；
  现在服务端随订阅回复下发 1.0。换台路径（本地台 → 网络台）重点验证。
- ⚠️ **调台噪手感** — 点台瞬间沙沙声应立刻起来并盖住旧台淡出与新台进入
  （HOLD 0.25 节目同档）；若峰值觉得吵可下调。
- ⚠️ **断网噪声是否够快** — 上游断流后，节目停→噪声满档应压在半秒左右；
  NOSIGNAL 灯仍按 4 秒超时（用户定值），如需与声音同步提前另行确认。

**第十九轮（架构改造）**：
- ⚠️ **断网完整链路** — 换台到网络电台 → 断网：缓冲变薄时底噪渐起、
  4 秒后灯闪 NOSIGNAL + 持续底噪（电平恒 1.0 不摆动）→ 恢复后底噪 1.5s 淡出、
  灯灭、切回 rate、续播，全程无响度跳变。
- ⚠️ **换曲不再误报** — 自定义电台换曲由空音量包声明静音窗（心跳已删），
  换曲时不应闪 NOSIGNAL / 响底噪。

**历史验证（已通过，仅作记录）**：
- ✅ 故障显示位置、黑色文字、液晶屏背景不变（第三轮）
- ✅ 选择框贴合机身、实体可穿过（第九轮）
- ✅ 液晶屏正常播放显示频率（第八轮）
- ✅ 日志不再刷屏（第七轮，"音频已恢复"只在真正恢复时一条）
- ✅ 「丢一半包」消失（第十三轮配速修复，用户实测确认）
- ✅ 音量 5% 步进 / txt 首行布尔 / ffmpeg 路径配置（第十三、十四轮）
- ✅ 跳变没了（第二十轮调台噪数字静音修复，用户确认）

### 已知限制

- 网络电台（直播流）**必须是 MP3**，且无法精确定位，只能从连接时刻顺序播放
- **网络电台由客户端直连上游**（AC 节起）：**玩家客户端需要能访问上游流**，
  受限网络里直连会失败（服务端代理方案已在 Z.3 → AC 节演进中废弃）
- **直播流不能写进 txt**：无 `Content-Length` 则无法缓存/转码，
  写了会被自动注释（见 T 节）。直播流应直接配成频道 `file`
- **转码强依赖 ffmpeg**：缺失时非 MP3 频道一律被拒绝播放（不静默降级）
- 首次启动需等转码完成（大文件可能几十秒），之后命中缓存秒完成
- 播放列表换曲不是无缝的：换曲时解码器重建，两首之间有极短空隙
- **HLS/AAC 流不支持**（已评估，用户决定不做）
- 临时签名链接（如 B 站带 `deadline` 的地址）**约 2 小时后失效**：
  已缓存的可继续播放，但要重新下载必须换新链接
- 中文**文件名**依赖 JVM 的 `sun.jnu.encoding`（Linux 默认 UTF-8 正常）

---

## 九、测试环境

`run/` 目录下的测试配置：

```
run/world/serverconfig/radio-smile/
├── stations.json     3 个频道（tiehen / testaudio / qtfm）
├── audio/
│   ├── qtfm_tiehen.ogg   22.5 MB（B站「铁痕电台#02」转码）
│   └── test_station.ogg  14.9 MB
└── cache/
    └── manifest.json
```

**当前实际测试配置**（`run/config/radio-smile/`）：

| id | 类型 | rate |
|---|---|---|
| `tiehen` | 本地 OGG（`qtfm_tiehen.ogg`，23MB） | FM105.7 |
| `fm907` | 网络直播流 `lhttp-hw.qtfm.cn/live/15318146/64k.mp3` | FM90.7 |
| `realtest` | **txt 播放列表**（1 本地 + 4 网络：网易云 mp3 ×2 / B站 m4s ×2） | FM101.7 |
| `mixed` | **txt 混播**（2 本地，演示新版首行格式） | FM100.0 |

两个 txt 均已改用**新版首行布尔**（`false`），`stations.json` 顶层已带 `ffmpeg` 字段。

> `realtest.txt` 是验证网络缓存/转码/时序的关键测试素材。
> 注意 B 站链接带 `deadline` 参数，**约 2 小时后失效**；
> 失效后仍能用已缓存的本地文件播放，但要重新下载就得换新链接。

客户端配置：`maxBandwidthKbps=1024`、`maxChunkBytes=8192`、`bufferMillis=1000`

---

## 十、快速恢复工作的方法

1. `cd "C:\Projects\Smile's Radio mod"`
2. `gradlew build` 确认构建通过
3. `gradlew runServer` 验证服务端启动 + 转码预热日志
4. `gradlew runClient`（配合 `--quickPlaySingleplayer` 自动进档）
5. 关键日志关键字（按排查顺序）：

   | 关键字 | 确认什么 |
   |---|---|
   | `电台配置已加载` | 配置位置与无效频道数（含「另有 N 个配置无效已隐藏」） |
   | `已从旧位置迁移` | 旧存档配置是否成功搬到 `config/` |
   | `转码预热` | ffmpeg 预热进度；`ffmpeg 不可用` 说明缺依赖 |
   | `已按配置指定 ffmpeg` | 顶层 `ffmpeg` 路径是否生效 |
   | `已连接网络电台` | 直播流建连 |
   | `客户端开始拉取网络电台流` | **客户端直连**拉流线程启动（AC 节） |
   | `网络流重连` | 上游断过又接回、解码器重建（sessionId 变化） |
   | `网络电台订阅登记` | 服务端订阅状态机登记（不推流） |
   | `网络音频已缓存到本地` | 网络文件下载完成 |
   | `txt 播放列表已预热` | txt 里网络条目数量 |
   | `MP3 定位到` + `播放列表定位` | 订阅时的精确定位（多人同步的基础） |
   | `[自动订阅]` | 自动订阅触发 |
   | `声卡已打开` | 音频设备与缓冲参数 |
   | `抖动缓冲溢出` | **⚠️ 供数快于消费**——第十三轮新增，看到它说明配速仍有问题 |

6. 想验证「时序是否正确」，最有效的方法是**录屏 + 看频谱图**（见 V 节）：
   用 `ffmpeg -i rec.mp4 -lavfi showspectrumpic` 生成，
   **规则性的柱状断续**就是丢包/配速错误，正常音乐不会有那种结构。

---

## 十一、后续可做

| 优先级 | 事项 | 备注 |
|---|---|---|
| **高** | 断网链路 + 网络电台音量复测 | 第十九、二十轮改动大，见「待确认」节 |
| 高 | 长时播放连续性验证 | 跨曲、长时间无人值守、网络抖动 |
| 中 | （此前提出、未执行）`v1.0.0` tag / GitHub Release / README Credits 节 | 用户点头即可做 |
| 中 | AutoSubscriber 扫描开销优化 | 当前每 0.5s 扫 81 区块 |
| 中 | 播放进度 UI | 当前界面无进度指示 |
| 中 | 故障显示字号 | 绿区 46×13 像素，`NOSIGNAL`(8字符) 已到宽度极限；
|    |  | 想更大必须把贴图提到 512×512（用户已知） |
| 低 | HLS/AAC 支持 | 技术已验证可行（jaad 0.9.4），用户决定暂不做 |
| 低 | 客户端自定义电台 | 仅网络流；本地文件仅限内置服务器 |
| 低 | 列表分页/滚动性能 | 频道很多时（>200）才需要考虑 |

---

## 十二、给下一个会话的提醒

**这个项目的 bug 有一个共同模式：同一个函数/字段承担了两种语义。** 已经修过 9 次：

| 函数 | 被混淆的语义 | 后果 |
|---|---|---|
| `AudioSubscription.markSent()` 的锚点 | 「读线程预读位置」vs「已发给客户端的位置」 | 首包后 4 秒空窗 |
| `isJitterBufferThinned()` 无音源时 | 「空选静止」vs「故障拿不到数据」 | 底噪该响时不响 |
| `ClientAudioPlayer.stop()` | 「用户不要了」vs「服务端故障」 | 底噪被当成用户主动关台 |
| `overlayEffects()` 的锚点 | 「换台拟音归属」vs「链路故障归属」 | 断网无噪音（K 节） |
| `PcmSink.stop()` | 「用户不要了」vs「故障静音」 | D 节没做完的下半截 |
| `ClientAudioPlayer.STREAMS` | 「真的在收流」vs「玩家点过确认」 | 首块前失败不响 / 关台后被迟到包点亮 |
| `PlaylistTimeline` 的曲目列表 | 「能播的」vs「配了的」 | 未转码文件进时间轴 → **整条列表时序错乱**（V 节）|
| `S2C_AUDIO_SILENCE` 接收器 | 「声明静音防误判」vs「换曲跳变要用噪声盖」 | 空音量包误触发调台噪，回退（AC 节）|
| `streamGains` 表项的生命周期 | 「服务端随音频块持续下发」vs「网络台再无音频块补发」 | 本地台 0.25 残留永久压低网络电台音量（AD 节）|
| `streamGains` 的 `1.0` | 「不衰减（合法业务值）」vs「没有增益记录（清理信号）」 | 客户端自己把本地台的 `outputGain` 抹掉，压缩配置完全失效（AE 节）|

> 第八至十次都在 2026-11-24~25：第八次让 NOSIGNAL 期间电平"1.0 之后一直 0.3"，
> 第九次让网络电台整路音量被静默压到 25%，第十次反过来把本地台的 `outputGain`
> 抹成 100%——三次都被用户一句话纠正。**先读代码确认生命周期再动手，
> 不要凭推测修**；尤其警惕「拿一个合法业务值当清理信号」。

**改动前先问：这个函数被几个语义不同的地方调用？**
如果答案不是 1，优先拆开入口，而不是在函数内部加条件判断。

另外三条硬约束：
1. **不要动效果音与缓冲区的关系** —— 见 B 节的三个坑
2. **不要用 gradle 之外的构建方式验证** —— 临时 javac 的 classpath 与 loom 不一致，容易假阳性
3. **网络电台架构约定（AC 节，用户明确）** —— 音频不经服务端；服务端只维护订阅状态机，
   不知道也不控制客户端的灯/NOSIGNAL；没有心跳包；缓存在客户端。
   任何把「检查上游网络」加回服务端的方案都违背这个设计

验证脚本在 `build-verify/`（含 REPORT.md 与 sim/），可重复执行。
本轮新增 `build-verify/audio/`（底噪/ERR 状态机模拟，`run-verify.ps1` → `VERIFY_EXIT=0`）
与 `build-verify/geom/`（原版 jar 反编译产物，用于验证 UV/旋转/字体矩阵）。

### 版本控制（2026-11-23 起）

`git` 已初始化，**每轮修复对应一个提交**（早期英文，近期中文）。常用：

```powershell
git log --oneline                  # 十五轮修复的历史
git log --oneline -S "关键词"      # 查某个函数/字段是何时引入的
git diff                           # 看未提交改动
git show <sha> --stat              # 看某次提交改了哪些文件
git show <sha> -- <file>           # 看某次提交对某文件的具体改动
```

> 排查"这行为什么是现在这样"时，`git log -S` 比读 HANDOVER 更快：
> 提交信息里写了每轮的核心改动，能直接定位到引入它的那一轮。

> ⚠️ **不要对 `build-verify/geom/` 里的反编译产物感到意外**：那是研究用的一次性资料，
> 体积不小但很有价值（下次改渲染/UV 直接查，不用重新反编译）。

---

## 附：依赖安装说明

```groovy
// build.gradle 关键配置
repositories {
    mavenLocal()  // smile_libbox
    maven { url = 'https://api.modrinth.com/maven' }
    maven { url = 'https://maven.terraformersmc.com/releases/' }
}

dependencies {
    modImplementation "smile_libbox:smile_libbox:1.0.26+1.20.1"
    modCompileOnly("maven.modrinth:modmenu:7.2.2") { exclude group: 'net.fabricmc.fabric-api' }
    include implementation("javazoom:jlayer:1.0.1")
}
```

smile_libbox 1.0.26 需从 `C:\Projects\Smile's Lib Box` 项目构建并发布到 mavenLocal
（`gradlew :1.20.1:publishToMavenLocal`）。
