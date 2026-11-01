package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 D —— 磁盘可视化（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：已打开文件表面板：列出最多 5 个 OFT 表项的路径、方式、起始块、块数、读/写指针
 *  上游依赖：FileSystemService（getOpenFiles / closeFile）
 *  下游调用：MainFrame 在每次操作后刷新它
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.OpenFileEntry;
import com.filesystem.util.Constants;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 已打开文件表（OFT）面板。
 * <p>
 * 固定显示 5 行（OFT 的容量就是 5），空闲的槽位显示为“—”。
 * 每一行显示：槽位、绝对路径、打开方式、起始块、占用块数、字节数、读指针、写指针。
 * <p>
 * 这里的读指针 / 写指针是 OFT 表项里的真实对象，因此能看到
 * “读了一下文件之后，读指针前进了”这种细节。
 *
 * @author 成员 D（负责 磁盘可视化，约 20%）
 * @version 1.0
 */
public class OpenFileTablePanel extends JPanel {

    /** 面板事件回调 */
    public interface Listener {
        /** 请求关闭某个文件 */
        void closeRequested(String path);

        /** 请求关闭全部文件 */
        void closeAllRequested();
    }

    /** 八列：槽位号 / 路径 / 读或写 / 起始块 / 块数 / 字节数 / 读指针 / 写指针 */
    private static final String[] COLUMNS = {
            "槽位", "文件路径", "方式", "起始块", "块数", "字节数", "读指针", "写指针"
    };

    /** 业务门面：读 OFT、关闭文件 */
    private final FileSystemService service;

    /** 表格模型：固定 5 行，空的槽位显示"（空）" */
    private final OfTableModel model = new OfTableModel();

    /** 表格组件 */
    private final JTable table;

    /** 顶部"已用 2 / 5"的计数标签 */
    private final JLabel countLabel;

    /** 请求关闭文件 / 关闭全部的回调，主窗口负责真正执行 */
    private Listener listener;

    public OpenFileTablePanel(FileSystemService service) {
        super(new BorderLayout());
        this.service = service;

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JLabel title = new JLabel("已打开文件表（OFT）");
        title.setFont(UiTheme.FONT_TITLE);
        countLabel = new JLabel("0 / " + Constants.MAX_OPEN_FILES);
        countLabel.setFont(UiTheme.FONT_SMALL);
        countLabel.setForeground(UiTheme.TEXT_SUB);
        header.add(title, BorderLayout.WEST);
        header.add(countLabel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        table = new JTable(model);
        table.setRowHeight(24);
        table.setFont(UiTheme.FONT_BASE);
        table.getTableHeader().setFont(UiTheme.FONT_BOLD);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setGridColor(new Color(0xE8E8E8));
        table.setDefaultRenderer(Object.class, new OfCellRenderer());
        int[] widths = {50, 190, 70, 60, 50, 60, 120, 120};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton closeOne = new JButton("关闭选中文件");
        closeOne.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0 && listener != null) {
                OpenFileEntry entry = model.getRow(row);
                if (entry != null) {
                    listener.closeRequested(entry.getAbsolutePath());
                }
            }
        });
        JButton closeAll = new JButton("关闭全部文件");
        closeAll.addActionListener(e -> {
            if (listener != null) {
                listener.closeAllRequested();
            }
        });
        buttons.add(closeOne);
        buttons.add(closeAll);
        add(buttons, BorderLayout.SOUTH);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 从 OFT 重新读取数据并刷新 */
    public void refresh() {
        OpenFileEntry[] slots = service.getOpenFileTable().getSlots();
        model.setSlots(slots);
        int used = 0;
        for (OpenFileEntry entry : slots) {
            if (entry != null) {
                used++;
            }
        }
        countLabel.setText(used + " / " + Constants.MAX_OPEN_FILES);
    }

    /** 某路径是否已在表中（界面判断“已打开”状态用） */
    public boolean contains(String path) {
        return service.getOpenFileTable().isOpen(path);
    }

    /** 表格模型：固定 5 行 */
    private static class OfTableModel extends AbstractTableModel {
        /** 5 个槽位的原始数组，null 表示该槽空闲 */
        private OpenFileEntry[] slots = new OpenFileEntry[Constants.MAX_OPEN_FILES];

        /** 只把非空的槽位拿出来显示（界面上不显示空行，用槽位号标明位置） */
        private List<OpenFileEntry> rows = new ArrayList<>();

        void setSlots(OpenFileEntry[] slots) {
            this.slots = slots;
            this.rows = new ArrayList<>();
            for (OpenFileEntry entry : slots) {
                rows.add(entry);
            }
            fireTableDataChanged();
        }

        OpenFileEntry getRow(int index) {
            return index >= 0 && index < rows.size() ? rows.get(index) : null;
        }

        @Override
        public int getRowCount() {
            return slots.length;
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
            OpenFileEntry entry = rows.get(rowIndex);
            if (columnIndex == 0) {
                return rowIndex; // 槽位号总是显示
            }
            if (entry == null) {
                return columnIndex == 1 ? "（空闲）" : "—";
            }
            switch (columnIndex) {
                case 1:
                    return entry.getAbsolutePath();
                case 2:
                    return entry.getOperationText();
                case 3:
                    return entry.getStartBlock();
                case 4:
                    return entry.getBlockCount();
                case 5:
                    return entry.getLengthBytes();
                case 6:
                    return entry.getReadPointer() == null ? "—" : entry.getReadPointer().toString();
                case 7:
                    return entry.getWritePointer() == null ? "—" : entry.getWritePointer().toString();
                default:
                    return "";
            }
        }
    }

    /** 渲染器：每个已打开文件用与磁盘网格 / FAT 表一致的配色，空闲槽位显示为浅灰 */
    private static class OfCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            if (isSelected) {
                return this;
            }
            OfTableModel m = (OfTableModel) table.getModel();
            OpenFileEntry entry = m.getRow(row);
            if (entry == null) {
                setBackground(new Color(0xFAFAFA));
                setForeground(new Color(0x9E9E9E));
            } else {
                Color color = UiTheme.OPEN_FILE_COLORS[row % UiTheme.OPEN_FILE_COLORS.length];
                setForeground(UiTheme.TEXT_MAIN);
                if (column == 0) {
                    // 槽位列用文件的主题色，和 FAT 表 / 磁盘网格对应起来
                    setBackground(color);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(Color.WHITE);
                }
            }
            return this;
        }
    }
}
