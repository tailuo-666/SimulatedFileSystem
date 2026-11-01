package com.filesystem.ui;

/* ==========================================================================
 *  【模块分工】成员 D —— 磁盘可视化（占总工作量约 20%）
 *  ------------------------------------------------------------------------
 *  本文件职责：磁盘网格：16×8 自绘 128 个格子，按 FAT 状态上色，悬停提示、点击回调
 *  上游依赖：BlockInfo、UiTheme
 *  下游调用：MainFrame 把 BlockInfo 列表喂给它；点击时回调通知主窗口刷新字节查看器
 * ========================================================================== */

import com.filesystem.model.BlockInfo;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * 磁盘空间可视化面板：16 列 × 8 行 = 128 个格子，每格代表一个磁盘块。
 * <p>
 * 这里全部是自绘（重写 paintComponent），因为用 128 个 JButton/JLabel 太重，
 * 而且不容易做出“整块区域随窗口缩放”的效果。
 * <p>
 * 颜色含义与 {@link UiTheme} 中定义的一致：
 * 浅灰=空闲、蓝=文件数据、深蓝=文件末块、绿=目录、深绿=根目录、
 * 深灰=FAT 表、红=坏块、紫红=异常、橙色边框=已被打开的文件占用。
 *
 * @author 成员 D（负责 磁盘可视化，约 20%）
 * @version 1.0
 */
public class DiskMapPanel extends JPanel {

    /** 点击磁盘块的回调 */
    public interface Listener {
        void onBlockSelected(int blockNumber);
    }

    /** 网格列数：每行 16 个块 */
    private static final int COLS = 16;

    /** 网格行数：8 行，16×8 = 128，正好是磁盘的总块数 */
    private static final int ROWS = 8;

    /** 每个格子的宽（像素） */
    private static final int CELL_WIDTH = 42;

    /** 每个格子的高（像素） */
    private static final int CELL_HEIGHT = 28;

    /** 网格四周的留白 */
    private static final int MARGIN = 10;

    /** 顶部图例区的高度，网格从这条线下面开始画 */
    private static final int LEGEND_HEIGHT = 44;

    /** 128 个块的信息，由主窗口在刷新时喂进来；下标就是块号 */
    private List<BlockInfo> blockInfos = new ArrayList<>();

    /** 当前被点中的块号，-1 表示没有选中；选中格会画粗边框 */
    private int selectedBlock = -1;

    /** 点击块的回调，主窗口用它去刷新右边的字节查看器 */
    private Listener listener;

    public DiskMapPanel() {
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createEmptyBorder());
        int width = MARGIN * 2 + COLS * CELL_WIDTH;
        int height = LEGEND_HEIGHT + MARGIN + ROWS * CELL_HEIGHT;
        setPreferredSize(new Dimension(width, height));
        setMinimumSize(new Dimension(width, height));

