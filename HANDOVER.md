# Smile's Radio Mod — 项目交接总结

> 最近更新：2026-11-23 17:40（第三轮：故障显示定位重写 + 断网底噪 + ERR 不消除）
> 项目路径：`C:\Projects\Smile's Radio mod`
> 构建产物：`build/libs/radio-smile-1.0.0.jar`（约 298 KB，BUILD SUCCESSFUL）
> 版本控制：**已初始化 git**（2026-11-23，单条初始提交）
> 前次更新：2026-11-23 16:05（第二轮：重连/调台噪/底噪/播放列表轮次）

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

路径：`<世界存档>/serverconfig/radio-smile/`

```
radio-smile/
├── stations.json      电台列表
├── audio/             原始音频文件（ogg/mp3/wav）
├── icons/             电台图标
└── cache/
    ├── manifest.json  音频哈希清单
    └── baked/         OGG→MP3 转码产物（启动时预热）
```

### stations.json 格式

```json
{
  "stations": [
    {
      "id": "tiehen",
      "name": "铁痕电台 #02",
      "icon": "tiehen.png",
      "file": "qtfm_tiehen.ogg",
      "mode": "block",
      "seed": 12345
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `id` | 唯一标识。**`example` 为保留值，该 id 的条目不加载** |
| `name` | 显示名称 |
| `icon` | 图标文件名（放 icons/） |
| `file` | 单文件 / 文件夹（播放列表）/ `http(s)` 网络流 |
| `mode` | `block` 单曲循环、`sequence` 自然序（1,2,10）、`random` 固定seed打乱 |
| `seed` | 可选，random 模式用。不填则按 id 派生（保证多客户端一致） |

**首次启动自动生成**示范条目（id=example，不加载）。服主改掉 id 即启用。

---

## 四、GUI

`RadioScreen`（客户端）：

| 区域 | 内容 |
|---|---|
| 顶部 | 搜索框 |
| 中间 | 可滚动电台列表（含图标、选中高亮） |
| 右侧 | 音量滑块（10% 一档） |
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
| `s2c_icon` | stationId + byte[] |
| `s2c_audio_chunk` | BlockPos + stationId + trackIndex + formatId + byte[] |
| `s2c_audio_status` | BlockPos + statusCode |

`AudioStatus`：0=OK, 1=UNKNOWN_STATION, 2=NO_AUDIO, 3=TOO_FAR, 4=UNSUPPORTED, 5=STOPPED

---

## 六、音频架构

### 数据流

```
服务端                                   客户端
─────────────────────────────────────────────────────
FileAudioSource  ──┐
  (本地音频/MP3)    │
                   ├→ AudioSubscription ──网络──→ ClientAudioPlayer
StreamAudioSource ─┘   (读线程+配速)              (AudioIngest 线程)
  (网络流+ICY剥离)                                        │
                                                          ↓
                                              Mp3StreamDecoder (JLayer)
                                              OggDecoder (原版 OggAudioStream)
                                                          │
                                                          ↓ PCM
                                                   PcmSink（混音器）
                                                          │
                                                          ↓
                                              SourceDataLine 48kHz/2ch/16bit
```

### 服务端核心类

| 类 | 职责 |
|---|---|
| `RadioStationRegistry` | 配置加载、哈希校验、**预热转码**（同一趟遍历） |
| `AudioTranscoder` | 启动时按 hash 预热 OGG→MP3（128kbps），缓存于 cache/baked/ |
| `AudioSource` | 音频源接口（read/durationMillis/format/skipMillis/positionMillis/seekTo/totalBytes） |
| `FileAudioSource` | 本地文件，MP3 可按帧定位 |
| `StreamAudioSource` | 网络流代理，含 ICY 元数据剥离 |
| `AudioSubscription` | 订阅状态 + 独立读线程 + 闭环配速 + 预热补发 |
| `AudioStreamManager` | 订阅管理、推送线程（已与游戏 tick 解耦）、发包回主线程 |

### 客户端核心类

| 类 | 职责 |
|---|---|
| `ClientAudioPlayer` | 按方块维护播放流，AudioIngest 线程消费 |
| `Mp3StreamDecoder` | JLayer 流式 MP3 解码 |
| `OggDecoder` | 原版 OggAudioStream 解码 |
| `PcmSink` | **单一声卡混音器**，固定 48kHz/2ch，设备常驻 |
| `AutoSubscriber` | 每 10 tick 扫描半径 52 格，自动订阅/退订/恢复中断 |
| `BgmSuppressor` | 播放时抑制原版 BGM |
| `AudioLifecycleHooks` | 断开连接时释放声卡 |
| `VanillaVolumeTracker` | 主线程跟踪 BLOCKS + MASTER 音量 |

### 音量与空间化

最终增益链：
```
方块独立音量 × 原版BLOCKS × 原版MASTER × 距离衰减 × 左右声像
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

