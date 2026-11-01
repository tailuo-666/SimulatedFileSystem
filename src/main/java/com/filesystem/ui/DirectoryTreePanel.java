package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 B —— 主窗口与导航（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：左侧目录树：懒加载子目录、点击切换、右键新建/删除目录
 *  上游依赖：FileSystemService（listDirectory 等）
 *  下游调用：MainFrame 通过回调接收『用户选中了哪个目录』
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.util.FileSystemException;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 左侧目录树面板。
 * <p>
 * 用 JTree 展示从根目录 "/" 开始的多级目录。树的每个节点都携带一个
 * {@link NodeData}，里面记着这个目录的<b>绝对路径</b>——点击节点时，
 * 界面就是靠这个路径去问文件系统“这个目录下有什么”。
 * <p>
 * 树的内容每次都从模拟磁盘重新读出来（不做缓存），所以磁盘上一旦变化，
 * 刷新之后树就跟着变，绝不会出现“界面显示的东西磁盘上其实没有”的情况。
 *
 * @author 成员 B（负责 主窗口与导航，约 20%）
 * @version 1.0
 */
public class DirectoryTreePanel extends JPanel {

    /** 目录被选中时的回调 */
    public interface Listener {
        void onDirectorySelected(String path);
    }

    /** 树节点携带的数据：绝对路径 + 显示名 */
    public static class NodeData {
        /** 该目录的绝对路径，例如 "/DOC/SUB"；根目录是 "/" */
        private final String path;

        /** 树上显示的名字；根目录显示为 "/"，其它显示目录名（如 SUB） */
        private final String name;

        public NodeData(String path, String name) {
            this.path = path;
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 业务门面：列子目录、建目录、删目录 */
    private final FileSystemService service;

    /** 树的数据模型：节点的增删都通过它，界面才会自动重画 */
    private final DefaultTreeModel treeModel;

    /** Swing 的树组件本身 */
    private final JTree tree;

    /** 用户点了某个目录时通知主窗口（主窗口刷新右侧列表和磁盘视图） */
    private Listener listener;

    /** 重建树的过程中要屏蔽选中事件，否则会触发一连串无意义的刷新 */
    private boolean suppressEvents;

    public DirectoryTreePanel(FileSystemService service) {
        super(new BorderLayout());
        this.service = service;

        // ---------- 标题 ----------
        JLabel title = new JLabel("目录树");
        title.setFont(UiTheme.FONT_TITLE);
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        add(title, BorderLayout.NORTH);

        // ---------- 树本体 ----------
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new NodeData("/", "/"));
        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setFont(UiTheme.FONT_BASE);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // 自定义渲染器：文件夹用画出来的图标
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setOpenIcon(FileIcons.FOLDER_OPEN);
        renderer.setClosedIcon(FileIcons.FOLDER);
        renderer.setLeafIcon(FileIcons.FOLDER);
        renderer.setFont(UiTheme.FONT_BASE);
        renderer.setTextSelectionColor(Color.WHITE);
        renderer.setBackgroundSelectionColor(new Color(0x1E88E5));
        tree.setCellRenderer(renderer);

        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(this::handleSelectionChanged);

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        setPreferredSize(new Dimension(230, 400));
        setMinimumSize(new Dimension(170, 200));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 重新从磁盘构建整棵树。
     *
     * @param pathToSelect 重建完成后要选中的目录路径（一般是当前正在浏览的目录）
     */
    public void reload(String pathToSelect) {
        Set<String> expanded = collectExpandedPaths();

        suppressEvents = true;
        try {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(new NodeData("/", "/"));
            buildChildren(root, "/", 0);
            treeModel.setRoot(root);
            // 根节点默认展开
            tree.expandPath(new TreePath(root));

            // 恢复之前展开过的目录
            for (String path : expanded) {
                DefaultMutableTreeNode node = findNode(path);
                if (node != null) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
            // 选中当前目录
            DefaultMutableTreeNode target = findNode(pathToSelect);
            if (target != null) {
                TreePath treePath = new TreePath(target.getPath());
                tree.expandPath(treePath);
                tree.setSelectionPath(treePath);
            }
        } finally {
            suppressEvents = false;
        }
    }

    /** 递归地把某个目录下的子目录加进树里 */
    private void buildChildren(DefaultMutableTreeNode parentNode, String parentPath, int depth) {
        if (depth > 16) {
            return; // 防御：目录层级过深时停止，避免异常数据导致栈溢出
        }
        try {
            List<DirectoryEntry> entries = service.listDirectory(parentPath);
            for (DirectoryEntry entry : entries) {
                if (!entry.isDirectory()) {
                    continue; // 树里只放目录
                }
                String childPath = "/".equals(parentPath)
                        ? "/" + entry.getFullName()
                        : parentPath + "/" + entry.getFullName();
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(new NodeData(childPath, entry.getFullName()));
                parentNode.add(child);
                buildChildren(child, childPath, depth + 1);
            }
        } catch (IOException | FileSystemException e) {
            // 某个目录读不出来（例如数据异常），跳过它，不影响整棵树
            parentNode.add(new DefaultMutableTreeNode(new NodeData("", "（读取失败）")));
        }
    }

    /** 收集当前所有处于展开状态的目录路径 */
    private Set<String> collectExpandedPaths() {
        Set<String> result = new HashSet<>();
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (path != null && tree.isExpanded(path)) {
                Object last = path.getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NodeData data) {
                    result.add(data.getPath());
                }
            }
        }
        return result;
    }

