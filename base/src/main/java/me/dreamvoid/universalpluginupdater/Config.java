package me.dreamvoid.universalpluginupdater;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置
 */
public abstract class Config {
    public static boolean Verbose = false;
    public static String Language = "system";
    public static String Platform_Name = null;
    public static List<String> Platform_GameVersions = null;
    public static List<String> Platform_Loaders = null;
    public static String Updater_Strategy = "native";
    public static boolean Updater_AllowUpgradeNow = true;
    public static String Updater_Filename = "${originName}";
    public static boolean Updater_ForceFilename = false;
    public static String Updater_Backup_Directory = ".disabled";
    public static String Updater_Proxy_Uri = null;
    public static String Updater_Proxy_Username = null;
    public static String Updater_Proxy_Password = null;
    public static int Updater_PluginListMode = 1;
    public static List<String> Updater_PluginList = new ArrayList<>();
    public static int Repository_CheckMode = 1;
    public static List<String> Repository_CheckList = new ArrayList<>();
    public static boolean Strategy_Native_UnloadBeforeUpgrade = true;

    /**
     * 加载/重载配置项
     */
    public void reloadConfig() throws IOException {
        loadConfig();

        Verbose = getBoolean("verbose", Verbose);
        Language = getString("language", Language);

        Platform_Name = getString("platform.name", null);
        Object gameVersionsRaw = getObject("platform.game-versions");
        Platform_GameVersions = gameVersionsRaw == null ? null : getStringList("platform.game-versions");
        Object loadersRaw = getObject("platform.loaders");
        Platform_Loaders = loadersRaw == null ? null : getStringList("platform.loaders");

        Updater_Strategy = getString("updater.strategy", Updater_Strategy);
        Updater_AllowUpgradeNow = getBoolean("updater.allow-upgrade-now", Updater_AllowUpgradeNow);
        Updater_Filename = getString("updater.filename", Updater_Filename);
        Updater_ForceFilename = getBoolean("updater.force-filename", Updater_ForceFilename);
        Updater_Backup_Directory = getString("updater.backup.directory", ".disabled");
        Updater_Proxy_Uri = getString("updater.proxy.uri", null);
        Updater_Proxy_Username = getString("updater.proxy.username", null);
        Updater_Proxy_Password = getString("updater.proxy.password", null);
        Updater_PluginListMode = getInt("updater.plugin-list-mode", Updater_PluginListMode);
        Updater_PluginList = getStringList("updater.plugin-list");
        Repository_CheckMode = getInt("repository.check-mode", Repository_CheckMode);
        Repository_CheckList = getStringList("repository.check-list");

        Strategy_Native_UnloadBeforeUpgrade = getBoolean("strategy.native.unload-before-upgrade", Strategy_Native_UnloadBeforeUpgrade);
    }

    /**
     * 加载配置文件（平台）<br>
     * 在 Bukkit 上，类似 saveDefaultConfig() + reloadConfig()
     */
    public abstract void loadConfig() throws IOException;

    public abstract String getString(String path, String def);

    public abstract int getInt(String path, int def);

    public abstract long getLong(String path, long def);

    public abstract boolean getBoolean(String path, boolean def);

    public abstract List<String> getStringList(String path);

    public abstract Object getObject(String path);
}
