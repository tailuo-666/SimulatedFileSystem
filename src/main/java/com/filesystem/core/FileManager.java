package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：文件数据的真正读写：顺序读（遇 '#' 停）、追加写、覆盖写、按需申请/释放块
 *  上游依赖：FatTable、DiskManager、OpenFileEntry、Constants
 *  下游调用：FileSystemService 的 readFile / writeFile
 * ========================================================================== */

import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文件内容管理器 —— 负责“文件数据块”的读写。
 * <p>
 * 本系统文件采用<b>显式链接</b>的物理结构：目录项的起始块号是入口，
 * 之后靠 FAT 表一项一项往下找，例如 5 → 8 → 12 → 15 → EOF。
 * <p>
 * 文件内容按 UTF-8 字节存放，末尾追加一个 '#' 作为结束标志。
 * 因此“实际字节数”= 从起始块开始逐块读取、直到遇见 '#' 为止所读到的字节数。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class FileManager {

    /** 底层磁盘：文件内容最终都要通过它写成 64 字节的块 */
    private final DiskManager disk;

    /** FAT 表：读的时候靠它找"下一块"，写的时候靠它申请新块 / 释放多余块 */
    private final FatTable fat;

    public FileManager(DiskManager disk, FatTable fat) {
        this.disk = disk;
        this.fat = fat;
    }

    /**
     * 读取整个文件的内容（不含结束符 '#'）。
     * <p>
     * 步骤：
     * <ol>
     *   <li>用 FAT 链拿到这个文件占用的所有块；</li>
     *   <li>一块一块从磁盘读出来；</li>
     *   <li>逐字节累加，读到 '#' 就立即停止。</li>
     * </ol>
     * 因为 '#' 的编码是单字节 0x23，它不可能出现在 UTF-8 多字节字符的中间，
     * 所以这样截断不会把一个汉字劈成两半。
     */
    public byte[] readContent(int startBlock) throws IOException {
        List<Integer> chain = fat.chainOf(startBlock);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int blockNumber : chain) {
            byte[] data = disk.readBlock(blockNumber);
            for (byte b : data) {
                if (b == Constants.EOF_FLAG) {
                    return out.toByteArray(); // 遇到结束符，读完
                }
                out.write(b);
            }
        }
        // 走完整条链都没遇到 '#'，说明数据异常，这里把读到的内容全部返回
        return out.toByteArray();
    }

    /**
     * 计算存放指定字节数的内容需要多少个磁盘块。
     * 注意要额外加上 1 个字节的结束符 '#'；空文件也至少占 1 块。
     *
     * @param contentLength 内容字节数（不含 '#'）
     */
    public static int blocksNeeded(int contentLength) {
        int total = contentLength + 1; // +1 是结束符
        return Math.max(1, (total + Constants.BLOCK_SIZE - 1) / Constants.BLOCK_SIZE);
    }

    /**
     * 把内容写入文件，必要时自动申请 / 释放磁盘块，并重建 FAT 链。
     * <p>
     * 这是全系统最核心、也最容易写错的一段，流程严格按下面顺序执行：
     * <ol>
     *   <li><b>空间预检查</b>：先算出需要几块，若不够就直接抛异常，
     *       此时代码还没有改动任何数据，原文件完好无损；</li>
     *   <li>备份 FAT 快照，后面任何一步出错都能回滚；</li>
     *   <li>块数不够就追加新块到链尾（复用原有块，避免空间浪费）；</li>
     *   <li>逐块写数据，每块先清零再填入属于它的那段字节；</li>
     *   <li>块数变少就把多余的块释放掉；</li>
     *   <li>最后按新的顺序重建 FAT 链，并把 FAT 写回磁盘。</li>
     * </ol>
     *
     * @param startBlock    文件起始块号
     * @param content       新的文件内容（不含 '#'）
     * @return 写入后文件占用的块数
     */
    public int writeContent(int startBlock, byte[] content) throws IOException {
        int needed = blocksNeeded(content.length);

        // ---------- 1. 取出现有链 ----------
        List<Integer> chain = new ArrayList<>(fat.chainOf(startBlock));
        int extraNeeded = needed - chain.size();

        // ---------- 2. 空间预检查（在改动任何数据之前） ----------
        if (extraNeeded > 0 && fat.countFreeBlocks() < extraNeeded) {
            throw new FileSystemException("磁盘空间不足：本次写入需要 " + needed
                    + " 个块，当前链已有 " + chain.size() + " 块，还需 " + extraNeeded
                    + " 块，但磁盘只剩 " + fat.countFreeBlocks() + " 个空闲块。原文件未被修改。");
        }

        int[] backup = fat.snapshot(); // 备份 FAT，出错时回滚
        try {
            // ---------- 3. 需要更多块就追加（追加在链尾） ----------
            while (chain.size() < needed) {
                int newBlock = fat.allocateBlock();
                if (newBlock < 0) {
                    throw new FileSystemException("磁盘空间不足，无法为文件分配新块。");
                }
                chain.add(newBlock);
            }

            // ---------- 4. 逐块写入数据（末尾补结束符 '#'） ----------
            byte[] payload = Arrays.copyOf(content, content.length + 1);
            payload[content.length] = Constants.EOF_FLAG;
            for (int i = 0; i < needed; i++) {
                byte[] buffer = new byte[Constants.BLOCK_SIZE];
                int from = i * Constants.BLOCK_SIZE;
                int len = Math.min(Constants.BLOCK_SIZE, payload.length - from);
                if (len > 0) {
                    System.arraycopy(payload, from, buffer, 0, len);
                }
                disk.writeBlock(chain.get(i), buffer);
            }

            // ---------- 5. 块数变少，释放多余的块 ----------
            for (int i = needed; i < chain.size(); i++) {
                fat.freeBlock(chain.get(i));
            }

            // ---------- 6. 重建 FAT 链 ----------
            for (int i = 0; i < needed; i++) {
                boolean last = (i == needed - 1);
                fat.set(chain.get(i), last ? Constants.FAT_EOF : chain.get(i + 1));
            }

            // ---------- 7. FAT 落盘 ----------
            fat.save();
            return needed;
        } catch (IOException | RuntimeException e) {
            // 出错了：把 FAT 恢复成写之前的样子，保证磁盘不会留下半截链
            fat.restore(backup);
            try {
                fat.save();
            } catch (IOException ignored) {
                // 回滚时的二次失败无法处理，交给上层提示
            }
            if (e instanceof FileSystemException) {
                throw (FileSystemException) e;
            }
            throw new FileSystemException("写入磁盘失败：" + e.getMessage(), e);
        }
    }
}
