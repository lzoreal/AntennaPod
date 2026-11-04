# Transcript

本仓库的转录（Transcript）上下文：把一集播客的音频变成带时间轴的文字，并在播放器中显示、跟随与跳转。

## Language

**转录（Transcript）**:
一集播客的带时间轴文本产物，由若干转录段组成。
_Avoid_: 字幕、caption（仅指显示形态）

**转录段（TranscriptSegment）**:
转录中一段连续文字及其起止时间，可带发言人。
_Avoid_: 句子、cue、行

**词级时间轴（Word timings）**:
转录段内每个词的字符区间与起止时间，用于逐词高亮、点击跳词与滚动。
_Avoid_: 词级时间戳、PTS、token 时间

**词片（Piece）**:
识别引擎输出的子词单元，经还原后成为词或 CJK 单字，不直接面向用户。
_Avoid_: token、子词

**官方转录（Published transcript）**:
随剧集发布方提供的转录文件（PodcastIndex 等来源），可能自带双语。
_Avoid_: 原始转录、源转录

**生成转录（Generated transcript）**:
由本机预读音频识别得到的单语转录，按缺口渐进累积并持久化；存在官方转录时不生成。
_Avoid_: 实时字幕、本地字幕

**预读（Lookahead）**:
在播放位置之前解码音频，供生成转录使用。
_Avoid_: 缓冲、PCM 抽头

**预读窗口（Lookahead window）**:
生成转录领先播放位置的时间量，用户可调 10–120 秒，默认 30 秒。
_Avoid_: 缓冲、延迟

**缺口（Gap）**:
转录中尚未生成的一段媒体时间区间，由跳转、暂停或缓存不足造成。
_Avoid_: 空洞、缺失

**定稿句（Finalized segment）**:
识别器确认后写入转录的整句；未定稿内容不进入转录，也不上屏。
_Avoid_: partial、临时句

**媒体时间（Media time）**:
相对音频文件起点的时间，是转录时间与播放位置之间的换算基准。
_Avoid_: PTS、绝对时间

**转录时间（Transcript time）**:
转录文件中记录的时间；与媒体时间可能因时长估算存在缩放。
_Avoid_: 字幕时间

**播放位置（Playback position）**:
播放器当前报告的位置，用于驱动高亮、跟随与跳转。
_Avoid_: 当前时间、进度

**字幕（Captions）**:
转录在屏幕上的显示形态（转录页、悬浮窗），不是领域产物本身。
_Avoid_: 用它指代转录
