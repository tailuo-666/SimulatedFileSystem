package com.filesystem.model;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：文件读写指针：一个位置由『第几块 + 块内第几字节』两个数字确定
 *  上游依赖：Constants
 *  下游调用：OpenFileEntry 持有两个 Pointer；FileManager 读写时按它定位
 * ========================================================================== */

/**
 * 文件读写指针。
 * <p>
 * 课程设计明确要求：指针不能只记一个“第几个字节”，
 * 必须同时记录【当前磁盘块号】和【块内偏移】，这样才能真实反映
 * “文件是显式链接的物理结构”这一事实。
 * <p>
 * 例如指针 (blockNumber=8, offset=20) 表示：下一次读写发生在第 8 块的
 * 第 20 个字节处。若一块 64 字节读完后，指针就要跳到 FAT[8] 指向的下一块，
 * 并把块内偏移重置为 0。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class Pointer {

    /** 当前所在磁盘块号 */
    private int blockNumber;

    /** 当前块内字节偏移（0 ~ 64） */
    private int offset;

    public Pointer(int blockNumber, int offset) {
        this.blockNumber = blockNumber;
        this.offset = offset;
    }

    public int getBlockNumber() {
        return blockNumber;
    }

    public void setBlockNumber(int blockNumber) {
        this.blockNumber = blockNumber;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    /** 把指针移动到指定位置 */
    public void moveTo(int blockNumber, int offset) {
        this.blockNumber = blockNumber;
        this.offset = offset;
    }

    /** 复制一份指针（OFT 里读写指针要互相独立） */
    public Pointer copy() {
        return new Pointer(blockNumber, offset);
    }

    /** 界面显示用，例如 "块 8 / 偏移 20" */
    @Override
    public String toString() {
        return "块 " + blockNumber + " / 偏移 " + offset;
    }
}