### 当前构建状态

- **`build/libs/radio-smile-1.0.0.jar`，约 301 KB**
- `gradlew clean build` → BUILD SUCCESSFUL
- **git 已初始化**（单条初始提交，含本轮全部修复）

### 待用户实测确认（下次会话优先问这些）

**第六轮刚改、优先确认**：
- ⚠️ **配置失败的频道不出现在列表** — 测试配置里的 `broken`（文件不存在）应看不到。
- ⚠️ **选中已配置但重载后失效的频道** → 持续噪音 + NOSIGNAL（不是死寂）。
- ⚠️ **未知频道**（我把 `broken` 的 id 直接写进方块 NBT 也能测）→ 同样持续噪音 + NOSIGNAL。
- ⚠️ **`fm907` 能播且显示 FM90.7 + 附图图标**；`tiehen` 显示 FM105.7；
  其余两个显示 NETWORK。右侧文字不与应用频道名重叠。
- ⚠️ 重载日志应有「另有 N 个配置无效已隐藏」。

**第五轮**：
- ⚠️ **自定义电台（本地文件/播放列表）断网时不应出现 NOSIGNAL** — 这是本轮收窄的重点。
  只有网络电台断流才允许点亮红灯 + NOSIGNAL。
- ⚠️ **弱网只响底噪、不重建订阅** — 断网期间日志不应出现反复的"订阅缺失/开始自动重试"，
  也不应有反复的网络建连（服务端已删掉重连状态机，日志里不该再有"第 N 次重连"）。
- ⚠️ **玩家重连服务器后能自动恢复** — 断开再连回来，收音机应重新响
  （服务端 DISCONNECT 清订阅 + 客户端 JOIN 清流表 + AutoSubscriber 重新订阅）。
- ⚠️ **下线日志** — 应能看到「玩家 X 已下线，已清理其全部音频订阅」与「玩家 X 已上线」。

**第四轮**：
- ⚠️ **红灯闪烁速度** — 半周期 250ms（2Hz）。用户说"闪得快一点"，
  若还想更快改 `LED_BLINK_HALF_PERIOD_MS`；但**不要低于 120ms**，否则与显示器刷新拍频、看起来像抖动。
- ⚠️ **`NOSIGNAL` 是否完整显示** — 8 个字符已在默认字体下量过（50px），
  字号 0.0065 恰好铺满绿区且不溢出。**若你装了字体资源包/改了 GUI 缩放导致溢出**，
  代码里有按实测宽度回缩的兜底，但字会变小。
- ⚠️ **文字不闪、红灯闪** — 这是有意分开的（文字要能读）。
- ⚠️ **音量滑块 5 档** — 拖动应只停在 0/25/50/75/100% 五个位置。
- ⚠️ **日志不再刷屏** — 断网后日志应只有「开始自动重试」一行，
  恢复时「音频已恢复」一行。中间不应有成片重复 WARN。
- ⚠️ **长断网能自愈** — 现在不放弃重试（原来 120 次后放弃）。可拔网线等 2 分钟再插回验证。

**第三轮（已确认渲染没问题）**：
- ✅ 故障显示位置、黑色文字、背景不变 —— 用户已实测通过
- ⚠️ **断网底噪** — 拔网线/断上游后应持续听到嘶声提示（不是一声就完），恢复后自动消失。
  两种场景都要测：**没换过台就断网**、**服务端报故障**。
- ⚠️ **ERR 自动消除** — 网络恢复后屏幕上的 ERR 必须自己消失；
  切走再切回也应消失；**清空频道后不该残留 ERR**。
- ⚠️ **正常播放不能有假底噪** — 起播那 1 秒内不该有嘶声（回归重点）。

