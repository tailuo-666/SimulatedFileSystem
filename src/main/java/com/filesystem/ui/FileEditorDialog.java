package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 C —— 交互与视觉（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：文件编辑窗口：只读判断、追加/覆盖模式切换、写入后刷新磁盘内容显示
 *  上游依赖：FileSystemService、UiTheme
 *  下游调用：MainFrame 在『打开文件』时弹出它
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.model.OpenFileEntry;
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
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;

/**
 * 文件编辑窗口（独立的非模态对话框）。
 * <p>
 * 界面分成三块，故意做得很直白，避免用户去理解磁盘块号之类的东西：
 * <ol>
 *   <li><b>上</b>：当前文件内容 —— 每次刷新都从磁盘重新读取，用来证明“数据真的落盘了”；</li>
 *   <li><b>中</b>：写入内容 —— 用户在这里打字；</li>
 *   <li><b>下</b>：选择“追加写入”还是“覆盖写入”，点按钮执行。</li>
 * </ol>
 * 只读文件打开后，输入区和写入按钮都会被禁用（真正的拦截在核心层，这里只是提前提示）。
 *
 * @author 成员 C（负责 交互与视觉，约 20%）
 * @version 1.0
 */
public class FileEditorDialog extends JDialog {

    /** 写入模式 */
    public enum WriteMode {
        APPEND("追加写入", "把下面框里的文字接到文件末尾"),
        OVERWRITE("覆盖写入", "用下面框里的文字完全替换文件内容");

        /** 按钮上显示的名字，例如 "追加写入" */
        private final String text;

        /** 选中这个模式时给用户的一句解释，显示在输入框上方 */
        private final String hint;

        WriteMode(String text, String hint) {
            this.text = text;
            this.hint = hint;
        }
    }

    /** 业务门面：读内容、写内容、查属性、关闭文件都通过它 */
    private final FileSystemService service;

    /** 本窗口正在编辑的文件绝对路径，关窗之前不会变 */
    private final String path;

    /**
     * 内容被改动后要通知谁。
     * <p>
     * 用 Runnable 而不是直接持有 MainFrame，是为了让本窗口不依赖主窗口，
     * 主窗口传一个"刷新界面"的 lambda 进来就行（回调 / 观察者模式的最小写法）。
     */
    private final Runnable onChanged;

    /** 上半部分：文件当前在磁盘上的真实内容，只读展示，用户改不了 */
    private final JTextArea currentArea = new JTextArea();

    /** 下半部分：用户输入的新内容，写入时才真正落盘 */
    private final JTextArea inputArea = new JTextArea();

    /** 追加模式单选钮（默认选中）：新内容接到原内容后面 */
    private final JRadioButton appendRadio = new JRadioButton("追加写入（APPEND）", true);

    /** 覆盖模式单选钮：新内容完全替换原内容 */
    private final JRadioButton overwriteRadio = new JRadioButton("覆盖写入（OVERWRITE）");

    /** 操作结果提示（"写入 5 字节" / "文件只读，禁止写入"） */
    private final JLabel statusLabel = new JLabel(" ");

    /** 当前模式的说明文字，随单选钮切换而变 */
    private final JLabel hintLabel = new JLabel(" ");

    /** 显示文件属性（只读 / 可读写），只读时整块会变红提醒 */
    private final JLabel attrLabel = new JLabel(" ");

    /** 执行写入按钮：只读文件时会被禁用 */
    private final JButton writeButton = new JButton("执行写入");

    /** 重新从磁盘读一遍内容（用于确认"真的写进去了"） */
    private final JButton refreshButton = new JButton("刷新");

    /** 关闭文件：把 OFT 表项移除，顺便把内容刷回磁盘 */
    private final JButton closeFileButton = new JButton("关闭文件");

    /** 文件当前是否仍处于打开状态 */
    private boolean fileOpen;

