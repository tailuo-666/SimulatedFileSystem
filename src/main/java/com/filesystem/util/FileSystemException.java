package com.filesystem.util;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：文件系统业务异常：磁盘满、重名、只读、已打开等所有可预期错误都用它抛出
 *  上游依赖：无
 *  下游调用：全部核心类；界面层 catch 之后弹提示框
 * ========================================================================== */

/**
 * 文件系统业务异常。
 * <p>
 * 所有“用户操作不合法”的情况（目录不存在、重名、磁盘满、只读文件被写……）
 * 都抛这个异常，异常信息本身就是可以展示给用户看的中文提示。
 * <p>
 * 继承 RuntimeException，是为了让核心层的方法签名保持干净（不必层层 throws），
 * 同时由界面层统一捕获并弹窗提示，保证一个非法操作不会让整个程序崩溃。
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public class FileSystemException extends RuntimeException {

    /** 序列化版本号：异常类实现 Serializable 后编译器会要求它，固定 1L 即可 */
    private static final long serialVersionUID = 1L;

    public FileSystemException(String message) {
        super(message);
    }

    public FileSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
