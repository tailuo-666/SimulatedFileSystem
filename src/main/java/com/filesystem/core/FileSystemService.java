package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：业务门面：把磁盘、FAT、目录、文件、OFT 组装成课程要求的 10 个操作 + 统计/归属查询
 *  上游依赖：上述全部 core 与 model 类
 *  下游调用：★ 界面层四个成员唯一允许调用的类（B/C/D 全靠它）
 * ========================================================================== */

import com.filesystem.model.BlockInfo;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.model.OpenFileEntry;
import com.filesystem.model.Pointer;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件系统服务层 —— 核心业务的总入口。
 * <p>
 * 界面层（ui 包）只跟这个类打交道，绝不直接碰 FAT 数组或磁盘块；
 * 它内部再去协调磁盘管理、FAT、目录、文件、OFT 和路径解析几个部件。
 * 这样分层的好处是：所有合法 / 非法的判断都集中在这里，界面只是“显示 + 调用”。
 * <p>
 * 对应课程设计的命令：create_file / open_file / read_file / write_file /
 * close_file / delete_file / typefile / change / md / dir / rd / rename。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class FileSystemService implements Closeable {

    /*
     * 下面 6 个成员就是"文件系统"的全部零件，它们在构造方法里按依赖顺序装配：
     *   磁盘 → FAT → 目录 → 文件读写 → 路径解析 → 已打开文件表
     * 界面层只看得见这个类，看不见它们，所以界面永远不可能绕过规则直接改 FAT。
     */

    /** 模拟磁盘：唯一真正碰 data/disk.img 的对象 */
    private final DiskManager disk;

    /** FAT 表：所有块的分配、回收、链遍历 */
    private final FatTable fat;

    /** 目录管理器：目录块的读写 */
    private final DirectoryManager dirManager;

    /** 文件管理器：文件内容的顺序读 / 追加写 / 覆盖写 */
    private final FileManager fileManager;

    /** 已打开文件表：最多 5 项，记录读写方式和读写指针 */
    private final OpenFileTable oft;

    /** 路径解析器：把 "/DOC/SUB/C.TX" 拆成一级一级去查 */
    private final PathResolver resolver;

    /**
     * 创建文件系统服务。
     * <p>
     * 如果 disk.img 不存在（首次运行），会自动初始化一张空磁盘；
     * 如果已经存在，则直接读入原来的 FAT 和目录，<b>不会重新格式化</b>，
     * 这样上次写的文件在下次启动时依然在。
     */
    public FileSystemService(Path imagePath) throws IOException {
        this.disk = new DiskManager(imagePath);
        boolean freshDisk = disk.open();
        this.fat = new FatTable(disk);
        this.dirManager = new DirectoryManager(disk);
        this.fileManager = new FileManager(disk, fat);
        this.oft = new OpenFileTable();
        this.resolver = new PathResolver(dirManager);

        if (freshDisk) {
            // 全新的空白磁盘：建立 FAT、坏块标记和根目录
            initializeNewDisk();
        } else {
            // 已有磁盘：读出 FAT 缓存
            fat.load();
        }
    }

    /** 用默认路径 data/disk.img 创建服务 */
    public static FileSystemService createDefault() throws IOException {
        return new FileSystemService(Path.of(Constants.DEFAULT_DISK_PATH));
    }

    /**
     * 首次启动时的磁盘初始化：
     * FAT[0..2] = 253（系统保留）、FAT[23]=FAT[49]=254（坏块）、其余 0，
     * 根目录块填满 '$'（8 个空目录项），然后把 FAT 写到块 0、1，根目录写到块 2。
     */
    private void initializeNewDisk() throws IOException {
        fat.initNewFat();
        fat.save();
        dirManager.initDirectoryBlock(Constants.ROOT_BLOCK);
        oft.clear();
    }

    /**
     * 格式化磁盘：清空全部 128 块，重建 FAT 与根目录。
     * 界面上调用前务必二次确认。
     */
    public void format() throws IOException {
        oft.clear();                  // 所有打开的文件随之作废
        disk.clearAll();              // 8192 字节全部写 0
        initializeNewDisk();
    }

    // ==================================================================
    //  一、查询类操作
    // ==================================================================

    /**
     * 列出某个目录下的全部目录项（先目录后文件，各自按名称排序）。
     */
    public List<DirectoryEntry> listDirectory(String path) throws IOException {
        int dirBlock = resolver.resolveDirectoryBlock(path);
        List<DirectoryEntry> entries = dirManager.readDirectory(dirBlock);
        entries.sort(Comparator
                .comparing((DirectoryEntry e) -> !e.isDirectory())  // 目录排在前面
                .thenComparing(DirectoryEntry::getFullName, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** 查找目录项，找不到抛异常 */
    public DirectoryEntry getEntry(String path) throws IOException {
        return resolver.resolveEntryOrThrow(path);
    }

    /** 路径是否存在 */
    public boolean exists(String path) throws IOException {
        return resolver.exists(path);
    }

    /** 是否是目录 */
    public boolean isDirectory(String path) throws IOException {
        return resolver.isDirectory(path);
    }

    /**
     * typefile：显示文件完整内容。
     * <p>
     * 按课程要求，文件正在打开时禁止直接 typefile（避免和 OFT 里的指针状态打架）。
     */
    public String readTypeFile(String path) throws IOException {
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("“" + p + "” 是目录，不能显示文件内容。");
        }
        if (oft.isOpen(p)) {
            throw new FileSystemException("文件正在打开，按课程要求不能执行 typefile。请先关闭该文件。");
        }
        byte[] content = fileManager.readContent(entry.getStartBlock());
        return new String(content, StandardCharsets.UTF_8);
    }

    /** 读取文件所占用的 FAT 链（属性窗口显示用） */
    public List<Integer> getFatChain(String path) throws IOException {
        DirectoryEntry entry = resolver.resolveEntryOrThrow(path);
        return fat.chainOf(entry.getStartBlock());
    }

    /**
     * 仅供界面刷新显示：直接把文件当前内容读出来。
     * <p>
     * 和 {@link #readTypeFile(String)} 的区别是：这里<b>不检查</b>文件是否打开、
     * 也不推进读指针，纯粹给编辑窗口“刷新一下看看磁盘里现在是什么”用。
     */
    public String loadContentForDisplay(String path) throws IOException {
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("“" + p + "” 是目录，没有内容可显示。");
        }
        byte[] content = fileManager.readContent(entry.getStartBlock());
        return new String(content, StandardCharsets.UTF_8);
    }

    /** 读取某个文件的实际字节数（读到 '#' 为止） */
    public int getFileByteLength(int startBlock) {
        try {
            return fileManager.readContent(startBlock).length;
        } catch (IOException | RuntimeException e) {
            return -1; // 链损坏时返回 -1，界面显示 “?”
        }
    }

    // ==================================================================
    //  二、目录操作（md / rd / rename）
    // ==================================================================

    /**
     * md：建立目录。
     * <p>
     * 流程：检查父目录 → 检查名称合法性 → 检查重名 → 找空目录项 →
     * 申请一个空闲块 → 初始化目录块 → 写目录登记项 → 更新 FAT。
     */
    public void createDirectory(String path) throws IOException {
        String p = PathResolver.normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            throw new FileSystemException("根目录已经存在，不能重复创建。");
        }
        String name = PathResolver.getBaseName(p);
        validateDirectoryName(name);

        String parentPath = PathResolver.getParentPath(p);
        int parentBlock = resolver.resolveDirectoryBlock(parentPath); // 父目录不存在会抛异常

        if (resolver.exists(p)) {
            throw new FileSystemException("同级目录下已存在同名文件或目录：" + name);
        }
        int slot = dirManager.findFreeSlot(parentBlock);
        if (slot < 0) {
            throw new FileSystemException("父目录已满：每个目录最多只能放 "
                    + Constants.ENTRIES_PER_BLOCK + " 个目录项。");
        }
        if (fat.countFreeBlocks() < 1) {
            throw new FileSystemException("磁盘空间不足，无法创建目录。");
        }

        int newBlock = fat.allocateBlock();
        if (newBlock < 0) {
            throw new FileSystemException("磁盘空间不足，无法创建目录。");
        }
        try {
            dirManager.initDirectoryBlock(newBlock);            // 新目录块：8 个空目录项
            DirectoryEntry entry = DirectoryEntry.forDirectory(parentBlock, slot, name.toUpperCase(), newBlock);
            dirManager.writeEntry(entry);                        // 写进父目录
            fat.set(newBlock, Constants.FAT_EOF);                // 目录只占 1 块，直接 EOF
            fat.save();                                          // FAT 落盘
        } catch (IOException | RuntimeException e) {
            fat.freeBlock(newBlock);                             // 失败则把块还回去，不留垃圾
            throw new FileSystemException("创建目录失败：" + e.getMessage(), e);
        }
    }

    /**
     * rd：删除空目录。
     * 根目录不能删；非空目录不能删；目录下有文件正在打开也不能删。
     */
    public void deleteDirectory(String path) throws IOException {
        String p = PathResolver.normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            throw new FileSystemException("根目录不能删除。");
        }
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (!entry.isDirectory()) {
            throw new FileSystemException("“" + p + "” 不是目录，请使用“删除文件”。");
        }
        List<DirectoryEntry> children = dirManager.readDirectory(entry.getStartBlock());
        if (!children.isEmpty()) {
            throw new FileSystemException("目录非空，不能删除。请先删除其中的 "
                    + children.size() + " 个文件或子目录。");
        }
        if (oft.hasOpenFileUnder(p)) {
            throw new FileSystemException("该目录下有文件正在打开，请先关闭。");
        }
        int dirBlock = entry.getStartBlock();
        dirManager.clearEntry(entry.getContainerBlock(), entry.getSlotIndex()); // 清空登记项
        fat.freeBlock(dirBlock);     // 回收目录块
        fat.save();
    }

    /**
     * 重命名（扩展功能）：文件和目录都支持。
     * 正在打开的文件 / 目录不允许重命名。
     */
    public void rename(String path, String newName) throws IOException {
        String p = PathResolver.normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            throw new FileSystemException("根目录不能重命名。");
        }
        String trimmed = newName == null ? "" : newName.trim();
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (oft.isOpen(p)) {
            throw new FileSystemException("文件正在打开，请先关闭再重命名。");
        }
        if (entry.isDirectory()) {
            validateDirectoryName(trimmed);
            if (oft.hasOpenFileUnder(p)) {
                throw new FileSystemException("该目录下有文件正在打开，请先关闭再重命名。");
            }
        } else {
            validateFileName(trimmed);
        }

        // 名称拆成主名 + 扩展名
        String base;
        String ext = "";
        if (entry.isDirectory()) {
            base = trimmed.toUpperCase();
        } else {
            int dot = trimmed.indexOf('.');
            if (dot < 0) {
                base = trimmed.toUpperCase();
            } else {
                base = trimmed.substring(0, dot).toUpperCase();
                ext = trimmed.substring(dot + 1).toUpperCase();
            }
        }
        String newFullName = ext.isEmpty() ? base : base + "." + ext;

        // 同级重名检查（要排除自己）
        DirectoryEntry sameName = dirManager.findEntry(entry.getContainerBlock(), newFullName);
        if (sameName != null && sameName.getSlotIndex() != entry.getSlotIndex()) {
            throw new FileSystemException("同级目录下已存在同名文件或目录：" + newFullName);
        }
        if (sameName != null) {
            return; // 名字没变，什么也不做
        }

        entry.setName(base);
        entry.setExt(ext);
        dirManager.writeEntry(entry); // 重写目录项后，里面的文件依然能按新路径访问
    }

    // ==================================================================
    //  三、文件操作（create_file / delete_file）
    // ==================================================================

    /**
     * create_file：建立文件，默认属性为“可读写”。
     * <p>
     * 创建成功后会以写方式自动加入 OFT（课程设计要求）；
     * 若 OFT 已满，文件仍然创建成功，只是不自动打开。
     */
    public void createFile(String path, int attribute) throws IOException {
        String p = PathResolver.normalize(path);
        String fullName = PathResolver.getBaseName(p);
        validateFileName(fullName);
        if (attribute != Constants.ATTR_READ_ONLY && attribute != Constants.ATTR_NORMAL) {
            throw new FileSystemException("文件属性只能是 1（只读）或 4（可读写）。");
        }

        String parentPath = PathResolver.getParentPath(p);
        int parentBlock = resolver.resolveDirectoryBlock(parentPath);

        if (resolver.exists(p)) {
            throw new FileSystemException("同级目录下已存在同名文件或目录：" + fullName);
        }
        int slot = dirManager.findFreeSlot(parentBlock);
        if (slot < 0) {
            throw new FileSystemException("父目录已满：每个目录最多只能放 "
                    + Constants.ENTRIES_PER_BLOCK + " 个目录项。");
        }
        if (fat.countFreeBlocks() < 1) {
            throw new FileSystemException("磁盘空间不足，无法创建文件。");
        }

        int newBlock = fat.allocateBlock();
        if (newBlock < 0) {
            throw new FileSystemException("磁盘空间不足，无法创建文件。");
        }
        String base;
        String ext;
        int dot = fullName.indexOf('.');
        if (dot < 0) {
            base = fullName.toUpperCase();
            ext = "";
        } else {
            base = fullName.substring(0, dot).toUpperCase();
            ext = fullName.substring(dot + 1).toUpperCase();
        }
        try {
            // 新文件的内容块：全部清零，第一个字节写结束符 '#'，表示空文件
            byte[] buffer = new byte[Constants.BLOCK_SIZE];
            buffer[0] = Constants.EOF_FLAG;
            disk.writeBlock(newBlock, buffer);

            DirectoryEntry entry = DirectoryEntry.forFile(parentBlock, slot, base, ext,
                    attribute, newBlock, 1);
            dirManager.writeEntry(entry);
            fat.set(newBlock, Constants.FAT_EOF);
            fat.save();
        } catch (IOException | RuntimeException e) {
            fat.freeBlock(newBlock);
            throw new FileSystemException("创建文件失败：" + e.getMessage(), e);
        }

        // 创建后以写方式打开（OFT 满了就不打开，不影响文件已经建好）
        try {
            openFile(p, Constants.OPEN_WRITE);
        } catch (FileSystemException ignored) {
            // 打开失败（例如 OFT 已满）不阻断创建流程
        }
    }

    /**
     * delete_file：删除文件。
     * 正在打开的文件不能删；删除时把整条 FAT 链上的块全部回收。
     */
    public void deleteFile(String path) throws IOException {
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("“" + p + "” 是目录，请使用“删除目录”。");
        }
        if (oft.isOpen(p)) {
            throw new FileSystemException("文件正在打开，请先关闭再删除：" + p);
        }
        dirManager.clearEntry(entry.getContainerBlock(), entry.getSlotIndex());
        if (!fat.isSystem(entry.getStartBlock()) && !fat.isBad(entry.getStartBlock())) {
            fat.freeChain(entry.getStartBlock()); // 沿链回收所有块
        }
        fat.save();
    }

    /**
     * change：修改文件属性（只读 / 可读写）。
     * 文件正在打开时不允许修改；目录属性固定为 8，不能改。
     */
    public void changeAttribute(String path, int newAttribute) throws IOException {
        if (newAttribute != Constants.ATTR_READ_ONLY && newAttribute != Constants.ATTR_NORMAL) {
            throw new FileSystemException("文件属性只能是 1（只读）或 4（可读写）。");
        }
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("目录属性固定为 8，不能修改。");
        }
        if (oft.isOpen(p)) {
            throw new FileSystemException("文件正在打开，请先关闭再修改属性。");
        }
        if (entry.getAttribute() == newAttribute) {
            return;
        }
        entry.setAttribute(newAttribute);
        dirManager.writeEntry(entry); // 目录项同步落盘
    }

    // ==================================================================
    //  四、打开 / 关闭 / 读写
    // ==================================================================

    /**
     * open_file：以读方式或写方式打开文件。
     *
     * @param operationType {@link Constants#OPEN_READ} 或 {@link Constants#OPEN_WRITE}
     */
    public void openFile(String path, int operationType) throws IOException {
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("“" + p + "” 是目录，不能作为文件打开。");
        }
        if (oft.isOpen(p)) {
            throw new FileSystemException("文件已经打开，不能重复打开：" + p
                    + "（同一个文件在 OFT 中只能有一个表项）");
        }
        if (oft.isFull()) {
            throw new FileSystemException("已打开文件表已满（最多 "
                    + Constants.MAX_OPEN_FILES + " 个文件），请先关闭其他文件。");
        }
        if (operationType == Constants.OPEN_WRITE && entry.isReadOnly()) {
            throw new FileSystemException("文件是只读属性，不能以写方式打开：" + p);
        }

        byte[] content = fileManager.readContent(entry.getStartBlock());
        OpenFileEntry openFile = new OpenFileEntry(p, entry.getAttribute(), entry.getStartBlock(),
                content.length, entry.getLengthBlocks(), operationType);

        // 读指针指向文件开头；写指针指向文件末尾（追加写入的起点）
        openFile.getReadPointer().moveTo(entry.getStartBlock(), 0);
        positionPointerAtEnd(openFile.getWritePointer(), entry.getStartBlock(), content.length);
        oft.add(openFile);
    }

    /**
     * close_file：关闭文件。
     * 以写方式打开的文件在关闭时会把内容重新整理一遍，确保末尾有结束符 '#'，
     * 并把最新的块数写回目录项，最后移除 OFT 表项。
     */
    public void closeFile(String path) throws IOException {
        String p = PathResolver.normalize(path);
        OpenFileEntry openFile = oft.find(p);
        if (openFile == null) {
            throw new FileSystemException("文件未打开，无法关闭：" + p);
        }
        DirectoryEntry entry = resolver.resolveEntry(p);
        if (entry != null && openFile.isWriteMode()) {
            byte[] content = fileManager.readContent(entry.getStartBlock());
            int blocks = fileManager.writeContent(entry.getStartBlock(), content); // 补结束符并归一化
            entry.setLengthBlocks(blocks);
            dirManager.writeEntry(entry);
            fat.save();
        }
        oft.remove(p);
    }

    /** 关闭全部已打开文件（程序退出 / 格式化前调用） */
    public void closeAllFiles() throws IOException {
        for (OpenFileEntry openFile : oft.list()) {
            closeFile(openFile.getAbsolutePath());
        }
    }

    /**
     * read_file：从读指针位置读取最多 length 个字节。
     * <p>
     * 如果文件还没打开，会自动以读方式打开（课程设计要求）；
     * 如果文件是以写方式打开的，则拒绝读取。
     */
    public String readFile(String path, int length) throws IOException {
        if (length <= 0) {
            throw new FileSystemException("读取长度必须大于 0。");
        }
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("“" + p + "” 是目录，不能读取内容。");
        }
        OpenFileEntry openFile = oft.find(p);
        if (openFile == null) {
            openFile(p, Constants.OPEN_READ); // 未打开则自动以读方式打开
            openFile = oft.find(p);
        }
        if (openFile != null && openFile.isWriteMode()) {
            throw new FileSystemException("文件当前以写方式打开，不能读取。请先关闭文件。");
        }

        byte[] all = fileManager.readContent(entry.getStartBlock());
        List<Integer> chain = fat.chainOf(entry.getStartBlock());
        Pointer pointer = openFile.getReadPointer();

        int start = linearPosition(chain, pointer);
        if (start >= all.length) {
            return ""; // 已经到文件末尾（遇到结束符）
        }
        int end = Math.min(all.length, start + length);
        byte[] part = Arrays.copyOfRange(all, start, end);
        updatePointer(pointer, chain, end); // 读完后推进读指针（可跨块）
        return new String(part, StandardCharsets.UTF_8);
    }

    /**
     * write_file：写入内容。
     *
     * @param mode {@link Constants#WRITE_APPEND} 追加 / {@link Constants#WRITE_OVERWRITE} 覆盖
     * @return 写入后文件的实际字节数
     */
    public int writeFile(String path, String content, int mode) throws IOException {
        if (content == null) {
            content = "";
        }
        if (content.indexOf(Constants.EOF_CHAR) >= 0) {
            throw new FileSystemException("正文中不允许出现结束符 '#'，请删除后再写入。");
        }
        String p = PathResolver.normalize(path);
        DirectoryEntry entry = resolver.resolveEntryOrThrow(p);
        if (entry.isDirectory()) {
            throw new FileSystemException("不能向目录写入内容：" + p);
        }
        // 只读校验放在最前面：即使绕过界面直接调用本方法，也无法写入只读文件
        if (entry.isReadOnly()) {
            throw new FileSystemException("文件是只读属性，禁止写入：" + p);
        }
        if (!oft.isOpen(p)) {
            openFile(p, Constants.OPEN_WRITE);
        }
        OpenFileEntry openFile = oft.find(p);
        if (openFile == null || !openFile.isWriteMode()) {
            throw new FileSystemException("文件以只读方式打开，不能写入：" + p);
        }

        byte[] newBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] data;
        if (mode == Constants.WRITE_APPEND) {
            // 追加：原有内容 + 新内容
            byte[] old = fileManager.readContent(entry.getStartBlock());
            data = Arrays.copyOf(old, old.length + newBytes.length);
            System.arraycopy(newBytes, 0, data, old.length, newBytes.length);
        } else {
            // 覆盖：直接用新内容替换，写指针回到文件开头
            data = newBytes;
            openFile.getReadPointer().moveTo(entry.getStartBlock(), 0);
        }

        int blocks = fileManager.writeContent(entry.getStartBlock(), data);

        // 目录项里的“长度”字段记录的是块数，同步更新
        entry.setLengthBlocks(blocks);
        dirManager.writeEntry(entry);
        fat.save();

        // OFT 表项里的统计信息和写指针一起更新到最新状态
        openFile.setBlockCount(blocks);
        openFile.setLengthBytes(data.length);
        openFile.setAttribute(entry.getAttribute());
        List<Integer> chain = fat.chainOf(entry.getStartBlock());
        positionPointerAtEnd(openFile.getWritePointer(), entry.getStartBlock(), data.length);
        return data.length;
    }

    // ==================================================================
    //  五、磁盘视图（供界面刷新用）
    // ==================================================================

    /**
     * 构建全部 128 个磁盘块的信息。
     * <p>
     * 块归属不是存在磁盘上的，而是每次现算：递归遍历目录树拿到每个文件的起始块，
     * 再沿 FAT 链把链上每一块都登记到“块号 → 路径”的映射里。
     */
    public List<BlockInfo> buildBlockInfos() throws IOException {
        int[] fatSnapshot = fat.snapshot();

        // 已打开文件占用的块，用于 FAT 表高亮
        Map<Integer, String> openBlocks = new HashMap<>();
        for (OpenFileEntry openFile : oft.list()) {
            try {
                for (int block : fat.chainOf(openFile.getStartBlock())) {
                    openBlocks.put(block, openFile.getAbsolutePath());
                }
            } catch (RuntimeException ignored) {
                // 链损坏时忽略，异常块会在下面单独标出来
            }
        }

        OwnerMap owners = new OwnerMap();
        owners.owner.put(Constants.FAT_BLOCK_FIRST, "[FAT 表]");
        owners.owner.put(Constants.FAT_BLOCK_SECOND, "[FAT 表]");
        owners.owner.put(Constants.ROOT_BLOCK, Constants.ROOT_PATH);
        owners.directory.put(Constants.ROOT_BLOCK, Boolean.TRUE);
        collectOwners(Constants.ROOT_PATH, Constants.ROOT_BLOCK, owners, new HashSet<>());

        List<BlockInfo> list = new ArrayList<>(Constants.TOTAL_BLOCKS);
        for (int block = 0; block < Constants.TOTAL_BLOCKS; block++) {
            int value = fatSnapshot[block];
            String owner = owners.owner.get(block);
            String remark = "";
            BlockInfo.State state;

            if (block == Constants.FAT_BLOCK_FIRST || block == Constants.FAT_BLOCK_SECOND) {
                state = BlockInfo.State.SYSTEM;
            } else if (block == Constants.ROOT_BLOCK) {
                state = BlockInfo.State.ROOT;
            } else if (value == Constants.FAT_BAD) {
                state = BlockInfo.State.BAD;
            } else if (value == Constants.FAT_FREE) {
                if (owner != null) {
                    // 有目录项指向它，FAT 却说它空闲 —— 数据不一致，必须显示出来
                    state = BlockInfo.State.ORPHAN;
                    remark = "数据不一致：FAT 标记为空闲，但有目录项指向该块";
                } else {
                    state = BlockInfo.State.FREE;
                }
            } else if (value == Constants.FAT_SYSTEM) {
                state = BlockInfo.State.SYSTEM;
            } else if (owners.conflicts.contains(block)) {
                state = BlockInfo.State.ORPHAN;
                remark = "异常：多个文件共享了同一个磁盘块";
            } else if (owner == null) {
                state = BlockInfo.State.ORPHAN;
                remark = "异常：已分配但找不到所属目录项（可能是残留块）";
            } else if (Boolean.TRUE.equals(owners.directory.get(block))) {
                state = BlockInfo.State.DIRECTORY;
            } else if (value == Constants.FAT_EOF) {
                state = BlockInfo.State.FILE_END;
            } else {
                state = BlockInfo.State.FILE_DATA;
            }
            list.add(new BlockInfo(block, value, state, owner, openBlocks.get(block), remark));
        }
        return list;
    }

    /** 块号 → BlockInfo 的映射，方便点击磁盘块时快速取信息 */
    public Map<Integer, BlockInfo> buildBlockInfoMap() throws IOException {
        List<BlockInfo> list = buildBlockInfos();
        Map<Integer, BlockInfo> map = new LinkedHashMap<>();
        for (BlockInfo info : list) {
            map.put(info.getBlockNumber(), info);
        }
        return map;
    }

    /**
     * 递归遍历目录树，把每个块登记到“块号 → 所属路径”的映射中。
     *
     * @param visitedDirs 已访问过的目录块集合，防止目录项互相指向形成死循环
     */
    private void collectOwners(String dirPath, int dirBlock, OwnerMap result, Set<Integer> visitedDirs)
            throws IOException {
        if (!visitedDirs.add(dirBlock)) {
            return; // 目录块成环，直接返回，避免无限递归
        }
        for (DirectoryEntry entry : dirManager.readDirectory(dirBlock)) {
            String childPath = PathResolver.join(dirPath, entry.getFullName());
            if (entry.isDirectory()) {
                result.owner.put(entry.getStartBlock(), childPath);
                result.directory.put(entry.getStartBlock(), Boolean.TRUE);
                collectOwners(childPath, entry.getStartBlock(), result, visitedDirs);
            } else {
                try {
                    for (int block : fat.chainOf(entry.getStartBlock())) {
                        if (result.owner.containsKey(block)) {
                            // 同一块被两个文件占用 —— 记录冲突，界面会标为异常
                            result.conflicts.add(block);
                        } else {
                            result.owner.put(block, childPath);
                            result.directory.put(block, Boolean.FALSE);
                        }
                    }
                } catch (RuntimeException e) {
                    // 链断裂或成环：至少把起始块标出来，并在界面标为异常
                    result.owner.put(entry.getStartBlock(), childPath);
                    result.directory.put(entry.getStartBlock(), Boolean.FALSE);
                }
            }
        }
    }

    /** 磁盘统计信息 */
    public DiskStatistics getStatistics() {
        int free = fat.countFreeBlocks();
        int system = 3;                                        // 块 0、1、2
        int bad = Constants.BAD_BLOCK_NUMBERS.length;          // 块 23、49
        int used = Constants.TOTAL_BLOCKS - free - system - bad;
        int usable = Constants.TOTAL_BLOCKS - system - bad;    // 可分配给文件/目录的块总数
        double usage = usable == 0 ? 0 : used * 100.0 / usable;
        return new DiskStatistics(Constants.TOTAL_BLOCKS, free, used, system, bad, usable, usage);
    }

    /** 直接读取一个磁盘块的原始 64 字节（字节查看器用，读的是真实磁盘数据） */
    public byte[] readBlockBytes(int blockNumber) throws IOException {
        return disk.readBlock(blockNumber);
    }

    /** FAT 表的只读快照 */
    public int[] getFatSnapshot() {
        return fat.snapshot();
    }

    public OpenFileTable getOpenFileTable() {
        return oft;
    }

    public PathResolver getPathResolver() {
        return resolver;
    }

    public Path getDiskImagePath() {
        return disk.getImagePath();
    }

    // ==================================================================
    //  六、内部工具：指针、校验
    // ==================================================================

    /** 把指针定位到文件的第 linear 个字节处（跨块时自动落到下一块） */
    private void updatePointer(Pointer pointer, List<Integer> chain, int linear) {
        if (chain.isEmpty()) {
            return;
        }
        int index = linear / Constants.BLOCK_SIZE;
        int offset = linear % Constants.BLOCK_SIZE;
        if (index >= chain.size()) {
            index = chain.size() - 1;
            offset = Constants.BLOCK_SIZE;
        }
        pointer.moveTo(chain.get(index), offset);
    }

    /** 把指针放到文件末尾 */
    private void positionPointerAtEnd(Pointer pointer, int startBlock, int contentLength) {
        try {
            List<Integer> chain = fat.chainOf(startBlock);
            updatePointer(pointer, chain, contentLength);
        } catch (RuntimeException e) {
            pointer.moveTo(startBlock, 0);
        }
    }

    /** 把指针换算成“从文件开头数第几个字节” */
    private int linearPosition(List<Integer> chain, Pointer pointer) {
        int index = chain.indexOf(pointer.getBlockNumber());
        if (index < 0) {
            return 0; // 指针不在链上（理论上不会发生），安全起见从头开始
        }
        return index * Constants.BLOCK_SIZE + pointer.getOffset();
    }

    /** 名称校验：目录名最多 3 个 ASCII 字符，不能包含 '.'、'$'、'/' */
    private void validateDirectoryName(String name) {
        if (name == null || name.isEmpty()) {
            throw new FileSystemException("目录名不能为空。");
        }
        if (name.length() > Constants.NAME_MAX_LENGTH) {
            throw new FileSystemException("目录名最多 " + Constants.NAME_MAX_LENGTH + " 个字符，当前是 " + name.length() + " 个。");
        }
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new FileSystemException("目录名只能使用字母、数字和下划线，不能包含 '.'、'$'、'/' 等字符。");
        }
    }

    /**
     * 名称校验：主名 1~3 个 ASCII 字符，扩展名 0~2 个 ASCII 字符，
     * 只能使用字母、数字和下划线，不允许 '$'、'.'、'/' 出现在名称里。
     */
    private void validateFileName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            throw new FileSystemException("文件名不能为空。");
        }
        if (fullName.indexOf(Constants.EOF_CHAR) >= 0) {
            throw new FileSystemException("文件名不能包含 '#'。");
        }
        int firstDot = fullName.indexOf('.');
        String base = fullName;
        String ext = "";
        if (firstDot >= 0) {
            if (fullName.indexOf('.', firstDot + 1) >= 0) {
                throw new FileSystemException("文件名中只能有一个 '.'，格式应为“主名.扩展名”。");
            }
            base = fullName.substring(0, firstDot);
            ext = fullName.substring(firstDot + 1);
        }
        if (base.isEmpty()) {
            throw new FileSystemException("主文件名不能为空。");
        }
        if (base.length() > Constants.NAME_MAX_LENGTH) {
            throw new FileSystemException("主文件名最多 " + Constants.NAME_MAX_LENGTH + " 个字符，当前是 " + base.length() + " 个。");
        }
        if (!base.matches("[A-Za-z0-9_]+")) {
            throw new FileSystemException("主文件名只能使用字母、数字和下划线，不能包含 '$'、'/' 等字符。");
        }
        if (ext.length() > Constants.EXT_MAX_LENGTH) {
            throw new FileSystemException("扩展名最多 " + Constants.EXT_MAX_LENGTH + " 个字符，当前是 " + ext.length() + " 个。");
        }
        if (!ext.isEmpty() && !ext.matches("[A-Za-z0-9]+")) {
            throw new FileSystemException("扩展名只能使用字母和数字。");
        }
    }

    @Override
    public void close() {
        try {
            closeAllFiles();
        } catch (Exception ignored) {
            // 退出时关闭失败无需打断流程
        }
        disk.close();
    }

    // ==================================================================
    //  七、内部辅助类型
    // ==================================================================

    /** 块归属收集结果：块号 → 路径 / 是否目录 / 冲突块 */
    private static class OwnerMap {
        final Map<Integer, String> owner = new HashMap<>();
        final Map<Integer, Boolean> directory = new HashMap<>();
        final Set<Integer> conflicts = new HashSet<>();
    }

    /**
     * 磁盘统计数据的只读快照。
     * <p>
     * 之所以做成"快照对象"而不是几个 getter，是因为界面要在一次刷新里同时用到
     * 这 7 个数字。如果每个数字都现算一遍，刷新一次就要遍历 7 遍 FAT，
     * 而且中途万一有别处改了 FAT，界面上就会出现"总数对不上"的怪现象。
     * 一次性算好装进对象，界面拿到的永远是同一时刻的一致数据。
     */
    public static class DiskStatistics {
        /** 磁盘总块数，固定 128 */
        private final int totalBlocks;
        /** 空闲块数（FAT 值为 0 的块） */
        private final int freeBlocks;
        /** 已分配块数（被文件或目录占用的数据块） */
        private final int usedBlocks;
        /** 系统保留块数（块 0、1、2，FAT 值为 253） */
        private final int systemBlocks;
        /** 坏块数（块 23、49，FAT 值为 254） */
        private final int badBlocks;
        /** 可用块数 = 总块数 - 系统块 - 坏块，即理论上最多能装多少块数据 */
        private final int usableBlocks;
        /** 使用率（%）= 已分配块 / 可用块 × 100，保留一位小数 */
        private final double usagePercent;

        public DiskStatistics(int totalBlocks, int freeBlocks, int usedBlocks,
                              int systemBlocks, int badBlocks, int usableBlocks, double usagePercent) {
            this.totalBlocks = totalBlocks;
            this.freeBlocks = freeBlocks;
            this.usedBlocks = usedBlocks;
            this.systemBlocks = systemBlocks;
            this.badBlocks = badBlocks;
            this.usableBlocks = usableBlocks;
            this.usagePercent = usagePercent;
        }

        public int getTotalBlocks() {
            return totalBlocks;
        }

        public int getFreeBlocks() {
            return freeBlocks;
        }

        public int getUsedBlocks() {
            return usedBlocks;
        }

        public int getSystemBlocks() {
            return systemBlocks;
        }

        public int getBadBlocks() {
            return badBlocks;
        }

        public int getUsableBlocks() {
            return usableBlocks;
        }

        public double getUsagePercent() {
            return usagePercent;
        }
    }
}