    public FileEditorDialog(Window owner, FileSystemService service, String path,
                           WriteMode initialMode, Runnable onChanged) {
        super(owner, "文件编辑 - " + path, ModalityType.MODELESS);
        this.service = service;
        this.path = path;
        this.onChanged = onChanged;

        buildUI();
        selectMode(initialMode);
        reloadFromDisk();
        // 构造时 currentArea 还是空的，这里再补一次：覆盖模式先把当前内容填进输入框
        if (overwriteRadio.isSelected() && inputArea.getText().isEmpty()) {
            inputArea.setText(currentArea.getText());
        }
        refreshState();

        setSize(760, 600);
        setMinimumSize(new Dimension(560, 420));
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        // ---------- 顶部：路径 / 属性 ----------
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel pathLabel = new JLabel("路径：" + path);
        pathLabel.setFont(UiTheme.FONT_BOLD);
        attrLabel.setFont(UiTheme.FONT_SMALL);
        attrLabel.setForeground(UiTheme.TEXT_SUB);
        JPanel left = new JPanel(new BorderLayout());
        left.add(pathLabel, BorderLayout.NORTH);
        left.add(attrLabel, BorderLayout.SOUTH);
        header.add(left, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        // ---------- 中部：当前内容 + 写入内容 ----------
        currentArea.setEditable(false);
        currentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        currentArea.setLineWrap(true);
        currentArea.setWrapStyleWord(false);
        currentArea.setBackground(new Color(0xFAFAFA));

        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(false);

        JPanel currentPanel = titledPanel("当前文件内容（每次刷新都从模拟磁盘重新读取）", currentArea);
        JPanel inputPanel = titledPanel("要写入的内容", inputArea);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, currentPanel, inputPanel);
        split.setResizeWeight(0.45);
        split.setDividerLocation(220);
        add(split, BorderLayout.CENTER);

        // ---------- 底部：模式 + 按钮 ----------
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));

        ButtonGroup group = new ButtonGroup();
        group.add(appendRadio);
        group.add(overwriteRadio);
        appendRadio.setFont(UiTheme.FONT_BASE);
        overwriteRadio.setFont(UiTheme.FONT_BASE);
        appendRadio.addActionListener(e -> onModeChanged());
        overwriteRadio.addActionListener(e -> onModeChanged());

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        modePanel.add(new JLabel("写入模式："));
        modePanel.add(appendRadio);
        modePanel.add(overwriteRadio);
        modePanel.add(hintLabel);
        hintLabel.setFont(UiTheme.FONT_SMALL);
        hintLabel.setForeground(UiTheme.TEXT_SUB);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        writeButton.setFont(UiTheme.FONT_BASE);
        writeButton.addActionListener(e -> doWrite());
        refreshButton.addActionListener(e -> reloadFromDisk());
        closeFileButton.addActionListener(e -> doCloseFile());
        buttonPanel.add(refreshButton);
        buttonPanel.add(closeFileButton);
        buttonPanel.add(writeButton);

        statusLabel.setFont(UiTheme.FONT_SMALL);
        statusLabel.setForeground(UiTheme.TEXT_SUB);

        // 状态文字和按钮放在同一行（状态靠左、按钮靠右），模式选择单独占一行，
        // 这样窗口变窄时也不会把按钮挤没
        JPanel actionRow = new JPanel(new BorderLayout());
        actionRow.add(statusLabel, BorderLayout.WEST);
        actionRow.add(buttonPanel, BorderLayout.EAST);

        south.add(modePanel, BorderLayout.NORTH);
        south.add(actionRow, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        onModeChanged();
    }

    /** 带标题的滚动文本区 */
    private JPanel titledPanel(String title, JTextArea area) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        JLabel label = new JLabel(title);
        label.setFont(UiTheme.FONT_SMALL);
        label.setForeground(UiTheme.TEXT_SUB);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
        panel.add(label, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xCFD8DC)));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void selectMode(WriteMode mode) {
        if (mode == WriteMode.OVERWRITE) {
            overwriteRadio.setSelected(true);
        } else {
            appendRadio.setSelected(true);
        }
        onModeChanged();
    }

    /** 切换模式时更新提示；从追加切到覆盖时，把当前内容填进输入框方便修改 */
    private void onModeChanged() {
        boolean overwrite = overwriteRadio.isSelected();
        hintLabel.setText("提示：" + (overwrite ? WriteMode.OVERWRITE.hint : WriteMode.APPEND.hint));
        if (overwrite && inputArea.getText().isEmpty()) {
            inputArea.setText(currentArea.getText());
        }
    }

    /** 从磁盘重新读出当前内容并刷新界面状态 */
    private void reloadFromDisk() {
        try {
            DirectoryEntry entry = service.getEntry(path);
            currentArea.setText(service.loadContentForDisplay(path));
            currentArea.setCaretPosition(0);
            int bytes = service.getFileByteLength(entry.getStartBlock());
            attrLabel.setText("属性：" + entry.getAttributeText()
                    + "　　实际字节数：" + bytes
                    + "　　占用块数：" + entry.getLengthBlocks()
                    + "　　起始块：" + entry.getStartBlock());
            status("已从磁盘刷新（" + bytes + " 字节）");
        } catch (IOException | FileSystemException e) {
            status("刷新失败：" + e.getMessage());
        }
        refreshState();
    }

    /** 根据文件是否打开、是否只读来决定哪些控件可用 */
    private void refreshState() {
        OpenFileEntry openFile = service.getOpenFileTable().find(path);
        fileOpen = openFile != null;
        boolean readOnly = false;
        try {
            readOnly = service.getEntry(path).isReadOnly();
        } catch (IOException | FileSystemException ignored) {
            // 文件可能已被删除
        }
        boolean canWrite = fileOpen && openFile.isWriteMode() && !readOnly;

        inputArea.setEditable(canWrite);
        inputArea.setBackground(canWrite ? Color.WHITE : new Color(0xF5F5F5));
        writeButton.setEnabled(canWrite);
        appendRadio.setEnabled(canWrite);
        overwriteRadio.setEnabled(canWrite);
        closeFileButton.setEnabled(fileOpen);

        if (!fileOpen) {
            status("文件当前未打开：可以点“关闭文件”前请先在列表中打开它，或直接关闭本窗口。");
        } else if (!openFile.isWriteMode()) {
            status("文件以【读方式】打开，只能查看不能修改。");
        } else if (readOnly) {
            status("文件是【只读】属性，禁止写入。");
        } else {
            status("可以写入：选择模式后在下方输入内容。");
        }
    }

    /** 执行写入 */
    private void doWrite() {
        String text = inputArea.getText();
        boolean overwrite = overwriteRadio.isSelected();
        if (!overwrite && text.isEmpty()) {
            status("追加的内容为空，无需写入。");
            return;
        }
        if (text.indexOf(Constants.EOF_CHAR) >= 0) {
            JOptionPane.showMessageDialog(this,
                    "正文中不允许出现结束符 '#'，请先删除后再写入。",
                    "内容不合法", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (overwrite && text.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "覆盖写入的内容为空，执行后文件内容将被清空，确定继续吗？",
                    "确认覆盖", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            int mode = overwrite ? Constants.WRITE_OVERWRITE : Constants.WRITE_APPEND;
            int bytes = service.writeFile(path, text, mode);
            if (!overwrite) {
                inputArea.setText(""); // 追加成功后清空输入框，避免再点一次重复追加
            }
            reloadFromDisk();
            status((overwrite ? "覆盖写入成功" : "追加写入成功") + "，文件现有 " + bytes + " 字节");
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (IOException | FileSystemException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "写入失败", JOptionPane.ERROR_MESSAGE);
            status("写入失败：" + e.getMessage());
        }
    }

    /** 关闭文件（从 OFT 中移除） */
    private void doCloseFile() {
        try {
            service.closeFile(path);
            status("文件已关闭，已从已打开文件表中移除。");
            reloadFromDisk();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (IOException | FileSystemException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "关闭失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void status(String text) {
        statusLabel.setText(text);
    }
}
