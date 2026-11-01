package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：已打开文件表：5 个槽位，负责查是否已打开、找空位、移除表项
 *  上游依赖：OpenFileEntry、Constants
 *  下游调用：FileSystemService；OFT 面板（成员 D）通过 Service 读它
 * ========================================================================== */

import com.filesystem.model.OpenFileEntry;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.util.ArrayList;
import java.util.List;

/**
 * 已打开文件表（Open File Table，OFT）。
 * <p>
 * 用长度为 5 的数组模拟，系统最多同时打开 5 个文件。
 * 一个文件一旦打开，就在表里占一格；关闭时把那一格置空。
 * <p>
 * 两条硬性规则（课程设计要求）：
 * <ul>
 *   <li>同一个文件不能重复登记——否则两个表项各自维护指针，必然写乱；</li>
 *   <li>表满时再打开文件必须报错，而不是悄悄覆盖别人的表项。</li>
 * </ul>
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class OpenFileTable {

    /** 5 个表项，null 表示这一格空闲 */
    private final OpenFileEntry[] slots = new OpenFileEntry[Constants.MAX_OPEN_FILES];

    /** 当前已打开的文件个数 */
    private int occupiedCount;

    /** 按路径查表项在数组中的下标，找不到返回 -1 */
    public int indexOf(String absolutePath) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].getAbsolutePath().equalsIgnoreCase(absolutePath)) {
                return i;
            }
        }
        return -1;
    }

    /** 按路径查找表项，找不到返回 null */
    public OpenFileEntry find(String absolutePath) {
        int index = indexOf(absolutePath);
        return index < 0 ? null : slots[index];
    }

    /** 该文件是否已经打开 */
    public boolean isOpen(String absolutePath) {
        return indexOf(absolutePath) >= 0;
    }

    /** 表是否已满 */
    public boolean isFull() {
        return occupiedCount >= Constants.MAX_OPEN_FILES;
    }

    /** 当前打开的文件数 */
    public int size() {
        return occupiedCount;
    }

    /**
     * 登记一个新的打开文件。调用前请先确认没打开、且表未满。
     */
    public void add(OpenFileEntry entry) {
        if (isOpen(entry.getAbsolutePath())) {
            throw new FileSystemException("文件已经打开：" + entry.getAbsolutePath());
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = entry;
                occupiedCount++;
                return;
            }
        }
        throw new FileSystemException("已打开文件表已满（最多 " + Constants.MAX_OPEN_FILES + " 个文件），请先关闭一个文件。");
    }

    /** 关闭文件：移除对应表项 */
    public boolean remove(String absolutePath) {
        int index = indexOf(absolutePath);
        if (index < 0) {
            return false;
        }
        slots[index] = null;
        occupiedCount--;
        return true;
    }

    /** 清空整张表（格式化磁盘、程序退出时用） */
    public void clear() {
        for (int i = 0; i < slots.length; i++) {
            slots[i] = null;
        }
        occupiedCount = 0;
    }

    /** 返回所有非空表项 */
    public List<OpenFileEntry> list() {
        List<OpenFileEntry> result = new ArrayList<>();
        for (OpenFileEntry entry : slots) {
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 返回 5 个槽位的拷贝（含 null），界面要画出完整 5 行 */
    public OpenFileEntry[] getSlots() {
        return slots.clone();
    }

    /** 是否有文件正在以写方式打开 */
    public boolean hasWriteModeFile() {
        for (OpenFileEntry entry : slots) {
            if (entry != null && entry.isWriteMode()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断某个目录（含其子孙目录）下面是否有文件正在打开。
     * 重命名 / 删除目录之前要检查这个，否则会把正在编辑的文件路径搞丢。
     */
    public boolean hasOpenFileUnder(String directoryPath) {
        String prefix = directoryPath.endsWith("/") ? directoryPath : directoryPath + "/";
        for (OpenFileEntry entry : slots) {
            if (entry != null && entry.getAbsolutePath().toUpperCase().startsWith(prefix.toUpperCase())) {
                return true;
            }
        }
        return false;
    }
}
