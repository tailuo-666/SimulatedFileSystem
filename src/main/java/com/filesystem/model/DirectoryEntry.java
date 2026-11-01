package com.filesystem.model;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：目录项模型：把磁盘上的 8 个字节解释成 名字 / 扩展名 / 属性 / 起始块 / 长度
 *  上游依赖：Constants
 *  下游调用：DirectoryManager 负责读写它；界面层读它填表格、目录树、属性窗口
 * ========================================================================== */

import com.filesystem.util.Constants;

/**
 * 目录项（Directory Entry）—— 内存中的表示。
 * <p>
 * 磁盘上的每个目录项固定占 8 字节，布局如下（文件与目录共用同一套字节位置）：
 * <pre>
 *   偏移 0 1 2 | 3 4        | 5      | 6        | 7
 *   文件：名称 3 | 扩展名 2   | 属性 1 | 起始块 1 | 占用块数 1
 *   目录：名称 3 | 保留 0 0   | 属性 1 | 起始块 1 | 保留 0
 * </pre>
 * 这样设计的好处：解析时只看偏移 5 的属性字节就能判断是文件还是目录，
 * 两种目录项的读写代码可以共用一套。
 * <p>
 * 注意：{@code lengthBlocks} 记录的是<b>占用的磁盘块数</b>，不是字符数。
 * 文件的真实字节数要靠沿着 FAT 链读到结束符 '#' 才能算出来。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class DirectoryEntry {

    /** 该目录项所在的目录块号（即父目录块） */
    private int containerBlock;

    /** 该目录项在自己的目录块里是第几个（0 ~ 7） */
    private int slotIndex;

    /** 文件主名或目录名（不含扩展名，最多 3 个 ASCII 字符） */
    private String name;

    /** 扩展名（仅文件使用，最多 2 个 ASCII 字符；目录为空串） */
    private String ext;

    /** 属性字节：1 只读，4 普通可读写，8 目录 */
    private int attribute;

    /** 起始盘块号 */
    private int startBlock;

    /** 占用的磁盘块数（文件），目录固定为 1（这里存 0，用目录块另计） */
    private int lengthBlocks;

    public DirectoryEntry(int containerBlock, int slotIndex, String name, String ext,
                          int attribute, int startBlock, int lengthBlocks) {
        this.containerBlock = containerBlock;
        this.slotIndex = slotIndex;
        this.name = name;
        this.ext = ext == null ? "" : ext;
        this.attribute = attribute;
        this.startBlock = startBlock;
        this.lengthBlocks = lengthBlocks;
    }

    /** 构造一个文件目录项 */
    public static DirectoryEntry forFile(int containerBlock, int slotIndex, String name, String ext,
                                         int attribute, int startBlock, int lengthBlocks) {
        return new DirectoryEntry(containerBlock, slotIndex, name, ext, attribute, startBlock, lengthBlocks);
    }

    /** 构造一个目录目录项（目录属性固定为 8，占用 1 个块） */
    public static DirectoryEntry forDirectory(int containerBlock, int slotIndex, String name, int startBlock) {
        return new DirectoryEntry(containerBlock, slotIndex, name, "", Constants.ATTR_DIRECTORY, startBlock, 1);
    }

    /**
     * 是否为目录。
     * <p>
     * 这里用按位与 {@code &} 而不是 {@code ==}：
     * 属性字节的每一位代表一个独立开关（见 Constants 里的说明），
     * 所以只要第 3 位是 1 就算目录，不用管其它位是什么。
     */
    public boolean isDirectory() {
        return (attribute & Constants.ATTR_DIRECTORY) != 0;
    }

    /**
     * 是否为只读文件。
     * <p>
     * 注意目录的属性是 8，只有第 3 位，所以目录不会误判成"只读"；
     * 而只读文件属性是 1，只有第 0 位，也不会误判成目录。两者互不干扰。
     */
    public boolean isReadOnly() {
        return (attribute & Constants.ATTR_READ_ONLY) != 0;
    }

    /**
     * 完整名称：目录直接返回名字；文件返回 "主名.扩展名"（没有扩展名时只返回主名）。
     */
    public String getFullName() {
        if (isDirectory() || ext == null || ext.isEmpty()) {
            return name;
        }
        return name + "." + ext;
    }

    /** 属性文字描述，供界面直接显示 */
    public String getAttributeText() {
        if (isDirectory()) {
            return "目录";
        }
        return isReadOnly() ? "只读" : "可读写";
    }

    /** 类型文字描述 */
    public String getTypeText() {
        if (isDirectory()) {
            return "目录";
        }
        return ext.isEmpty() ? "文件" : "文件(" + ext + ")";
    }

    /** 复制一份（修改名称时用，避免直接改动原对象造成前后不一致） */
    public DirectoryEntry copy() {
        return new DirectoryEntry(containerBlock, slotIndex, name, ext, attribute, startBlock, lengthBlocks);
    }

    // ==================== Getter / Setter ====================

    public int getContainerBlock() {
        return containerBlock;
    }

    public void setContainerBlock(int containerBlock) {
        this.containerBlock = containerBlock;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
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

    public int getLengthBlocks() {
        return lengthBlocks;
    }

    public void setLengthBlocks(int lengthBlocks) {
        this.lengthBlocks = lengthBlocks;
    }

    @Override
    public String toString() {
        return "DirectoryEntry{" + getFullName() + ", attr=" + attribute
                + ", start=" + startBlock + ", blocks=" + lengthBlocks + "}";
    }
}
