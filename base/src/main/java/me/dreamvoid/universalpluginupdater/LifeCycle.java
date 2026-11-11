package me.dreamvoid.universalpluginupdater;

import me.dreamvoid.universalpluginupdater.platform.Platform;
import me.dreamvoid.universalpluginupdater.service.LanguageManager;
import me.dreamvoid.universalpluginupdater.service.RepositoryManager;
import me.dreamvoid.universalpluginupdater.service.UpdateManager;
import me.dreamvoid.universalpluginupdater.service.UpgradeManager;
import me.dreamvoid.universalpluginupdater.update.*;
import me.dreamvoid.universalpluginupdater.upgrade.ApplierUpgradeStrategy;
import me.dreamvoid.universalpluginupdater.upgrade.BackupUpgradeStrategy;
import me.dreamvoid.universalpluginupdater.upgrade.NativeUpgradeStrategy;
import me.dreamvoid.universalpluginupdater.upgrade.UpgradeStrategyRegistry;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import static me.dreamvoid.universalpluginupdater.service.LanguageManager.tr;

public class LifeCycle {
    private final Platform platform;
    private Logger logger;
    private Config config;

    public LifeCycle(Platform plugin) {
        platform = plugin;
    }

    /**
     * 此方法应在插件实例化时调用，用于设置必要的运行环境，此时配置尚未初始化。
     *
     * @param logger {@link java.util.logging.Logger} 实例。由于各平台初始化 Logger 的时机不一，因此需要一个 Logger 来辅助。
     */
    public void startUp(Logger logger) {
        try {
            logger.info(tr("message.lifecycle.startup.start"));

            Utils.setLogger(logger);

            logger.info(tr("message.lifecycle.startup.finish"));
        } catch (Throwable e) {
            logger.severe(tr("message.lifecycle.startup.exception", e));
        }
    }

    public void preLoad() {
        try {
            logger = platform.getPlatformLogger();
            LanguageManager.setPlatform(platform);

            logger.info(tr("message.lifecycle.preload.start"));

            config = platform.getPlatformConfig();
            try {
                logger.info(LanguageManager.tr("message.lifecycle.config.load"));
                config.reloadConfig();
            } catch (IOException e) {
                logger.warning(tr("message.lifecycle.config.exception"));
            }

            List<String> effectiveLoaders = (Config.Platform_Loaders != null && !Config.Platform_Loaders.isEmpty())
                    ? Config.Platform_Loaders
                    : platform.getLoaders();
            List<String> effectiveGameVersions = (Config.Platform_GameVersions != null)
                    ? Config.Platform_GameVersions
                    : platform.getGameVersions();

            logger.info(tr("message.lifecycle.preload.loaders", String.join(", ", effectiveLoaders)));
            logger.info(tr("message.lifecycle.preload.gameversions", effectiveGameVersions == null ? "通用" : String.join(", ", effectiveGameVersions)));

            UpgradeStrategyRegistry.instance().registerStrategy(new NativeUpgradeStrategy(platform));
            UpgradeStrategyRegistry.instance().registerStrategy(new ApplierUpgradeStrategy(platform));

            // 初始化服务
            UpdateManager.initialize(platform);

            // 注册内置更新渠道（与第三方更新渠道使用相同的注册方式）
            UpdateManager.registerChannel(URLUpdate.class);
            UpdateManager.registerChannel(ModrinthUpdate.class);
            UpdateManager.registerChannel(GitHubUpdate.class);
            UpdateManager.registerChannel(HangarUpdate.class);
            UpdateManager.registerChannel(SpigotMCUpdate.class);

            BackupUpgradeStrategy backup = new BackupUpgradeStrategy(platform);
            UpgradeStrategyRegistry.instance().registerStrategy(backup);
            backup.recover();
            UpgradeManager.initialize(platform);
            RepositoryManager.initialize(platform);

            logger.info(tr("message.lifecycle.preload.finish"));
        } catch (Throwable e) {
            logger.severe(tr("message.lifecycle.preload.exception", e));
        }
    }

    public void postLoad() {
        try {
            logger.info(tr("message.lifecycle.postload.start"));

            UpgradeStrategyRegistry.instance().setActiveStrategy(Config.Updater_Strategy);

            logger.info(tr("message.lifecycle.postload.moretasks"));
            logger.info(tr("message.lifecycle.postload.finish"));
        } catch (Exception e) {
            logger.severe(tr("message.lifecycle.postload.exception", e));
        }
    }

    public void unload() {
        try {
            logger.info(tr("message.lifecycle.unload.start"));

            UpgradeManager.ExecutionResult result = UpgradeManager.instance().flushPending();
            if (result.totalCount() > 0) {
                logger.info(tr("message.lifecycle.unload.result", result.successCount(), result.failureCount()));
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }

            logger.info(tr("message.lifecycle.unload.finish"));
        } catch (NoClassDefFoundError e) {
            logger.severe(tr("message.lifecycle.unload.exception.no-class-def-found"));
        } catch (Throwable e) {
            logger.severe(tr("message.lifecycle.unload.exception", e));
        }
    }

    public void reload() {
        logger.info(tr("message.lifecycle.reload.start"));

        try {
            logger.info(LanguageManager.tr("message.lifecycle.config.load"));
            config.reloadConfig();
        } catch (IOException e) {
            logger.warning(tr("message.lifecycle.config.exception"));
        }

        // 重新读取语言配置，使外部语言文件与 language 变更立即生效
        LanguageManager.setPlatform(platform);
        UpgradeStrategyRegistry.instance().setActiveStrategy(Config.Updater_Strategy);

        logger.info(tr("message.lifecycle.reload.finish"));
    }
}
