package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：目录块的读写：从 64 字节里切出 8 个目录项、查空项、判重名、落盘
 *  上游依赖：DiskManager、FatTable、DirectoryEntry
 *  下游调用：PathResolver、FileSystemService
 * ========================================================================== */

import com.filesystem.model.DirectoryEntry;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录管理器 —— 负责“目录块”的解析与写入。
 * <p>
 * 每个目录块 64 字节，正好放 8 个 8 字节的目录项：
 * <pre>
 *   目录项 0 : 字节 0  ~ 7
 *   目录项 1 : 字节 8  ~ 15
 *   ...
 *   目录项 7 : 字节 56 ~ 63
 * </pre>
 * 目录项第一个字节是 '$' 就表示这一格是空的（本系统的“空项标志”）。
 * <p>
 * 名称字段的存放方式：不足 3 个字符的用 0x00 补齐，读取时遇到 0x00 就停。
 * 名称只允许 ASCII 字符，所以一个字符就是一个字节，不会出现中文被截断的问题。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class DirectoryManager {

    /**
     * 底层磁盘。
     * <p>
     * 注意这里只需要 DiskManager，不需要 FatTable——目录管理只负责
     * "一个 64 字节块里的 8 条记录怎么读怎么写"，
     * "这个块该不该分配"是 FAT 和上层 Service 的事，职责分开才不会互相扯皮。
     */
    private final DiskManager disk;

    public DirectoryManager(DiskManager disk) {
        this.disk = disk;
    }

    /**
     * 初始化一个目录块：64 个字节全填 '$'。
     * 这样每个目录项的第一个字节都是 '$'，就等于 8 个空目录项。
     */
    public void initDirectoryBlock(int blockNumber) throws IOException {
        byte[] buffer = new byte[Constants.BLOCK_SIZE];
        java.util.Arrays.fill(buffer, Constants.ENTRY_FREE_FLAG);
        disk.writeBlock(blockNumber, buffer);
    }

    // ==================== 一、读取 ====================

    /**
     * 读出目录块中的全部有效目录项（跳过空项）。
     *
     * @param dirBlockNumber 目录所在块号
     */
    public List<DirectoryEntry> readDirectory(int dirBlockNumber) throws IOException {
        byte[] block = disk.readBlock(dirBlockNumber);
        List<DirectoryEntry> list = new ArrayList<>(Constants.ENTRIES_PER_BLOCK);
        for (int slot = 0; slot < Constants.ENTRIES_PER_BLOCK; slot++) {
            DirectoryEntry entry = parseEntry(block, slot, dirBlockNumber);
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    /**
     * 解析目录块中的一个 8 字节目录项。
     * <p>
     * 解析步骤（对应课程里“目录项解析”的考点）：
     * <ol>
     *   <li>先看第 1 个字节是不是 '$'，是就说明这一格是空的；</li>
     *   <li>取偏移 0~2 共 3 字节作为名称；</li>
     *   <li>取偏移 5 的属性字节，判断是文件还是目录；</li>
     *   <li>取偏移 6 的起始块号；</li>
     *   <li>文件再取偏移 3~4 的扩展名、偏移 7 的块数。</li>
     * </ol>
     *
     * @return 目录项；如果这一格是空的，返回 null
     */
    public DirectoryEntry parseEntry(byte[] block, int slot, int dirBlockNumber) {
        int offset = slot * Constants.ENTRY_SIZE;
        if (block[offset] == Constants.ENTRY_FREE_FLAG || block[offset] == 0) {
            return null; // 空目录项
        }
        String name = readFixedString(block, offset + Constants.OFFSET_NAME, Constants.NAME_MAX_LENGTH);
        int attribute = block[offset + Constants.OFFSET_ATTR] & 0xFF; // 属性也要转无符号
        int startBlock = block[offset + Constants.OFFSET_START] & 0xFF;
        boolean directory = (attribute & Constants.ATTR_DIRECTORY) != 0;

        String ext = "";
        int lengthBlocks = 0;
        if (!directory) {
            ext = readFixedString(block, offset + Constants.OFFSET_TYPE, Constants.EXT_MAX_LENGTH);
            lengthBlocks = block[offset + Constants.OFFSET_LENGTH] & 0xFF;
        }
        return new DirectoryEntry(dirBlockNumber, slot, name, ext, attribute, startBlock, lengthBlocks);
    }

    /**
     * 按名称在目录中查找目录项（大小写不敏感）。
     *
     * @param dirBlockNumber 目录块号
     * @param fullName       完整名称，例如 "A.TX" 或 "DOC"
     * @return 找到的目录项；找不到返回 null
     */
    public DirectoryEntry findEntry(int dirBlockNumber, String fullName) throws IOException {
        for (DirectoryEntry entry : readDirectory(dirBlockNumber)) {
            if (entry.getFullName().equalsIgnoreCase(fullName)) {
                return entry;
            }
        }
        return null;
    }

    // ==================== 二、写入 ====================

    /**
     * 把一个目录项写到它所属的目录块和槽位上。
     * <p>
     * 注意：这里采用“读整块 → 改 8 字节 → 写回整块”的做法。
     * 因为磁盘的最小读写单位就是 64 字节的块，不能只改其中 8 个字节。
     */
    public void writeEntry(DirectoryEntry entry) throws IOException {
        int dirBlock = entry.getContainerBlock();
        int slot = entry.getSlotIndex();
        if (slot < 0 || slot >= Constants.ENTRIES_PER_BLOCK) {
            throw new FileSystemException("目录槽位非法：" + slot);
        }
        byte[] block = disk.readBlock(dirBlock);
        int offset = slot * Constants.ENTRY_SIZE;

        // 整格先清零，避免残留旧名称的字符
        for (int i = 0; i < Constants.ENTRY_SIZE; i++) {
            block[offset + i] = 0;
        }
        writeFixedString(block, offset + Constants.OFFSET_NAME, entry.getName(), Constants.NAME_MAX_LENGTH);
        if (entry.isDirectory()) {
            // 目录项：偏移 3~4 是保留字段（保持 0），偏移 7 也是保留字段
            block[offset + Constants.OFFSET_ATTR] = (byte) entry.getAttribute();
            block[offset + Constants.OFFSET_START] = (byte) entry.getStartBlock();
        } else {
            writeFixedString(block, offset + Constants.OFFSET_TYPE, entry.getExt(), Constants.EXT_MAX_LENGTH);
            block[offset + Constants.OFFSET_ATTR] = (byte) entry.getAttribute();
            block[offset + Constants.OFFSET_START] = (byte) entry.getStartBlock();
            block[offset + Constants.OFFSET_LENGTH] = (byte) entry.getLengthBlocks();
        }
        disk.writeBlock(dirBlock, block);
    }

    /**
     * 清空一个目录项：把第一个字节写成 '$'，其余字节清零。
     * 删除文件 / 目录时调用。
     */
    public void clearEntry(int dirBlock, int slot) throws IOException {
        byte[] block = disk.readBlock(dirBlock);
        int offset = slot * Constants.ENTRY_SIZE;
        for (int i = 0; i < Constants.ENTRY_SIZE; i++) {
            block[offset + i] = 0;
        }
        block[offset] = Constants.ENTRY_FREE_FLAG;
        disk.writeBlock(dirBlock, block);
    }

    /**
     * 找目录块里第一个空槽位。
     *
     * @return 0 ~ 7 的槽位号；目录已满返回 -1
     */
    public int findFreeSlot(int dirBlock) throws IOException {
        byte[] block = disk.readBlock(dirBlock);
        for (int slot = 0; slot < Constants.ENTRIES_PER_BLOCK; slot++) {
            int offset = slot * Constants.ENTRY_SIZE;
            if (block[offset] == Constants.ENTRY_FREE_FLAG || block[offset] == 0) {
                return slot;
            }
        }
        return -1;
    }

    /** 统计目录块中已使用的目录项个数 */
    public int countEntries(int dirBlock) throws IOException {
        return readDirectory(dirBlock).size();
    }

    // ==================== 三、定长字符串读写 ====================

    /**
     * 从固定长度字段中读出一个字符串：遇到 0x00 或空格就结束，并去掉尾部空白。
     */
    private String readFixedString(byte[] block, int offset, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            byte b = block[offset + i];
            if (b == 0 || b == ' ') {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 把一个字符串写入固定长度字段：不足的部分补 0x00，超长的部分直接截断
     * （正常流程在“名称校验”阶段就已经拦住超长名称了，这里只是兜底）。
     */
    private void writeFixedString(byte[] block, int offset, String value, int length) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < length; i++) {
            block[offset + i] = i < bytes.length ? bytes[i] : 0;
        }
    }
}