**第二轮遗留（仍待确认）**：
- ⚠️ **调台噪重叠** — 旧音频淡出 120ms + 噪声淡入，是否真无缝
- ⚠️ **断网重连** — 能否自动恢复、会不会「卡回」
- ⚠️ **点扫描** — 音量条是否稳定、播放是否完全不断
- ⚠️ **起播延迟** — 抖动缓冲下移后起播晚约 `bufferMillis`（默认 1 秒）；
  嫌慢可调小，设备缓冲固定 150ms 不受影响

### 已知限制

- 网络电台（直播流）**必须是 MP3**，且无法精确定位，只能从连接时刻顺序播放
- **转码强依赖 ffmpeg**：缺失时非 MP3 频道一律被拒绝播放（不再静默降级）
- 首次启动需等转码完成（两个 15MB 文件约 40 秒），之后命中缓存秒完成
- 播放列表换曲不是无缝的：换曲时解码器重建，两首之间有极短空隙
- **HLS/AAC 流不支持**（已评估，用户决定不做）

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

- `tiehen` / `testaudio`：本地 OGG
- `qtfm`：网络流 `https://lhttp-hw.qtfm.cn/live/1163/64k.mp3`（MP3/CBR/48kHz/ICY metaint=16000）

客户端配置：`maxBandwidthKbps=1024`、`maxChunkBytes=6722`、`bufferMillis=1000`

---

## 十、快速恢复工作的方法

1. `cd "C:\Projects\Smile's Radio mod"`
2. `gradlew build` 确认构建通过
3. `gradlew runServer` 验证服务端启动 + 转码预热日志
4. `gradlew runClient`（配合 `--quickPlaySingleplayer` 自动进档）
5. 关键日志关键字：
   - `转码预热` — 确认预热状态
   - `MP3 定位到` — 确认订阅定位
   - `[自动订阅]` — 确认自动订阅触发
   - `声卡已打开` — 确认音频设备
   - `OGG 流参数` / `音频格式` — 确认解码器

---

## 十一、后续可做

| 优先级 | 事项 | 备注 |
|---|---|---|
| **高** | 验证本轮所有待确认项（见第八节） | 渲染/底噪/重连都是刚改的 |
| 高 | 长时播放连续性验证 | 断网恢复、跨曲、长时间无人值守 |
| 中 | AutoSubscriber 扫描开销优化 | 当前每 0.5s 扫 81 区块 |
| 中 | 播放进度 UI | 当前界面无进度指示 |
| 中 | 故障显示的字号 | 屏幕仅 46×13 像素，`ERR408` 用 0.0085 字号已到宽度极限；
|    |  | 想更大必须把贴图提到 512×512（用户已知此项） |
| 低 | HLS/AAC 支持 | 技术已验证可行（jaad 0.9.4），用户决定暂不做 |
| 低 | 客户端自定义电台 | 仅网络流；本地文件仅限内置服务器 |
| 低 | 图标网络下发后的渲染验证 | |

---

## 十二、给下一个会话的提醒

**这个项目的 bug 有一个共同模式：同一个函数承担了两种语义。** 已经修过的三处：

| 函数 | 被混淆的语义 | 后果 |
|---|---|---|
| `AudioSubscription.markSent()` 的锚点 | 「读线程预读位置」vs「已发给客户端的位置」 | 首包后 4 秒空窗 |
| `isJitterBufferThinned()` 无音源时 | 「空选静止」vs「故障拿不到数据」 | 底噪该响时不响 |
| `ClientAudioPlayer.stop()` | 「用户不要了」vs「服务端故障」 | 底噪被当成用户主动关台 |

**改动前先问：这个函数被几个语义不同的地方调用？**
如果答案不是 1，优先拆开入口，而不是在函数内部加条件判断。

另外两条硬约束：
1. **不要动效果音与缓冲区的关系** —— 见 B 节的三个坑
2. **不要用 gradle 之外的构建方式验证** —— 临时 javac 的 classpath 与 loom 不一致，容易假阳性

验证脚本在 `build-verify/`（含 REPORT.md 与 sim/），可重复执行。
本轮新增 `build-verify/audio/`（底噪/ERR 状态机模拟，`run-verify.ps1` → `VERIFY_EXIT=0`）
与 `build-verify/geom/`（原版 jar 反编译产物，用于验证 UV/旋转/字体矩阵）。

### 版本控制（2026-11-23 起）

`git` 已初始化，初始提交包含本轮全部修复。常用：

```powershell
git log --oneline            # 查看历史
git diff                     # 看未提交改动
git show <sha> --stat        # 看某次提交改了哪些文件
```

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
