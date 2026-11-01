# 团队分工说明（4 人）

> 本文档说明本项目如何拆给 4 个成员完成，以及每个成员负责哪些文件、对外提供什么接口、
> 哪些文件不能随便动。代码里每个文件头部也有同样的标注，可对照阅读。

---

## 一、总体分工与占比

| 成员 | 角色 | 负责模块 | 代码行数 | 占比 |
|---|---|---|---|---|
| **成员 A** | 存储内核负责人 | 磁盘 I/O、FAT、目录项、路径解析、文件读写、OFT、业务门面、单元测试 | 2668 | **40.6 %** |
| **成员 B** | 主界面负责人 | 程序入口、主窗口、目录树导航 | 1294 | 19.7 % |
| **成员 C** | 交互与视觉负责人 | 文件列表、编辑窗口、属性窗口、右侧信息面板、全局配色主题 | 1301 | 19.8 % |
| **成员 D** | 可视化负责人 | 磁盘网格、FAT 表、字节查看器、已打开文件表面板、图标、块信息模型 | 1314 | 20.0 % |
| | | **合计** | **6577** | 100 % |

> 行数为 `src/main/java` 下的实际代码量（**含中文注释**，本项目注释占比很高，是刻意为了答辩讲解写的）。
> 成员 A 另有 `src/test/java/com/filesystem/FileSystemTest.java`（约 500 行）的 18 个单元测试。

**为什么 A 占 40%**：文件系统的难点全在"磁盘上的字节怎么摆"——FAT 链怎么接、目录项怎么塞进 8 字节、
写坏了怎么回滚。这部分逻辑错一个字节整个系统就跑不起来，而且要写测试兜底，所以给一个人统一负责，
避免四个人各改一半把 FAT 改乱。**A 不写任何界面代码**，界面全部给 B、C、D。

---

## 二、每个成员负责的文件

### 成员 A —— 存储内核（40%）

```
src/main/java/com/filesystem/
├── util/Constants.java               常量：块大小、块号、FAT 特殊值、属性值……
├── util/FileSystemException.java     业务异常（磁盘满、只读、重名……统一抛这个）
├── model/DirectoryEntry.java         目录项：8 字节怎么拆成"名字/类型/属性/起始块/长度"
├── model/Pointer.java                读写指针：块号 + 块内偏移
├── model/OpenFileEntry.java          OFT 表项
├── core/DiskManager.java             模拟磁盘：RandomAccessFile 读写 64 字节块
├── core/FatTable.java                FAT 表：分配、回收、链遍历、环检测
├── core/DirectoryManager.java        目录块读写、查空目录项、判重名
├── core/PathResolver.java            绝对路径逐级解析（/DOC/SUB/C.TX）
├── core/FileManager.java             文件数据块的读写（顺序读、追加、覆盖）
├── core/OpenFileTable.java           OFT：最多 5 项，查重、找空位
└── core/FileSystemService.java       业务门面：create/open/read/write/delete/rename/属性……
```

**A 对外提供的唯一入口是 `FileSystemService`**，B/C/D 只准调它的公开方法，
不准 import `FatTable`、`DiskManager` 这些内部类自己改数据（UI 直接改 FAT 是课程设计的大忌）。

### 成员 B —— 主窗口与目录导航（20%）

```
src/main/java/com/filesystem/Main.java            启动入口：初始化主题、建磁盘、开窗口
src/main/java/com/filesystem/ui/MainFrame.java    主窗口：菜单、工具栏、三栏分割、状态栏、所有回调
src/main/java/com/filesystem/ui/DirectoryTreePanel.java  左侧 JTree 目录树
```

**B 的核心工作**是"把 A 的能力接成一套操作流程"：用户点右键 → 弹输入框 → 校验名字 →
调 `service.createFile()` → 刷新三个面板 → 状态栏提示。所有"串场"逻辑在 `MainFrame` 的 `refreshAll()`。

### 成员 C —— 文件列表、对话框与视觉主题（20%）

```
src/main/java/com/filesystem/ui/FileListPanel.java      中央 JTable 文件列表
src/main/java/com/filesystem/ui/FileEditorDialog.java   文件编辑窗口（追加 / 覆盖两种模式）
src/main/java/com/filesystem/ui/PropertiesDialog.java   属性窗口（只读 ↔ 可读写切换、FAT 链展示）
src/main/java/com/filesystem/ui/SelectionInfoPanel.java 右侧"当前选中项"信息面板
src/main/java/com/filesystem/ui/UiTheme.java            全局配色与字体（所有面板共用）
```