    /** 按路径在树里找节点 */
    private DefaultMutableTreeNode findNode(String path) {
        if (path == null) {
            return null;
        }
        Object root = treeModel.getRoot();
        if (!(root instanceof DefaultMutableTreeNode rootNode)) {
            return null;
        }
        return findNode(rootNode, com.filesystem.core.PathResolver.normalize(path));
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode node, String path) {
        if (node.getUserObject() instanceof NodeData data && data.getPath().equalsIgnoreCase(path)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode found = findNode(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 让界面选中指定目录（不触发回调之外的副作用） */
    public void selectPath(String path) {
        DefaultMutableTreeNode node = findNode(path);
        if (node == null) {
            return;
        }
        TreePath treePath = new TreePath(node.getPath());
        suppressEvents = true;
        try {
            tree.expandPath(treePath);
            tree.setSelectionPath(treePath);
            tree.scrollPathToVisible(treePath);
        } finally {
            suppressEvents = false;
        }
    }

    /** 当前选中的目录路径，没选中时返回根目录 */
    public String getSelectedPath() {
        TreePath path = tree.getSelectionPath();
        if (path != null) {
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NodeData data) {
                return data.getPath();
            }
        }
        return "/";
    }

    private void handleSelectionChanged(TreeSelectionEvent e) {
        if (suppressEvents || listener == null) {
            return;
        }
        TreePath path = e.getNewLeadSelectionPath();
        if (path == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NodeData data) {
            // “读取失败”的占位节点路径为空，不是真目录，忽略
            if (!data.getPath().isEmpty()) {
                listener.onDirectorySelected(data.getPath());
            }
        }
    }

    /** 供外部获取所有目录路径（右键菜单里定位用） */
    public List<String> getAllDirectoryPaths() {
        List<String> paths = new ArrayList<>();
        Object root = treeModel.getRoot();
        if (root instanceof DefaultMutableTreeNode rootNode) {
            collectPaths(rootNode, paths);
        }
        return paths;
    }

    private void collectPaths(DefaultMutableTreeNode node, List<String> out) {
        if (node.getUserObject() instanceof NodeData data) {
            out.add(data.getPath());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectPaths((DefaultMutableTreeNode) node.getChildAt(i), out);
        }
    }
}
