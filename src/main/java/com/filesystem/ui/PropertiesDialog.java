package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 C —— 交互与视觉（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：属性窗口：显示路径/类型/字节数/块数/起始块/FAT 链，并提供只读↔可读写切换
 *  上游依赖：FileSystemService、Constants、UiTheme
 *  下游调用：MainFrame 在『属性』菜单里弹出它
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.util.List;

/**
 * 文件属性窗口。
 * <p>
 * 显示文件名称、绝对路径、类型、属性、实际字节数、占用块数、起始盘块和完整 FAT 链，
 * 并可以在这里把文件属性在“只读 / 可读写”之间切换。
 * <p>
 * 属性修改会真正写回磁盘上的目录项（属性字节），而不是只在界面上显示一下。
 *
 * @author 成员 C（负责 交互与视觉，约 20%）
 * @version 1.0
 */
public class PropertiesDialog extends JDialog {

    /** 业务门面：读 FAT 链、改属性都要用它 */
    private final FileSystemService service;

    /** 目标文件的绝对路径 */
    private final String path;

    /** 打开窗口那一刻的目录项快照，用来显示名字、类型、起始块等固定信息 */
    private final DirectoryEntry entry;

    /** 属性改成功后的回调，让主窗口刷新列表和磁盘视图 */
    private final Runnable onChanged;

    /** 只读（属性值 1）：选中后保存，文件就再也写不进去了 */
    private final JRadioButton readOnlyRadio = new JRadioButton("只读（属性值 1）");

    /** 可读写（属性值 4）：默认状态 */
    private final JRadioButton normalRadio = new JRadioButton("可读写（属性值 4）");

