package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：模拟磁盘：用 RandomAccessFile 对 data/disk.img 按 64 字节块读写，含两个缓冲区
 *  上游依赖：Constants（磁盘参数）、FileSystemException
 *  下游调用：FatTable / DirectoryManager / FileManager / FileSystemService；界面层禁止直接调用
 * ========================================================================== */

import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 磁盘管理器 —— 整个系统唯一直接碰 disk.img 的地方。
 * <p>
 * 职责：
 * <ul>
 *   <li>用 {@link RandomAccessFile} 打开 / 创建磁盘映像文件；</li>
 *   <li>提供“按块读、按块写”两个最基本的方法，每次固定 64 字节；</li>
 *   <li>维护两个 64 字节的缓冲区 buffer1 / buffer2，模拟真实的磁盘读写缓冲。</li>
 * </ul>
 * <b>重要设计原则</b>：绝不把整个 8192 字节的磁盘一次性读进内存数组来操作。
 * 上层的 FAT、目录、文件内容都必须通过 readBlock / writeBlock 一块一块地访问，
 * 这样“模拟磁盘 I/O”才有意义（课程设计考的正是这一点）。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class DiskManager implements Closeable {

    /** 磁盘映像文件的路径 */
    private final Path imagePath;

    /** 真实文件句柄 */
    private RandomAccessFile raf;

    /** 磁盘读缓冲 1：读块时先把数据读到这里，再拷贝给调用者 */
    private final byte[] buffer1 = new byte[Constants.BLOCK_SIZE];

    /** 磁盘写缓冲 2：写块时先把数据整理到这里，再整块刷到文件 */
    private final byte[] buffer2 = new byte[Constants.BLOCK_SIZE];

    public DiskManager(Path imagePath) {
        this.imagePath = imagePath;
    }

    /**
     * 打开（必要时创建）磁盘映像文件。
     *
     * @return true 表示是新建的空白磁盘（首次使用），false 表示读取的是已有磁盘
     */
    public boolean open() throws IOException {
        Path parent = imagePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean existed = Files.exists(imagePath) && Files.size(imagePath) == Constants.DISK_SIZE;
        raf = new RandomAccessFile(imagePath.toFile(), "rw");
        if (!existed) {
            // 首次使用（或文件尺寸不对）：把映像文件撑到 8192 字节并全部清零
            raf.setLength(0);
            raf.setLength(Constants.DISK_SIZE);
        }
        return !existed;
    }

    /** 磁盘映像是否已经存在且尺寸正确 */
    public boolean imageExists() {
        try {
            return Files.exists(imagePath) && Files.size(imagePath) == Constants.DISK_SIZE;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读取一个物理块（64 字节）。
     * <p>
     * 实现要点：先 seek 到 blockNumber × 64 的位置，整块读进 buffer1，
     * 再把 buffer1 的内容复制一份返回。返回副本可以防止调用方
     * 不小心改到内部缓冲区、影响后续的读写。
     */
    public byte[] readBlock(int blockNumber) throws IOException {
        checkBlockNumber(blockNumber);
        raf.seek((long) blockNumber * Constants.BLOCK_SIZE);
        raf.readFully(buffer1);                       // 整块读入读缓冲
        byte[] result = new byte[Constants.BLOCK_SIZE];
        System.arraycopy(buffer1, 0, result, 0, Constants.BLOCK_SIZE);
        return result;
    }

    /**
     * 写入一个物理块（64 字节）。
     * <p>
     * 实现要点：先把数据整理进写缓冲 buffer2，再一次性写到磁盘，
     * 模拟“先填缓冲区、再整块落盘”的过程。
     */
    public void writeBlock(int blockNumber, byte[] data) throws IOException {
        checkBlockNumber(blockNumber);
        if (data == null || data.length != Constants.BLOCK_SIZE) {
            throw new IOException("写块失败：数据长度必须是 " + Constants.BLOCK_SIZE + " 字节");
        }
        System.arraycopy(data, 0, buffer2, 0, Constants.BLOCK_SIZE); // 整理到写缓冲
        raf.seek((long) blockNumber * Constants.BLOCK_SIZE);
        raf.write(buffer2, 0, Constants.BLOCK_SIZE);
    }

    /**
     * 把整个磁盘的 128 个块全部写成 0。
     * 只有“格式化磁盘”会用到它，用来彻底抹掉旧数据。
     */
    public void clearAll() throws IOException {
        byte[] zero = new byte[Constants.BLOCK_SIZE];
        for (int i = 0; i < Constants.TOTAL_BLOCKS; i++) {
            writeBlock(i, zero);
        }
    }

    /** 校验块号是否合法，防止越界读写把映像文件搞坏 */
    private void checkBlockNumber(int blockNumber) {
        if (blockNumber < 0 || blockNumber >= Constants.TOTAL_BLOCKS) {
            throw new FileSystemException("非法块号：" + blockNumber + "（合法范围 0 ~ "
                    + (Constants.TOTAL_BLOCKS - 1) + "）");
        }
    }

    public Path getImagePath() {
        return imagePath.toAbsolutePath();
    }

    @Override
    public void close() {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignored) {
                // 关闭失败不影响程序退出，忽略即可
            }
            raf = null;
        }
    }
}
