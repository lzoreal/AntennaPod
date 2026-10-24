# AntennaPod 转录（Transcript）功能交接文档

> 面向对象：接手这个 fork 转录功能的下一个 agent / 开发者。
> 基线：换句高亮 250ms 刷新基于 `ffcc05320`（2026-10-25）；转录功能历史基线提交 `7e2b4e5b9`（2026-10-24）；交接文档与真机脚本自 `35cf3c35e`（2026-10-24）起，后续修订以 `git log -- docs/transcript-handover.md` 为准；上游基线提交 `bbe756414`（Merge develop，2026-10-13）。
> 全量单测在代码基线 `7e2b4e5b9` 上已跑通：`./gradlew --console=plain test` → `BUILD SUCCESSFUL`，3666 个任务，0 失败。

## 0. 30 秒上手

| 想做的事 | 命令 / 入口 |
|---|---|
| 改解析 / 分句 | `:parser:transcript`，先看 `VttTranscriptParser` 与 `TranscriptSentenceUtils` |
| 改转录页显示 | `:app` 的 `TranscriptAdapter.getDisplayInfo()` + `TranscriptPageFragment` |
| 改悬浮窗 | `:ui:transcript` 的 `TranscriptFloatingWindowService` |
| 改本地缓存/文件 | `:ui:transcript` 的 `TranscriptUtils` |
| 跑相关单测 | `./gradlew --console=plain :parser:transcript:test :model:test :ui:transcript:test :storage:preferences:test :app:test` |
| 编译 | `./gradlew :app:assembleDebug` |
| 真机验证（自动） | `bash scripts/transcript_device_test.sh`（见 §5.2） |

阅读顺序建议：§3 代码地图 → §4 数据/配置约定 → §1 功能总览 → §6 坑速查 → §7 未完成项。

## 1. 功能总览

### 1.1 展示入口

