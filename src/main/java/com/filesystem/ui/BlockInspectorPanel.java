package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 D —— 磁盘可视化（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：字节查看器：读某个块的 64 字节，显示 偏移/十六进制/十进制/ASCII，并给出 UTF-8 预览
 *  上游依赖：FileSystemService（readBlockBytes）、BlockInfo
 *  下游调用：MainFrame 在用户点击磁盘块时调用它的 showBlock
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.BlockInfo;
import com.filesystem.util.Constants;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 磁盘块字节查看器。
 * <p>
 * 点击磁盘网格中的某一格后，这里会用 {@link FileSystemService#readBlockBytes(int)}
 * 把那一块的 <b>64 个真实字节</b>从 disk.img 读出来，再按
 * “偏移量 / 十六进制 / 十进制 / ASCII” 四列显示，下方再给一个 UTF-8 文本预览。
 * <p>
 * 注意：这里的数据只能来自真实磁盘，绝不允许拿编辑框里的文本伪造。
 * 所以你在编辑窗口里改完内容、还没写入磁盘时，这里看到的仍然是旧数据，
 * 写入之后刷新才会变——这正是“模拟磁盘”该有的效果。
 *
 * @author 成员 D（负责 磁盘可视化，约 20%）
 * @version 1.0
 */
public class BlockInspectorPanel extends JPanel {

    /** 字节表的四列：偏移量（00~63）/ 十六进制 / 十进制 / ASCII 字符 */
    private static final String[] COLUMNS = {"偏移量", "十六进制", "十进制", "ASCII"};

    /** 业务门面：readBlockBytes() 是拿到真实 64 字节的唯一途径 */
    private final FileSystemService service;

    /** 大标题，显示"磁盘块 7" */
    private final JLabel titleLabel;

    /** 副标题，显示该块的 FAT 值、所属文件、状态 */
    private final JLabel subTitleLabel;

    /** 64 行的字节表模型 */
    private final HexTableModel model = new HexTableModel();

    /** 字节表组件 */
    private final JTable table;

    /** 下方 UTF-8 文本预览区，把 64 字节按 UTF-8 解码给人看 */
    private final JTextArea previewArea;

    /** 当前显示的块号，-1 表示还没选 */
    private int currentBlock = -1;
    /** 当前显示块的完整信息（含所属文件、状态），用来拼副标题 */
    private BlockInfo currentInfo;

    public BlockInspectorPanel(FileSystemService service) {
        super(new BorderLayout());
        this.service = service;

        // ---------- 顶部信息 ----------
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        titleLabel = new JLabel("磁盘块字节查看器");
        titleLabel.setFont(UiTheme.FONT_TITLE);
        subTitleLabel = new JLabel("在左侧磁盘网格中点击任意一块，即可查看它的 64 个字节");
        subTitleLabel.setFont(UiTheme.FONT_SMALL);
        subTitleLabel.setForeground(UiTheme.TEXT_SUB);
        JPanel headerText = new JPanel(new BorderLayout());
        headerText.add(titleLabel, BorderLayout.NORTH);
        headerText.add(subTitleLabel, BorderLayout.SOUTH);
        header.add(headerText, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        // ---------- 十六进制表格 ----------
        table = new JTable(model);
        table.setRowHeight(20);
        table.setFont(UiTheme.FONT_MONO);
        table.getTableHeader().setFont(UiTheme.FONT_BOLD);
        table.setGridColor(new Color(0xEEEEEE));
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new HexCellRenderer());
        int[] widths = {70, 90, 90, 90};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        // ---------- 文本预览 ----------
        previewArea = new JTextArea();
        previewArea.setEditable(false);
        previewArea.setFont(UiTheme.FONT_MONO);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(false);
        previewArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        previewArea.setText("（尚未选择磁盘块）");

        JPanel previewPanel = new JPanel(new BorderLayout());
        JLabel previewTitle = new JLabel("UTF-8 文本预览（不可打印字符显示为小圆点）");
        previewTitle.setFont(UiTheme.FONT_SMALL);
        previewTitle.setForeground(UiTheme.TEXT_SUB);
        previewTitle.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        previewPanel.add(previewTitle, BorderLayout.NORTH);
        previewPanel.add(new JScrollPane(previewArea), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), previewPanel);
        splitPane.setResizeWeight(0.72);
        splitPane.setDividerLocation(300);
        add(splitPane, BorderLayout.CENTER);

        setMinimumSize(new Dimension(400, 220));
    }

    /**
     * 显示指定磁盘块的内容（重新从磁盘读取）。
     *
     * @param blockNumber 块号
     * @param info        该块的综合信息，可能为 null
     */
    public void showBlock(int blockNumber, BlockInfo info) {
        this.currentBlock = blockNumber;
        this.currentInfo = info;
        try {
            byte[] data = service.readBlockBytes(blockNumber);
            model.setData(blockNumber, data);
            updateHeader();
            updatePreview(blockNumber, info, data);
        } catch (IOException e) {
            model.setData(blockNumber, new byte[64]);
            titleLabel.setText("磁盘块 " + blockNumber + " 读取失败");
            subTitleLabel.setText(e.getMessage());
            previewArea.setText("读取失败：" + e.getMessage());
        }
    }

    /** 重新读一遍当前块（写入文件之后刷新用） */
    public void refreshCurrentBlock() {
        if (currentBlock >= 0) {
            showBlock(currentBlock, currentInfo);
        }
    }

    /** 当前显示的块号，-1 表示未选择 */
    public int getCurrentBlock() {
        return currentBlock;
    }

    private void updateHeader() {
        if (currentInfo == null) {
            titleLabel.setText("磁盘块 " + currentBlock);
            subTitleLabel.setText("64 字节");
            return;
        }
        titleLabel.setText("磁盘块 " + currentBlock + "　FAT 值 "
                + UiTheme.fatValueText(currentInfo.getFatValue())
                + "　状态 " + currentInfo.getState().getText());
        String owner = currentInfo.getOwnerPath();
        subTitleLabel.setText("所属：" + (owner == null ? "—" : owner)
                + (currentInfo.getRemark().isEmpty() ? "" : "　　备注：" + currentInfo.getRemark()));
    }

    /** 把 64 字节解码成可读文本，控制字符换成 '·'，让人能直接看出存了什么 */
    private void updatePreview(int blockNumber, BlockInfo info, byte[] data) {
        // ---------- 情况一：FAT 表块（块 0、块 1）----------
        // 这两块里装的是 FAT 表本身，字节值大量是 253 / 254 / 255 这类特殊标记。
        // 它们不是合法的 UTF-8 起始字节，硬按文本解码只会得到一串替换字符 "�"，
        // 也就是眼睛看到的“乱码”。所以这两块改用「FAT 表项解读」，信息量大得多。
        if (blockNumber == Constants.FAT_BLOCK_FIRST || blockNumber == Constants.FAT_BLOCK_SECOND) {
            previewArea.setText(describeFatBlock(blockNumber, data));
            previewArea.setCaretPosition(0);
            return;
        }

        // ---------- 情况二：普通块，按 UTF-8 解码 ----------
        StringBuilder sb = new StringBuilder();
        String text = new String(data, StandardCharsets.UTF_8);
        char[] chars = text.toCharArray();
        int brokenCount = 0;      // 解码失败留下的替换字符个数（U+FFFD）
        int firstBroken = -1;     // 第一个替换字符的位置
        int lastNormal = -1;      // 最后一个正常字符的位置
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\uFFFD') {
                if (firstBroken < 0) {
                    firstBroken = i;
                }
                brokenCount++;
                sb.append('·');           // 非法字节：不再原样显示成 "�"，统一画成小圆点
                continue;
            }
            if (c >= 0x20 && c != 0x7F) {
                lastNormal = i;
            }
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(c == '\n' ? '\n' : ' ');
            } else if (c < 0x20 || c == 0x7F) {
                sb.append('·');
            } else {
                sb.append(c);
            }
        }
        // 去掉尾部那些由 0 字节转成的 '·'，让预览更干净
        String content = sb.toString().replaceAll("[·\\s]+$", "");
        if (content.trim().isEmpty()) {
            content = "（该块全部是 0，没有可读文本）";
        }
        if (brokenCount > 0) {
            // 分两种：一种是真的二进制；一种是文件内容里的汉字正好被 64 字节边界切断
            // 只有“坏的都堆在最后、且数量很少（最多 3 个，对应 4 字节汉字）”才算截断，
            // 否则（比如整块都是 0xFD）仍按二进制处理，不能误报成截断。
            boolean onlyTailCut = lastNormal >= 0 && firstBroken > lastNormal && brokenCount <= 3;
            if (onlyTailCut) {
                content = "（块末尾的汉字被 64 字节边界切断，只存了前半个字，"
                        + "跨到下一块才能拼完整——这是按块存储的正常现象）\n\n" + content;
            } else {
                content = "（本块含 " + brokenCount + " 处非 UTF-8 字节，属于二进制数据，"
                        + "不能当文本读；下面只能粗略预览，乱码处已画成小圆点）\n\n" + content;
            }
        }
        if (info != null && info.getState() == BlockInfo.State.ROOT) {
            content = "【根目录块】下面每个 8 字节为一组目录项：\n\n" + content;
        } else if (info != null && info.getState() == BlockInfo.State.BAD) {
            content = "【坏块】本块在 FAT 里被标记为 254，分配器永远不会使用它。\n\n" + content;
        }
        previewArea.setText(content);
        previewArea.setCaretPosition(0);
    }

    /**
     * 把 FAT 表块按「块号 → 字节值 → 含义」逐条解读。
     * <p>
     * 块 0 对应 FAT 的第 0~63 项，块 1 对应第 64~127 项，一个字节一项。
     * 只列非 0 的项，其余归到一句“空闲”里，避免刷屏。
     */
    private String describeFatBlock(int blockNumber, byte[] data) {
        int base = blockNumber * Constants.BLOCK_SIZE;   // 本块第一个表项对应的块号
        StringBuilder sb = new StringBuilder();
        sb.append("【FAT 表块】本块装的不是文件内容，而是 FAT 表本身的一部分。\n");
        sb.append("它记录块 ").append(base).append(" ~ ").append(base + Constants.BLOCK_SIZE - 1)
                .append(" 的分配状态，1 个字节就是 1 个表项。\n");
        sb.append("取值含义： 253 系统保留 ｜ 254 坏块 ｜ 255 文件结束 ｜ 0 空闲 ｜ 其他 = 下一块号\n\n");
        sb.append("块号    字节值   代表的 FAT 值   含义\n");
        int zeroCount = 0;
        for (int i = 0; i < Constants.BLOCK_SIZE; i++) {
            int block = base + i;
            int value = data[i] & 0xFF;   // 转无符号，否则 253/254/255 会变成负数
            if (value == Constants.FAT_FREE) {
                zeroCount++;
                continue;
            }
            sb.append(String.format("%4d    0x%02X          %3d        %s%n",
                    block, value, value, fatMeaning(value)));
        }
        if (zeroCount == Constants.BLOCK_SIZE) {
            sb.append("（本块 64 个表项全是 0，说明对应的 64 个块都空闲）");
        } else {
            sb.append("\n其余 ").append(zeroCount).append(" 个表项为 0（空闲），未逐条列出。");
        }
        return sb.toString();
    }

    /** FAT 数值翻译成中文含义 */
    private static String fatMeaning(int value) {
        if (value == Constants.FAT_SYSTEM) {
            return "系统保留块";
        }
        if (value == Constants.FAT_BAD) {
            return "坏块（永不分配）";
        }
        if (value == Constants.FAT_EOF) {
            return "文件结束（EOF）";
        }
        return "指向下一块 " + value;
    }

    // ==================== 表格模型与渲染器 ====================

    /** 64 行，每行一个字节 */
    private static class HexTableModel extends AbstractTableModel {
        /** 当前显示的块号，-1 表示还没选块（此时四列都显示占位符） */
        private int blockNumber = -1;

        /** 该块的 64 个原始字节，直接来自 disk.img，不做任何加工 */
        private byte[] data = new byte[64];

        void setData(int blockNumber, byte[] data) {
            this.blockNumber = blockNumber;
            this.data = data;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return 64;
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            int value = data[rowIndex] & 0xFF; // 转无符号，否则 253/254/255 会显示成负数
            switch (columnIndex) {
                case 0:
                    return String.format("%02d", rowIndex); // 偏移量 00 ~ 63
                case 1:
                    return String.format("%02X", value);
                case 2:
                    return value;
                case 3:
                    return printable(value) ? String.valueOf((char) value) : ".";
                default:
                    return "";
            }
        }

        private boolean printable(int value) {
            return value >= 0x20 && value <= 0x7E;
        }
    }

    /** 字节为 0 的行用浅灰显示，方便一眼看出“这块大部分是空的” */
    private static class HexCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            if (!isSelected) {
                if (column == 1) {
                    // 十六进制列：非 0 字节用深色，0 用浅灰
                    boolean isZero = "00".equals(value);
                    setForeground(isZero ? new Color(0xBDBDBD) : UiTheme.TEXT_MAIN);
                    setBackground(Color.WHITE);
                } else {
                    setForeground(UiTheme.TEXT_MAIN);
                    setBackground(row % 2 == 0 ? Color.WHITE : new Color(0xFAFAFA));
                }
            }
            return this;
        }
    }
}
