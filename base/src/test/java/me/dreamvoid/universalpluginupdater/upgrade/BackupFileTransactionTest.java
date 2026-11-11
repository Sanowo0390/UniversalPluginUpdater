package me.dreamvoid.universalpluginupdater.upgrade;

import me.dreamvoid.universalpluginupdater.Config;
import me.dreamvoid.universalpluginupdater.platform.Platform;
import me.dreamvoid.universalpluginupdater.service.UpgradeManager;
import me.dreamvoid.universalpluginupdater.update.AbstractUpdate;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * 覆盖事务失败、中断恢复及调度边界
 */
public class BackupFileTransactionTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path jar(Path directory, String name, String descriptor, String id, String payload) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry(descriptor));
            out.write((descriptor.endsWith(".json") ? "{\"id\":\"" + id + "\"}" : "name: '" + id + "'\nmain: example.Main\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            byte[] payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payloadBytes);
            JarEntry payloadEntry = new JarEntry("payload.txt");
            payloadEntry.setMethod(JarEntry.STORED);
            payloadEntry.setSize(payloadBytes.length);
            payloadEntry.setCrc(crc.getValue());
            out.putNextEntry(payloadEntry);
            out.write(payloadBytes);
            out.closeEntry();
        }
        return path;
    }

    private Path root() throws IOException {
        return temp.newFolder().toPath();
    }

    /**
     * 验证 allDescriptorsAndBackupNames 场景
     */
    @Test
    public void allDescriptorsAndBackupNames() throws Exception {
        for (String descriptor : List.of("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json")) {
            Path root = root();
            Path old = jar(root, "Example-1.jar", descriptor, "Example", "old");
            byte[] original = Files.readAllBytes(old);
            Path download = jar(root.resolve("downloads"), "Example-2.jar", descriptor, "example", "new");
            BackupFileTransaction engine = new BackupFileTransaction(root, descriptor);
            Path receipt = engine.prepare("EXAMPLE", download, old, ".disabled");
            assertArrayEquals(original, Files.readAllBytes(old));
            engine.execute(receipt);
            assertFalse(Files.exists(old));
            assertArrayEquals(original, Files.readAllBytes(receipt.resolve("Example-1.jar")));
            assertArrayEquals(Files.readAllBytes(download), Files.readAllBytes(root.resolve("Example-2.jar")));
            assertEquals("COMMITTED", engine.recover(receipt));
        }
    }

    /**
     * 验证 sameFilenameAndUniqueBackupDirectories 场景
     */
    @Test
    public void sameFilenameAndUniqueBackupDirectories() throws Exception {
        Path root = root();
        Path old = jar(root, "Example.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example.jar", "plugin.yml", "example", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        Path first = engine.prepare("example", download, old, ".disabled");
        engine.execute(first);
        Path second = engine.prepare("example", download, old, ".disabled");
        engine.execute(second);
        assertNotEquals(first, second);
        assertTrue(Files.exists(first.resolve("Example.jar")));
        assertTrue(Files.exists(second.resolve("Example.jar")));
    }

    /**
     * 验证 rejectsWrongIdInvalidJarAndFilename 场景
     */
    @Test
    public void rejectsWrongIdInvalidJarAndFilename() throws Exception {
        Path root = root();
        Path old = jar(root, "Example.jar", "plugin.yml", "example", "old");
        byte[] original = Files.readAllBytes(old);
        Path other = jar(root.resolve("downloads"), "Other.jar", "plugin.yml", "other", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        assertThrows(IOException.class, () -> engine.prepare("example", other, old, ".disabled"));
        Path broken = root.resolve("downloads/broken.jar");
        Files.writeString(broken, "not a jar");
        assertThrows(IOException.class, () -> engine.prepare("example", broken, old, ".disabled"));
        Path badName = root.resolve("downloads/NUL.jar");
        assertThrows(IOException.class, () -> engine.prepare("example", badName, old, ".disabled"));
        assertArrayEquals(original, Files.readAllBytes(old));
    }

    /**
     * 验证 rejectsSameFilenameAndDuplicateIdCollisions 场景
     */
    @Test
    public void rejectsSameFilenameAndDuplicateIdCollisions() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        Path occupied = jar(root, "Example-2.jar", "plugin.yml", "other", "other");
        assertThrows(IOException.class, () -> engine.prepare("example", download, old, ".disabled"));
        Files.delete(occupied);
        Path duplicate = jar(root, "duplicate.jar", "plugin.yml", "example", "duplicate");
        assertThrows(IOException.class, () -> engine.prepare("example", download, old, ".disabled"));
        assertTrue(Files.exists(duplicate));
        assertTrue(Files.exists(old));
    }

    /**
     * 验证 verifiesAgainAndUsesFixedDownload 场景
     */
    @Test
    public void verifiesAgainAndUsesFixedDownload() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        byte[] expected = Files.readAllBytes(download);
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        Path receipt = engine.prepare("example", download, old, ".disabled");
        Files.writeString(download, "changed after scheduling");
        engine.execute(receipt);
        assertArrayEquals(expected, Files.readAllBytes(root.resolve("Example-2.jar")));
        Path next = jar(root.resolve("downloads"), "Example-3.jar", "plugin.yml", "example", "next");
        Path second = engine.prepare("example", next, root.resolve("Example-2.jar"), ".disabled");
        jar(root, "Example-2.jar", "plugin.yml", "example", "externally replaced");
        assertThrows(IOException.class, () -> engine.execute(second));
        assertTrue(Files.exists(root.resolve("Example-2.jar")));
    }

    /**
     * 验证 installationFailureRestoresOldJar 场景
     */
    @Test
    public void installationFailureRestoresOldJar() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        byte[] original = Files.readAllBytes(old);
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml", (from, to) -> {
            if (from.getFileName().toString().equals("new.bin")) throw new IOException("simulated placement failure");
            Files.move(from, to);
        });
        Path receipt = engine.prepare("example", download, old, ".disabled");
        assertThrows(IOException.class, () -> engine.execute(receipt));
        assertArrayEquals(original, Files.readAllBytes(old));
        assertFalse(Files.exists(root.resolve("Example-2.jar")));
        assertEquals("ROLLED_BACK", engine.recover(receipt));
    }

    /**
     * 验证 lockedOldJarLeavesOriginalUntouched 场景
     */
    @Test
    public void lockedOldJarLeavesOriginalUntouched() throws Exception {
        Path root = root();
        Path old = jar(root, "Example.jar", "plugin.yml", "example", "old");
        byte[] original = Files.readAllBytes(old);
        Path download = jar(root.resolve("downloads"), "Example.jar", "plugin.yml", "example", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml", (from, to) -> {
            throw new AccessDeniedException(from.toString());
        });
        Path receipt = engine.prepare("example", download, old, ".disabled");
        assertThrows(IOException.class, () -> engine.execute(receipt));
        assertArrayEquals(original, Files.readAllBytes(old));
    }

    /**
     * 验证 failedRestorePreservesBackupAndCanRecoverLater 场景
     */
    @Test
    public void failedRestorePreservesBackupAndCanRecoverLater() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        byte[] original = Files.readAllBytes(old);
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        AtomicInteger moves = new AtomicInteger();
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml", (from, to) -> {
            if (moves.incrementAndGet() > 1) throw new IOException("placement and restore blocked");
            Files.move(from, to);
        });
        Path receipt = engine.prepare("example", download, old, ".disabled");
        IOException error = assertThrows(IOException.class, () -> engine.execute(receipt));
        assertTrue(error.getMessage().contains("Automatic restore failed"));
        assertArrayEquals(original, Files.readAllBytes(receipt.resolve("Example-1.jar")));
        assertEquals("ROLLED_BACK", new BackupFileTransaction(root, "plugin.yml").recover(receipt));
        assertArrayEquals(original, Files.readAllBytes(old));
    }

    /**
     * 验证 crashBeforePlacementRestoresAndAfterPlacementCommits 场景
     */
    @Test
    public void crashBeforePlacementRestoresAndAfterPlacementCommits() throws Exception {
        for (boolean afterPlacement : List.of(false, true)) {
            Path root = root();
            Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
            Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
            BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml", (from, to) -> {
                if (from.getFileName().toString().equals("new.bin")) {
                    if (afterPlacement) Files.move(from, to);
                    throw new AssertionError("simulated process termination");
                }
                Files.move(from, to);
            });
            Path receipt = engine.prepare("example", download, old, ".disabled");
            assertThrows(AssertionError.class, () -> engine.execute(receipt));
            String result = new BackupFileTransaction(root, "plugin.yml").recover(receipt);
            assertEquals(afterPlacement ? "COMMITTED" : "ROLLED_BACK", result);
            assertEquals(!afterPlacement, Files.exists(old));
        }
    }

    /**
     * 验证 recoveryNeverOverwritesUnrelatedFiles 场景
     */
    @Test
    public void recoveryNeverOverwritesUnrelatedFiles() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml", (from, to) -> {
            if (from.getFileName().toString().equals("new.bin")) throw new AssertionError("crash");
            Files.move(from, to);
        });
        Path receipt = engine.prepare("example", download, old, ".disabled");
        assertThrows(AssertionError.class, () -> engine.execute(receipt));
        Path unrelated = root.resolve("Example-2.jar");
        Files.writeString(unrelated, "someone else's file");
        assertThrows(IOException.class, () -> engine.recover(receipt));
        assertEquals("someone else's file", Files.readString(unrelated));
        assertTrue(Files.exists(receipt.resolve("Example-1.jar")));
    }

    /**
     * 验证 rejectsUnsafePathsAndCancelsUnexecutedReservation 场景
     */
    @Test
    public void rejectsUnsafePathsAndCancelsUnexecutedReservation() throws Exception {
        Path root = root();
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        for (String invalid : List.of("../outside", ".", root.toString(), ".disabled/../outside", "C:\\outside")) {
            assertThrows(IOException.class, () -> engine.backupRoot(invalid));
        }
        Path old = jar(root, "Example.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example.jar", "plugin.yml", "example", "new");
        Path receipt = engine.prepare("example", download, old, ".disabled");
        assertEquals("CANCELLED", engine.recover(receipt));
        assertThrows(IOException.class, () -> engine.execute(receipt));
        assertTrue(Files.exists(old));
    }


    /**
     * 拒绝内容损坏但 ZIP 结构仍可读取的 JAR
     */
    @Test
    public void rejectsCorruptedEntryCrc() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "tamper-marker");
        byte[] bytes = Files.readAllBytes(download);
        byte[] marker = "tamper-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean changed = false;
        for (int i = 0; i <= bytes.length - marker.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + marker.length), marker)) {
                bytes[i] ^= 1;
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        Files.write(download, bytes);
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        assertThrows(IOException.class, () -> engine.prepare("example", download, old, ".disabled"));
        assertTrue(Files.exists(old));
    }

    /**
     * 执行前拒绝被篡改的暂存文件及新出现的目标冲突
     */
    @Test
    public void rejectsStagedTamperingAndLateCollision() throws Exception {
        Path root = root();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        byte[] original = Files.readAllBytes(old);
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        Path first = engine.prepare("example", download, old, ".disabled");
        Files.writeString(first.resolve("new.bin"), "modified");
        assertThrows(IOException.class, () -> engine.execute(first));
        Path second = engine.prepare("example", download, old, ".disabled");
        Path occupied = jar(root, "Example-2.jar", "plugin.yml", "other", "unrelated");
        byte[] foreign = Files.readAllBytes(occupied);
        assertThrows(IOException.class, () -> engine.execute(second));
        assertArrayEquals(foreign, Files.readAllBytes(occupied));
        assertArrayEquals(original, Files.readAllBytes(old));
    }

    /**
     * 拒绝符号链接保存目录
     */
    @Test
    public void rejectsSymlinkBackupDirectory() throws Exception {
        Path root = root();
        Path outside = root();
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException(e);
        }
        BackupFileTransaction engine = new BackupFileTransaction(root, "plugin.yml");
        assertThrows(IOException.class, () -> engine.backupRoot("linked/backups"));
        assertFalse(Files.exists(outside.resolve("backups")));
    }

    /**
     * 验证 schedulingImmediateDeduplicationAndNoNativeFallback 场景
     */
    @Test
    public void schedulingImmediateDeduplicationAndNoNativeFallback() throws Exception {
        Path root = root();
        Path data = Files.createDirectory(root.resolve("UniversalPluginUpdater"));
        AtomicInteger unloads = new AtomicInteger();
        Platform platform = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[]{Platform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataPath" -> data;
                    case "getPlatformName" -> "Paper";
                    case "getPlatformLogger" -> Logger.getLogger("backup-test");
                    case "getPlugins" -> List.of("example", "now", "first", "second");
                    case "getPluginFile" -> root.resolve(args[0] + "-old.jar");
                    case "unloadPlugin" -> { unloads.incrementAndGet(); yield true; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UpgradeStrategyRegistry registry = UpgradeStrategyRegistry.instance();
        registry.setActiveStrategy("backup");
        assertNull(registry.getActiveStrategy());
        assertEquals("backup", registry.getActiveStrategyId());
        UpgradeManager.initialize(platform);
        UpgradeManager manager = UpgradeManager.instance();
        Path old = jar(root, "Example-1.jar", "plugin.yml", "example", "old");
        Path download = jar(root.resolve("downloads"), "Example-2.jar", "plugin.yml", "example", "new");
        UpgradeTask example = new UpgradeTask("example", download, old);
        assertEquals(1, manager.submit(List.of(example), true).failureCount());

        AtomicInteger ordinaryCalls = new AtomicInteger();
        registry.registerStrategy(new UpgradeStrategy() {
            @Override
            public String getId() { return "native"; }

            @Override
            public List<UpgradeResult> upgrade(List<UpgradeTask> tasks) {
                ordinaryCalls.addAndGet(tasks.size());
                return tasks.stream().map(task -> new UpgradeResult(task.pluginId(), true)).toList();
            }
        });
        registry.registerStrategy(new BackupUpgradeStrategy(platform));
        Config.Updater_Backup_Directory = ".disabled";
        Config.Updater_AllowUpgradeNow = true;
        assertTrue(manager.submit(List.of(example), false).queued());
        assertTrue(Files.exists(old));
        Path replacement = jar(root.resolve("downloads"), "Example-3.jar", "plugin.yml", "example", "replacement");
        assertTrue(manager.submit(List.of(new UpgradeTask("example", replacement, old)), false).queued());
        Files.writeString(download, "invalid");
        assertEquals(1, manager.submit(List.of(example), false).failureCount());

        Path nowOld = jar(root, "Now-1.jar", "plugin.yml", "now", "old");
        Path nowDownload = jar(root.resolve("downloads"), "Now-2.jar", "plugin.yml", "now", "new");
        UpgradeTask now = new UpgradeTask("now", nowDownload, nowOld);
        assertTrue(manager.submit(List.of(now), false).queued());
        assertEquals(1, manager.submit(List.of(now), true).successCount());
        assertFalse(Files.exists(nowOld));
        assertTrue(Files.exists(root.resolve("Now-2.jar")));

        jar(root, "first-old.jar", "plugin.yml", "first", "old");
        jar(root, "second-old.jar", "plugin.yml", "second", "old");
        List<AbstractUpdate> channels = List.of(sharedDownload(platform, root, "first"), sharedDownload(platform, root, "second"));
        UpgradeManager.ExecutionResult batch = manager.upgradeAll(channels, false, List.of());
        assertTrue(batch.queued());
        assertEquals(1, batch.successCount());
        assertEquals(1, batch.failureCount());

        registry.setActiveStrategy("native");
        assertEquals(1, manager.submit(List.of(example), true).failureCount());
        assertEquals(2, manager.flushPending().successCount());
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(root.resolve("Example-3.jar")));
        assertEquals("first", BackupJar.id(root.resolve("shared.jar"), "plugin.yml", true));
        assertTrue(Files.exists(root.resolve("second-old.jar")));
        registry.setActiveStrategy("backup");
        assertEquals(1, manager.submit(List.of(example), false).failureCount());
        assertEquals(0, unloads.get());
        assertEquals(0, ordinaryCalls.get());
        registry.setActiveStrategy("native");
    }

    private AbstractUpdate sharedDownload(Platform platform, Path root, String id) {
        return new AbstractUpdate(id, platform) {
            @Override
            public boolean hasUpdate() { return true; }

            @Override
            public boolean checkUpdate() { return true; }

            @Override
            public String getVersion() { return "2"; }

            @Override
            public Path download() {
                try {
                    return jar(root.resolve("downloads"), "shared.jar", "plugin.yml", id, "new");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
}
