package com.filesystem.model;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：已打开文件表（OFT）的一个表项：路径、属性、起始块、字节数、块数、读/写指针
 *  上游依赖：Pointer、Constants
 *  下游调用：OpenFileTable 管理它；OFT 面板（成员 D）只读展示它
 * ========================================================================== */

import com.filesystem.util.Constants;

/**
 * 已打开文件表（OFT）中的一个表项。
 * <p>
 * 一个文件被打开后，系统就在 OFT 中登记一条记录，记录：
 * <ul>
 *   <li>文件绝对路径 —— 用来定位目录项；</li>
 *   <li>属性 —— 判断当前操作是否被允许（只读文件不许写）；</li>
 *   <li>起始块号 —— 进入 FAT 链的入口；</li>
 *   <li>实际字节数 lengthBytes —— 真实内容长度（读到 '#' 为止的字节数）；</li>
 *   <li>占用块数 blockCount —— 目录项里记录的那个“长度”字段；</li>
 *   <li>操作方式 operationType —— 0 读方式 / 1 写方式；</li>
 *   <li>读指针、写指针 —— 两个互相独立的 Pointer 对象。</li>
 * </ul>
 * 注意 lengthBytes 与 blockCount 是两回事，绝对不能混用：
 * 前者是内容有多少字节，后者是内容占了多少个 64 字节的块。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class OpenFileEntry {

    /** 文件绝对路径（大小写不敏感比较时用 equalsIgnoreCase） */
    private String absolutePath;

    /** 文件属性（打开时的快照，写入前要用磁盘上的最新值再校验一次） */
    private int attribute;

    /** 文件起始盘块号 */
    private int startBlock;

    /** 文件实际内容字节数（不含结束符 '#'） */
    private int lengthBytes;

    /** 文件占用的磁盘块数 */
    private int blockCount;

    /** 操作方式：0 = 读方式，1 = 写方式 */
    private int operationType;

    /** 读指针：从文件开头开始 */
    private Pointer readPointer;

    /** 写指针：写方式打开时位于文件末尾（追加）；覆盖写入时被重置到开头 */
    private Pointer writePointer;

    public OpenFileEntry(String absolutePath, int attribute, int startBlock,
                         int lengthBytes, int blockCount, int operationType) {
        this.absolutePath = absolutePath;
        this.attribute = attribute;
        this.startBlock = startBlock;
        this.lengthBytes = lengthBytes;
        this.blockCount = blockCount;
        this.operationType = operationType;
        // 新打开的文件：读指针指向文件开头
        this.readPointer = new Pointer(startBlock, 0);
        // 写指针默认指向文件末尾（追加模式），真正写入时再按模式调整
        this.writePointer = new Pointer(startBlock, 0);
    }

    /** 当前是否以写方式打开 */
    public boolean isWriteMode() {
        return operationType == Constants.OPEN_WRITE;
    }

    /** 操作方式的文字描述 */
    public String getOperationText() {
        return isWriteMode() ? "写方式" : "读方式";
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    public void setAbsolutePath(String absolutePath) {
        this.absolutePath = absolutePath;
    }

    public int getAttribute() {
        return attribute;
    }

    public void setAttribute(int attribute) {
        this.attribute = attribute;
    }

    public int getStartBlock() {
        return startBlock;
    }

    public void setStartBlock(int startBlock) {
        this.startBlock = startBlock;
    }

    public int getLengthBytes() {
        return lengthBytes;
    }

    public void setLengthBytes(int lengthBytes) {
        this.lengthBytes = lengthBytes;
    }

    public int getBlockCount() {
        return blockCount;
    }

    public void setBlockCount(int blockCount) {
        this.blockCount = blockCount;
    }

    public int getOperationType() {
        return operationType;
    }

    public void setOperationType(int operationType) {
        this.operationType = operationType;
    }

    public Pointer getReadPointer() {
        return readPointer;
    }

    public void setReadPointer(Pointer readPointer) {
        this.readPointer = readPointer;
    }

    public Pointer getWritePointer() {
        return writePointer;
    }

    public void setWritePointer(Pointer writePointer) {
        this.writePointer = writePointer;
    }

    @Override
    public String toString() {
        return absolutePath + " [" + getOperationText() + "]";
    }
}
