package me.dreamvoid.universalpluginupdater.upgrade;

import me.dreamvoid.universalpluginupdater.Utils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static me.dreamvoid.universalpluginupdater.service.LanguageManager.tr;

/**
 * 升级策略全局注册表<br>
 * 管理所有可用的升级策略
 */
public final class UpgradeStrategyRegistry {
    private static final UpgradeStrategyRegistry INSTANCE = new UpgradeStrategyRegistry();
    private final Logger logger;
    private final Map<String, UpgradeStrategy> strategies = new HashMap<>();

    private UpgradeStrategyRegistry() {
        logger = Utils.getLogger();
    }

    public static UpgradeStrategyRegistry instance() {
        return INSTANCE;
    }

    private String activeStrategy = "native"; // 默认使用 native 策略


    /**
     * 注册一个升级策略
     */
    public void registerStrategy(UpgradeStrategy strategy) {
        if (strategy == null) return;

        strategies.put(strategy.getId(), strategy);

        logger.info(tr("message.service.strategy.registered", strategy.getId(), strategy.getName()));
    }

    /**
     * 获取指定标识符的升级策略
     * @param strategyId 策略标识符
     * @return 策略实现，如果不存在返回null
     */
    @Nullable
    public UpgradeStrategy getStrategy(String strategyId) {
        return strategies.get(strategyId);
    }

    /**
     * 设置当前活跃的升级策略
     * 允许设置尚未注册的策略，因为策略可能在后续才被注册
     * @param strategyId 策略标识符
     */
    public void setActiveStrategy(String strategyId) {
        if (strategyId == null) return;
        
        this.activeStrategy = strategyId;

        // 如果策略已注册，可以获取显示名，否则仅显示标识符
        UpgradeStrategy strategy = strategies.get(strategyId);
        if (strategy != null) {
            logger.info(tr("message.service.strategy.active", strategyId, strategy.getName()));
        } else {
            logger.info(tr("message.service.strategy.active.unregistered", strategyId));
        }
    }

    /**
     * 获取当前活跃的升级策略
     * 如果当前活跃策略不存在，除 backup 外自动回退到 native 策略
     * @return 当前活跃的升级策略
     */
    @Nullable
    public UpgradeStrategy getActiveStrategy() {
        // 检查当前活跃策略是否存在
        UpgradeStrategy strategy = strategies.get(activeStrategy);
        
        // 如果不存在且不是 native，则回退到 native
        if (strategy == null && !activeStrategy.equals("native") && !activeStrategy.equals(BackupUpgradeStrategy.ID)) {
            logger.warning(tr("message.service.strategy.warn.unavailable-fallback", activeStrategy));
            this.activeStrategy = "native";
            strategy = strategies.get("native");
        }
        
        return strategy;
    }

    /**
     * 获取当前活跃的升级策略标识符
     * @return 策略标识符
     */
    public String getActiveStrategyId() {
        return activeStrategy;
    }
}
