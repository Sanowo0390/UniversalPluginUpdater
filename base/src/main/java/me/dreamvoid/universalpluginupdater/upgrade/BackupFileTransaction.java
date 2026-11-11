package me.dreamvoid.universalpluginupdater.upgrade;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/**
 * 带持久化日志的单插件文件事务
 */
final class BackupFileTransaction {
    private final Path root;
    private final String descriptor;
    private final Path journals;
    private final MoveOperation mover;

    BackupFileTransaction(Path root, String descriptor) throws IOException {
        this(root, descriptor, (from, to) -> Files.move(from, to));
    }

    BackupFileTransaction(Path root, String descriptor, MoveOperation mover) throws IOException {
        this(root, descriptor, root.resolve(".backup-transactions"), mover);
    }

    BackupFileTransaction(Path root, String descriptor, Path journals, MoveOperation mover) throws IOException {
        this.root = root.toRealPath();
        this.journals = journals.toAbsolutePath().normalize();
        this.descriptor = descriptor;
        this.mover = mover;
    }

    Path backupRoot(String directory) throws IOException {
        if (directory == null || directory.isBlank() || directory.contains("\\") || directory.contains(":")) {
            throw new IOException("Invalid backup directory");
        }
        Path relative = Path.of(directory);
        if (relative.isAbsolute()) throw new IOException("Backup directory must be relative");
        Path current = root;
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".") || name.equals("..") || name.isBlank()) throw new IOException("Unsafe backup directory");
            current = current.resolve(part);
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    throw new IOException("Backup directory contains a link or non-directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
            if (!current.toRealPath().equals(current)) throw new IOException("Unsafe backup directory: " + current);
        }
        if (current.equals(root) || !Files.getFileStore(root).equals(Files.getFileStore(current))) {
            throw new IOException("Backup directory must be inside plugins on the same filesystem");
        }
        return current;
    }

    Path prepare(String pluginId, Path download, Path old, String directory) throws IOException {
        String id = pluginId.toLowerCase(Locale.ROOT);
        String name = download.getFileName().toString();
        checkName(name);
        Path target = root.resolve(name);
        if (old != null) {
            old = old.toAbsolutePath().normalize();
            checkRootFile(old);
            if (!BackupJar.id(old, descriptor, false).equals(id)) throw new IOException("Old plugin ID mismatch");
        }
        if (!Files.isRegularFile(download, NOFOLLOW_LINKS)) throw new IOException("Invalid download: " + download);
        collision(id, target, old);
        Path backups = backupRoot(directory);
        String updateId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID();
        Path transaction = Files.createDirectory(backups.resolve(updateId));
        Path stage = transaction.resolve("new.bin");
        try {
            Files.copy(download, stage);
            force(stage);
            // 校验的是固定副本，避免下载目录后续变化影响预约
            if (!BackupJar.id(stage, descriptor, true).equals(id)) throw new IOException("Staged plugin ID mismatch");
            Properties state = new Properties();
            state.setProperty("id", id);
            state.setProperty("directory", root.relativize(transaction).toString());
            state.setProperty("descriptor", descriptor);
            state.setProperty("target", name);
            state.setProperty("old", old == null ? "" : old.getFileName().toString());
            state.setProperty("newHash", hash(stage));
            state.setProperty("oldHash", old == null ? "" : hash(old));
            state.setProperty("phase", "PREPARED");
            save(transaction, state);
            return transaction;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(stage);
                Files.deleteIfExists(transaction);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    void execute(Path transaction) throws IOException {
        Properties state = load(transaction);
        Path stage = transaction.resolve("new.bin");
        Path old = old(state);
        Path target = target(state);
        Path backup = old == null ? null : transaction.resolve(old.getFileName());
        if (!state.getProperty("phase").equals("PREPARED")) throw new IOException("Transaction is not prepared");
        if (!matches(stage, state.getProperty("newHash"))) throw new IOException("Staged file changed");
        if (old != null && !matches(old, state.getProperty("oldHash"))) throw new IOException("Installed JAR changed since scheduling");
        collision(state.getProperty("id"), target, old);
        boolean oldMoved = false;
        boolean newMoved = false;
        try {
            phase(transaction, state, "MOVING_OLD");
            if (old != null) {
                mover.move(old, backup);
                oldMoved = true;
            }
            phase(transaction, state, "INSTALLING");
            mover.move(stage, target);
            newMoved = true;
            if (!matches(target, state.getProperty("newHash"))) throw new IOException("Installed JAR hash mismatch");
            phase(transaction, state, "COMMITTED");
        } catch (IOException | RuntimeException failure) {
            try {
                // 只撤销本事务创建且哈希一致的文件，绝不覆盖未知文件
                if (newMoved) {
                    if (!matches(target, state.getProperty("newHash"))) throw new IOException("New destination changed; manual recovery required");
                    mover.move(target, stage);
                }
                if (oldMoved) {
                    if (Files.exists(old, NOFOLLOW_LINKS)) throw new IOException("Original path occupied; manual recovery required");
                    if (!matches(backup, state.getProperty("oldHash"))) {
                        throw new IOException("Backup file changed; manual recovery required");
                    }
                    mover.move(backup, old);
                }
                phase(transaction, state, "ROLLED_BACK");
            } catch (IOException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
                throw new RecoveryRequiredException("Automatic restore failed; keep files and inspect " + transaction
                        + "; original=" + old + "; backup=" + backup + "; target=" + target, failure);
            }
            throw failure;
        }
    }

    String recover(Path transaction) throws IOException {
        Properties state = load(transaction);
        String phase = state.getProperty("phase");
        if (Set.of("COMMITTED", "ROLLED_BACK", "CANCELLED").contains(phase)) return phase;
        Path old = old(state);
        Path target = target(state);
        Path backup = old == null ? null : transaction.resolve(old.getFileName());
        if (phase.equals("PREPARED")) {
            // 未执行预约在重启后取消，防止下一次停止时意外安装过期版本
            cancel(transaction);
            return "CANCELLED";
        }
        if (!Set.of("MOVING_OLD", "INSTALLING").contains(phase)) throw new IOException("Unknown transaction phase");
        if (phase.equals("INSTALLING") && matches(target, state.getProperty("newHash"))
                && (old == null || matches(backup, state.getProperty("oldHash")))
                && (old == null || same(old, target) || !Files.exists(old, NOFOLLOW_LINKS))) {
            phase(transaction, state, "COMMITTED");
            return "COMMITTED";
        }
        if (old != null && !Files.exists(old, NOFOLLOW_LINKS)
                && !Files.exists(target, NOFOLLOW_LINKS) && matches(backup, state.getProperty("oldHash"))) {
            mover.move(backup, old);
            phase(transaction, state, "ROLLED_BACK");
            return "ROLLED_BACK";
        }
        if ((old == null || matches(old, state.getProperty("oldHash")))
                && (old != null && same(old, target) || !Files.exists(target, NOFOLLOW_LINKS))
                && (backup == null || !Files.exists(backup, NOFOLLOW_LINKS))) {
            phase(transaction, state, "CANCELLED");
            return "CANCELLED";
        }
        throw new IOException("Ambiguous interrupted update; manual recovery required: " + transaction
                + "; original=" + old + "; backup=" + backup + "; target=" + target);
    }

    private Properties load(Path transaction) throws IOException {
        if (Files.isSymbolicLink(transaction) || !Files.isDirectory(transaction, NOFOLLOW_LINKS)
                || !transaction.toRealPath().startsWith(root) || transaction.toRealPath().equals(root)) {
            throw new IOException("Unsafe transaction directory");
        }
        Properties state = readJournal(journal(transaction));
        if (!receipt(state).equals(transaction)) throw new IOException("Journal directory mismatch");
        return state;
    }

    List<Path> journals() throws IOException {
        if (Files.notExists(journals, NOFOLLOW_LINKS)) return List.of();
        checkDirectory(journals);
        try (Stream<Path> files = Files.list(journals)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
        }
    }

    String recoverJournal(Path journal) throws IOException {
        Properties state = readJournal(journal);
        if (Set.of("COMMITTED", "ROLLED_BACK", "CANCELLED").contains(state.getProperty("phase"))) {
            return null;
        }
        Path transaction = receipt(state);
        if (!journal(transaction).equals(journal)) throw new IOException("Journal filename mismatch");
        return recover(transaction);
    }

    void cancel(Path transaction) throws IOException {
        Properties state = load(transaction);
        if (!state.getProperty("phase").equals("PREPARED")) return;
        phase(transaction, state, "CANCELLED");
        Files.deleteIfExists(transaction.resolve("new.bin"));
    }

    private Path receipt(Properties state) throws IOException {
        Path relative = Path.of(state.getProperty("directory"));
        Path transaction = root.resolve(relative).normalize();
        if (relative.isAbsolute() || transaction.equals(root)) throw new IOException("Invalid transaction directory");
        checkDirectory(transaction);
        return transaction;
    }

    private Path journal(Path transaction) throws IOException {
        String name = transaction.getFileName().toString();
        if (!name.matches("[0-9]{8}-[0-9]{6}-[0-9a-f-]{36}")) throw new IOException("Invalid transaction ID");
        return journals.resolve(name + ".properties");
    }

    private Properties readJournal(Path journal) throws IOException {
        checkDirectory(journals);
        if (!Files.isRegularFile(journal, NOFOLLOW_LINKS) || Files.size(journal) > 16384) throw new IOException("Invalid journal");
        Properties state = new Properties();
        try (InputStream in = Files.newInputStream(journal)) {
            state.load(in);
        }
        for (String key : List.of("id", "descriptor", "target", "old", "newHash", "oldHash", "phase", "directory")) {
            if (!state.containsKey(key)) throw new IOException("Missing journal field: " + key);
        }
        if (!state.getProperty("descriptor").equals(descriptor)) throw new IOException("Journal platform mismatch");
        checkName(state.getProperty("target"));
        if (!state.getProperty("old").isEmpty()) checkName(state.getProperty("old"));
        return state;
    }

    private void collision(String id, Path target, Path old) throws IOException {
        if (Files.exists(target, NOFOLLOW_LINKS) && (old == null || !same(target, old))) {
            throw new IOException("Destination already exists: " + target);
        }
        try (Stream<Path> jars = Files.list(root)) {
            for (Path path : jars.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                if (old != null && same(path, old)) continue;
                String other;
                try {
                    other = BackupJar.id(path, descriptor, false);
                } catch (IOException ignored) {
                    continue;
                }
                if (id.equals(other)) throw new IOException("Another JAR has the same plugin ID: " + path);
            }
        }
    }

    private void checkRootFile(Path path) throws IOException {
        if (!root.equals(path.getParent()) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw new IOException("Original JAR must be a regular file directly inside plugins: " + path);
        }
        checkName(path.getFileName().toString());
    }

    private static void checkName(String name) throws IOException {
        if (name.isBlank() || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                || name.chars().anyMatch(c -> c < 32 || c == 127 || "/\\:*?\"<>|".indexOf(c) >= 0)
                || name.endsWith(" ") || name.endsWith(".")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])\\..*")) {
            throw new IOException("Unsafe JAR filename: " + name);
        }
    }

    private Path old(Properties state) {
        String old = state.getProperty("old");
        return old.isEmpty() ? null : root.resolve(old);
    }

    private Path target(Properties state) {
        return root.resolve(state.getProperty("target"));
    }

    private static boolean same(Path a, Path b) throws IOException {
        return a.equals(b);
    }

    private static boolean matches(Path path, String hash) throws IOException {
        return Files.isRegularFile(path, NOFOLLOW_LINKS) && hash(path).equals(hash);
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, WRITE)) {
            channel.force(true);
        }
    }

    private void phase(Path transaction, Properties state, String phase) throws IOException {
        state.setProperty("phase", phase);
        save(transaction, state);
    }

    private void save(Path transaction, Properties state) throws IOException {
        checkDirectory(journals.getParent());
        if (Files.notExists(journals, NOFOLLOW_LINKS)) Files.createDirectory(journals);
        checkDirectory(journals);
        Path temp = Files.createTempFile(journals, "journal-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp, WRITE, TRUNCATE_EXISTING)) {
                state.store(out, "UPU backup transaction");
            }
            force(temp);
            Files.move(temp, journal(transaction), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void checkDirectory(Path directory) throws IOException {
        if (!directory.startsWith(root)) throw new IOException("Directory escapes plugins");
        Path current = root;
        for (Path part : root.relativize(directory)) {
            if (part.toString().isEmpty()) continue;
            current = current.resolve(part);
            if (!Files.isDirectory(current, NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) {
                throw new IOException("Unsafe directory: " + current);
            }
        }
    }

    static final class RecoveryRequiredException extends IOException {
        RecoveryRequiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface MoveOperation {
        void move(Path from, Path to) throws IOException;
    }
}
