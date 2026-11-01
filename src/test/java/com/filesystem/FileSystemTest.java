package com.filesystem;

/* ==========================================================================
 *  【模块分工】成员 A —— 存储内核（占总工作量约 40%）
 *  ------------------------------------------------------------------------
 *  本文件职责：单元测试：不启动界面，直接调核心层验证磁盘、FAT、目录、文件读写、OFT、只读保护、回滚
 *  覆盖内容：课程要求的九个测试场景 + 空间不足回滚、重命名、目录容量、非法名称、指针跨块推进
 * ========================================================================== */

import com.filesystem.core.FileSystemService;
import com.filesystem.model.BlockInfo;
import com.filesystem.model.DirectoryEntry;
import com.filesystem.util.Constants;
import com.filesystem.util.FileSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心功能测试。
 * <p>
 * 每个测试方法都用 @TempDir 里的独立磁盘映像，互不干扰；
 * 测试直接调用 FileSystemService，不经过界面，因此也顺便验证了
 * “绕过 GUI 直接调用核心层同样会被只读属性拦住”这条要求。
 * <p>
 * 覆盖课程设计要求的九个测试场景，外加目录容量、名称校验、
 * 空间不足回滚、块归属等边界检查。
 *
 * @author 成员 A（存储内核，约 40%）
 * @version 1.0
 */
class FileSystemTest {

    @TempDir
    Path tempDir;

    private Path imagePath;

    @BeforeEach
    void setUp() {
        imagePath = tempDir.resolve("disk.img");
    }

    /** 打开一个新的文件系统实例（磁盘不存在时会自动初始化） */
    private FileSystemService open() throws IOException {
        return new FileSystemService(imagePath);
    }

    // ==================================================================
    //  测试一：基础目录操作
    // ==================================================================

    @Test
    @DisplayName("测试一：创建 DOC / SUB 目录与 A.TX / B.TX 文件，目录树正确")
    void testBasicDirectoryOperations() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createDirectory("/DOC");
            fs.createDirectory("/DOC/SUB");
            fs.createFile("/DOC/A.TX", Constants.ATTR_NORMAL);
            fs.createFile("/DOC/SUB/B.TX", Constants.ATTR_NORMAL);

            List<DirectoryEntry> rootList = fs.listDirectory("/");
            assertEquals(1, rootList.size());
            assertEquals("DOC", rootList.get(0).getFullName());
            assertTrue(rootList.get(0).isDirectory());

            List<DirectoryEntry> docList = fs.listDirectory("/DOC");
            // DOC 下应有一个子目录 SUB 和一个文件 A.TX
            assertEquals(2, docList.size());

            List<DirectoryEntry> subList = fs.listDirectory("/DOC/SUB");
            assertEquals(1, subList.size());
            assertEquals("B.TX", subList.get(0).getFullName());

