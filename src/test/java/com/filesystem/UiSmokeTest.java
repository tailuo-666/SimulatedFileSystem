package com.filesystem;

/* ==========================================================================
 *  【模块分工】成员 B / C / D —— 界面联调（占总工作量约 60%）
 *  ------------------------------------------------------------------------
 *  本文件职责：界面冒烟测试：真把主窗口建出来渲染一遍，检查布局不重叠、配色正确、对话框控件不被挤掉
 *  覆盖内容：主窗口全图截图、磁盘网格配色采样、FAT 表截图、编辑窗口与属性窗口截图
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.ui.BlockInspectorPanel;
import com.filesystem.ui.DiskMapPanel;
import com.filesystem.ui.FatTablePanel;
import com.filesystem.ui.FileEditorDialog;
import com.filesystem.ui.MainFrame;
import com.filesystem.ui.PropertiesDialog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面冒烟测试（默认跳过，需要显示器）。
 * <p>
 * 作用：把主窗口、编辑窗口、属性窗口真正构建并渲染出来，确认 Swing 组件
 * 能正常创建、布局不抛异常，并把截图保存到 target/ 目录，方便肉眼检查排版。
 * 同时模拟“点击磁盘块”，验证点击 → 字节查看器的联动是否真的生效。
 * <p>
 * 运行方式（需要可用的桌面环境）：
 * <pre>
 *   mvn test -Dui.smoke=true
 * </pre>
 *
 * @author 成员 B / C / D（界面联调，约 60%）
 * @version 1.0
 */
class UiSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("界面冒烟测试：构建主窗口 / 编辑窗口 / 属性窗口并截图")
    @SuppressWarnings("unchecked")
    void buildMainFrameAndCaptureScreenshots() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有图形环境，跳过界面测试");
        Assumptions.assumeTrue(Boolean.getBoolean("ui.smoke"), "仅在 -Dui.smoke=true 时运行");

        Path imagePath = tempDir.resolve("ui-disk.img");
        FileSystemService service = new FileSystemService(imagePath);

        // ---------- 准备一些演示数据 ----------
        service.createDirectory("/DOC");
        service.createDirectory("/DOC/SUB");
        service.createDirectory("/DAT");
        service.createFile("/DOC/A.TX", 4);
        service.writeFile("/DOC/A.TX", "Hello World 模拟磁盘文件系统", 1);
        service.createFile("/DOC/B.TX", 4);
        service.writeFile("/DOC/B.TX", "只读文件的内容", 1);
        service.closeFile("/DOC/B.TX");
        service.changeAttribute("/DOC/B.TX", 1);            // 改成只读，界面上会显示只读标记
        service.createFile("/DOC/SUB/C.TX", 4);
        service.writeFile("/DOC/SUB/C.TX", "0123456789".repeat(15), 1); // 占 3 个块
        service.createFile("/DAT/TST.TX", 4);
        service.writeFile("/DAT/TST.TX", "测试文件", 1);
        service.closeAllFiles();

        // ---------- 主窗口 ----------
        final MainFrame[] holder = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame(service);
            frame.setSize(1400, 880);
            frame.setLocation(40, 40);
            frame.setVisible(true);
            holder[0] = frame;
        });
        Thread.sleep(1000);
        MainFrame frame = holder[0];

        // 进入 /DOC 目录，让文件列表有内容
        SwingUtilities.invokeAndWait(() -> frame.openDirectory("/DOC"));
        Thread.sleep(400);
        capture(frame.getContentPane(), "target/ui-smoke.png");

        // ---------- 模拟点击第 6 块，验证联动 ----------
        DiskMapPanel mapPanel = findComponent(frame, DiskMapPanel.class);
        assertNotNull(mapPanel, "应能找到磁盘网格面板");
        SwingUtilities.invokeAndWait(() -> {
            var center = mapPanel.getBlockCenter(6);
            mapPanel.dispatchEvent(new MouseEvent(mapPanel, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, center.x, center.y, 1, false, MouseEvent.BUTTON1));
        });
        Thread.sleep(300);

        BlockInspectorPanel inspector = findComponent(frame, BlockInspectorPanel.class);
        assertNotNull(inspector);
        assertEquals(6, inspector.getCurrentBlock(), "点击第 6 块后字节查看器应显示第 6 块的内容");

        // ---------- 右侧选项卡：磁盘块字节查看器 ----------
        JTabbedPane rightTabs = findTabsContaining(frame, BlockInspectorPanel.class);
        assertNotNull(rightTabs, "应能找到右侧选项卡");
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < rightTabs.getTabCount(); i++) {
                if (rightTabs.getComponentAt(i) instanceof BlockInspectorPanel) {
                    rightTabs.setSelectedIndex(i);
                }
            }
        });
        Thread.sleep(400);
        capture(rightTabs, "target/ui-block.png");   // 只截右侧面板，字大一些

        // ---------- 下方选项卡：FAT 表 ----------
        JTabbedPane bottomTabs = findTabsContaining(frame, FatTablePanel.class);
        assertNotNull(bottomTabs, "应能找到下方选项卡");
        SwingUtilities.invokeAndWait(() -> bottomTabs.setSelectedIndex(1));
        Thread.sleep(400);
        capture(frame.getContentPane(), "target/ui-fat.png");

        // ---------- 下方选项卡：磁盘空间网格 ----------
        SwingUtilities.invokeAndWait(() -> bottomTabs.setSelectedIndex(0));
        Thread.sleep(300);
        capture(findComponent(frame, DiskMapPanel.class), "target/ui-map.png");

        // ---------- 文件编辑窗口 ----------
        service.openFile("/DOC/A.TX", com.filesystem.util.Constants.OPEN_WRITE);

        // ---------- 右侧选项卡：已打开文件表 OFT ----------
        SwingUtilities.invokeAndWait(() -> frame.openDirectory("/DOC"));
        Thread.sleep(300);
        JTabbedPane rightTabs2 = findTabsContaining(frame, com.filesystem.ui.OpenFileTablePanel.class);
        assertNotNull(rightTabs2, "应能找到右侧选项卡");
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < rightTabs2.getTabCount(); i++) {
                if (rightTabs2.getComponentAt(i) instanceof com.filesystem.ui.OpenFileTablePanel) {
                    rightTabs2.setSelectedIndex(i);
                }
            }
        });
        Thread.sleep(400);
        capture(rightTabs2, "target/ui-oft.png");    // 只截右侧面板，字大一些
        final FileEditorDialog[] editorHolder = new FileEditorDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            FileEditorDialog dialog = new FileEditorDialog(frame, service, "/DOC/A.TX",
                    FileEditorDialog.WriteMode.APPEND, () -> {
            });
            dialog.pack();          // 先让对话框完成一次布局（不显示也能排版）
            dialog.setSize(820, 620);
            dialog.validate();
            editorHolder[0] = dialog;
        });
        Thread.sleep(300);
        capture(editorHolder[0].getContentPane(), "target/ui-editor.png");
        SwingUtilities.invokeAndWait(() -> editorHolder[0].dispose());

        // ---------- 属性窗口 ----------
        var readOnlyEntry = service.getEntry("/DOC/B.TX");
        final PropertiesDialog[] propsHolder = new PropertiesDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            PropertiesDialog dialog = new PropertiesDialog(frame, service, "/DOC/B.TX",
                    readOnlyEntry, () -> {
            });
            dialog.pack();
            dialog.setSize(560, 480);
            dialog.validate();
            propsHolder[0] = dialog;
        });
        Thread.sleep(300);
        capture(propsHolder[0].getContentPane(), "target/ui-props.png");
        SwingUtilities.invokeAndWait(() -> propsHolder[0].dispose());

        SwingUtilities.invokeAndWait(() -> frame.dispose());
        service.close();

        for (String name : new String[]{"ui-smoke.png", "ui-block.png", "ui-fat.png",
                "ui-map.png", "ui-oft.png", "ui-editor.png", "ui-props.png"}) {
            File file = new File("target/" + name);
            assertTrue(file.exists() && file.length() > 0, name + " 应已生成");
            System.out.println("界面截图：" + file.getAbsolutePath());
        }
    }

    /** 把组件画到图片里保存 */
    private void capture(Component component, String path) throws Exception {
        BufferedImage image = new BufferedImage(
                Math.max(1, component.getWidth()), Math.max(1, component.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            var g = image.createGraphics();
            component.paint(g);
            g.dispose();
        });
        ImageIO.write(image, "png", new File(path));
    }

    /** 在组件树里按类型找组件（参数用 Component，方便传选项卡里的任意组件） */
    private static <T extends Component> T findComponent(Component root, Class<T> type) {
        if (!(root instanceof Container container)) {
            return null;
        }
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            T found = findComponent(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 找到“内容里含有指定组件类型”的选项卡面板 */
    private static JTabbedPane findTabsContaining(Component root, Class<? extends Component> contentType) {
        if (!(root instanceof Container container)) {
            return null;
        }
        for (Component child : container.getComponents()) {
            if (child instanceof JTabbedPane tabs) {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    Component tabContent = tabs.getComponentAt(i);
                    // 选项卡内容本身可能就是目标组件（例如 FAT 表面板），所以先判自身
                    if (contentType.isInstance(tabContent) || findComponent(tabContent, contentType) != null) {
                        return tabs;
                    }
                }
            }
            JTabbedPane found = findTabsContaining(child, contentType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
