package me.dreamvoid.universalpluginupdater.service;

import me.dreamvoid.universalpluginupdater.Config;
import me.dreamvoid.universalpluginupdater.LifeCycle;
import me.dreamvoid.universalpluginupdater.platform.Platform;
import me.dreamvoid.universalpluginupdater.update.AbstractUpdate;
import me.dreamvoid.universalpluginupdater.upgrade.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static me.dreamvoid.universalpluginupdater.service.LanguageManager.tr;

/**
 * 升级管理器<br>
 * 此服务在 {@link LifeCycle#preLoad()} 通过 {@link #initialize(Platform)} 实例化，并通过 {@link #instance()} 提供实例。
 */
public final class UpgradeManager {
    private static UpgradeManager INSTANCE;
    private final Logger logger;
    private final Platform platform;

    private boolean stopping;
    private final Queue<UpgradeTask> pendingUpgrades = new ConcurrentLinkedQueue<>();

    private UpgradeManager(Platform platform) {
        this.logger = platform.getPlatformLogger();
        this.platform = platform;
    }

    public static UpgradeManager instance() {
        if (INSTANCE != null) {
            return INSTANCE;
        } else {
            throw new IllegalStateException(tr("message.service.error.not-initialized", "UpgradeManager"));
        }
    }

    public static void initialize(Platform platform) {
        if (INSTANCE == null) {
            INSTANCE = new UpgradeManager(platform);
        } else {
            throw new IllegalStateException();
        }
    }

    /**
     * 提交一批升级任务<br>
     * RUNTIME 策略立即执行；SHUTDOWN 策略默认排队，--now 可强制立即执行
     *
     * @param tasks      升级任务
     * @param executeNow 是否由 --now 要求立即执行
     * @return 提交结果
     */
    public synchronized ExecutionResult submit(List<UpgradeTask> tasks, boolean executeNow) {
        if (tasks == null || tasks.isEmpty()) {
            return new ExecutionResult(0, 0, false);
        }

        UpgradeStrategy strategy = UpgradeStrategyRegistry.instance().getActiveStrategy();
        if (strategy instanceof BackupUpgradeStrategy backup) {
            List<BackupUpgradeStrategy.Prepared> prepared = new ArrayList<>();
            for (UpgradeTask task : tasks) {
                prepareBackup(backup, task, prepared);
            }
            return submitBackup(backup, prepared, executeNow, tasks.size() - prepared.size());
        }
        return submitOrdinary(strategy, tasks, executeNow);
    }

    private ExecutionResult submitOrdinary(UpgradeStrategy strategy, List<UpgradeTask> tasks, boolean executeNow) {
        if (tasks.isEmpty()) return new ExecutionResult(0, 0, false);
        if (stopping) return new ExecutionResult(0, tasks.size(), false);
        if (strategy == null) {
            logUnavailable(tasks.size());
            return new ExecutionResult(0, tasks.size(), false);
        }

        int originalCount = tasks.size();
        BackupUpgradeStrategy backup = registeredBackup();
        if (backup != null) {
            tasks = tasks.stream().filter(task -> !backup.owns(task)).toList();
        }
        int conflicts = originalCount - tasks.size();
        if (conflicts > 0) logger.warning(tr("message.strategy.backup.conflict", conflicts));
        if (tasks.isEmpty()) return new ExecutionResult(0, conflicts, false);
        Set<String> pluginIds = tasks.stream().map(task -> task.pluginId().toLowerCase()).collect(Collectors.toSet());
        pendingUpgrades.removeIf(task -> pluginIds.contains(task.pluginId().toLowerCase()));

        boolean forced = executeNow && Config.Updater_AllowUpgradeNow;
        if (forced || strategy.getExecutionPhase() == ExecutionPhase.RUNTIME) {
            ExecutionResult result = executeBatch(strategy, tasks);
            return new ExecutionResult(result.successCount(), result.failureCount() + conflicts, false);
        } else {
            pendingUpgrades.addAll(tasks);
            logger.info(tr("message.service.upgrade.queued", tasks.size(), strategy.getId()));
            return new ExecutionResult(tasks.size(), conflicts, true);
        }
    }

    /**
     * 在插件禁用阶段执行所有待处理任务
     *
     * @return 执行结果
     */
    public synchronized ExecutionResult flushPending() {
        stopping = true;
        BackupUpgradeStrategy backup = registeredBackup();
        ExecutionResult backedUp = backup == null
                ? new ExecutionResult(0, 0, false) : summarize(backup.flushPending(), false);

        List<UpgradeTask> tasks = new ArrayList<>();
        int conflicts = 0;
        UpgradeTask task;
        while ((task = pendingUpgrades.poll()) != null) {
            if (backup != null && backup.owns(task)) conflicts++;
            else tasks.add(task);
        }
        if (conflicts > 0) logger.warning(tr("message.strategy.backup.conflict", conflicts));
        if (tasks.isEmpty()) {
            return new ExecutionResult(backedUp.successCount(), backedUp.failureCount() + conflicts, false);
        }

        UpgradeStrategy strategy = UpgradeStrategyRegistry.instance().getActiveStrategy();
        if (strategy == null || strategy instanceof BackupUpgradeStrategy) {
            logUnavailable(tasks.size());
            return new ExecutionResult(backedUp.successCount(), backedUp.failureCount() + conflicts + tasks.size(), false);
        }
        ExecutionResult ordinary = executeBatch(strategy, tasks);
        return new ExecutionResult(backedUp.successCount() + ordinary.successCount(),
                backedUp.failureCount() + ordinary.failureCount() + conflicts, false);
    }