            // 大小写不敏感
            assertNotNull(fs.getEntry("/doc/a.tx"));
        }
    }

    // ==================================================================
    //  测试二：文件写入（追加 / 覆盖）
    // ==================================================================

    @Test
    @DisplayName("测试二：写入 Hello，追加 World 得 HelloWorld，覆盖为 OperatingSystem")
    void testWriteAppendAndOverwrite() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/A.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/A.TX", "Hello", Constants.WRITE_OVERWRITE);
            assertEquals("Hello", fs.loadContentForDisplay("/A.TX"));

            fs.writeFile("/A.TX", "World", Constants.WRITE_APPEND);
            assertEquals("HelloWorld", fs.loadContentForDisplay("/A.TX"));

            fs.writeFile("/A.TX", "OperatingSystem", Constants.WRITE_OVERWRITE);
            assertEquals("OperatingSystem", fs.loadContentForDisplay("/A.TX"));

            // 覆盖后长度小于原来占用时，多余的块必须被回收
            assertEquals(1, fs.getEntry("/A.TX").getLengthBlocks());
        }
    }

    // ==================================================================
    //  测试三：FAT 链与多块文件
    // ==================================================================

    @Test
    @DisplayName("测试三：写入超过 64 字节内容，自动申请多个块且 FAT 链正确")
    void testMultiBlockFatChain() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/BIG.TX", Constants.ATTR_NORMAL);
            String content = "0123456789".repeat(10); // 100 字节
            fs.writeFile("/BIG.TX", content, Constants.WRITE_OVERWRITE);

            DirectoryEntry entry = fs.getEntry("/BIG.TX");
            assertEquals(2, entry.getLengthBlocks(), "100 字节 + 结束符应占 2 个块");

            List<Integer> chain = fs.getFatChain("/BIG.TX");
            assertEquals(2, chain.size());
            int[] fat = fs.getFatSnapshot();
            assertEquals(chain.get(1), fat[chain.get(0)], "第一块应指向第二块");
            assertEquals(Constants.FAT_EOF, fat[chain.get(1)], "最后一块应为 255(EOF)");

            // 读回来的内容必须完全一致
            assertEquals(content, fs.loadContentForDisplay("/BIG.TX"));
            assertEquals(100, fs.getFileByteLength(entry.getStartBlock()));

            // 块归属：这两个块应属于 /BIG.TX
            List<BlockInfo> infos = fs.buildBlockInfos();
            for (int block : chain) {
                assertEquals("/BIG.TX", infos.get(block).getOwnerPath());
            }
            // 磁盘网格应有对应的占用状态
            assertEquals(BlockInfo.State.FILE_END, infos.get(chain.get(1)).getState());
            assertEquals(BlockInfo.State.FILE_DATA, infos.get(chain.get(0)).getState());
        }
    }

    // ==================================================================
    //  测试四：只读属性
    // ==================================================================

    @Test
    @DisplayName("测试四：只读文件拒绝写入，改回可读写后恢复")
    void testReadOnlyAttribute() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/RO.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/RO.TX", "data", Constants.WRITE_OVERWRITE);
            fs.closeFile("/RO.TX");

            fs.changeAttribute("/RO.TX", Constants.ATTR_READ_ONLY);
            assertTrue(fs.getEntry("/RO.TX").isReadOnly());

            // 追加、覆盖都必须被拒绝——即使绕过界面直接调用核心层
            assertThrows(FileSystemException.class,
                    () -> fs.writeFile("/RO.TX", "x", Constants.WRITE_APPEND));
            assertThrows(FileSystemException.class,
                    () -> fs.writeFile("/RO.TX", "x", Constants.WRITE_OVERWRITE));
            // 也不能以写方式打开
            assertThrows(FileSystemException.class,
                    () -> fs.openFile("/RO.TX", Constants.OPEN_WRITE));

            // 内容没有被破坏
            assertEquals("data", fs.readTypeFile("/RO.TX"));

            fs.changeAttribute("/RO.TX", Constants.ATTR_NORMAL);
            fs.openFile("/RO.TX", Constants.OPEN_WRITE);
            fs.writeFile("/RO.TX", "!", Constants.WRITE_APPEND);
            assertEquals("data!", fs.loadContentForDisplay("/RO.TX"));
        }
    }

    // ==================================================================
    //  测试五：文件删除与空间回收
    // ==================================================================

    @Test
    @DisplayName("测试五：删除多块文件后，FAT 对应项全部变 0，其他文件不受影响")
    void testDeleteFileFreesBlocks() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/KEP.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/KEP.TX", "keep", Constants.WRITE_OVERWRITE);
            fs.createFile("/DEL.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/DEL.TX", "x".repeat(200), Constants.WRITE_OVERWRITE); // 4 块

            List<Integer> chain = fs.getFatChain("/DEL.TX");
            assertEquals(4, chain.size());
            int freeBefore = fs.getStatistics().getFreeBlocks();

            fs.closeFile("/DEL.TX");
            fs.deleteFile("/DEL.TX");

            int[] fat = fs.getFatSnapshot();
            for (int block : chain) {
                assertEquals(Constants.FAT_FREE, fat[block], "块 " + block + " 应被回收为 0");
            }
            assertEquals(freeBefore + chain.size(), fs.getStatistics().getFreeBlocks());
            assertFalse(fs.exists("/DEL.TX"));
            // 另一个文件不受影响
            assertEquals("keep", fs.loadContentForDisplay("/KEP.TX"));
        }
    }

    // ==================================================================
    //  测试六：目录删除
    // ==================================================================

    @Test
    @DisplayName("测试六：非空目录不能删除，清空后可以删除")
    void testDeleteDirectory() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createDirectory("/TMP");
            fs.createFile("/TMP/X.TX", Constants.ATTR_NORMAL);
            fs.closeFile("/TMP/X.TX");

            assertThrows(FileSystemException.class, () -> fs.deleteDirectory("/TMP"));

            fs.deleteFile("/TMP/X.TX");
            int dirBlock = fs.getEntry("/TMP").getStartBlock();
            fs.deleteDirectory("/TMP");

            assertFalse(fs.exists("/TMP"));
            assertEquals(Constants.FAT_FREE, fs.getFatSnapshot()[dirBlock], "目录块应被回收");

            // 根目录不能删除
            assertThrows(FileSystemException.class, () -> fs.deleteDirectory("/"));
        }
    }

    // ==================================================================
    //  测试七：已打开文件表（最多 5 个）
    // ==================================================================

    @Test
    @DisplayName("测试七：连续打开 5 个文件成功，第 6 个被拒绝；关闭后可再打开")
    void testOpenFileTableLimit() throws IOException {
        try (FileSystemService fs = open()) {
            for (int i = 1; i <= 5; i++) {
                fs.createFile("/F" + i + ".TX", Constants.ATTR_NORMAL);
                fs.closeFile("/F" + i + ".TX");
            }
            for (int i = 1; i <= 5; i++) {
                fs.openFile("/F" + i + ".TX", Constants.OPEN_READ);
            }
            assertEquals(5, fs.getOpenFileTable().size());
            assertTrue(fs.getOpenFileTable().isFull());

            fs.createFile("/F6.TX", Constants.ATTR_NORMAL);
            assertThrows(FileSystemException.class, () -> fs.openFile("/F6.TX", Constants.OPEN_READ));

            // 关闭一个之后又能打开了
            fs.closeFile("/F1.TX");
            fs.openFile("/F6.TX", Constants.OPEN_READ);
            assertEquals(5, fs.getOpenFileTable().size());
        }
    }

    // ==================================================================
    //  测试八：磁盘持久化
    // ==================================================================

    @Test
    @DisplayName("测试八：关闭后重新打开，目录、文件内容与 FAT 全部保持不变")
    void testPersistenceAcrossRestart() throws IOException {
        int[] fatBefore;
        long sizeBefore;
        try (FileSystemService fs = open()) {
            fs.createDirectory("/DOC");
            fs.createFile("/DOC/A.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/DOC/A.TX", "中文内容 persistence test", Constants.WRITE_OVERWRITE);
            fs.closeAllFiles();
            fatBefore = fs.getFatSnapshot();
            sizeBefore = Files.size(imagePath);
        }

        try (FileSystemService fs = open()) {
            assertEquals(Constants.DISK_SIZE, sizeBefore, "磁盘映像必须固定为 8192 字节");
            assertArrayEquals(fatBefore, fs.getFatSnapshot(), "重启后 FAT 应完全一致");
            assertTrue(fs.exists("/DOC/A.TX"));
            assertEquals("中文内容 persistence test", fs.readTypeFile("/DOC/A.TX"));
        }
    }

    // ==================================================================
    //  测试九：坏块与系统块
    // ==================================================================

    @Test
    @DisplayName("测试九：第 23、49 块始终是坏块，系统块永远不被分配")
    void testBadAndSystemBlocksNeverAllocated() throws IOException {
        try (FileSystemService fs = open()) {
            int[] fat = fs.getFatSnapshot();
            assertEquals(Constants.FAT_BAD, fat[23]);
            assertEquals(Constants.FAT_BAD, fat[49]);
            assertEquals(Constants.FAT_SYSTEM, fat[0]);
            assertEquals(Constants.FAT_SYSTEM, fat[1]);
            assertEquals(Constants.FAT_SYSTEM, fat[2]);

            // 尽量写满磁盘，坏块依然不能被分配
            fs.createFile("/BIG.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/BIG.TX", "z".repeat(3000), Constants.WRITE_OVERWRITE);
            fs.createDirectory("/D1");

            fat = fs.getFatSnapshot();
            assertEquals(Constants.FAT_BAD, fat[23], "坏块 23 不能被分配");
            assertEquals(Constants.FAT_BAD, fat[49], "坏块 49 不能被分配");
            assertEquals(Constants.FAT_SYSTEM, fat[2], "根目录块不能被分配");
            for (int block : fs.getFatChain("/BIG.TX")) {
                assertTrue(block >= Constants.FIRST_DATA_BLOCK, "文件块号必须从 3 开始");
                assertTrue(block != 23 && block != 49, "文件不能占用坏块");
            }

            List<BlockInfo> infos = fs.buildBlockInfos();
            assertEquals(BlockInfo.State.BAD, infos.get(23).getState());
            assertEquals(BlockInfo.State.BAD, infos.get(49).getState());
            assertEquals(BlockInfo.State.SYSTEM, infos.get(0).getState());
            assertEquals(BlockInfo.State.ROOT, infos.get(2).getState());
        }
    }

    // ==================================================================
    //  附加测试：磁盘初始化 / 格式化
    // ==================================================================

    @Test
    @DisplayName("附加一：首次启动自动初始化磁盘，布局符合课程规定")
    void testInitialDiskLayout() throws IOException {
        try (FileSystemService fs = open()) {
            assertEquals(Constants.DISK_SIZE, Files.size(imagePath));
            byte[] rootBlock = fs.readBlockBytes(Constants.ROOT_BLOCK);
            assertEquals(Constants.BLOCK_SIZE, rootBlock.length);
            for (byte b : rootBlock) {
                assertEquals('$', (char) b, "根目录初始应全部是空目录项标志 '$'");
            }
            assertEquals(0, fs.listDirectory("/").size());
            // 128 块 - 3 个系统块 - 2 个坏块 = 123 个可用块
            assertEquals(123, fs.getStatistics().getFreeBlocks());
        }
    }

    @Test
    @DisplayName("附加二：格式化后磁盘回到初始状态，且 8192 字节全被重写")
    void testFormatResetsDisk() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createDirectory("/DOC");
            fs.createFile("/DOC/A.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/DOC/A.TX", "hello", Constants.WRITE_OVERWRITE);

            fs.format();

            assertEquals(0, fs.listDirectory("/").size());
            assertEquals(0, fs.getOpenFileTable().size());
            int[] fat = fs.getFatSnapshot();
            assertEquals(Constants.FAT_SYSTEM, fat[0]);
            assertEquals(Constants.FAT_BAD, fat[23]);
            assertEquals(Constants.FAT_FREE, fat[3]);
            assertEquals(123, fs.getStatistics().getFreeBlocks());
        }
    }

    // ==================================================================
    //  附加测试：边界与异常
    // ==================================================================

    @Test
    @DisplayName("附加三：目录最多 8 个目录项，第 9 个被拒绝")
    void testDirectoryCapacity() throws IOException {
        try (FileSystemService fs = open()) {
            for (int i = 1; i <= 8; i++) {
                fs.createFile("/A" + i + ".TX", Constants.ATTR_NORMAL);
                fs.closeFile("/A" + i + ".TX");
            }
            assertThrows(FileSystemException.class,
                    () -> fs.createFile("/A9.TX", Constants.ATTR_NORMAL));
        }
    }

    @Test
    @DisplayName("附加四：非法名称、重名、内容含 '#' 都会被拒绝")
    void testInvalidInputs() throws IOException {
        try (FileSystemService fs = open()) {
            assertThrows(FileSystemException.class, () -> fs.createFile("/TOOLONG.TX", Constants.ATTR_NORMAL));
            assertThrows(FileSystemException.class, () -> fs.createFile("/A.TOOLONG", Constants.ATTR_NORMAL));
            assertThrows(FileSystemException.class, () -> fs.createFile("/A$B.TX", Constants.ATTR_NORMAL));
            assertThrows(FileSystemException.class, () -> fs.createDirectory("/ABCD"));
            assertThrows(FileSystemException.class, () -> fs.createDirectory("/A.B"));

            fs.createFile("/OK.TX", Constants.ATTR_NORMAL);
            assertThrows(FileSystemException.class, () -> fs.createFile("/ok.tx", Constants.ATTR_NORMAL));
            assertThrows(FileSystemException.class,
                    () -> fs.writeFile("/OK.TX", "abc#def", Constants.WRITE_OVERWRITE));

            // 不存在的父目录
            assertThrows(FileSystemException.class, () -> fs.createFile("/NOPE/A.TX", Constants.ATTR_NORMAL));
        }
    }

    @Test
    @DisplayName("附加五：空间不足时拒绝写入，且原文件内容不被破坏")
    void testOutOfSpaceRollback() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/BIG.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/BIG.TX", "original", Constants.WRITE_OVERWRITE);

            // 磁盘总共 8192 字节，写 9000 字节必然放不下
            assertThrows(FileSystemException.class,
                    () -> fs.writeFile("/BIG.TX", "x".repeat(9000), Constants.WRITE_OVERWRITE));

            assertEquals("original", fs.loadContentForDisplay("/BIG.TX"), "写入失败后原内容必须完好");
            assertEquals(1, fs.getEntry("/BIG.TX").getLengthBlocks());
        }
    }

    @Test
    @DisplayName("附加六：重命名文件 / 目录后仍能正常访问")
    void testRename() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createDirectory("/OLD");
            fs.createFile("/OLD/A.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/OLD/A.TX", "content", Constants.WRITE_OVERWRITE);
            fs.closeFile("/OLD/A.TX");

            fs.rename("/OLD/A.TX", "B.TX");
            assertFalse(fs.exists("/OLD/A.TX"));
            assertEquals("content", fs.readTypeFile("/OLD/B.TX"));

            fs.rename("/OLD", "NEW");
            assertFalse(fs.exists("/OLD"));
            assertEquals("content", fs.readTypeFile("/NEW/B.TX"));

            // 重名检查
            fs.createFile("/NEW/C.TX", Constants.ATTR_NORMAL);
            assertThrows(FileSystemException.class, () -> fs.rename("/NEW/C.TX", "B.TX"));
        }
    }

    @Test
    @DisplayName("附加七：读文件从读指针开始，跨块连续读取并推进指针")
    void testReadPointerAcrossBlocks() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/R.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/R.TX", "ABCDEFGHIJ", Constants.WRITE_OVERWRITE);
            fs.closeFile("/R.TX");
            fs.openFile("/R.TX", Constants.OPEN_READ);

            assertEquals("ABC", fs.readFile("/R.TX", 3));
            assertEquals("DEF", fs.readFile("/R.TX", 3));
            assertEquals("GHIJ", fs.readFile("/R.TX", 100));
            assertEquals("", fs.readFile("/R.TX", 10), "读到结束符后应返回空串");

            // 指针应停在文件末尾（第 10 个字节）
            var pointer = fs.getOpenFileTable().find("/R.TX").getReadPointer();
            assertEquals(fs.getEntry("/R.TX").getStartBlock(), pointer.getBlockNumber());
            assertEquals(10, pointer.getOffset());
        }
    }

    @Test
    @DisplayName("附加八：已打开文件占用块在块信息里被标记为“已打开”")
    void testOpenFileBlockHighlight() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/OPN.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/OPN.TX", "y".repeat(70), Constants.WRITE_OVERWRITE);
            List<Integer> chain = fs.getFatChain("/OPN.TX");

            List<BlockInfo> infos = fs.buildBlockInfos();
            for (int block : chain) {
                assertTrue(infos.get(block).isOpened(), "块 " + block + " 应标记为已打开");
                assertEquals("/OPN.TX", infos.get(block).getOpenedBy());
            }

            fs.closeFile("/OPN.TX");
            infos = fs.buildBlockInfos();
            for (int block : chain) {
                assertFalse(infos.get(block).isOpened(), "关闭文件后不应再标记为已打开");
            }
        }
    }

    @Test
    @DisplayName("附加九：文件正在打开时不能删除、不能改属性、不能 typefile")
    void testOperationsRejectedWhileOpen() throws IOException {
        try (FileSystemService fs = open()) {
            fs.createFile("/P.TX", Constants.ATTR_NORMAL);
            fs.writeFile("/P.TX", "abc", Constants.WRITE_OVERWRITE);

            assertThrows(FileSystemException.class, () -> fs.deleteFile("/P.TX"));
            assertThrows(FileSystemException.class,
                    () -> fs.changeAttribute("/P.TX", Constants.ATTR_READ_ONLY));
            assertThrows(FileSystemException.class, () -> fs.readTypeFile("/P.TX"));

            fs.closeFile("/P.TX");
            assertThrows(FileSystemException.class, () -> fs.closeFile("/P.TX"));
        }
    }
}
