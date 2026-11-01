package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 D —— 磁盘可视化（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：FAT 表面板：JTable 列出 128 项（块号/FAT 值/下一块/状态/所属文件/是否打开）并高亮打开文件
 *  上游依赖：FileSystemService（拿块归属）、BlockInfo、UiTheme
 *  下游调用：MainFrame 在刷新时调用它的 refresh
 * ========================================================================== */

import com.filesystem.model.BlockInfo;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FAT 表可视化面板：用 JTable 显示全部 128 个 FAT 表项。
 * <p>
 * 除了 FAT 原始数值，这里还额外算出“下一块 / 状态 / 所属文件 / 是否打开”，
 * 这些都不是磁盘上存的字段，而是根据目录树 + FAT 链推导出来的，目的是
 * 让人一眼就能看懂这块盘是怎么用的。
 * <p>
 * 已被打开的文件所占的块会按文件用不同颜色高亮（最多 5 种颜色，对应 OFT 的 5 个表项）。
 *
 * @author 成员 D（负责 磁盘可视化，约 20%）
 * @version 1.0
 */
public class FatTablePanel extends JPanel {

    /** 表头六列：块号 / FAT 原始值 / 下一块 / 状态 / 所属文件 / 是否打开 */
    private static final String[] COLUMNS = {"块号", "FAT 值", "下一块", "状态", "所属文件", "是否打开"};

    /** 表格数据模型：把 128 条 BlockInfo 映射成 6 列 */
    private final FatTableModel model = new FatTableModel();

    /** Swing 表格组件，固定 128 行 */
    private final JTable table;

    /** 顶部标题，会显示"共 128 项，其中 3 个系统块…"之类的汇总 */
    private final JLabel headerLabel;

    public FatTablePanel() {
        super(new BorderLayout());

        headerLabel = new JLabel("全部 128 个 FAT 表项");
        headerLabel.setFont(UiTheme.FONT_SMALL);
        headerLabel.setForeground(UiTheme.TEXT_SUB);
        headerLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(headerLabel, BorderLayout.NORTH);

        table = new JTable(model);
        table.setRowHeight(22);
        table.setFont(UiTheme.FONT_BASE);
        table.getTableHeader().setFont(UiTheme.FONT_BOLD);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setGridColor(new Color(0xE8E8E8));
        table.setAutoCreateRowSorter(false);

        int[] widths = {60, 90, 70, 130, 220, 180};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.setDefaultRenderer(Object.class, new FatCellRenderer());

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * 刷新表格。
     *
     * @param infos 128 个块的信息（由 FileSystemService 现算出来）
     */
    public void setBlockInfos(List<BlockInfo> infos) {
        model.setRows(infos == null ? new ArrayList<>() : infos);
        int openCount = 0;
        for (BlockInfo info : model.rows) {
            if (info.isOpened()) {
                openCount++;
            }
        }
        headerLabel.setText("全部 128 个 FAT 表项　|　已打开文件占用的块：" + openCount
                + " 块（用不同颜色高亮，橙色为第 1 个打开的文件）");
    }

    /** 选中某个块对应的行（从磁盘网格点过来时联动） */
    public void selectBlock(int blockNumber) {
        if (blockNumber < 0 || blockNumber >= model.getRowCount()) {
            return;
        }
        table.setRowSelectionInterval(blockNumber, blockNumber);
        table.scrollRectToVisible(table.getCellRect(blockNumber, 0, true));
    }

    /** 把颜色调淡（和白色混合），用作行背景 */
    private static Color tint(Color color, double ratio) {
        int r = (int) Math.round(color.getRed() * ratio + 255 * (1 - ratio));
        int g = (int) Math.round(color.getGreen() * ratio + 255 * (1 - ratio));
        int b = (int) Math.round(color.getBlue() * ratio + 255 * (1 - ratio));
        return new Color(r, g, b);
    }

    /** 表格数据模型 */
    private static class FatTableModel extends AbstractTableModel {
        /** 128 条块信息，行号 = 块号 */
        private List<BlockInfo> rows = new ArrayList<>();

        /** 已打开文件路径 → 高亮颜色 */
        private Map<String, Color> openColorMap = new LinkedHashMap<>();

        void setRows(List<BlockInfo> infos) {
            this.rows = infos;
            // 按出现顺序给每个打开的文件分配一种颜色（OFT 最多 5 个）
            openColorMap = new LinkedHashMap<>();
            for (BlockInfo info : infos) {
                String path = info.getOpenedBy();
                if (path != null && !openColorMap.containsKey(path)) {
                    int index = openColorMap.size() % UiTheme.OPEN_FILE_COLORS.length;
                    openColorMap.put(path, UiTheme.OPEN_FILE_COLORS[index]);
                }
            }
            fireTableDataChanged();
        }

        BlockInfo getRow(int index) {
            return rows.get(index);
        }

        Color colorOf(BlockInfo info) {
            return info.getOpenedBy() == null ? null : openColorMap.get(info.getOpenedBy());
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            return false; // FAT 是只读展示，禁止在表格里直接改（改了也不落盘，会误导）
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            BlockInfo info = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return info.getBlockNumber();
                case 1:
                    return info.getFatValue();
                case 2:
                    return info.getNextBlockText();
                case 3:
                    return info.getState().getText() + (info.getRemark().isEmpty() ? "" : "（" + info.getRemark() + "）");
                case 4:
                    return info.getOwnerText();
                case 5:
                    return info.isOpened() ? info.getOpenedBy() : "";
                default:
                    return "";
            }
        }
    }

    /** 单元格渲染器：按块状态 / 是否打开上色 */
    private class FatCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            FatTableModel m = (FatTableModel) table.getModel();
            BlockInfo info = m.getRow(row);

            if (isSelected) {
                setBackground(new Color(0x1E88E5));
                setForeground(Color.WHITE);
            } else {
                Color openColor = m.colorOf(info);
                if (openColor != null) {
                    setBackground(tint(openColor, 0.28));  // 已打开：彩色底
                } else {
                    switch (info.getState()) {
                        case BAD:
                            setBackground(new Color(0xFFEBEE));
                            break;
                        case SYSTEM:
                        case ROOT:
                            setBackground(new Color(0xF0F0F0));
                            break;
                        case FREE:
                            setBackground(Color.WHITE);
                            break;
                        case ORPHAN:
                            setBackground(new Color(0xFCE4EC));
                            break;
                        case DIRECTORY:
                            setBackground(new Color(0xE8F5E9));
                            break;
                        default:
                            setBackground(new Color(0xE3F2FD));
                            break;
                    }
                }
                setForeground(UiTheme.TEXT_MAIN);
            }
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            return this;
        }
    }
}