        ToolTipManager.sharedInstance().registerComponent(this);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int block = blockAt(e.getPoint());
                if (block >= 0) {
                    selectedBlock = block;
                    repaint();
                    if (listener != null) {
                        listener.onBlockSelected(block);
                    }
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(blockAt(e.getPoint()) >= 0
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 刷新数据；selectedBlock 保持不变，方便连续点击对比 */
    public void setBlockInfos(List<BlockInfo> infos) {
        this.blockInfos = infos == null ? new ArrayList<>() : infos;
        if (selectedBlock >= blockInfos.size()) {
            selectedBlock = -1;
        }
        repaint();
    }

    public void setSelectedBlock(int blockNumber) {
        this.selectedBlock = blockNumber;
        repaint();
    }

    public int getSelectedBlock() {
        return selectedBlock;
    }

    /** 网格整体的起始 X：面板比网格宽时把它水平居中，看起来更整齐 */
    private int gridStartX() {
        return Math.max(MARGIN, (getWidth() - COLS * CELL_WIDTH) / 2);
    }

    /** 某个块格子的中心点坐标（自动化测试点击、无障碍定位用） */
    public Point getBlockCenter(int blockNumber) {
        int col = blockNumber % COLS;
        int row = blockNumber / COLS;
        return new Point(gridStartX() + col * CELL_WIDTH + CELL_WIDTH / 2,
                LEGEND_HEIGHT + MARGIN / 2 + row * CELL_HEIGHT + CELL_HEIGHT / 2);
    }

    /** 根据鼠标位置算出是第几块，空白处返回 -1 */
    private int blockAt(Point point) {
        int x = point.x - gridStartX();
        int y = point.y - LEGEND_HEIGHT - MARGIN / 2;
        if (x < 0 || y < 0) {
            return -1;
        }
        int col = x / CELL_WIDTH;
        int row = y / CELL_HEIGHT;
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) {
            return -1;
        }
        int block = row * COLS + col;
        return block < blockInfos.size() ? block : -1;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int block = blockAt(event.getPoint());
        if (block < 0) {
            return null;
        }
        for (BlockInfo info : blockInfos) {
            if (info.getBlockNumber() == block) {
                return info.toTooltipText();
            }
        }
        return "磁盘块 " + block;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawLegend(g2);
        drawBlocks(g2);

        g2.dispose();
    }

    /** 画颜色图例 */
    private void drawLegend(Graphics2D g2) {
        Object[][] items = {
                {UiTheme.C_FREE, "空闲"},
                {UiTheme.C_FILE_DATA, "文件数据"},
                {UiTheme.C_FILE_END, "文件末块"},
                {UiTheme.C_DIRECTORY, "目录"},
                {UiTheme.C_ROOT, "根目录"},
                {UiTheme.C_SYSTEM, "FAT表"},
                {UiTheme.C_BAD, "坏块"},
                {UiTheme.C_ORPHAN, "异常"},
                {UiTheme.C_OPEN_BORDER, "已打开边框"}
        };
        g2.setFont(UiTheme.FONT_SMALL.deriveFont(Font.PLAIN, 12f));
        int x = MARGIN;
        int y = 8;
        int itemWidth = 88;
        int index = 0;
        for (Object[] item : items) {
            int col = index % 7;
            int row = index / 7;
            x = MARGIN + col * itemWidth;
            y = 8 + row * 20;
            g2.setColor((Color) item[0]);
            g2.fillRoundRect(x, y, 12, 12, 3, 3);
            g2.setColor(UiTheme.TEXT_SUB);
            g2.drawRoundRect(x, y, 12, 12, 3, 3);
            g2.setColor(UiTheme.TEXT_MAIN);
            g2.drawString((String) item[1], x + 17, y + 11);
            index++;
        }
    }

    /** 画 128 个磁盘块 */
    private void drawBlocks(Graphics2D g2) {
        Font numberFont = UiTheme.FONT_BASE.deriveFont(Font.BOLD, 11f);
        g2.setFont(numberFont);
        for (BlockInfo info : blockInfos) {
            int block = info.getBlockNumber();
            int col = block % COLS;
            int row = block / COLS;
            int x = gridStartX() + col * CELL_WIDTH;
            int y = LEGEND_HEIGHT + MARGIN / 2 + row * CELL_HEIGHT;

            Color fill = UiTheme.blockColor(info.getState());
            g2.setColor(fill);
            g2.fillRoundRect(x + 1, y + 1, CELL_WIDTH - 3, CELL_HEIGHT - 3, 5, 5);

            // 已打开文件：橙色粗边框
            if (info.isOpened()) {
                g2.setColor(UiTheme.C_OPEN_BORDER);
                g2.setStroke(new BasicStroke(2.4f));
                g2.drawRoundRect(x + 1, y + 1, CELL_WIDTH - 3, CELL_HEIGHT - 3, 5, 5);
            } else {
                g2.setColor(new Color(0xCFD8DC));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(x + 1, y + 1, CELL_WIDTH - 3, CELL_HEIGHT - 3, 5, 5);
            }

            // 当前选中的块：用黑色虚线框再套一圈
            if (block == selectedBlock) {
                g2.setColor(new Color(0x212121));
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{3f, 2f}, 0f));
                g2.drawRoundRect(x - 1, y - 1, CELL_WIDTH - 1, CELL_HEIGHT - 1, 6, 6);
            }

            // 块号文字（按底色自动选黑 / 白）
            g2.setColor(UiTheme.contrastText(fill));
            String text = String.valueOf(block);
            int textWidth = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, x + (CELL_WIDTH - textWidth) / 2, y + CELL_HEIGHT / 2 + 4);
        }
    }
}
