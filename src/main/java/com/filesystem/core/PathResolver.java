package com.filesystem.core;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：绝对路径逐级解析：/DOC/SUB/C.TX 从根目录一层层往下找，不借助 java.io.File
 *  上游依赖：DirectoryManager、FatTable、DirectoryEntry
 *  下游调用：FileSystemService 的所有按路径操作；界面的目录树也用它判断目录是否存在
 * ========================================================================== */

import com.filesystem.model.DirectoryEntry;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 绝对路径解析器 —— 像操作系统那样一级一级地走目录树。
 * <p>
 * 路径形如 {@code /DOC/SUB/C.TX}，解析过程是：
 * 从根目录块（第 2 块）出发 → 在根目录里找 DOC → 得到 DOC 的目录块
 * → 在 DOC 里找 SUB → 得到 SUB 的目录块 → 在 SUB 里找 C.TX → 拿到目录项。
 * <p>
 * <b>不能用</b> java.nio.file.Paths 之类的本地文件 API 来代替这套逻辑，
 * 因为模拟磁盘里根本没有真实的目录，只有我们自己定义的目录块。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class PathResolver {

    /** 目录管理器：每一级目录都要靠它把一个块里的 8 个目录项读出来 */
    private final DirectoryManager dirManager;

    public PathResolver(DirectoryManager dirManager) {
        this.dirManager = dirManager;
    }

    // ==================== 一、路径字符串处理 ====================

    /**
     * 把用户输入的路径规范化：
     * 统一用 '/' 分隔、确保以 '/' 开头、合并连续的 '/'、去掉末尾的 '/'。
     * 例如 "doc//sub/" → "/doc/sub"。
     */
    public static String normalize(String path) {
        if (path == null || path.trim().isEmpty()) {
            return Constants.ROOT_PATH;
        }
        String p = path.trim().replace('\\', '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** 把路径拆成各级名称，例如 "/DOC/SUB/C.TX" → ["DOC", "SUB", "C.TX"] */
    public static List<String> split(String path) {
        String p = normalize(path);
        List<String> parts = new ArrayList<>();
        for (String segment : p.split("/")) {
            if (!segment.isEmpty()) {
                parts.add(segment);
            }
        }
        return parts;
    }

    /** 取父目录路径："/DOC/A.TX" → "/DOC"；根目录返回 null */
    public static String getParentPath(String path) {
        String p = normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            return null;
        }
        int index = p.lastIndexOf('/');
        return index <= 0 ? Constants.ROOT_PATH : p.substring(0, index);
    }

    /** 取最后一级名称："/DOC/A.TX" → "A.TX"；根目录返回 "/" */
    public static String getBaseName(String path) {
        String p = normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            return Constants.ROOT_PATH;
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** 拼接路径：join("/DOC", "A.TX") → "/DOC/A.TX" */
    public static String join(String parentPath, String name) {
        String parent = normalize(parentPath);
        return Constants.ROOT_PATH.equals(parent) ? "/" + name : parent + "/" + name;
    }

    // ==================== 二、逐级解析 ====================

    /**
     * 解析目录路径，返回该目录所占用的磁盘块号。
     * 路径不存在、或中间某一级其实是文件，都抛异常。
     */
    public int resolveDirectoryBlock(String path) throws IOException {
        String p = normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            return Constants.ROOT_BLOCK; // 根目录固定第 2 块
        }
        int currentBlock = Constants.ROOT_BLOCK;
        for (String name : split(p)) {
            DirectoryEntry entry = dirManager.findEntry(currentBlock, name);
            if (entry == null) {
                throw new FileSystemException("目录不存在：" + p + "（缺少 “" + name + "”）");
            }
            if (!entry.isDirectory()) {
                throw new FileSystemException("“" + name + "” 不是目录，无法继续进入。");
            }
            currentBlock = entry.getStartBlock();
        }
        return currentBlock;
    }

    /**
     * 解析某个路径的父目录块号。
     */
    public int resolveParentBlock(String path) throws IOException {
        String parent = getParentPath(path);
        if (parent == null) {
            throw new FileSystemException("根目录没有父目录。");
        }
        return resolveDirectoryBlock(parent);
    }

    /**
     * 根据绝对路径查找目录项（文件或目录都行）。
     *
     * @return 找到的目录项；路径不存在（含父目录不存在）返回 null
     */
    public DirectoryEntry resolveEntry(String path) throws IOException {
        String p = normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            return null; // 根目录没有目录项，它是固定块
        }
        String parentPath = getParentPath(p);
        if (parentPath == null) {
            return null;
        }
        int parentBlock;
        try {
            parentBlock = resolveDirectoryBlock(parentPath);
        } catch (FileSystemException e) {
            return null; // 父目录都不存在，当然找不到
        }
        return dirManager.findEntry(parentBlock, getBaseName(p));
    }

    /**
     * 根据绝对路径查找目录项，找不到就抛异常（带中文提示）。
     */
    public DirectoryEntry resolveEntryOrThrow(String path) throws IOException {
        DirectoryEntry entry = resolveEntry(path);
        if (entry == null) {
            throw new FileSystemException("文件或目录不存在：" + normalize(path));
        }
        return entry;
    }

    /** 路径是否存在 */
    public boolean exists(String path) throws IOException {
        return resolveEntry(path) != null;
    }

    /** 该路径是否是目录（根目录也算） */
    public boolean isDirectory(String path) throws IOException {
        String p = normalize(path);
        if (Constants.ROOT_PATH.equals(p)) {
            return true;
        }
        DirectoryEntry entry = resolveEntry(p);
        return entry != null && entry.isDirectory();
    }
}
