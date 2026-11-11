package me.dreamvoid.universalpluginupdater.upgrade;

import me.dreamvoid.universalpluginupdater.Config;
import me.dreamvoid.universalpluginupdater.platform.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static me.dreamvoid.universalpluginupdater.service.LanguageManager.tr;

/**
 * 备份旧 JAR 的文件更新策略，不卸载或重载运行中的插件
 */
public final class BackupUpgradeStrategy implements UpgradeStrategy {
    private final Platform platform;
    /** 备份升级策略标识符 */
    public static final String ID = "backup";
    private final Map<String, Prepared> pending = new LinkedHashMap<>();
    private final Map<String, String> completed = new HashMap<>();
    private final Map<String, String> blocked = new HashMap<>();
    private boolean stopping;
    private boolean recoveryBlocked;

    /**
     * 创建备份策略
     * @param platform 平台服务
     */
    public BackupUpgradeStrategy(@NotNull Platform platform) {
        this.platform = platform;
    }

    @Override
    @NotNull
    public String getId() {
        return ID;
    }

    @Override
    @NotNull
    public String getName() {
        return "Backup";
    }

    private Path root() throws IOException {
        return platform.getDataPath().toAbsolutePath().getParent().toRealPath();
    }

    private BackupFileTransaction engine() throws IOException {
        Path root = root();
        return new BackupFileTransaction(root, descriptor(), root.resolve(platform.getDataPath().getFileName())
                .resolve("backup-transactions"), (from, to) -> Files.move(from, to));
    }

