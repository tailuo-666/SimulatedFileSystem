package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 B —— 主窗口与导航（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：主窗口：菜单栏、工具栏、三栏 JSplitPane、状态栏，以及所有菜单/右键动作的串场逻辑
 *  上游依赖：FileSystemService、DirectoryTreePanel、FileListPanel、三个可视化面板、各对话框
 *  下游调用：程序入口；面板之间靠它转发事件
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.core.PathResolver;
import com.filesystem.model.BlockInfo;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.model.OpenFileEntry;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主窗口 —— 界面层的总调度。
 * <p>
 * 布局（用 JSplitPane 组合，可以自由拖动分隔条，窗口拉伸时不会重叠）：
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 菜单栏                                                        │
 * │ 工具栏：新建文件夹 新建文件 刷新 格式化 上一级   当前路径       │
 * ├──────────┬──────────────────────────┬────────────────────────┤
 * │ 目录树    │        文件列表           │ 文件信息 / 已打开文件   │
 * │ (JTree)  │        (JTable)          │ / 磁盘块字节（选项卡）   │
 * ├──────────┴──────────────────────────┴────────────────────────┤
 * │ 磁盘空间可视化 / FAT 表（选项卡）                              │
 * ├──────────────────────────────────────────────────────────────┤
 * │ 状态栏：空闲块 / 已用块 / 系统块 / 坏块 / 使用率 / 映像路径      │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 * 所有按钮和菜单项都对应真实的核心层调用，界面本身不保存任何“假数据”。
 *
 * @author 成员 B（负责 主窗口与导航，约 20%）
 * @version 1.0
 */
