package com.filesystem.model;

/* ==========================================================================
 *  【模块分工】成员 D —— 磁盘可视化（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：单个磁盘块的展示模型（DTO）：块号、FAT 值、状态、所属路径、是否被打开——只装数据不含逻辑
 *  上游依赖：Constants（判断状态）
 *  下游调用：A 的 FileSystemService.buildBlockInfos() 负责填充；D 的三个面板负责展示
 * ========================================================================== */

import com.filesystem.util.Constants;

/**
 * 单个磁盘块的综合信息（FAT 值 + 归属 + 状态），只用于界面展示。
 * <p>
 * 这个类<b>不参与磁盘数据的存盘</b>，它是每次刷新界面时，
 * 由 {@code FileSystemService} 遍历目录树、再沿 FAT 链追踪后现算出来的。
 * 之所以要现算而不写进磁盘，是因为课程规定 FAT 结构一个字节都不能改，
 * 所有权信息只能靠“目录项 + FAT 链”推导。
 *
 * @author 成员 D（负责 磁盘可视化，约 20%）
 * @version 1.0
 */
public class BlockInfo {

    /** 块的当前状态，直接决定界面用什么颜色画 */
    public enum State {
        /** 空闲块（FAT = 0），未分配给任何文件 */
        FREE("空闲"),
        /** 文件的数据块（链中还有下一块） */
        FILE_DATA("文件数据块"),
        /** 文件的最后一块（FAT = 255） */
        FILE_END("文件末块(EOF)"),
        /** 子目录占用的块 */
        DIRECTORY("目录块"),
        /** 根目录块（固定第 2 块） */
        ROOT("根目录(系统保留)"),
        /** FAT 表本身占用的块 0、1 */
        SYSTEM("FAT表(系统保留)"),
        /** 坏块（第 23、49 块） */
        BAD("坏块"),
        /** 异常：已分配但找不到任何目录项归属，或链断开 */
        ORPHAN("异常块");

        /** 界面上显示的中文状态文字，例如 "空闲块" "普通文件数据块" */
        private final String text;

        State(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /** 块号，0 ~ 127 */
    private final int blockNumber;

    /** 该块在 FAT 中的原始值（0 空闲 / 1~127 下一块 / 253 系统 / 254 坏块 / 255 EOF） */
    private final int fatValue;

    /** 该块当前的状态分类，界面靠它决定涂什么颜色 */
    private final State state;

    /** 该块属于哪个文件 / 目录的绝对路径，可能为 null */
    private final String ownerPath;
    /** 该块是否正被某个已打开的文件占用（用于 FAT 表高亮） */
    private final String openedBy;
    /** 备注信息，例如异常原因 */
    private final String remark;

    public BlockInfo(int blockNumber, int fatValue, State state,
                     String ownerPath, String openedBy, String remark) {
        this.blockNumber = blockNumber;
        this.fatValue = fatValue;
        this.state = state;
        this.ownerPath = ownerPath;
        this.openedBy = openedBy;
        this.remark = remark == null ? "" : remark;
    }

    /** FAT 值的文字形式：0 / 255(EOF) / 254(坏块) / 253(系统) / 数字 */
    public String getFatValueText() {
        switch (fatValue) {
            case Constants.FAT_FREE:
                return "0";
            case Constants.FAT_EOF:
                return "255 (EOF)";
            case Constants.FAT_BAD:
                return "254 (坏块)";
            case Constants.FAT_SYSTEM:
                return "253 (系统)";
            default:
                return String.valueOf(fatValue);
        }
    }

    /** “下一块”列显示的内容 */
    public String getNextBlockText() {
        if (fatValue == Constants.FAT_FREE) {
            return "-";
        }
        if (fatValue == Constants.FAT_EOF) {
            return "EOF";
        }
        if (fatValue == Constants.FAT_BAD) {
            return "坏块";
        }
        if (fatValue == Constants.FAT_SYSTEM) {
            return "系统";
        }
        return String.valueOf(fatValue);
    }

    public boolean isOpened() {
        return openedBy != null;
    }

    /** 所属文件的显示文本 */
    public String getOwnerText() {
        if (ownerPath == null) {
            if (state == State.BAD) {
                return "坏块";
            }
            if (state == State.FREE) {
                return "空闲";
            }
            return "—";
        }
        return ownerPath;
    }

    public int getBlockNumber() {
        return blockNumber;
    }

    public int getFatValue() {
        return fatValue;
    }

    public State getState() {
        return state;
    }

    public String getOwnerPath() {
        return ownerPath;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public String getRemark() {
        return remark;
    }

    /** 鼠标悬停提示用的多行文本 */
    public String toTooltipText() {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>磁盘块 ").append(blockNumber).append("</b><br>");
        sb.append("FAT 值：").append(getFatValueText()).append("<br>");
        sb.append("状态：").append(state.getText()).append("<br>");
        sb.append("所属：").append(getOwnerText());
        if (openedBy != null) {
            sb.append("<br>已打开：").append(openedBy);
        }
        if (!remark.isEmpty()) {
            sb.append("<br><font color='#c0392b'>").append(remark).append("</font>");
        }
        sb.append("</html>");
        return sb.toString();
    }
}
