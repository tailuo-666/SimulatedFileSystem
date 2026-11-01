package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 D —— 磁盘可视化（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：图标绘制：用 Java2D 现画文件/目录/只读/已打开的 16×16 图标，不依赖图片资源
 *  上游依赖：无
 *  下游调用：FileListPanel 给表格行装图标；DirectoryTreePanel 给树节点装图标
 * ========================================================================== */

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 界面图标工厂。
 * <p>
 * 项目不依赖任何图片资源文件，所有图标都用 Graphics2D 现画成 16×16 的小图片，
 * 这样把源码拷到任何机器上都能直接编译运行，不会出现“图标丢失”。
 *
 * @author 成员 D（负责 磁盘可视化，约 20%）
 * @version 1.0
 */
public final class FileIcons {

    /** 所有图标统一画成 16×16，跟 JTable 行高、JTree 节点高刚好搭得上 */
    private static final int SIZE = 16;

    /** 文件夹（关闭状态） */
    public static final Icon FOLDER = createFolder(false);

    /** 文件夹（展开状态） */
    public static final Icon FOLDER_OPEN = createFolder(true);

    /** 普通文件 */
    public static final Icon FILE = createFile(false);

    /** 只读文件（右下角有一个红点） */
    public static final Icon FILE_READ_ONLY = createFile(true);

    private FileIcons() {
    }

    /** 画一个文件夹图标：主体 + 上方小标签 */
    private static Icon createFolder(boolean open) {
        BufferedImage image = newCanvas();
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 上方的小标签（文件夹的“耳朵”）
        g.setColor(new Color(0xE0A800));
        g.fillRoundRect(1, 3, 6, 4, 3, 3);

        // 文件夹主体
        g.setColor(open ? new Color(0xFFD54F) : new Color(0xF6C445));
        g.fillRoundRect(1, 5, 14, 9, 3, 3);
        g.setColor(new Color(0xD9A300));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(1, 5, 14, 9, 3, 3);

        if (open) {
            // 展开状态：中间画一条浅色分隔线，和关闭状态区分开
            g.setColor(new Color(0xFFF3C4));
            g.drawLine(2, 9, 14, 9);
        }
        g.dispose();
        return new ImageIcon(image);
    }

    /** 画一个文件图标：白纸 + 折角 + 几条文字线 */
    private static Icon createFile(boolean readOnly) {
        BufferedImage image = newCanvas();
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 纸张
        g.setColor(Color.WHITE);
        g.fillRoundRect(3, 1, 10, 14, 2, 2);
        g.setColor(new Color(0x90A4AE));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(3, 1, 10, 14, 2, 2);

        // 右上角折角
        g.setColor(new Color(0xCFD8DC));
        g.fillPolygon(new int[]{9, 13, 13}, new int[]{1, 1, 5}, 3);

        // 正文线条
        g.setColor(new Color(0xB0BEC5));
        g.drawLine(5, 6, 11, 6);
        g.drawLine(5, 8, 11, 8);
        g.drawLine(5, 10, 9, 10);

        if (readOnly) {
            // 只读：右下角一个红点作标记
            g.setColor(new Color(0xE53935));
            g.fillOval(9, 9, 5, 5);
        }
        g.dispose();
        return new ImageIcon(image);
    }

    private static BufferedImage newCanvas() {
        return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    }
}
