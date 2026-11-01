package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 C —— 交互与视觉（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：右侧信息面板：显示当前选中项的关键信息（路径、属性、起始块、FAT 链摘要）
 *  上游依赖：FileSystemService、DirectoryEntry
 *  下游调用：MainFrame 在刷新时喂给它当前选中的目录项
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.util.Constants;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 右侧“文件信息”面板：显示当前选中的文件 / 目录的关键信息。
 * <p>
 * 内容全部来自磁盘上的真实数据（目录项 + FAT 链），不缓存、不臆造。
 *
 * @author 成员 C（负责 交互与视觉，约 20%）
 * @version 1.0
 */
public class SelectionInfoPanel extends JPanel {

    /** 业务门面：用来查 FAT 链、算块数 */
    private final FileSystemService service;

    /** 各个字段对应的值标签，按显示顺序排列 */
    private final Map<String, JLabel> valueLabels = new LinkedHashMap<>();

    public SelectionInfoPanel(FileSystemService service) {
        super(new BorderLayout());
        this.service = service;

        JLabel title = new JLabel("文件信息");
        title.setFont(UiTheme.FONT_TITLE);
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        add(title, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        content.setBackground(Color.WHITE);

        String[] fields = {"名称", "路径", "类型", "属性", "实际字节数", "占用块数", "起始盘块", "FAT 链", "打开状态"};
        GridBagConstraints gbc = new GridBagConstraints();
        int row = 0;
        for (String field : fields) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.anchor = GridBagConstraints.NORTHEAST;
            gbc.insets = new Insets(4, 0, 4, 8);
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            JLabel key = new JLabel(field + "：");
            key.setFont(UiTheme.FONT_SMALL);
            key.setForeground(UiTheme.TEXT_SUB);
            content.add(key, gbc);

            gbc.gridx = 1;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = new Insets(4, 0, 4, 0);
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            JLabel value = new JLabel("—");
            value.setFont(UiTheme.FONT_BASE);
            content.add(value, gbc);
            valueLabels.put(field, value);
            row++;
        }
        // 底部用一根“弹簧”把内容顶到上方
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weighty = 1;
        content.add(new JLabel(""), gbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.WHITE);
        wrapper.add(content, BorderLayout.NORTH);
        add(wrapper, BorderLayout.CENTER);

        setPreferredSize(new Dimension(280, 300));
        setMinimumSize(new Dimension(220, 200));
        setBackground(Color.WHITE);
    }

    /** 显示某个目录项的信息；entry 为 null 时清空 */
    public void showEntry(String parentPath, DirectoryEntry entry) {
        if (entry == null) {
            clear();
            return;
        }
        String path = "/".equals(parentPath) ? "/" + entry.getFullName() : parentPath + "/" + entry.getFullName();
        set("名称", entry.getFullName());
        set("路径", path);
        set("类型", entry.isDirectory() ? "目录" : "文件" + (entry.getExt().isEmpty() ? "" : "（." + entry.getExt() + "）"));
        set("属性", entry.getAttributeText() + "（字节值 " + entry.getAttribute() + "）");

        if (entry.isDirectory()) {
            int count = 0;
            try {
                count = service.listDirectory(path).size();
            } catch (IOException ignored) {
                count = -1;
            }
            set("实际字节数", "—（目录不存内容）");
            set("占用块数", "1 块（目录块）");
            set("起始盘块", String.valueOf(entry.getStartBlock()));
            set("FAT 链", entry.getStartBlock() + " → 255(EOF)");
            set("打开状态", "目录不能作为文件打开");
        } else {
            set("实际字节数", formatBytes(entry));
            set("占用块数", entry.getLengthBlocks() + " 块");
            set("起始盘块", String.valueOf(entry.getStartBlock()));
            set("FAT 链", formatChain(entry.getStartBlock()));
            boolean open = service.getOpenFileTable().isOpen(path);
            if (open) {
                var ofe = service.getOpenFileTable().find(path);
                set("打开状态", "已打开（" + ofe.getOperationText() + "）");
            } else {
                set("打开状态", "未打开");
            }
        }
        setTooltips(path);
    }

    /** 清空所有字段 */
    public void clear() {
        for (JLabel label : valueLabels.values()) {
            label.setText("—");
            label.setToolTipText(null);
        }
    }

    private void set(String field, String text) {
        JLabel label = valueLabels.get(field);
        if (label != null) {
            label.setText(text == null || text.isEmpty() ? "—" : text);
        }
    }

    private void setTooltips(String path) {
        // 长路径被裁掉时，鼠标悬停可以看到完整内容
        JLabel pathLabel = valueLabels.get("路径");
        if (pathLabel != null) {
            pathLabel.setToolTipText(path);
        }
    }

    /** 实际字节数：沿 FAT 链读到 '#' 为止 */
    private String formatBytes(DirectoryEntry entry) {
        try {
            int bytes = service.getFileByteLength(entry.getStartBlock());
            return bytes < 0 ? "读取失败" : bytes + " 字节";
        } catch (RuntimeException e) {
            return "读取失败";
        }
    }

    /** FAT 链显示成 "5 → 8 → 255(EOF)" 的形式 */
    private String formatChain(int startBlock) {
        try {
            int[] fat = service.getFatSnapshot();
            StringBuilder sb = new StringBuilder();
            int current = startBlock;
            int guard = 0;
            while (guard++ < Constants.TOTAL_BLOCKS) {
                sb.append(current);
                int next = fat[current];
                boolean end = next == Constants.FAT_EOF || next == Constants.FAT_SYSTEM
                        || next == Constants.FAT_FREE || next == Constants.FAT_BAD
                        || next < 0 || next >= Constants.TOTAL_BLOCKS;
                sb.append(" → ");
                if (end) {
                    sb.append(next == Constants.FAT_EOF ? "255(EOF)" : String.valueOf(next));
                    break;
                }
                current = next;
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "链读取失败";
        }
    }
}
