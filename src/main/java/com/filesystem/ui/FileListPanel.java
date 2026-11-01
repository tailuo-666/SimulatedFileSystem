package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 C —— 交互与视觉（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：中央文件列表：JTable 展示 名称/类型/属性/大小/起始块/状态，双击进入或打开，右键菜单
 *  上游依赖：FileSystemService、UiTheme、FileIcons
 *  下游调用：MainFrame 拿它的选中行去开对话框
 * ========================================================================== */

import com.filesystem.model.DirectoryEntry;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 中央文件列表面板。
 * <p>
 * 用 JTable 显示当前目录下的所有文件和子目录，列包括：
 * 名称 / 类型 / 属性 / 大小 / 起始块 / 状态。
 * <p>
 * 这个类只负责“显示 + 把鼠标动作转成回调”，不认识文件系统内部结构，
 * 真正的操作由 MainFrame 拿去调用 FileSystemService 完成。
 *
 * @author 成员 C（负责 交互与视觉，约 20%）
 * @version 1.0
 */
public class FileListPanel extends JPanel {

    /** 文件列表的事件回调 */
    public interface Listener {
        /** 双击某一行（目录则进入，文件则打开） */
        void entryDoubleClicked(DirectoryEntry entry);

        /** 选中行发生变化，entry 可能为 null（表示没有选中） */
        void entrySelected(DirectoryEntry entry);

        /** 请求弹出右键菜单；entry 为 null 表示点在空白区域 */
        void contextMenuRequested(DirectoryEntry entry, Component invoker, int x, int y);
    }

    /** 表格的一行数据 */
    public static class Row {
        /** 这一行对应的目录项（名字、属性、起始块、块数都在里面） */
        private final DirectoryEntry entry;

        /** "大小"列的文字：文件显示 "2块 / 71字节"，目录显示 "-" */
        private final String sizeText;

        /** "状态"列的文字：正常 / 已打开 / 只读 */
        private final String statusText;

        /** 该文件当前是否在 OFT 里（打开中的文件不允许删除） */
        private final boolean opened;

        public Row(DirectoryEntry entry, String sizeText, String statusText, boolean opened) {
            this.entry = entry;
            this.sizeText = sizeText;
            this.statusText = statusText;
            this.opened = opened;
        }

        public DirectoryEntry getEntry() {
            return entry;
        }
    }

    /** 表头六列，顺序和 RowTableModel.getValueAt 里的下标一一对应 */
    private static final String[] COLUMNS = {"名称", "类型", "属性", "大小", "起始块", "状态"};

    /** 表格数据模型：把 Row 列表翻译成 6 列文字 */
    private final RowTableModel model = new RowTableModel();

    /** Swing 的表格组件 */
    private final JTable table;

    /** 底部的提示条，显示"共 4 项，双击目录进入……"这类说明 */
    private final JLabel hintLabel;

    /** 选中行变化 / 右键时通知主窗口 */
    private Listener listener;

    public FileListPanel() {
        super(new BorderLayout());

        // ---------- 顶部标题 ----------
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JLabel title = new JLabel("文件列表");
        title.setFont(UiTheme.FONT_TITLE);
        hintLabel = new JLabel("共 0 项");
        hintLabel.setFont(UiTheme.FONT_SMALL);
        hintLabel.setForeground(UiTheme.TEXT_SUB);
        header.add(title, BorderLayout.WEST);
        header.add(hintLabel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ---------- 表格 ----------
        table = new JTable(model);
        table.setRowHeight(26);
        table.setFont(UiTheme.FONT_BASE);
        table.getTableHeader().setFont(UiTheme.FONT_BOLD);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xE0E0E0));
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);

        int[] widths = {170, 80, 70, 110, 70, 100};
        for (int i = 0; i < widths.length; i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(widths[i]);
        }

        // 名称列：根据是文件还是目录画不同的图标；只读文件用带红点的图标
        table.getColumnModel().getColumn(0).setCellRenderer(new NameCellRenderer());
        // 其余列统一左对齐 + 斑马纹
        DefaultTableCellRenderer plain = new PlainCellRenderer();
        for (int i = 1; i < COLUMNS.length; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(plain);
        }

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingConstants.LEFT == MouseEvent.BUTTON1) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && listener != null) {
                        listener.entryDoubleClicked(model.getRow(row).entry);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || listener == null) {
                return;
            }
            listener.entrySelected(getSelectedEntry());
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        setMinimumSize(new Dimension(320, 200));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 刷新表格数据 */
    public void setRows(List<Row> rows) {
        model.setRows(rows);
        hintLabel.setText("共 " + rows.size() + " 项（目录最多 " + com.filesystem.util.Constants.ENTRIES_PER_BLOCK + " 项）");
        table.clearSelection();
    }

    /** 当前选中的目录项，没有选中返回 null */
    public DirectoryEntry getSelectedEntry() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.getRowCount()) {
            return null;
        }
        return model.getRow(row).entry;
    }

    /** 选中第一行（刷新后让右侧“文件信息”面板有内容可显示） */
    public void selectFirstRow() {
        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
            table.scrollRectToVisible(table.getCellRect(0, 0, true));
        }
    }

    /** 按名称选中某一行（新建 / 重命名之后用来把光标定位到新项目上） */
    public void selectByName(String name) {
        for (int i = 0; i < model.getRowCount(); i++) {
            if (model.getRow(i).entry.getFullName().equalsIgnoreCase(name)) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger() || listener == null) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
            listener.contextMenuRequested(model.getRow(row).entry, table, e.getX(), e.getY());
        } else {
            table.clearSelection();
            listener.contextMenuRequested(null, table, e.getX(), e.getY());
        }
    }

    // ==================== 表格模型与渲染器 ====================

    /** 表格数据模型：把 Row 列表映射成 6 列 */
    private static class RowTableModel extends AbstractTableModel {
        /** 当前要显示的所有行；切换目录时整体替换并触发重画 */
        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            fireTableDataChanged();
        }

        Row getRow(int index) {
            return rows.get(index);
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
            return false; // 所有修改都走菜单 / 对话框，表格本身不支持直接改
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            DirectoryEntry entry = row.entry;
            switch (columnIndex) {
                case 0:
                    return entry.getFullName();
                case 1:
                    return entry.getTypeText();
                case 2:
                    return entry.getAttributeText();
                case 3:
                    return row.sizeText;
                case 4:
                    return entry.getStartBlock();
                case 5:
                    return row.statusText;
                default:
                    return "";
            }
        }
    }

    /** 名称列的渲染器：加图标、已打开的文件加粗 */
    private static class NameCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            RowTableModel model = (RowTableModel) table.getModel();
            Row data = model.getRow(row);
            DirectoryEntry entry = data.entry;

            if (entry.isDirectory()) {
                setIcon(FileIcons.FOLDER);
            } else if (entry.isReadOnly()) {
                setIcon(FileIcons.FILE_READ_ONLY);
            } else {
                setIcon(FileIcons.FILE);
            }
            setFont(data.opened ? UiTheme.FONT_BOLD : UiTheme.FONT_BASE);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            return this;
        }
    }

    /** 普通列渲染器：选中时用系统颜色，未选中时保持白底 */
    private static class PlainCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            if (!isSelected) {
                setBackground(Color.WHITE);
                setForeground(UiTheme.TEXT_MAIN);
            }
            return this;
        }
    }
}