`UiTheme` 虽然是公共的，但由 C 维护配色表；B、D 只能**引用**其中的常量，
想改颜色要先跟 C 说一声，否则三个面板的图例会不一致。

### 成员 D —— 磁盘与 FAT 可视化（20%）

```
src/main/java/com/filesystem/ui/DiskMapPanel.java       16×8 磁盘块网格（自绘）
src/main/java/com/filesystem/ui/FatTablePanel.java      128 项 FAT 表
src/main/java/com/filesystem/ui/BlockInspectorPanel.java 64 字节十六进制查看器
src/main/java/com/filesystem/ui/OpenFileTablePanel.java  已打开文件表（OFT）面板
src/main/java/com/filesystem/ui/FileIcons.java           文件 / 目录 / 只读 / 打开的图标
src/main/java/com/filesystem/model/BlockInfo.java        单块的展示信息（FAT 值 + 归属 + 状态）
```

**关于 `BlockInfo` 为什么给 D**：它是个纯粹的数据载体（DTO），只有 getter/setter，
不含任何文件系统逻辑，专门给"磁盘网格 / FAT 表 / 字节查看器"三个面板喂数据。
D 定义这个结构的字段，A 的 `FileSystemService.buildBlockInfos()` 负责往里填真实数据。

---

## 三、依赖关系（谁调谁）

```
                    ┌──────────────────────────────┐
   成员 B            │  Main → MainFrame            │
                    │         ├─ DirectoryTreePanel│
   成员 C            │         ├─ FileListPanel     │
                    │         ├─ SelectionInfoPanel│
                    │         ├─ FileEditorDialog  │
                    │         └─ PropertiesDialog  │
   成员 D            │  DiskMapPanel / FatTablePanel│
                    │  BlockInspectorPanel / OFT   │
                    └──────────────┬───────────────┘
                                   │ 只准调 FileSystemService 的公开方法
                    ┌──────────────┴───────────────┐
   成员 A            │      FileSystemService       │  业务门面
                    │   ├─ PathResolver            │
                    │   ├─ DirectoryManager        │
                    │   ├─ FileManager             │
                    │   ├─ OpenFileTable           │
                    │   ├─ FatTable                │
                    │   └─ DiskManager             │
                    │        └─ data/disk.img      │  ← 真实磁盘文件
                    └──────────────────────────────┘
```

**单向依赖**：UI → Service → Core → Disk。核心层不 import 任何 `javax.swing`，
所以 A 的代码可以脱离界面单独跑单元测试（这也是 `FileSystemTest` 能跑起来的原因）。

---

## 四、接口约定（四人必须遵守）

| 约定 | 说明 | 谁维护 |
|---|---|---|
| `FileSystemService` 公开方法 | 界面层唯一入口，签名改动要通知 B/C/D | A |
| `DirectoryEntry` | 界面读它来填表格/树，字段语义不能改 | A |
| `BlockInfo` | D 定义字段，A 负责填充 | D 定，A 填 |
| `UiTheme` 颜色常量 | 三个可视化面板共用同一套颜色语义 | C |
| `Constants` | 块大小 64、总块数 128、FAT 特殊值 253/254/255 等，**任何人不得修改** | A |

**红线（谁碰谁负责）**：

1. 界面层禁止 `new FatTable()`、禁止直接 `disk.writeBlock()`——必须走 Service。
2. 核心层禁止 `import javax.swing.*`——保证可测试。
3. `Constants` 里的磁盘参数是课程规定值，改了就等于换了一个题目。
4. 别人负责的文件要改，先在群里说，改完在自己提交信息里写清楚。

---

## 五、答辩分工建议（现场演示时每人讲一段）

| 顺序 | 成员 | 讲什么 | 对应演示动作 |
|---|---|---|---|
| 1 | A | 磁盘 8192 字节怎么切、FAT 253/254/255 的含义、目录项 8 字节怎么拆 | 格式化磁盘 → 点开 FAT 表 → 点开块 2 看根目录字节 |
| 2 | B | 目录树与路径解析、右键菜单怎么串到 Service | 建 DOC / SUB 目录，双击进入 |
| 3 | C | 读写指针、追加 vs 覆盖、只读属性为什么真拦得住 | 写 Hello → 追加 World → 覆盖 OperatingSystem → 改只读后再试写入被拒 |
| 4 | D | 磁盘网格与 FAT 表怎么做到"实时反映真实磁盘"、块归属怎么算出来 | 写一个跨块文件，看网格多出蓝格、FAT 表链、点块看 64 字节 |

这样四个人的部分都摸到了，也正好覆盖课程要求的九个测试场景。
