package com.filesystem;

/* ==========================================================================
 *  【模块分工】成员 B —— 主窗口与导航（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：程序入口：设置外观与字体、打开（或首次创建）模拟磁盘、创建并显示主窗口
 *  上游依赖：FileSystemService、MainFrame、UiTheme
 *  下游调用：无（JVM 从这里启动）
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.ui.MainFrame;
import com.filesystem.ui.UiTheme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Font;

/**
 * 程序入口。
 * <p>
 * 启动顺序：
 * <ol>
 *   <li>切换到系统自带的界面风格（Windows 下看起来就是原生窗口）；</li>
 *   <li>统一设置中文字体，避免出现方块字；</li>
 *   <li>打开（或首次创建）磁盘映像 data/disk.img；</li>
 *   <li>显示主窗口。</li>
 * </ol>
 *
 * @author 成员 B（负责 主窗口与导航，约 20%）
 * @version 1.0
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 系统风格不可用时用默认风格，不影响功能
            }
            UiTheme.applyGlobalFont();
            // 部分 LookAndFeel 会覆盖字体，这里再补一次
            UIManager.put("Table.font", UiTheme.FONT_BASE);
            UIManager.put("TableHeader.font", new Font(UiTheme.FONT_BASE.getFamily(), Font.BOLD, 13));

            try {
                FileSystemService service = FileSystemService.createDefault();
                MainFrame frame = new MainFrame(service);
                frame.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "程序启动失败：\n" + e.getMessage() + "\n\n请确认程序目录下可以创建 data/disk.img。",
                        "启动失败", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