    private String descriptor() {
        String name = platform.getPlatformName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "velocity" -> "velocity-plugin.json";
            case "bungeecord", "waterfall" -> "bungee.yml";
            case "paper", "purpur", "folia" -> "paper-plugin.yml";
            default -> "plugin.yml";
        };
    }

    /**
     * 已验证的插件更新
     *
     * @param pluginId 插件标识符
     * @param targetName 目标文件名
     * @param receipt 事务目录
     */
    public record Prepared(String pluginId, String targetName, Path receipt) {}

    /**
     * 固定下载文件并验证插件标识
     *
     * @param task 升级任务
     * @return 已验证的更新
     * @throws IOException 文件验证或准备失败
     */
    public synchronized Prepared prepare(UpgradeTask task) throws IOException {
        String id = task.pluginId().toLowerCase(Locale.ROOT);
        String target = task.newFilePath().getFileName().toString().toLowerCase(Locale.ROOT);
        checkAvailable(id, target);
        Path root = root();
        Path old = task.oldFilePath();
        if (old != null) old = old.toAbsolutePath().normalize();
        if (old == null || !root.equals(old.getParent())) {
            List<Path> candidates = new ArrayList<>();
            try (Stream<Path> stream = Files.list(root)) {
                for (Path candidate : stream.filter(path -> path.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                    try {
                        if (BackupJar.id(candidate, descriptor(), false).equals(id)) candidates.add(candidate);
                    } catch (IOException ignored) {
                    }
                }
            }
            if (candidates.size() > 1) throw new IOException("Multiple installed JARs for " + id);
            if (candidates.isEmpty() && (old != null || platform.getPlugins().stream().anyMatch(id::equalsIgnoreCase))) {
                throw new IOException("Cannot locate original JAR directly inside plugins: " + id);
            }
            old = candidates.isEmpty() ? null : candidates.get(0);
        }
        Path receipt = engine().prepare(id, task.newFilePath(), old, Config.Updater_Backup_Directory);
        return new Prepared(id, target, receipt);
    }

    private void checkAvailable(String id, String target) throws IOException {
        if (stopping) throw new IOException("Shutdown has started");
        if (recoveryBlocked || blocked.containsKey(id) || blocked.containsValue(target)) {
            throw new IOException("Unresolved backup transaction");
        }
        if (completed.containsKey(id) || completed.containsValue(target)) {
            throw new IOException("Restart before updating this plugin or destination again");
        }
        for (Prepared reservation : pending.values()) {
            if (!reservation.pluginId().equals(id) && reservation.targetName().equals(target)) {
                throw new IOException("Destination already reserved: " + target);
            }
        }
    }

    /**
     * 提交已准备的更新
     *
     * @param tasks 已验证的更新
     * @param immediate 是否立即执行
     * @return 各任务的提交结果
     */
    public synchronized List<UpgradeResult> submitPrepared(List<Prepared> tasks, boolean immediate) {
        List<UpgradeResult> results = new ArrayList<>(tasks.size());
        for (Prepared task : tasks) {
            boolean success = false;
            try {
                checkAvailable(task.pluginId(), task.targetName());
                if (immediate) {
                    success = execute(task);
                    if (success) cancel(pending.remove(task.pluginId()));
                } else {
                    cancel(pending.put(task.pluginId(), task));
                    success = true;
                }
            } catch (IOException e) {
                logFailure(task.pluginId(), task.receipt(), e);
                cancel(task);
            }
            results.add(new UpgradeResult(task.pluginId(), success));
        }
        return results;
    }

    /**
     * 检查其他策略是否可以处理指定插件
     *
     * @param task 其他策略的任务
     * @return 是否与备份更新或待恢复事务冲突
     */
    public synchronized boolean owns(UpgradeTask task) {
        String id = task.pluginId().toLowerCase(Locale.ROOT);
        String target = task.newFilePath().getFileName().toString().toLowerCase(Locale.ROOT);
        return recoveryBlocked || blocked.containsKey(id) || blocked.containsValue(target)
                || pending.containsKey(id) || completed.containsKey(id)
                || completed.containsValue(target)
                || pending.values().stream().anyMatch(reservation -> reservation.targetName().equals(target));
    }

    /**
     * 执行已预约的备份更新
     *
     * @return 各任务的执行结果
     */
    public synchronized List<UpgradeResult> flushPending() {
        stopping = true;
        List<UpgradeResult> results = new ArrayList<>(pending.size());
        for (Prepared task : pending.values()) {
            results.add(new UpgradeResult(task.pluginId(), execute(task)));
        }
        pending.clear();
        return results;
    }

    private boolean execute(Prepared task) {
        try {
            if (recoveryBlocked || blocked.containsKey(task.pluginId()) || blocked.containsValue(task.targetName())) {
                throw new IOException("Unresolved backup transaction");
            }
            engine().execute(task.receipt());
            completed.put(task.pluginId(), task.targetName());
            platform.getPlatformLogger().info(tr("message.strategy.backup.success", task.pluginId(), task.receipt()));
            return true;
        } catch (Exception e) {
            if (e instanceof BackupFileTransaction.RecoveryRequiredException) {
                blocked.put(task.pluginId(), task.targetName());
            } else {
                cancel(task);
            }
            logFailure(task.pluginId(), task.receipt(), e);
            return false;
        }
    }

    private void cancel(@Nullable Prepared task) {
        if (task == null) return;
        try {
            engine().cancel(task.receipt());
        } catch (Exception e) {
            logFailure(task.pluginId(), task.receipt(), e);
        }
    }

    private void logFailure(String pluginId, Path path, Exception error) {
        platform.getPlatformLogger().log(java.util.logging.Level.WARNING,
                tr("message.strategy.backup.failed", pluginId, path, error.getMessage()), error);
    }

    @Override
    public @NotNull List<UpgradeResult> upgrade(@NotNull List<UpgradeTask> tasks) {
        List<UpgradeResult> results = new ArrayList<>(tasks.size());
        for (UpgradeTask task : tasks) {
            try {
                results.addAll(submitPrepared(List.of(prepare(task)), true));
            } catch (Exception e) {
                logFailure(task.pluginId(), task.newFilePath(), e);
                results.add(new UpgradeResult(task.pluginId(), false));
            }
        }
        return results;
    }

    /**
     * 恢复中断的备份更新
     */
    public synchronized void recover() {
        if (Files.notExists(platform.getDataPath().resolve("backup-transactions"), LinkOption.NOFOLLOW_LINKS)) return;
        try {
            BackupFileTransaction engine = engine();
            for (Path journal : engine.journals()) {
                try {
                    String result = engine.recoverJournal(journal);
                    if (result != null) {
                        platform.getPlatformLogger().info(tr("message.strategy.backup.recovered", journal, result));
                    }
                } catch (Exception e) {
                    recoveryBlocked = true;
                    platform.getPlatformLogger().severe(tr("message.strategy.backup.recovery-failed", journal, e.getMessage()));
                }
            }
        } catch (Exception e) {
            recoveryBlocked = true;
            platform.getPlatformLogger().severe(tr("message.strategy.backup.recovery-failed", platform.getDataPath(), e.getMessage()));
        }
    }
}