| 能力 | 行为 | 位置 |
|---|---|---|
| 音频播放器转录页 | 播放器内的整屏转录列表，含控制条 | [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L58)，布局 [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml) |
| 视频/浮层转录弹窗 | `DialogFragment`，复用同一 adapter | [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java#L42)，布局 [transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml) |
| 单行渲染 | 发言人 + 正文，正文用可点击/可选词的 TextView | [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L163)、[TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java)、布局 [transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml) |
| 悬浮窗（其他应用之上） | 只显示当前句的双语小窗 | [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L45)，布局 [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) |
| 菜单开关 | `transcript_menu_item`（进入转录页）、`floating_transcript_item`（悬浮窗，checkable）、`transcript_item`（旧入口） | [mediaplayer.xml](file:///D:/AntennaPod/app/src/main/res/menu/mediaplayer.xml#L5) |
| 外部拉起播放器并打开转录页 | `am start ... --ez open_player true --ez open_transcript true` | [MainActivity.java:740-745](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/activity/MainActivity.java#L740) |
| 播放服务驱动悬浮窗 | `TranscriptFloatingWindowService.start/stop` | [Media3PlaybackService.java:317](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L317)、[AudioPlayerFragment.java:248](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java#L248) |
| 服务注册（未导出） | `android:exported="false"` | [AndroidManifest.xml:301](file:///D:/AntennaPod/app/src/main/AndroidManifest.xml#L301) |

### 1.2 解析与双语

| 能力 | 行为 | 位置 |
|---|---|---|
| 三种格式 | `TranscriptParser.parse(text, type)` 按 MIME 分发 JSON / VTT / SRT | [TranscriptParser.java:12](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java#L12) |
| VTT 结构 | 忽略 `NOTE`/`STYLE`，解析 cue timing/`<v.speaker>` voice span，跳过非法或零时长 cue | [VttTranscriptParser.java:33](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L33) |
| 双语判定 | 双语 cue 占比过半即 `bilingual`；无双语 cue 时检测“交替单语言 cue”（两种文字体系严格交替，各 ≥2 条，≥4 cue） | `VttTranscriptParser.detectAlternatingPrimaryScript()` |
| 交替配对 | 主语言 cue + 下一条译文 cue 合并为一个 segment（`primary\ntranslation`） | [VttTranscriptParser.java:199](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L199) |
| 长双语 cue 不拆句 | 一条 cue 一个 segment（原文 + 换行 + 译文）；段内句子仍按**字符位置比例**估算时间，供高亮/点击/悬浮窗使用 | [VttTranscriptParser.java:100](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L100) |
| 语言切换边界 | 按文字体系（Latin / CJK+假名+韩文 / 其他）判定主/译边界，纯符号行忽略 | [TranscriptSentenceUtils.java:22](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L22) |
| 句子切分 | `。！？…` 直接断句；`.` 需后随空白/引号，且**两侧都是数字时不断**（避免把 `2024.` 当小数）；收尾标点并入前句 | [TranscriptSentenceUtils.java:106](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L106) |
| SRT | 仅看第一行做发言人识别，支持纯发言人行、保留额外冒号后文本（已知缺陷见 §7） | [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) |
| JSON | `startTime/endTime`（秒→毫秒），null/空 speaker 沿用上一位发言人（首段为空），`end <= start` 丢弃 | [JsonTranscriptParser.java:40](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L40) |
| 乱序容错 | `Transcript.addSegment` 稳定二分插入，不再因乱序抛异常 | [Transcript.java:12](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java#L12) |
| 重叠 cue | `TranscriptSegment.append` 取最大 endTime | [TranscriptSegment.java:16](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java#L16) |

UI 侧的 `TranscriptTextUtils` 是兼容层：分句/语言边界/句子时间转发到 `TranscriptSentenceUtils`，`progressAt` 为本地实现（保留旧入口，避免 `:app`/`:ui:transcript` 重复实现）。

### 1.3 显示控制

| 能力 | 行为 | 位置 |
|---|---|---|
| 语言模式三态 | 双语 / 仅原文 / 仅译文，循环切换并持久化 | `UserPreferences.TRANSCRIPT_LANGUAGE_MODE_*`（[UserPreferences.java:136](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L136)）、`TranscriptAdapter.setLanguageMode()` |
| 逐句换行 | 每个句子独占一行（双语时主/译成对） | `isTranscriptSentencePerLine()`、`TranscriptPageFragment.toggleSentencePerLine()` |
| 字号 | 步进制（`16 + step*2` sp，高亮 `19 + step*2`），缓存在 adapter 字段 | [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L337) |
| 分级高亮 | 当前段（一条 cue）整块加粗+19sp+全亮，其他段 16sp 半透明；段内只分当前句（全色+加粗）与其他句（统一半透明）两档，不再区分已播/未播；字号跃迁保留，便于快速定位当前声音 | `TranscriptAdapter.highlightViewHolder()` / `applyGradedHighlight()` |
| 跟随音频 | 整屏页 RESUMED 且播放中时以 250ms 轮询位置，高亮与滚动同源；相邻句平滑滚动已放慢（2000/dpi），超过 5 行仍瞬跳；用户手动滚动时暂停跟随，回到当前句后恢复；弹窗/悬浮窗仍按 1 秒事件 | `TranscriptPageFragment` 的 `highlightTicker`/`userScrolling`/`returnToCurrentSentence()`/`resumeFollowingIfHighlightVisible()` |
| 沉浸模式 | 隐藏播放器其他区块，只看转录 | `btnImmersive`、提交 `0792497f4` |
| 回到当前句 | `btnBackToCurrent`，仅在偏离时可见 | [TranscriptPageFragment.java:592](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L592) |

### 1.4 时间操作

| 能力 | 行为 | 位置 |
|---|---|---|
| 点击跳转 | 点击落到**所点句子**的起止区间（非整段），双语时按触摸落在主/译区域分别映射 | [TranscriptAdapter.java:312](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L312) → `SegmentClickListener.onTranscriptClicked(pos, seg, start, end)` |
| 选词 | 长按选中文本触发 `onTranscriptWordSelected` | [TranscriptPageFragment.java:625](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L625) |
| 偏移控制条 | 粗调 ±5/±10/±30s、细调 ±0.5s、重置、当前偏移显示 | `toggleOffsetBar()`、`setupOffsetButtons()`（[TranscriptPageFragment.java:745](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L745)） |
| 偏移持久化 | **按 media id** 存 JSON map；旧的单槽偏好首次读取时迁移；删除媒体/条目时移除；范围在 `applyTimeOffset()` 中 clamp 到 ±时长 | [UserPreferences.java:952-999](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L952)、[DBWriter.java:121](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java#L121)、[TranscriptPageFragment.java:712](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L712) |
| 单段循环 | 循环当前 `TranscriptSegment` 区间，偏移变化后若区间无效自动停止 | [TranscriptPageFragment.java:633](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L633) |

### 1.5 悬浮窗细节

- 只显示当前句（不是全文），主/译两个 `ScrollTextView`（自定义 `ValueAnimator` 横向滚动，最高 300px/s），间距已收紧。
- 播放暂停时保持显示但停止滚动；播放结束、被关闭、当前集无转录时消失。
- 锁定/关闭按钮约 4s 无操作自动隐藏；隐藏时第一次点击只唤出控件，控件可见时点击打开转录页。
- 可拖动并水平吸附到最近左右边缘（纵向只做边界 clamp）；位置以 dp 存偏好（`prefFloatingTranscriptXDp/YDp/Locked`）。
- **不再显示发言人**（按需求移除，`TranscriptSegment` 仍保留 speaker 供转录页使用）。
- 竞态修复：后台 `Callable` 只返回 `FeedMedia`，`currentFeedId`/`lastPlaybackPosition`/`transcript` 一律在主线程回调里赋值，因此 `dispose()` 能真正阻止过期请求污染新剧集状态（[TranscriptFloatingWindowService.java:360](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L360)）。

### 1.6 本地缓存与来源元数据

- 文件三件套：`<localFileUrl>.transcript`、`<...>.transcript.source`（内容 `type\nURL`）、`<...>.transcript.tmp`（原子写入临时文件）。
- 内存缓存：access-order LRU，最多 8 个，key = mediaId + type + URL + 本地文件 mtime（[TranscriptUtils.java:229](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java#L229)）。
- 下载与本地读取均限 5 MB；下载按响应 charset 解码。
- 本地文件只有在 `.source` 与当前条目一致时才使用；无 `.source` 的旧文件会被解析并**收编**（补写 sidecar）。
- 本地文件损坏/不可解析 → 回退到当前 URL 并覆盖本地文件。
- 强制刷新失败 → 依次回退内存缓存、仍然有效的本地文件。
- `storeTranscript` 先校验可解析才落盘，写 `.tmp` 后 rename（失败则删除目标再 rename，再失败则 copy），并写 sidecar。
- 删除媒体 / 发现本地文件缺失 → `FeedMedia.deleteTranscriptFiles()` 清三件套；同时删除该 media 的时间偏移。

### 1.7 刷新联动

| 事件 | 触发方 | 谁响应 | 结果 |
|---|---|---|---|
| `PlaybackPositionEvent` | 播放服务（1 秒） | 弹窗/悬浮窗；整屏页用于 seek/暂停兜底 | 高亮、滚动、句子时间；整屏页播放中另由 250ms 本地轮询驱动 |
| `FeedItemEvent` | 条目更新 | 转录页 | 仅当转录来源 URL/类型变化或尚无数据时强制重载 |
| `FeedListUpdateEvent` | feed 更新（`contains(long)` 判定） | 转录页、弹窗、`AudioPlayerFragment`、悬浮窗 | 重新加载媒体信息/转录，菜单可见性刷新 |
| `PlayerStatusEvent` | 播放状态 | 转录页、弹窗、悬浮窗 | 跟随/滚动/暂停语义 |

## 2. 提交历史

完整列表：`git log --author=wenhao --oneline`。下表只保留影响当前实现的关键节点。

| 日期 | 节点 | 影响 |
|---|---|---|
| 2026-08-18 ~ 08-19 | `fdddd12ef`/`f70eeacf6`/`92e6fb82f` | 转录页、Adapter/滚动、`:ui:transcript` 模块与悬浮窗 |
| 2026-08-21 | `3069aa9df`/`0792497f4`/`2b24e787a`/`867088d88` | 双语 VTT、沉浸模式、固定高亮色、幂等加载 |
| 2026-09-11 ~ 10-13 | `3164027b6`/`7f4eaa113`/`bbe756414` | 上游 develop 合并 |
| 2026-10-22 ~ 10-23 | `a77c90ce7`/`8d8f5616f`/`9df9271a4`/`2ff80e94d`/`fa8e29b85` | 共享分句工具、偏移、分级高亮、图标、正确性修复与单测起步 |
| 2026-10-24 | `3128fe4a9` ~ `7e2b4e5b9` | 当前句悬浮窗、双语配对、细粒度偏移、解析/缓存/刷新加固 |
| 2026-10-24 | `35cf3c35e` | 交接文档与真机脚本 |

## 3. 代码地图

### `:model` — 数据模型
| 文件 | 职责 |
|---|---|
| [Transcript.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java) | 段落列表、`addSegment` 有序插入(L12)、`findSegmentIndexBefore`、`isBilingual`(L60) |
| [TranscriptSegment.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java) | 单段 start/end/words/speaker，`append`(L16) 合并重叠 cue |
| [FeedItem.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedItem.java) | `podcastIndexTranscriptUrl/Type`，`transient transcript`(L48)，`updateFromOther` 来源变更失效(L181) |
| [FeedMedia.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedMedia.java) | `getTranscriptFileUrl`(L501)、`getTranscriptMetadataFileUrl`(L508)、`deleteTranscriptFiles`(L513)、`setTranscript` |

### `:parser:transcript` — 解析
| 文件 | 职责 |
|---|---|
| [TranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java) | MIME 分发(L12)、`MIN_SPAN` |
| [TranscriptSentenceUtils.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java) | **分句/文字体系/句子时间的唯一实现**：`languageSplitIndex`(L22)、`scriptOf`(L64)、`chunkStarts`(L106)、`sentenceIndexAt`(L137)、`sentenceEndTime`(L148/152)、`sentenceStartTime`、`splitIntoSentences` |
| [VttTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java) | VTT 结构(L33)、双语判定(L83-L94)、`addAlternatingSegments`(L199)、一条 cue 一个 segment(L100)、`detectAlternatingPrimaryScript`(L160) |
| [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) | SRT + 发言人识别 |
| [JsonTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java) | JSON 词级/段级 |

### `:ui:transcript` — 加载与悬浮窗
| 文件 | 职责 |
|---|---|
| [TranscriptUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java) | `loadTranscriptFromUrl`(L32)、`loadTranscript`(L84)、`loadLocalTranscript`(L136)、`cacheKey`(L229)、`parseTranscript`(L257)、`storeTranscript`(L274)、`.source` 读写与收编 |
| [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java) | `start`(L85)/`stop`(L102)、窗口与拖拽、`loadTranscript`(L356/360)、`onFeedListUpdateEvent`(L415)、`updateTranscript`(L432) + 句子缓存、`hideTranscript`(L554) |
| [TranscriptTextUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptTextUtils.java) | 兼容层，分句/语言边界/句子时间转发 `TranscriptSentenceUtils`；`progressAt` 为本地实现 |
| [ScrollTextView.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/ScrollTextView.java) | 悬浮窗用自动横向滚动 TextView（`ValueAnimator`，最高 300px/s） |
| [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) | `tv_floating_transcript`、`tv_floating_transcript_translation`、`btn_floating_lock`、`btn_floating_close` |

### `:app` — 页面与交互
| 文件 | 职责 |
|---|---|
| [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java) | 转录页全部逻辑（加载 L245/L249、事件 L418-L451、滚动 L492、点击 L565、循环 L633、偏移 L705-L803、语言/换行/字号 L824-L895） |
| [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java) | `onCreateViewHolder`(L99 绑定监听器)、`getDisplayInfo`(L220 显示缓存)、高亮(L337/L373)、句子时间(L312)、`SegmentClickListener`(L458) |
| [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java) | 视频弹窗：`onTranscriptClicked`(L106)、`onFeedListUpdateEvent`(L153)、`loadMediaInfo`(L173/L177)、`scrollToPosition` |
| [TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java) | 点击 + 选词 TextView |
| [AudioPlayerFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java) | 悬浮窗开关(L248/L250)、菜单可见性、`FeedListUpdateEvent` 重载 |
| [PlayActionButton.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/actionbutton/PlayActionButton.java) | 本地文件缺失时一并清理转录文件 |
| 布局 | [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml)（`recyclerView`/`controlsBar`/`offsetControls`/`btnBackToCurrent`/`btnImmersive`/`btnLineBreakToggle`/`btnTranslationToggle`/`btnFontLarger`/`btnFontSmaller`/`btnSegmentLoop`/`btnOffsetToggle`/`txtOffsetValue`/`emptyView`/`progLoading`）、[transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml)（`transcript_list`/`followAudioCheckbox`）、[transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml)（`speaker`/`content`） |
| 图标 | `app/src/main/res/drawable/`：`ic_transcript_*`、`ic_fullscreen*.xml`、`ic_floating_transcript.xml`、`bg_transcript_offset_button.xml`；`ui/transcript/src/main/res/drawable/`：`bg_floating_control.xml`、`ic_transcript_{close,lock,unlock}.xml` |

### 其他
| 文件 | 职责 |
|---|---|
| [UserPreferences.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java) | 所有转录偏好（L129-L141 keys，L934-L1045 getters/setters） |
| [DBWriter.java](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java) | 删除媒体/条目时清理转录文件与偏移(L121/L133/L217) |
| [FeedListUpdateEvent.java](file:///D:/AntennaPod/event/src/main/java/de/danoeh/antennapod/event/FeedListUpdateEvent.java) | `contains(Feed)` / `contains(long)` |
| [strings.xml](file:///D:/AntennaPod/ui/i18n/src/main/res/values/strings.xml)、[strings.xml (zh-rCN)](file:///D:/AntennaPod/ui/i18n/src/main/res/values-zh-rCN/strings.xml) | `transcript_*`、`pref_floating_transcript_*`、`show_transcript`、`no_transcript_label`；英文与简体中文同步维护 |
| DB 列 | `FeedItems.podcastindex_transcript_url`、`FeedItems.podcastindex_transcript_type`（**不在 FeedMedia**） |

## 4. 偏好键与默认值

| key | 类型/默认 | 含义 |
|---|---|---|
| `prefFloatingTranscript` | bool/false | 悬浮窗开关 |
| `prefFloatingTranscriptXDp/YDp` | int/-1 | 悬浮窗位置 |
| `prefFloatingTranscriptLocked` | bool/false | 是否锁定（禁拖） |
| `prefTranscriptLanguageMode` | int/0 | 0 双语、1 仅原文、2 仅译文 |
| `prefTranscriptSentencePerLine` | bool/true | 逐句成行 |
| `prefTranscriptFontSize` | int/0 | 字号步进（clamp -2..3） |
| `prefTranscriptTimeOffsets` | string JSON | mediaId → 偏移毫秒（当前实现） |
| `prefTranscriptTimeOffset` + `prefTranscriptTimeOffsetMedia` | long | 旧单槽值，读取时迁移到上表 |

## 5. 测试

### 5.1 单元测试

按 `AGENTS.md`：`./gradlew --console=plain` + 模块的 `:test` 任务。

| 模块 | 测试文件 | 覆盖点 |
|---|---|---|
| `:model` | [TranscriptTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/TranscriptTest.java)、[TranscriptSegmentTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/TranscriptSegmentTest.java)、[FeedItemTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/FeedItemTest.java)、[FeedMediaTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/FeedMediaTest.java) | 乱序插入、append 单调、来源变更失效、序列化忽略 transcript、三文件清理 |
| `:parser:transcript` | [VttTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParserTest.java)、[SrtTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParserTest.java)、[JsonTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParserTest.java) | 双语保留译文、非双语不误判、长 cue 不拆句（含数字句末）、null speaker、非法时间、乱序 |
| `:ui:transcript` | [TranscriptUtilsTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptUtilsTest.java)、[TranscriptFloatingWindowServiceTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowServiceTest.java) | sidecar 写入/收编/过期、5MB、损坏回退 URL、强刷回退、原子写、悬浮窗过期请求竞态（可控调度队列 + `RxJavaPlugins.reset()`） |
| `:storage:preferences` | [UserPreferencesTest](file:///D:/AntennaPod/storage/preferences/src/test/java/de/danoeh/antennapod/storage/preferences/UserPreferencesTest.java) | 按媒体偏移、删除、旧值迁移 |
| `:app` | [TranscriptTextUtilsTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextUtilsTest.java) | 数字句末 chunkStarts、sentenceEndTime |

注意：`TranscriptFloatingWindowServiceTest` 里 Mockito 静态 mock 只对创建它的线程可见，跨 IO 线程的竞态必须用同一可控调度器（`Schedulers.from(executor)` + `trampoline`）复现，否则测试会假通过。

### 5.2 真机自动验证脚本

[scripts/transcript_device_test.sh](file:///D:/AntennaPod/scripts/transcript_device_test.sh)（Git Bash，仓库根目录运行；`--help` 列出全部参数）。

```bash
bash scripts/transcript_device_test.sh \
  --vtt-dir "D:/MyTranscript/site/the-rest-is-history/transcripts/zh" \
  --vtt-file "1._Greatness.vtt" \
  --media-id 1 --position 5000
```

脚本按「备份设备 → 注入测试 DB/偏好 → 本地 HTTP + `adb reverse` 提供 VTT → 重启并断言 → 还原设备」执行；任一步失败或 `INT`/`TERM` 都会先恢复设备。参数与各阶段细节见 `--help` 及脚本内注释。

自动断言：转录页存在双语逐句行、无 `FATAL EXCEPTION`、悬浮窗服务在运行；截图小于 60 KB 直接判黑屏失败，不产出假 PASS。MIUI 唤醒/AOD 的成因与处理见 §6。

产物：`~/.qoder-cn/tmp/transcript-ondevice/run-<时间戳>/`（`results.txt`、`01_transcript_page.png`、`04_floating.png`、`transcript.log`、各阶段 UI dump 与数据库副本）。

其他用法：`--restore-only --work <目录>`（只恢复）、`--keep`（保留现场）、`--serial <序列号>`（多设备）。

2026-10-25 在代码基线 `7e2b4e5b9`（脚本提交 `d66169281`）上、Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）的完整跑通结果（含 `:app:installPlayDebug`）：14/14 PASS，`restored integrity: ok`、`test urls left: 0`、偏好与数据库与备份逐字节一致、`adb reverse` 已清空。截图里可见 `1._Greatness.vtt` 第 1 个 cue（00:00:00–00:00:49）拆出的逐句双语行、当前句蓝色高亮，以及悬浮窗对同一句的滚动显示；另有 `--skip-install` 一轮 13/13 PASS。

2026-10-25 在“换句高亮 250ms 刷新”的未提交改动（基于 `ffcc05320`，脚本提交 `d66169281`，含临时时延日志）上完整跑通 32/32 PASS，偏好与数据库按字节还原。临时日志测得 250ms 轮询实际约 4.1 次/秒，句边界→高亮时延 73/65/148/153/162/170/−15 ms，均 ≤250ms。**测试注意**：设备偏好若给该 media 存了时间偏移（`prefTranscriptTimeOffsets`，例如 `{"1":103500}`），高亮会长时间停在第一段，需先清零或换 media id。

2026-10-25 在“一条 cue 一行 + 整段高亮”的未提交改动上完整跑通 32/32 PASS（可见行数 1：整条 cue 即一个高亮块），偏好与数据库按字节还原。截图确认第一条 cue（00:00:00–00:00:49）整块 19sp 高亮，块内当前句全色、其余句统一半透明。

### 5.3 语料基线（人工比对用）

真实双语语料：`D:\MyTranscript\site\the-rest-is-history\transcripts\zh\`（远端镜像 `https://lzoreal.github.io/TMLT/transcripts/zh/`）。
`1._Greatness.vtt` 共 62 个 cue，每个 cue 两行（拉丁英文 + CJK 中文）；一条 cue 一行后应得到 **62** 个 segment（逐句拆行时代的 316 行基线已废弃）。合成 fixture 不足以证明双语渲染正确，需用该语料在真机核对。

### 5.4 手工验收清单

- [ ] 转录页一条 cue 一行、整段 19sp 高亮、当前句颜色更深；逐句换行开关只影响行内排版
- [ ] 语言三态循环（双语 / 仅原文 / 仅译文）与逐句换行开关
- [ ] 字号放大/缩小后高亮行同步
- [ ] 点击句子从该句起点播到该句终点；单段循环可开关
- [ ] 偏移粗调/细调/重置，切集后再切回仍保留；删除单集后偏移消失
- [ ] 手动滚动暂停跟随，“回到当前句”恢复；首段也能滚动到位
- [ ] 换句高亮在句边界约 250ms 内切换，无整行淡入淡出；相邻句滚动明显放慢，>5 行仍瞬跳
- [ ] feed 刷新后（转录 URL 变化）页面/弹窗/悬浮窗/菜单自动更新
- [ ] 悬浮窗只显示当前句、双语间距、暂停不滚动、锁定/关闭 4s 自动隐藏、拖动水平吸附、点击回转录页
- [ ] 断网 + 已有本地文件时可离线显示；本地文件损坏时能重新下载

## 6. 已知坑速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `database disk image is malformed` | 用 `adb shell ... > databases/Antennapod.db` 写库 | 用 `adb exec-in run-as ... sh -c "cat > ..."` 并回读 `integrity_check` |
| `cat: C:/Program: No such file...` | Git Bash 转换了 `/sdcard/...` | 命令前加 `MSYS_NO_PATHCONV=1` |
| 启动后停在首页 | `-e open_player true` 是字符串 extra | 用 `--ez` |
| 传了 `--ez` 仍停在首页 | 同时传 `episode_id` 会优先走单集页 | 打开播放器时不要传 |
| 冷启动时 `--ez` 不生效 | 播放器还没 attach，底部抽屉处于锁定态，`setState(EXPANDED)` 被忽略 | 等首启完成后再发一次同一 intent，走 `onNewIntent` |
| `am start` 无反应、UI dump 里只有 `com.android.systemui`/`com.miui.aod`、`input tap` 全部丢失 | 设备息屏/Dozing（`mWakefulness=Dozing`）或停在 MIUI AOD 表盘（`mWakefulness=Awake` 但屏幕没 App），非 App 问题；反复重发唤醒手势会打断亮屏动画 | `KEYCODE_WAKEUP` + `wm dismiss-keyguard` + 一次上滑（仅在 App 未聚焦时）；有 PIN/图案锁时脚本无法解锁，需人工解锁 |
| 断言全 PASS 但截图是一片黑 | 息屏状态下 `screencap` 仍成功，只是帧内容为黑；UI dump 与截图是两条独立证据 | 看 PNG 字节数（全黑约 19 KB、AOD 表盘约 30 KB）；脚本已用 `capture_screen` 判 FAIL，人工解锁后重跑 |
| `reverse: inaccessible or not found` | `adb reverse` 是主机侧命令，被塞进了 `adb shell` | 直接 `adb reverse`，不要经 `adb shell` |
| `Can't start from invalid source` | 悬浮窗服务 `exported=false`，外部 `adb`/其它应用不能直接启动 | 在播放器溢出菜单开启（需悬浮窗权限）；开启后由播放服务按需自动 start/stop |
| `no such column: transcript_url` | 列在 `FeedItems` 不在 `FeedMedia` | 用 `podcastindex_transcript_url/_type` |
| 日志有 `Unsatisfiable Request (only-if-cached)` | 正常缓存回退 | 看后续 `Done Downloading` |
| 悬浮窗不出现 | 无悬浮窗权限 / 未从菜单开 / 当前集无转录 | 查 `appops`、菜单勾选、DB 中 URL |
| UI dump 里找不到 `tv_floating_transcript` | 悬浮窗是独立 window，`uiautomator dump` 只导出活动窗口 | 用 `dumpsys activity services` 判断服务 + 截图人工核对 |
| 流媒体播放失败 | 测试机 DNS 被污染（环境问题，非 App bug） | 用本地 VTT + `adb reverse`，或 `--isolate-network` |
| 播放器加载的不是目标单集 | 转录页打开 `currentlyPlayingMedia` | 先在 UI 播放该集，或用脚本推断的 media id |
| 设备测试里高亮长时间停在第一句 | 设备偏好中该 media 有时间偏移（`prefTranscriptTimeOffsets`） | 测试前清零该 media 的偏移或换 media id；测完按字节还原偏好 |

## 7. 已知限制 / 未完成

- 没有转录搜索/过滤。
- SRT：首行散文中的冒号（如 `Note: ...`）仍可能被当成发言人。
- 悬浮窗/页面在 fragment 停止期间发生外部 seek 时，要等下一个 `PlaybackPositionEvent` 才刷新。
- 换句高亮的 250ms 刷新只接在整屏转录页；弹窗与悬浮窗仍按 1 秒 `PlaybackPositionEvent` 更新（弹窗视为废弃，仅视频播放器入口保留）。
- 悬浮窗不显示发言人（用户要求移除）；`TranscriptSegment.speaker` 仍供转录页使用。
- 音频转录页的“跟随音频”开关与视频弹窗的其他增强：用户明确表示**暂不做**（“那不用管这个功能”），未被再次要求前不要实现或重提。
- `checkstyle`/`lint` 在 transcript 相关模块存在**既有**违规（未格式化历史代码），按 `AGENTS.md`「不修自己改动之外的告警」保持原样；只在被要求时跑 `./gradlew checkstyle lint`。

## 8. 更新本文档（完成标准）

1. 行为变了 → 更新 §1 对应行；入口增删 → 同步 §0/§3。
2. 代码动过锚点 → 重新 grep，确保 §3 每个行号仍落在对应符号上。
3. 测试变了 → 更新 §5；真机跑完 → 写 §5.2 结果（代码基线 + 脚本提交 + 日期）。
4. 完成标准：代码与本文档在同一提交，`git diff --stat` 两者都在；§1/§3/§5 无未更新项。

全局代码风格、编译、PR 约定见 [AGENTS.md](file:///D:/AntennaPod/AGENTS.md)。
相关记忆（`C:\Users\游梦\.qoder-cn\projects\D--AntennaPod\memory\`）：`transcript-feature-initiative.md`（设计决策与限制）、`transcript-test-corpus.md`（语料基线）、`checkstyle-transcript-baseline.md`、`test-device-network.md`。
