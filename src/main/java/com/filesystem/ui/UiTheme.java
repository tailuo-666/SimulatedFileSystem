package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 C —— 交互与视觉（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：全局视觉主题：暗色/亮色配色、字体、以及『块状态 → 颜色』的统一映射表
 *  上游依赖：Constants、BlockInfo
 *  下游调用：B 的 MainFrame、C 的各个弹窗、D 的三个可视化面板全部引用它
 * ========================================================================== */

import com.filesystem.model.BlockInfo;
import com.filesystem.util.Constants;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 界面的统一风格：字体、颜色、各种状态色。
 * <p>
 * 全部集中在这里定义，好处是磁盘网格和 FAT 表用的是同一套颜色，
 * 同一块磁盘在两边看到的样子是一致的，不会让人看花眼。
 *
 * @author 成员 C（负责 交互与视觉，约 20%）
 * @version 1.0
 */
public final class UiTheme {

    private UiTheme() {
    }

    /** 界面基础字体：优先用微软雅黑，保证中文不出方块 */
    /** 正文：表格单元格、普通标签都用它 */
    public static final Font FONT_BASE;
    /** 加粗正文：用于强调和表头 */
    public static final Font FONT_BOLD;
    /** 标题：各面板顶部的小标题 */
    public static final Font FONT_TITLE;
    /** 小字：提示条、图例、状态栏 */
    public static final Font FONT_SMALL;
    /** 等宽字体：十六进制查看器必须用等宽，否则 64 行对不齐 */
    public static final Font FONT_MONO;

    // ---------- 磁盘块状态色（浅灰色/蓝色/绿色/深灰/红色/橙色，对应课程要求） ----------
    /** 空闲块：浅灰 */
    public static final Color C_FREE = new Color(0xECEFF1);
    /** 普通文件数据块：蓝 */
    public static final Color C_FILE_DATA = new Color(0x64B5F6);
    /** 文件末块：深一点的蓝 */
    public static final Color C_FILE_END = new Color(0x1E88E5);
    /** 目录块：绿 */
    public static final Color C_DIRECTORY = new Color(0x66BB6A);
    /** 根目录：深绿 */
    public static final Color C_ROOT = new Color(0x2E7D32);
    /** FAT 表自身：深灰 */
    public static final Color C_SYSTEM = new Color(0x9E9E9E);
    /** 坏块：红 */
    public static final Color C_BAD = new Color(0xE53935);
    /** 异常块：紫红 */
    public static final Color C_ORPHAN = new Color(0xAD1457);
    /** 已打开文件的高亮描边：橙 */
    public static final Color C_OPEN_BORDER = new Color(0xFF9800);

    /** 5 个已打开文件的高亮配色（OFT 最多 5 个文件，正好一一对应） */
    public static final Color[] OPEN_FILE_COLORS = {
            new Color(0xFF9800), // 橙
            new Color(0x8E24AA), // 紫
            new Color(0x00897B), // 青
            new Color(0xD81B60), // 粉
            new Color(0x6D4C41)  // 棕
    };

    /** 文字主色 / 次要色 */
    /** 主要文字颜色（深灰黑），保证在浅色底上对比度足够 */
    public static final Color TEXT_MAIN = new Color(0x212121);

    /** 次要文字颜色（蓝灰），用于说明性文字、提示语 */
    public static final Color TEXT_SUB = new Color(0x607D8B);

    static {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String family = pick(available, "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑", "SimHei", "Dialog");
        FONT_BASE = new Font(family, Font.PLAIN, 13);
        FONT_BOLD = new Font(family, Font.BOLD, 13);
        FONT_TITLE = new Font(family, Font.BOLD, 15);
        FONT_SMALL = new Font(family, Font.PLAIN, 12);

        String monoFamily = pick(available, "Consolas", "JetBrains Mono", "Courier New", "Monospaced");
        FONT_MONO = new Font(monoFamily, Font.PLAIN, 13);
    }

    private static String pick(Set<String> available, String... candidates) {
        for (String name : candidates) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.SANS_SERIF;
    }

    /** 把统一的字体应用到 Swing 的默认组件上（在创建界面之前调用） */
    public static void applyGlobalFont() {
        String[] keys = {
                "Label.font", "Button.font", "ToggleButton.font", "RadioButton.font",
                "CheckBox.font", "ComboBox.font", "TextField.font", "TextArea.font",
                "Table.font", "TableHeader.font", "Tree.font", "List.font",
                "Menu.font", "MenuItem.font", "CheckBoxMenuItem.font", "RadioButtonMenuItem.font",
                "TabbedPane.font", "OptionPane.messageFont", "OptionPane.buttonFont",
                "TitledBorder.font", "ToolTip.font", "Spinner.font"
        };
        for (String key : keys) {
            UIManager.put(key, FONT_BASE);
        }
        UIManager.put("ToolTip.font", FONT_SMALL);
    }

    /** 根据磁盘块状态取填充色 */
    public static Color blockColor(BlockInfo.State state) {
        switch (state) {
            case FREE:
                return C_FREE;
            case FILE_DATA:
                return C_FILE_DATA;
            case FILE_END:
                return C_FILE_END;
            case DIRECTORY:
                return C_DIRECTORY;
            case ROOT:
                return C_ROOT;
            case SYSTEM:
                return C_SYSTEM;
            case BAD:
                return C_BAD;
            case ORPHAN:
            default:
                return C_ORPHAN;
        }
    }

    /** 在指定底色上应该用黑字还是白字（按亮度自动判断，保证对比度） */
    public static Color contrastText(Color background) {
        double luminance = (0.299 * background.getRed()
                + 0.587 * background.getGreen()
                + 0.114 * background.getBlue()) / 255.0;
        return luminance > 0.62 ? TEXT_MAIN : Color.WHITE;
    }

    /** FAT 值转成简短文字，供表格 / 提示使用 */
    public static String fatValueText(int value) {
        if (value == Constants.FAT_FREE) {
            return "0";
        }
        if (value == Constants.FAT_EOF) {
            return "255(EOF)";
        }
        if (value == Constants.FAT_BAD) {
            return "254(坏块)";
        }
        if (value == Constants.FAT_SYSTEM) {
            return "253(系统)";
        }
        return String.valueOf(value);
    }
}