    /**
     * 批量执行升级操作（下载 + 升级）<br>
     * 遍历更新渠道，过滤出存在更新的渠道，下载新文件后统一提交给当前升级策略
     *
     * @param channels      更新渠道实例列表
     * @param executeNow    是否立即执行（false 则延迟到插件卸载阶段执行）
     * @param targetPlugins 目标插件ID白名单（小写，为空或null表示全部）
     * @return 升级执行结果统计
     */
    public ExecutionResult upgradeAll(List<AbstractUpdate> channels, boolean executeNow, List<String> targetPlugins) {
        List<UpgradeTask> tasks = new ArrayList<>();
        int failureCount = 0;
        UpgradeStrategy strategy = UpgradeStrategyRegistry.instance().getActiveStrategy();
        BackupUpgradeStrategy backup = strategy instanceof BackupUpgradeStrategy selected ? selected : null;
        List<BackupUpgradeStrategy.Prepared> prepared = new ArrayList<>();
        boolean immediate = strategy != null && ((executeNow && Config.Updater_AllowUpgradeNow)
                || strategy.getExecutionPhase() == ExecutionPhase.RUNTIME);

        for (AbstractUpdate channel : channels) {
            String pluginId = channel.getPluginId();

            if (targetPlugins != null && !targetPlugins.isEmpty() && !targetPlugins.contains(pluginId.toLowerCase())) {
                continue;
            }

            if (!channel.hasUpdate()) {
                continue;
            }

            logger.info(tr(immediate ? "message.command.upgrade.executing" : "message.command.upgrade.scheduling", pluginId));

            try {
                // 下载新文件
                Path newPluginFile = channel.download();
                if (newPluginFile == null || !Files.exists(newPluginFile)) {
                    logger.warning(tr("message.update.error.downloaded-file-missing", newPluginFile));
                    failureCount++;
                    continue;
                }

                // 获取当前插件文件
                Path oldPluginFile = platform.getPluginFile(pluginId);
                UpgradeTask task = new UpgradeTask(pluginId, newPluginFile, oldPluginFile);
                if (backup == null) {
                    tasks.add(task);
                } else if (!prepareBackup(backup, task, prepared)) {
                    failureCount++;
                }
            } catch (Exception e) {
                logger.warning(tr("message.command.upgrade.error.failed.exception", pluginId, e.getMessage()));
                failureCount++;
            }
        }

        if (backup != null) return submitBackup(backup, prepared, executeNow, failureCount);
        synchronized (this) {
            ExecutionResult result = submitOrdinary(strategy, tasks, executeNow);
            return new ExecutionResult(result.successCount(), result.failureCount() + failureCount, result.queued());
        }
    }

    private boolean prepareBackup(BackupUpgradeStrategy backup, UpgradeTask task,
                                  List<BackupUpgradeStrategy.Prepared> prepared) {
        try {
            // 必须在下载下一文件前固定内容，共用下载目录可能出现同名文件
            prepared.add(backup.prepare(task));
            return true;
        } catch (Exception e) {
            logger.warning(tr("message.strategy.backup.failed", task.pluginId(), task.newFilePath(), e.getMessage()));
            return false;
        }
    }

    private synchronized ExecutionResult submitBackup(BackupUpgradeStrategy backup,
                                                      List<BackupUpgradeStrategy.Prepared> tasks,
                                                      boolean executeNow, int failures) {
        boolean immediate = executeNow && Config.Updater_AllowUpgradeNow;
        List<UpgradeResult> results = backup.submitPrepared(tasks, immediate);
        Set<String> accepted = results.stream().filter(UpgradeResult::success)
                .map(result -> result.pluginId().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        pendingUpgrades.removeIf(task -> accepted.contains(task.pluginId().toLowerCase(Locale.ROOT)));
        boolean queued = !immediate && !accepted.isEmpty();
        if (queued) logger.info(tr("message.service.upgrade.queued", accepted.size(), backup.getId()));
        ExecutionResult result = summarize(results, queued);
        return new ExecutionResult(result.successCount(), result.failureCount() + failures, queued);
    }

    private BackupUpgradeStrategy registeredBackup() {
        UpgradeStrategy strategy = UpgradeStrategyRegistry.instance().getStrategy(BackupUpgradeStrategy.ID);
        return strategy instanceof BackupUpgradeStrategy backup ? backup : null;
    }

    private void logUnavailable(int count) {
        String key = BackupUpgradeStrategy.ID.equals(UpgradeStrategyRegistry.instance().getActiveStrategyId())
                ? "message.strategy.backup.unavailable" : "message.service.upgrade.error.native-unavailable";
        logger.severe(tr(key, count));
    }

    private ExecutionResult executeBatch(UpgradeStrategy strategy, List<UpgradeTask> tasks) {
        try {
            return summarize(strategy.upgrade(tasks), false);
        } catch (Exception e) {
            logger.warning(tr("message.service.upgrade.execute.error.exception", tasks.size(), e));
            return new ExecutionResult(0, tasks.size(), false);
        }
    }

    private ExecutionResult summarize(List<UpgradeResult> results, boolean queued) {
        int success = 0;
        int failure = 0;
        for (UpgradeResult result : results) {
            if (result.success()) {
                success++;
            } else {
                failure++;
                logger.warning(tr("message.command.upgrade.error.failed", result.pluginId()));
            }
        }
        return new ExecutionResult(success, failure, queued);
    }

    /**
     * 升级执行结果
     *
     * @param successCount 成功数量；{@code queued} 为 true 时表示已排队的数量
     * @param failureCount 失败数量
     * @param queued       成功数量是否代表已排队的任务
     */
    public record ExecutionResult(
            int successCount,
            int failureCount,
            boolean queued
    ) {
        public int totalCount() {
            return successCount + failureCount;
        }
    }
}