    public PropertiesDialog(Window owner, FileSystemService service, String path,
                            DirectoryEntry entry, Runnable onChanged) {
        super(owner, "属性 - " + entry.getFullName(), ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.path = path;
        this.entry = entry;
        this.onChanged = onChanged;

        buildUI();
        setSize(520, 480);
        setMinimumSize(new Dimension(440, 420));
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        content.setBackground(Color.WHITE);

        String[] keys = {"文件名称", "绝对路径", "类型", "属性", "实际字节数", "占用块数", "起始盘块"};
        String[] values = new String[keys.length];

        values[0] = entry.getFullName();
        values[1] = path;
        values[2] = entry.isDirectory() ? "目录" : "文件" + (entry.getExt().isEmpty() ? "" : "（扩展名 ." + entry.getExt() + "）");
        values[3] = entry.getAttributeText() + "（属性字节 = " + entry.getAttribute() + "）";

        if (entry.isDirectory()) {
            int count = 0;
            try {
                count = service.listDirectory(path).size();
            } catch (IOException ignored) {
                count = -1;
            }
            values[4] = "—（目录存放的是目录项，不存文本内容）";
            values[5] = "1 块（目录块），已使用 " + count + "/" + Constants.ENTRIES_PER_BLOCK + " 个目录项";
            values[6] = String.valueOf(entry.getStartBlock());
        } else {
            int bytes = service.getFileByteLength(entry.getStartBlock());
            values[4] = bytes < 0 ? "读取失败" : bytes + " 字节（读到结束符 '#' 为止）";
            values[5] = entry.getLengthBlocks() + " 块";
            values[6] = String.valueOf(entry.getStartBlock());
        }

        GridBagConstraints gbc = new GridBagConstraints();
        for (int i = 0; i < keys.length; i++) {
            gbc.gridx = 0;
            gbc.gridy = i;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.NORTHEAST;
            gbc.insets = new Insets(5, 0, 5, 10);
            JLabel key = new JLabel(keys[i] + "：");
            key.setFont(UiTheme.FONT_BASE);
            key.setForeground(UiTheme.TEXT_SUB);
            content.add(key, gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = new Insets(5, 0, 5, 0);
            JLabel value = new JLabel(values[i]);
            value.setFont(UiTheme.FONT_BASE);
            content.add(value, gbc);
        }

        // ---------- FAT 链 ----------
        gbc.gridx = 0;
        gbc.gridy = keys.length;
        gbc.anchor = GridBagConstraints.NORTHEAST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel chainKey = new JLabel("FAT 链：");
        chainKey.setFont(UiTheme.FONT_BASE);
        chainKey.setForeground(UiTheme.TEXT_SUB);
        content.add(chainKey, gbc);

        JTextArea chainArea = new JTextArea(fatChainText());
        chainArea.setEditable(false);
        chainArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        chainArea.setBackground(new Color(0xF7F9FA));
        chainArea.setBorder(BorderFactory.createLineBorder(new Color(0xCFD8DC)));
        chainArea.setLineWrap(true);
        chainArea.setRows(2);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        content.add(chainArea, gbc);

        // ---------- 属性切换 ----------
        gbc.gridx = 0;
        gbc.gridy = keys.length + 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(14, 0, 4, 0);
        JPanel attrPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        attrPanel.setBackground(Color.WHITE);
        attrPanel.add(new JLabel("修改属性："));
        ButtonGroup group = new ButtonGroup();
        group.add(readOnlyRadio);
        group.add(normalRadio);
        attrPanel.add(readOnlyRadio);
        attrPanel.add(normalRadio);
        content.add(attrPanel, gbc);

        // 目录属性固定为 8，不允许改
        boolean editable = !entry.isDirectory() && !service.getOpenFileTable().isOpen(path);
        readOnlyRadio.setEnabled(editable);
        normalRadio.setEnabled(editable);
        if (entry.isDirectory()) {
            readOnlyRadio.setSelected(false);
            normalRadio.setSelected(false);
        } else if (entry.isReadOnly()) {
            readOnlyRadio.setSelected(true);
        } else {
            normalRadio.setSelected(true);
        }

        // ---------- 底部说明与按钮 ----------
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(4, 16, 12, 16));
        String tip = entry.isDirectory()
                ? "目录属性固定为 8（目录位），不能修改。"
                : (editable ? "只读文件的写入请求会在核心层被拒绝，不只是界面变灰。"
                : "文件正在打开，请先关闭文件再修改属性。");
        JLabel tipLabel = new JLabel(tip);
        tipLabel.setFont(UiTheme.FONT_SMALL);
        tipLabel.setForeground(UiTheme.TEXT_SUB);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton saveButton = new JButton("保存属性");
        saveButton.setEnabled(editable);
        saveButton.addActionListener(e -> saveAttribute());
        JButton closeButton = new JButton("关闭");
        closeButton.addActionListener(e -> dispose());
        buttons.add(saveButton);
        buttons.add(closeButton);

        south.add(tipLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.WHITE);
        wrapper.add(content, BorderLayout.NORTH);
        add(wrapper, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    /** 把 FAT 链拼成 "5 → 8 → 255(EOF)" */
    private String fatChainText() {
        try {
            List<Integer> chain = service.getFatChain(path);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chain.size(); i++) {
                sb.append(chain.get(i));
                if (i < chain.size() - 1) {
                    sb.append(" → ");
                }
            }
            sb.append(" → 255(EOF)");
            sb.append("　（共 ").append(chain.size()).append(" 块）");
            return sb.toString();
        } catch (IOException | FileSystemException e) {
            return "读取失败：" + e.getMessage();
        }
    }

    /** 保存属性修改 */
    private void saveAttribute() {
        int newAttribute = readOnlyRadio.isSelected() ? Constants.ATTR_READ_ONLY : Constants.ATTR_NORMAL;
        try {
            service.changeAttribute(path, newAttribute);
            if (onChanged != null) {
                onChanged.run();
            }
            JOptionPane.showMessageDialog(this, "属性已修改并写回磁盘目录项。",
                    "修改成功", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (IOException | FileSystemException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "修改失败", JOptionPane.ERROR_MESSAGE);
        }
    }
}
