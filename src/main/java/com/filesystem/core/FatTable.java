package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：FAT 文件分配表：128 项，负责加载/保存、分配空闲块、回收、沿链遍历、环检测
 *  上游依赖：DiskManager（真正读盘写盘）、Constants
 *  下游调用：DirectoryManager、FileManager、FileSystemService；界面层只读 snapshot()
 * ========================================================================== */

import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * FAT 文件分配表。
 * <p>
 * FAT 共 128 项，每项 1 字节，整表 128 字节，正好占磁盘第 0 块（前 64 项）
 * 和第 1 块（后 64 项）。
 * <p>
 * 表项取值的含义：
 * <pre>
 *   0        空闲
 *   1 ~ 127  指向下一个磁盘块（显式链接）
 *   253      系统保留块（块 0、1 是 FAT 自己，块 2 是根目录）
 *   254      坏块（块 23、49）
 *   255      EOF，文件的最后一块
 * </pre>
 * <b>易错点（课程设计要求）</b>：Java 的 byte 是有符号的（-128 ~ 127），
 * 直接读出来会把 253 / 254 / 255 当成负数。所以读盘时一定要做
 * {@code value & 0xFF} 转成 0 ~ 255 的无符号整数；写盘时再用强制类型转换写回去。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class FatTable {

    /** 内存中的 FAT 缓存，下标即块号，值范围 0 ~ 255 */
    private final int[] entries = new int[Constants.TOTAL_BLOCKS];

    /** 底层磁盘，用于把 FAT 同步写回块 0、1 */
    private final DiskManager disk;

    public FatTable(DiskManager disk) {
        this.disk = disk;
    }

    // ==================== 一、加载与保存 ====================

    /**
     * 从磁盘第 0、1 块读入整张 FAT 表。
     */
    public void load() throws IOException {
        byte[] first = disk.readBlock(Constants.FAT_BLOCK_FIRST);   // 块 0：FAT[0] ~ FAT[63]
        byte[] second = disk.readBlock(Constants.FAT_BLOCK_SECOND); // 块 1：FAT[64] ~ FAT[127]
        for (int i = 0; i < Constants.BLOCK_SIZE; i++) {
            // & 0xFF：把有符号 byte 转成 0 ~ 255 的无符号整数
            entries[i] = first[i] & 0xFF;
            entries[Constants.BLOCK_SIZE + i] = second[i] & 0xFF;
        }
    }

    /**
     * 把整张 FAT 表写回磁盘第 0、1 块。
     * 每次修改 FAT 之后都必须调用它，保证“内存缓存”和“磁盘数据”一致。
     */
    public void save() throws IOException {
        byte[] first = new byte[Constants.BLOCK_SIZE];
        byte[] second = new byte[Constants.BLOCK_SIZE];
        for (int i = 0; i < Constants.BLOCK_SIZE; i++) {
            first[i] = (byte) entries[i];
            second[i] = (byte) entries[Constants.BLOCK_SIZE + i];
        }
        disk.writeBlock(Constants.FAT_BLOCK_FIRST, first);
        disk.writeBlock(Constants.FAT_BLOCK_SECOND, second);
    }

    /**
     * 初始化一张新磁盘的 FAT：
     * 块 0、1、2 设为 253（系统保留），块 23、49 设为 254（坏块），其余为 0（空闲）。
     * 只在“首次创建磁盘”和“格式化磁盘”时调用。
     */
    public void initNewFat() {
        for (int i = 0; i < Constants.TOTAL_BLOCKS; i++) {
            entries[i] = Constants.FAT_FREE;
        }
        entries[Constants.FAT_BLOCK_FIRST] = Constants.FAT_SYSTEM;
        entries[Constants.FAT_BLOCK_SECOND] = Constants.FAT_SYSTEM;
        entries[Constants.ROOT_BLOCK] = Constants.FAT_SYSTEM;
        for (int bad : Constants.BAD_BLOCK_NUMBERS) {
            entries[bad] = Constants.FAT_BAD;
        }
    }

    // ==================== 二、基本读写 ====================

    public int get(int blockNumber) {
        checkBlock(blockNumber);
        return entries[blockNumber];
    }

    public void set(int blockNumber, int value) {
        checkBlock(blockNumber);
        if (value < 0 || value > 255) {
            throw new FileSystemException("FAT 表项取值必须在 0 ~ 255 之间，当前：" + value);
        }
        entries[blockNumber] = value;
    }

    /** 该块是否空闲（可以被分配） */
    public boolean isFree(int blockNumber) {
        return entries[blockNumber] == Constants.FAT_FREE;
    }

    /** 该块是否坏块 */
    public boolean isBad(int blockNumber) {
        return entries[blockNumber] == Constants.FAT_BAD;
    }

    /** 该块是否系统保留块 */
    public boolean isSystem(int blockNumber) {
        return entries[blockNumber] == Constants.FAT_SYSTEM;
    }

    /** 返回整张 FAT 的副本（界面刷新时用，防止外部直接改到内部数组） */
    public int[] snapshot() {
        return entries.clone();
    }

    /** 回滚用：把 FAT 恢复成快照的样子（写入失败时保证不破坏原数据） */
    public void restore(int[] snap) {
        System.arraycopy(snap, 0, entries, 0, Constants.TOTAL_BLOCKS);
    }

    // ==================== 三、分配与回收 ====================

    /**
     * 申请一个空闲磁盘块。
     * <p>
     * 从第 3 块开始向后搜索，跳过 0、1、2（系统保留）和 23、49（坏块）。
     * 因为坏块的 FAT 值是 254、系统块是 253，都不是 0，
     * 所以“值为 0 才可用”这一条判断天然就排除了它们，这里再额外判一次，
     * 属于“防御式编程”，让人更放心。
     *
     * @return 分配到的块号；如果磁盘已满返回 -1
     */
    public int allocateBlock() {
        for (int block = Constants.FIRST_DATA_BLOCK; block < Constants.TOTAL_BLOCKS; block++) {
            if (isBad(block)) {
                continue; // 坏块永远不分配
            }
            if (entries[block] == Constants.FAT_FREE) {
                entries[block] = Constants.FAT_EOF; // 先标记为已用（EOF），后续再按需改链
                return block;
            }
        }
        return -1;
    }

    /** 释放一个磁盘块（FAT 值置 0） */
    public void freeBlock(int blockNumber) {
        checkBlock(blockNumber);
        entries[blockNumber] = Constants.FAT_FREE;
    }

    /**
     * 沿着 FAT 链走完整个文件，返回它占用的所有块号（按链接顺序）。
     * <p>
     * 同时做了三重安全检查，任何一条不满足都会抛异常而不是死循环：
     * <ol>
     *   <li>块号必须在 0 ~ 127 之间；</li>
     *   <li>同一个块不能出现两次（防止 FAT 链被改成环）；</li>
     *   <li>走到 255（EOF）或 253（系统值）就结束，走到 0 说明链断了。</li>
     * </ol>
     */
    public List<Integer> chainOf(int startBlock) {
        checkBlock(startBlock);
        List<Integer> chain = new ArrayList<>();
        boolean[] visited = new boolean[Constants.TOTAL_BLOCKS];
        int current = startBlock;
        while (true) {
            if (current < 0 || current >= Constants.TOTAL_BLOCKS) {
                throw new FileSystemException("FAT 链中出现非法块号：" + current);
            }
            if (visited[current]) {
                throw new FileSystemException("FAT 链出现循环，起始块 " + startBlock + "，重复块 " + current);
            }
            visited[current] = true;
            chain.add(current);

            int next = entries[current];
            if (next == Constants.FAT_EOF || next == Constants.FAT_SYSTEM) {
                break; // 到达文件末尾（根目录的 253 也当作结束）
            }
            if (next == Constants.FAT_BAD) {
                throw new FileSystemException("FAT 链指向坏块：" + current + " → 254");
            }
            if (next == Constants.FAT_FREE) {
                break; // 链意外断在空闲块上，容错结束，避免死循环
            }
            current = next;
        }
        return chain;
    }

    /** 把一条链全部释放（删除文件 / 目录时用） */
    public void freeChain(int startBlock) {
        for (int block : chainOf(startBlock)) {
            freeBlock(block);
        }
    }

    /** 统计空闲块数量 */
    public int countFreeBlocks() {
        int count = 0;
        for (int i = 0; i < Constants.TOTAL_BLOCKS; i++) {
            if (entries[i] == Constants.FAT_FREE) {
                count++;
            }
        }
        return count;
    }

    private void checkBlock(int blockNumber) {
        if (blockNumber < 0 || blockNumber >= Constants.TOTAL_BLOCKS) {
            throw new FileSystemException("非法块号：" + blockNumber);
        }
    }
}
