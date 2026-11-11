package me.dreamvoid.universalpluginupdater.upgrade;

import com.google.gson.JsonObject;
import me.dreamvoid.universalpluginupdater.Utils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.CRC32;

/**
 * 按目标平台校验插件描述与 JAR 完整性
 */
final class BackupJar {
    private BackupJar() {}

    static String id(Path path, String descriptor, boolean verify) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("JAR is not a regular file: " + path);
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            if (verify) {
                byte[] buffer = new byte[16384];
                long total = 0;
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    CRC32 crc = new CRC32();
                    try (InputStream in = jar.getInputStream(entry)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > 1024L * 1024 * 1024) throw new IOException("Expanded JAR exceeds 1 GiB");
                            crc.update(buffer, 0, n);
                        }
                    }
                    if (entry.getCrc() != crc.getValue()) throw new IOException("Invalid JAR CRC: " + entry.getName());
                }
            }
            JarEntry entry = jar.getJarEntry(descriptor);
            if (entry == null && descriptor.equals("paper-plugin.yml")) entry = jar.getJarEntry("plugin.yml");
            if (entry == null && descriptor.equals("bungee.yml")) entry = jar.getJarEntry("plugin.yml");
            if (entry == null) throw new IOException("Missing plugin descriptor: " + descriptor);
            byte[] bytes;
            try (InputStream in = jar.getInputStream(entry)) {
                bytes = in.readNBytes(1024 * 1024 + 1);
            }
            if (bytes.length > 1024 * 1024) throw new IOException("Plugin descriptor too large");
            String text = new String(bytes, StandardCharsets.UTF_8);
            String id;
            if (descriptor.equals("velocity-plugin.json")) {
                JsonObject json = Utils.gson.fromJson(text, JsonObject.class);
                id = json.get("id").getAsString();
            } else {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                Object loaded = new Yaml(new SafeConstructor(options)).load(text);
                if (!(loaded instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)) {
                    throw new IOException("Missing plugin name");
                }
                id = name;
            }
            if (id.isBlank()) throw new IOException("Empty plugin ID");
            return id.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            throw new IOException("Invalid plugin descriptor: " + path, e);
        }
    }
}