public class MainFrame extends JFrame
        implements DirectoryTreePanel.Listener, FileListPanel.Listener,
        DiskMapPanel.Listener, OpenFileTablePanel.Listener {

    /** 业务门面：主窗口所有动作最终都汇到这一个对象上 */
    private final FileSystemService service;

    /** 当前浏览的目录 */
    private String currentPath = Constants.ROOT_PATH;

    /** 正在刷新时置 true，用来避免“刷新 → 选中事件 → 再刷新”的死循环 */
    private boolean refreshing;

    /*
     * 下面 7 个面板分别由成员 B / C / D 实现，主窗口只负责"摆位置 + 转发事件 + 统一刷新"。
     * 它们都是先声明、在 buildUI() 里才 new，所以不能加 final。
     */

    /** 左侧：目录树（成员 B） */
    private DirectoryTreePanel treePanel;

    /** 中央：文件列表（成员 C） */
    private FileListPanel fileListPanel;

    /** 右侧上：当前选中项的信息（成员 C） */
    private SelectionInfoPanel infoPanel;

    /** 右侧中：已打开文件表 OFT（成员 D） */
    private OpenFileTablePanel openFilePanel;

    /** 底部选项卡之一：磁盘块网格（成员 D） */
    private DiskMapPanel diskMapPanel;

    /** 底部选项卡之二：FAT 表（成员 D） */
    private FatTablePanel fatTablePanel;

    /** 底部选项卡之三：磁盘块字节查看器（成员 D） */
    private BlockInspectorPanel inspectorPanel;

    /** 右侧的选项卡容器（信息 / OFT / 字节查看器 切换用） */
    private JTabbedPane rightTabs;

    /** 工具栏右侧的"当前路径：/DOC"文字 */
    private JLabel pathLabel;

    /** 最底部状态栏：空闲块 / 已用块 / 使用率 / 磁盘映像路径 */
    private JLabel statusLabel;

    /** 状态栏最右边显示的 disk.img 绝对路径，方便答辩时证明数据真在文件里 */
    private JLabel imageLabel;

    /** 最近一次算出的 128 个块信息（点击块时直接复用，避免重复遍历目录树） */
    private List<BlockInfo> lastBlockInfos = new ArrayList<>();

    public MainFrame(FileSystemService service) {
        this.service = service;

        setTitle("模拟磁盘文件系统 —— 课程设计");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        buildUI();
        refreshAll(Constants.ROOT_PATH, null);

        // 窗口尺寸按屏幕自适应，保证在 1366×768 这类小屏上也放得下
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(1460, screen.width - 80);
        int height = Math.min(920, screen.height - 90);
        setSize(width, Math.max(660, height));
        setMinimumSize(new Dimension(1080, 660));
        setLocationRelativeTo(null);
    }

    // ==================================================================
    //  一、界面搭建
    // ==================================================================

    private void buildUI() {
        setJMenuBar(buildMenuBar());

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolBar(), BorderLayout.NORTH);

        // ---------- 左：目录树 ----------
        treePanel = new DirectoryTreePanel(service);
        treePanel.setListener(this);

        // ---------- 中：文件列表 ----------
        fileListPanel = new FileListPanel();
        fileListPanel.setListener(this);

        // ---------- 右：文件信息 / 已打开文件 / 磁盘块字节 ----------
        infoPanel = new SelectionInfoPanel(service);
        openFilePanel = new OpenFileTablePanel(service);
        openFilePanel.setListener(this);
        inspectorPanel = new BlockInspectorPanel(service);

        rightTabs = new JTabbedPane();
        rightTabs.addTab("文件信息", infoPanel);
        rightTabs.addTab("已打开文件", openFilePanel);
        rightTabs.addTab("磁盘块字节", inspectorPanel);
        rightTabs.setFont(UiTheme.FONT_BASE);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileListPanel, rightTabs);
        rightSplit.setResizeWeight(1.0);          // 窗口变宽时，多出来的宽度给文件列表
        rightSplit.setDividerLocation(760);
        rightSplit.setContinuousLayout(true);
        rightSplit.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, rightSplit);
        topSplit.setResizeWeight(0.16);
        topSplit.setDividerLocation(230);
        topSplit.setContinuousLayout(true);
        topSplit.setBorder(BorderFactory.createEmptyBorder());

        // ---------- 下：磁盘空间 + FAT 表 ----------
        diskMapPanel = new DiskMapPanel();
        diskMapPanel.setListener(this);
        fatTablePanel = new FatTablePanel();

        JScrollPane mapScroll = new JScrollPane(diskMapPanel);
        mapScroll.setBorder(BorderFactory.createEmptyBorder());
        mapScroll.getVerticalScrollBar().setUnitIncrement(16);

        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.setFont(UiTheme.FONT_BASE);
        bottomTabs.addTab("磁盘空间（16×8 网格）", mapScroll);
        bottomTabs.addTab("FAT 表（128 项）", fatTablePanel);

        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, bottomTabs);
        verticalSplit.setResizeWeight(0.62);
        verticalSplit.setDividerLocation(430);
        verticalSplit.setContinuousLayout(true);
        verticalSplit.setBorder(BorderFactory.createEmptyBorder());

        root.add(verticalSplit, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("文件");
        fileMenu.add(menuItem("新建文件…", "Ctrl+N", this::actionNewFile));
        fileMenu.add(menuItem("新建文件夹…", "Ctrl+Shift+N", this::actionNewDirectory));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("打开并编辑（读写）", "Enter", this::actionOpenSelectedForWrite));
        fileMenu.add(menuItem("以只读方式打开", null, this::actionOpenSelectedForRead));
        fileMenu.add(menuItem("显示文件内容（typefile）", null, this::actionShowContent));
        fileMenu.add(menuItem("关闭文件（close_file）", null, this::actionCloseSelectedFile));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("重命名…", "F2", this::actionRename));
        fileMenu.add(menuItem("删除", "Delete", this::actionDelete));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("退出", null, this::shutdown));
        bar.add(fileMenu);

        JMenu diskMenu = new JMenu("磁盘");
        diskMenu.add(menuItem("刷新", "F5", () -> refreshAll(currentPath, null)));
        diskMenu.addSeparator();
        diskMenu.add(menuItem("格式化磁盘…", null, this::actionFormat));
        diskMenu.add(menuItem("磁盘统计…", null, this::actionShowStatistics));
        bar.add(diskMenu);

        JMenu helpMenu = new JMenu("帮助");
        helpMenu.add(menuItem("使用说明与演示流程", null, this::actionShowHelp));
        helpMenu.add(menuItem("关于", null, this::actionShowAbout));
        bar.add(helpMenu);
        return bar;
    }

    private JToolBar buildToolBar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE0E0E0)));

        toolbar.add(toolButton("新建文件夹", this::actionNewDirectory));
        toolbar.add(toolButton("新建文件", this::actionNewFile));
        toolbar.addSeparator();
        toolbar.add(toolButton("刷新", () -> refreshAll(currentPath, null)));
        toolbar.add(toolButton("上一级", this::actionGoUp));
        toolbar.addSeparator();
        toolbar.add(toolButton("格式化磁盘", this::actionFormat));

        pathLabel = new JLabel("当前路径：/");
        pathLabel.setFont(UiTheme.FONT_BOLD);
        pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 12));
        toolbar.add(javax.swing.Box.createHorizontalGlue());
        toolbar.add(pathLabel);
        return toolbar;
    }

    private JPanel buildStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        statusLabel = new JLabel(" ");
        statusLabel.setFont(UiTheme.FONT_SMALL);
        imageLabel = new JLabel("磁盘映像：" + service.getDiskImagePath());
        imageLabel.setFont(UiTheme.FONT_SMALL);
        imageLabel.setForeground(UiTheme.TEXT_SUB);
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(imageLabel, BorderLayout.EAST);
        return statusBar;
    }

    private JMenuItem menuItem(String text, String accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(UiTheme.FONT_BASE);
        if (accelerator != null) {
            KeyStroke stroke = KeyStroke.getKeyStroke(accelerator);
            if (stroke != null) {
                item.setAccelerator(stroke);
            }
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private JButton toolButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFont(UiTheme.FONT_BASE);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(4, 10, 4, 10));
        button.addActionListener(e -> action.run());
        return button;
    }

    // ==================================================================
    //  二、刷新（所有显示都从这里重新读磁盘）
    // ==================================================================

    /**
     * 刷新整个界面。
     *
     * @param path       要显示的目录
     * @param selectName 刷新后要选中的文件名（可以为 null）
     */
    private void refreshAll(String path, String selectName) {
        if (refreshing) {
            return;
        }
        refreshing = true;
        try {
            currentPath = PathResolver.normalize(path);

            // 1. 目录树
            treePanel.reload(currentPath);

            // 2. 文件列表
            List<DirectoryEntry> entries = service.listDirectory(currentPath);
            fileListPanel.setRows(buildRows(entries));
            if (selectName != null) {
                fileListPanel.selectByName(selectName);
            } else {
                fileListPanel.selectFirstRow();
            }

            // 3. 磁盘块信息（一次算出，供网格 / FAT 表 / 字节查看器共用）
            lastBlockInfos = service.buildBlockInfos();
            diskMapPanel.setBlockInfos(lastBlockInfos);
            fatTablePanel.setBlockInfos(lastBlockInfos);

            // 4. 已打开文件表
            openFilePanel.refresh();

            // 5. 字节查看器同步刷新（如果之前看过某一块）
            int selectedBlock = diskMapPanel.getSelectedBlock();
            if (selectedBlock >= 0) {
                inspectorPanel.showBlock(selectedBlock, findBlockInfo(selectedBlock));
            }

            // 6. 路径与状态栏
            pathLabel.setText("当前路径：" + currentPath);
            updateStatusBar(entries.size());

            // 7. 右侧信息面板：显示当前选中项的详情（刷新后默认选中第一行）
            DirectoryEntry selected = fileListPanel.getSelectedEntry();
            if (selected != null) {
                infoPanel.showEntry(currentPath, selected);
            } else {
                infoPanel.clear();
            }
        } catch (IOException | FileSystemException e) {
            showError("刷新失败：" + e.getMessage());
        } finally {
            refreshing = false;
        }
    }

    /**
     * 切换到指定目录并刷新整个界面。
     * <p>
     * 提供给外部调用（例如自动化测试、其它窗口操作完成后回到某个目录）。
     */
    public void openDirectory(String path) {
        refreshAll(path, null);
    }

    /** 把目录项转成表格行，顺便算出大小和状态文字 */
    private List<FileListPanel.Row> buildRows(List<DirectoryEntry> entries) {
        List<FileListPanel.Row> rows = new ArrayList<>();
        for (DirectoryEntry entry : entries) {
            String path = PathResolver.join(currentPath, entry.getFullName());
            boolean open = service.getOpenFileTable().isOpen(path);
            String sizeText;
            String statusText;

            if (entry.isDirectory()) {
                int count;
                try {
                    count = service.listDirectory(path).size();
                } catch (IOException | FileSystemException e) {
                    count = -1;
                }
                sizeText = count < 0 ? "?" : count + " 项";
                statusText = "目录";
            } else {
                int bytes = service.getFileByteLength(entry.getStartBlock());
                sizeText = entry.getLengthBlocks() + " 块 / " + (bytes < 0 ? "?" : bytes + " B");
                if (open) {
                    OpenFileEntry ofe = service.getOpenFileTable().find(path);
                    statusText = "已打开(" + (ofe != null && ofe.isWriteMode() ? "写" : "读") + ")";
                } else if (entry.isReadOnly()) {
                    statusText = "只读";
                } else {
                    statusText = "可读写";
                }
            }
            rows.add(new FileListPanel.Row(entry, sizeText, statusText, open));
        }
        return rows;
    }

    /** 状态栏：统计数字全部来自真实 FAT */
    private void updateStatusBar(int entryCount) {
        FileSystemService.DiskStatistics stat = service.getStatistics();
        statusLabel.setText(String.format(
                "总块数：%d ｜ 空闲块：%d ｜ 已用块：%d ｜ 系统保留块：%d ｜ 坏块：%d ｜ 使用率：%.1f%% ｜ 本目录 %d 项 ｜ 已打开 %d/%d",
                stat.getTotalBlocks(), stat.getFreeBlocks(), stat.getUsedBlocks(),
                stat.getSystemBlocks(), stat.getBadBlocks(), stat.getUsagePercent(),
                entryCount, service.getOpenFileTable().size(), Constants.MAX_OPEN_FILES));
        imageLabel.setText("磁盘映像：" + service.getDiskImagePath());
    }

    private BlockInfo findBlockInfo(int blockNumber) {
        for (BlockInfo info : lastBlockInfos) {
            if (info.getBlockNumber() == blockNumber) {
                return info;
            }
        }
        return null;
    }

    // ==================================================================
    //  三、目录树 / 文件列表 / 磁盘网格的回调
    // ==================================================================

    @Override
    public void onDirectorySelected(String path) {
        if (refreshing) {
            return;
        }
        refreshAll(path, null);
    }

    @Override
    public void entryDoubleClicked(DirectoryEntry entry) {
        String path = PathResolver.join(currentPath, entry.getFullName());
        if (entry.isDirectory()) {
            refreshAll(path, null);           // 双击目录：进入
        } else {
            openEditor(path, null);           // 双击文件：打开编辑窗口
        }
    }

    @Override
    public void entrySelected(DirectoryEntry entry) {
        if (refreshing) {
            return;
        }
        infoPanel.showEntry(currentPath, entry);
    }

    @Override
    public void contextMenuRequested(DirectoryEntry entry, Component invoker, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        if (entry == null) {
            // 空白区域
            menu.add(menuItem("新建文件…", null, this::actionNewFile));
            menu.add(menuItem("新建文件夹…", null, this::actionNewDirectory));
            menu.addSeparator();
            menu.add(menuItem("刷新", null, () -> refreshAll(currentPath, null)));
        } else if (entry.isDirectory()) {
            String path = PathResolver.join(currentPath, entry.getFullName());
            menu.add(menuItem("打开目录", null, () -> refreshAll(path, null)));
            menu.addSeparator();
            menu.add(menuItem("在此新建文件…", null, () -> {
                refreshAll(path, null);
                actionNewFile();
            }));
            menu.add(menuItem("在此新建文件夹…", null, () -> {
                refreshAll(path, null);
                actionNewDirectory();
            }));
            menu.addSeparator();
            menu.add(menuItem("重命名…", null, this::actionRename));
            menu.add(menuItem("删除目录", null, this::actionDelete));
            menu.addSeparator();
            menu.add(menuItem("属性…", null, this::actionShowProperties));
        } else {
            boolean open = service.getOpenFileTable().isOpen(PathResolver.join(currentPath, entry.getFullName()));
            boolean readOnly = entry.isReadOnly();

            JMenuItem openWrite = menuItem("打开（读写方式）", null, () -> openSelected(Constants.OPEN_WRITE));
            openWrite.setEnabled(!readOnly);
            JMenuItem openRead = menuItem("打开（只读方式）", null, () -> openSelected(Constants.OPEN_READ));
            menu.add(openWrite);
            menu.add(openRead);
            menu.add(menuItem("显示内容（typefile）", null, this::actionShowContent));
            menu.addSeparator();

            JMenuItem append = menuItem("追加写入…", null, this::actionAppendWrite);
            JMenuItem overwrite = menuItem("覆盖写入…", null, this::actionOverwriteWrite);
            append.setEnabled(!readOnly);
            overwrite.setEnabled(!readOnly);
            menu.add(append);
            menu.add(overwrite);
            menu.addSeparator();
            menu.add(menuItem("重命名…", null, this::actionRename));
            menu.add(menuItem("删除", null, this::actionDelete));
            JMenuItem changeAttr = menuItem("修改属性（只读/可读写）…", null, this::actionShowProperties);
            changeAttr.setEnabled(!open);
            menu.add(changeAttr);
            menu.addSeparator();
            JMenuItem closeItem = menuItem("关闭文件（close_file）", null, this::actionCloseSelectedFile);
            closeItem.setEnabled(open);
            menu.add(closeItem);
        }
        menu.show(invoker, x, y);
    }

    @Override
    public void onBlockSelected(int blockNumber) {
        BlockInfo info = findBlockInfo(blockNumber);
        inspectorPanel.showBlock(blockNumber, info);
        fatTablePanel.selectBlock(blockNumber);
        rightTabs.setSelectedComponent(inspectorPanel); // 自动切到字节查看器
    }

    @Override
    public void closeRequested(String path) {
        try {
            service.closeFile(path);
            refreshAll(currentPath, null);
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    @Override
    public void closeAllRequested() {
        try {
            service.closeAllFiles();
            refreshAll(currentPath, null);
            info("已关闭全部文件。");
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    // ==================================================================
    //  四、各种操作
    // ==================================================================

    /** 新建文件 */
    private void actionNewFile() {
        String name = JOptionPane.showInputDialog(this,
                "请输入文件名：\n主名最多 3 个字符，扩展名最多 2 个字符，例如 A.TX\n只能使用字母、数字、下划线",
                "新建文件", JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String trimmed = name.trim();
        try {
            String path = PathResolver.join(currentPath, trimmed);
            service.createFile(path, Constants.ATTR_NORMAL);
            refreshAll(currentPath, trimmed.toUpperCase());
            info("文件创建成功：" + path + "\n\n已按课程要求自动以【写方式】加入已打开文件表。");
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 新建目录 */
    private void actionNewDirectory() {
        String name = JOptionPane.showInputDialog(this,
                "请输入目录名：\n最多 3 个字符，只能使用字母、数字、下划线",
                "新建文件夹", JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String trimmed = name.trim();
        try {
            String path = PathResolver.join(currentPath, trimmed);
            service.createDirectory(path);
            refreshAll(currentPath, trimmed.toUpperCase());
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 上一级目录 */
    private void actionGoUp() {
        String parent = PathResolver.getParentPath(currentPath);
        if (parent != null) {
            refreshAll(parent, null);
        }
    }

    /** 打开选中项（读写方式） */
    private void actionOpenSelectedForWrite() {
        openSelected(Constants.OPEN_WRITE);
    }

    /** 打开选中项（只读方式） */
    private void actionOpenSelectedForRead() {
        openSelected(Constants.OPEN_READ);
    }

    private void openSelected(int mode) {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择一个文件。");
            return;
        }
        if (entry.isDirectory()) {
            refreshAll(PathResolver.join(currentPath, entry.getFullName()), null);
            return;
        }
        openEditor(PathResolver.join(currentPath, entry.getFullName()), mode);
    }

    /**
     * 打开编辑窗口。
     *
     * @param mode 打开方式；传 null 表示“按文件属性自动选择”
     */
    private void openEditor(String path, Integer mode) {
        try {
            if (!service.getOpenFileTable().isOpen(path)) {
                int actualMode = mode != null ? mode
                        : (service.getEntry(path).isReadOnly() ? Constants.OPEN_READ : Constants.OPEN_WRITE);
                service.openFile(path, actualMode);
            }
            FileEditorDialog dialog = new FileEditorDialog(this, service, path,
                    FileEditorDialog.WriteMode.APPEND, () -> refreshAll(currentPath, null));
            dialog.setVisible(true);
            refreshAll(currentPath, null);
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 追加写入：打开编辑窗口并预选“追加”模式 */
    private void actionAppendWrite() {
        openEditorWithMode(FileEditorDialog.WriteMode.APPEND);
    }

    /** 覆盖写入：打开编辑窗口并预选“覆盖”模式 */
    private void actionOverwriteWrite() {
        openEditorWithMode(FileEditorDialog.WriteMode.OVERWRITE);
    }

    private void openEditorWithMode(FileEditorDialog.WriteMode writeMode) {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择一个文件。");
            return;
        }
        if (entry.isDirectory()) {
            showError("目录没有内容可以写入。");
            return;
        }
        String path = PathResolver.join(currentPath, entry.getFullName());
        try {
            if (!service.getOpenFileTable().isOpen(path)) {
                service.openFile(path, entry.isReadOnly() ? Constants.OPEN_READ : Constants.OPEN_WRITE);
            }
            FileEditorDialog dialog = new FileEditorDialog(this, service, path, writeMode,
                    () -> refreshAll(currentPath, null));
            dialog.setVisible(true);
            refreshAll(currentPath, null);
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 显示文件内容（typefile） */
    private void actionShowContent() {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择一个文件。");
            return;
        }
        if (entry.isDirectory()) {
            showError("目录没有文件内容可以显示。");
            return;
        }
        String path = PathResolver.join(currentPath, entry.getFullName());
        try {
            String content = service.readTypeFile(path);
            JTextArea area = new JTextArea(content.isEmpty() ? "（空文件）" : content);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            area.setLineWrap(true);
            JScrollPane scroll = new JScrollPane(area);
            scroll.setPreferredSize(new Dimension(560, 360));
            JOptionPane.showMessageDialog(this, scroll,
                    "文件内容（typefile）：" + path, JOptionPane.PLAIN_MESSAGE);
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 关闭选中文件 */
    private void actionCloseSelectedFile() {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择一个文件。");
            return;
        }
        String path = PathResolver.join(currentPath, entry.getFullName());
        try {
            service.closeFile(path);
            refreshAll(currentPath, entry.getFullName());
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 重命名 */
    private void actionRename() {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择一个文件或目录。");
            return;
        }
        String path = PathResolver.join(currentPath, entry.getFullName());
        String newName = (String) JOptionPane.showInputDialog(this,
                "请输入新的名称：\n" + (entry.isDirectory()
                        ? "目录名最多 3 个字符，不能包含 '.'"
                        : "文件格式为 主名.扩展名，主名最多 3 个字符，扩展名最多 2 个字符"),
                "重命名", JOptionPane.QUESTION_MESSAGE, null, null, entry.getFullName());
        if (newName == null || newName.trim().isEmpty() || newName.trim().equalsIgnoreCase(entry.getFullName())) {
            return;
        }
        try {
            service.rename(path, newName.trim());
            refreshAll(currentPath, null);
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 删除文件或目录（危险操作，需要确认） */
    private void actionDelete() {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择要删除的文件或目录。");
            return;
        }
        String path = PathResolver.join(currentPath, entry.getFullName());

        if (entry.isDirectory()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "确定要删除目录【" + entry.getFullName() + "】吗？\n目录必须为空才能删除。",
                    "删除目录", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                service.deleteDirectory(path);
                refreshAll(currentPath, null);
            } catch (IOException | FileSystemException e) {
                showError(e.getMessage());
            }
            return;
        }

        String extra = entry.isReadOnly() ? "\n\n注意：这是一个【只读文件】，删除后无法恢复。" : "";
        int choice = JOptionPane.showConfirmDialog(this,
                "确定要删除文件【" + entry.getFullName() + "】吗？\n它的磁盘块会被全部回收为 0（空闲）。" + extra,
                "删除文件", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            service.deleteFile(path);
            refreshAll(currentPath, null);
        } catch (IOException | FileSystemException e) {
            showError(e.getMessage());
        }
    }

    /** 属性窗口 */
    private void actionShowProperties() {
        DirectoryEntry entry = fileListPanel.getSelectedEntry();
        if (entry == null) {
            info("请先在文件列表中选择一个文件或目录。");
            return;
        }
        String path = PathResolver.join(currentPath, entry.getFullName());
        PropertiesDialog dialog = new PropertiesDialog(this, service, path, entry,
                () -> refreshAll(currentPath, null));
        dialog.setVisible(true);
    }

    /** 格式化磁盘（两次确认） */
    private void actionFormat() {
        if (service.getOpenFileTable().size() > 0) {
            int closeFirst = JOptionPane.showConfirmDialog(this,
                    "当前还有 " + service.getOpenFileTable().size() + " 个文件处于打开状态。\n"
                            + "格式化会清空整个磁盘，是否继续？",
                    "格式化磁盘", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (closeFirst != JOptionPane.YES_OPTION) {
                return;
            }
        }
        int first = JOptionPane.showConfirmDialog(this,
                "【危险操作】格式化磁盘会清除磁盘映像中的全部目录和文件，\n"
                        + "FAT 表、根目录都会被重新初始化，操作不可撤销。\n\n确定要继续吗？",
                "格式化磁盘（第一次确认）", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (first != JOptionPane.YES_OPTION) {
            return;
        }
        int second = JOptionPane.showConfirmDialog(this,
                "请再次确认：真的要格式化磁盘吗？",
                "格式化磁盘（第二次确认）", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
        if (second != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            service.format();
            diskMapPanel.setSelectedBlock(-1);
            refreshAll(Constants.ROOT_PATH, null);
            info("磁盘已格式化：FAT[0..2]=253，FAT[23]=FAT[49]=254，其余为 0，根目录已清空。");
        } catch (IOException | FileSystemException e) {
            showError("格式化失败：" + e.getMessage());
        }
    }

    /** 磁盘统计 */
    private void actionShowStatistics() {
        FileSystemService.DiskStatistics stat = service.getStatistics();
        String text = String.format(
                "总磁盘块数：%d\n空闲块数：%d\n已分配块数（文件 + 目录）：%d\n"
                        + "系统保留块数：%d（块 0、1 = FAT 表，块 2 = 根目录）\n坏块数：%d（块 23、49）\n"
                        + "可用于分配的块总数：%d\n磁盘使用率：%.2f%%\n\n"
                        + "磁盘映像文件：%s\n文件大小：%d 字节",
                stat.getTotalBlocks(), stat.getFreeBlocks(), stat.getUsedBlocks(),
                stat.getSystemBlocks(), stat.getBadBlocks(), stat.getUsableBlocks(),
                stat.getUsagePercent(), service.getDiskImagePath(), Constants.DISK_SIZE);
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JOptionPane.showMessageDialog(this, area, "磁盘统计", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 使用说明 / 演示流程 */
    private void actionShowHelp() {
        String help = "一、界面组成\n"
                + "  · 左侧目录树：显示多级目录，点击即可切换当前目录\n"
                + "  · 中间文件列表：显示当前目录下的文件与子目录\n"
                + "  · 右侧选项卡：文件信息 / 已打开文件表 / 磁盘块字节\n"
                + "  · 下方选项卡：磁盘空间 16×8 网格 / 全部 128 项 FAT 表\n\n"
                + "二、常用操作\n"
                + "  · 新建文件：工具栏“新建文件”，主名 ≤ 3 字符，扩展名 ≤ 2 字符\n"
                + "  · 写入内容：双击文件打开编辑窗口，可选“追加写入”或“覆盖写入”\n"
                + "  · 修改属性：右键 → 修改属性，可切换只读 / 可读写\n"
                + "  · 查看磁盘：点击磁盘网格中的任意块，右下角会显示它的 64 个真实字节\n\n"
                + "三、现场演示流程（建议顺序）\n"
                + "  1. 磁盘 → 格式化磁盘（先展示初始 FAT：0/1/2=253，23/49=254）\n"
                + "  2. 新建文件夹 DOC，进入 DOC 再新建 SUB\n"
                + "  3. 在 DOC 下新建文件 A.TX，双击打开，写入 Hello → 追加 World → 覆盖 OperatingSystem\n"
                + "  4. 输入超过 64 字节的内容，观察 FAT 链变长、磁盘网格出现多个蓝块\n"
                + "  5. 切换到 FAT 表，指出 3→5→8→255 这样的链接关系\n"
                + "  6. 点击某个数据块，在“磁盘块字节”里看十六进制与 ASCII\n"
                + "  7. 修改 A.TX 为只读，再尝试写入 → 被拒绝；改回可读写后可写\n"
                + "  8. 连续打开 5 个文件，第 6 个会被拒绝（OFT 只有 5 个表项）\n"
                + "  9. 删除文件，观察磁盘网格上的块变回浅灰、FAT 对应项变 0\n"
                + " 10. 关闭程序再启动，确认目录与文件内容仍然存在（持久化）\n\n"
                + "四、注意事项\n"
                + "  · 文件内容中不允许出现 '#'，它是文件结束符\n"
                + "  · 每个目录最多 8 个目录项，根目录也一样\n"
                + "  · 坏块 23、49 永远不会被分配";
        JTextArea area = new JTextArea(help);
        area.setEditable(false);
        area.setFont(UiTheme.FONT_BASE);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(680, 460));
        JOptionPane.showMessageDialog(this, scroll, "使用说明与演示流程", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 关于 */
    private void actionShowAbout() {
        String text = "模拟磁盘文件系统（Simulated File System）\n\n"
                + "技术栈：Java 17 + Swing + Maven\n"
                + "磁盘参数：128 块 × 64 字节 = 8192 字节\n"
                + "磁盘布局：块 0、1 为 FAT 表，块 2 为根目录，块 3~127 为数据块\n"
                + "坏块：第 23、49 块\n"
                + "目录项：8 字节，每个目录块 8 项\n"
                + "文件结构：显式链接（FAT 链），'#' 为文件结束符\n"
                + "已打开文件表：5 个表项，每个文件一个读写指针\n\n"
                + "所有数据都真实存放在 data/disk.img 中，\n"
                + "关闭程序后再次启动，内容依然存在。";
        JOptionPane.showMessageDialog(this, text, "关于", JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================================================================
    //  五、工具方法
    // ==================================================================

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private void info(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 退出：关闭所有打开的文件并释放磁盘文件句柄 */
    private void shutdown() {
        try {
            service.close();
        } catch (Exception ignored) {
            // 退出阶段不打断流程
        }
        dispose();
        System.exit(0);
    }
}
