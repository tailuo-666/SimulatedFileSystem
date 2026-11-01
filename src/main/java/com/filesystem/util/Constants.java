package com.filesystem.util;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：全系统常量表：磁盘块大小、块号布局、FAT 特殊值、文件属性位、OFT 容量、名字长度限制
 *  上游依赖：无（最底层，谁都依赖它）
 *  下游调用：core / model / ui 全部包；界面画网格、算统计也读这里的常量
 * ========================================================================== */

/**
 * 全系统的常量定义。
 * <p>
 * 课程设计对磁盘参数有硬性规定（128 块 / 每块 64 字节 / FAT 每项 1 字节 等），
 * 这里把它们集中成常量，任何模块都只引用这里，避免各处硬编码数字写错。
 * <p>
 * 磁盘空间布局（总 8192 字节）：
 * <pre>
 *   块 0        ：FAT 表前 64 项（64 字节）
 *   块 1        ：FAT 表后 64 项（64 字节）
 *   块 2        ：根目录（固定，8 个目录项）
 *   块 3 ~ 127  ：普通数据块 / 子目录块（其中 23、49 是坏块）
 * </pre>
 *
 * @author 成员 A（负责 存储内核，约 40%）
 * @version 1.0
 */
public final class Constants {

    /** 工具类不允许被实例化 */
    private Constants() {
    }

    // ==================== 一、磁盘几何参数 ====================

    /** 每个物理块的大小：64 字节 */
    public static final int BLOCK_SIZE = 64;

    /** 磁盘物理块数量：128 块 */
    public static final int TOTAL_BLOCKS = 128;

    /** 磁盘总容量：128 × 64 = 8192 字节 */
    public static final int DISK_SIZE = BLOCK_SIZE * TOTAL_BLOCKS;

    /** FAT 表所在的第一个块（存放 FAT[0] ~ FAT[63]） */
    public static final int FAT_BLOCK_FIRST = 0;

    /** FAT 表所在的第二个块（存放 FAT[64] ~ FAT[127]） */
    public static final int FAT_BLOCK_SECOND = 1;

    /** 根目录固定占用的磁盘块号 */
    public static final int ROOT_BLOCK = 2;

    /** 可以分配给文件 / 子目录的第一个磁盘块号 */
    public static final int FIRST_DATA_BLOCK = 3;

    /** 坏块表：这两块永远不能被分配 */
    public static final int[] BAD_BLOCK_NUMBERS = {23, 49};

    // ==================== 二、FAT 表项的特殊值 ====================

    /** FAT 值 0：该块空闲 */
    public static final int FAT_FREE = 0;

    /** FAT 值 253：系统保留块（FAT 表自身两块 + 根目录块） */
    public static final int FAT_SYSTEM = 253;

    /** FAT 值 254：坏块 */
    public static final int FAT_BAD = 254;

    /** FAT 值 255：文件（或目录）的最后一个块，即 EOF */
    public static final int FAT_EOF = 255;

    // ==================== 三、目录项结构 ====================

    /** 每个目录项占 8 字节 */
    public static final int ENTRY_SIZE = 8;

    /** 每个目录块可放 8 个目录项（64 / 8） */
    public static final int ENTRIES_PER_BLOCK = BLOCK_SIZE / ENTRY_SIZE;

    /** 文件主名最大长度（3 个 ASCII 字符） */
    public static final int NAME_MAX_LENGTH = 3;

    /** 扩展名最大长度（2 个 ASCII 字符） */
    public static final int EXT_MAX_LENGTH = 2;

    /** 空目录项的标志：第一个字节是 '$' */
    public static final byte ENTRY_FREE_FLAG = (byte) '$';

    /** 目录项内部字段的偏移量（文件和目录共用同一套 8 字节布局） */
    /** 偏移 0：名称，3 字节（ASCII，不足补 0） */
    public static final int OFFSET_NAME = 0;
    /** 偏移 3：文件是扩展名 2 字节；目录这两位保留不用 */
    public static final int OFFSET_TYPE = 3;
    /** 偏移 5：属性 1 字节（1 只读 / 4 可读写 / 8 目录） */
    public static final int OFFSET_ATTR = 5;
    /** 偏移 6：起始盘块号 1 字节 */
    public static final int OFFSET_START = 6;
    /** 偏移 7：文件是"占用块数"1 字节；目录这位保留不用 */
    public static final int OFFSET_LENGTH = 7;

    // ==================== 四、文件 / 目录属性 ====================
    /*
     * 属性只用 1 个字节，却要表示"只读 / 系统 / 可读写 / 目录"好几件事，
     * 办法是让每一位（bit）单独表示一件事，这叫"位标志"：
     *
     *   属性值 4  = 0000 0100   可读写文件  （第 2 位为 1）
     *   属性值 8  = 0000 1000   目录        （第 3 位为 1）
     *   属性值 12 = 0000 1100   既是目录又……（理论上可组合，本实现不用）
     *
     * 判断时用按位与 & ： (attribute & ATTR_DIRECTORY) != 0 就表示"是目录"。
     * 好处是一个字节就能同时表达多个开关，而且判断速度极快。
     * 初学者千万不要写成 attribute == 8，那样一旦以后加上别的位就会判断错。
     */

    /** 属性位 0：只读文件 */
    public static final int ATTR_READ_ONLY = 0x01;

    /** 属性位 1：系统文件（本实现保留，不提供界面开关） */
    public static final int ATTR_SYSTEM = 0x02;

    /** 属性位 2：普通可读写文件 */
    public static final int ATTR_NORMAL = 0x04;

    /** 属性位 3：目录 */
    public static final int ATTR_DIRECTORY = 0x08;

    // ==================== 五、文件内容与结束符 ====================

    /**
     * 文件结束符 '#'。
     * 文件内容按 UTF-8 字节存放，末尾追加一个 '#'；
     * '#' 是单字节 0x23，不可能出现在 UTF-8 多字节字符的中间，因此不会误判。
     */
    public static final byte EOF_FLAG = (byte) '#';

    /** 结束符对应的字符形式，用于界面校验 */
    public static final char EOF_CHAR = '#';

    // ==================== 六、文件打开表 OFT ====================

    /** 系统最多同时打开 5 个文件 */
    public static final int MAX_OPEN_FILES = 5;

    /** 打开方式：只读（读方式） */
    public static final int OPEN_READ = 0;

    /** 打开方式：写方式 */
    public static final int OPEN_WRITE = 1;

    // ==================== 七、写入模式 ====================

    /** 追加写入：把新内容接在文件末尾 */
    public static final int WRITE_APPEND = 0;

    /** 覆盖写入：用新内容完全替换原内容 */
    public static final int WRITE_OVERWRITE = 1;

    // ==================== 八、其它 ====================

    /** 磁盘映像文件默认相对路径（相对于程序运行目录） */
    public static final String DEFAULT_DISK_PATH = "data/disk.img";

    /** 根目录的绝对路径 */
    public static final String ROOT_PATH = "/";
}
