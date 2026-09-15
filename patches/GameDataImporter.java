package com.kurdish.roleplay.launcher.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports GTA SA Android data selected by the user into the VN-RP game directory.
 * This class does not download or bundle GTA assets; it only copies a folder/OBB
 * the user explicitly selects from storage.
 */
public final class GameDataImporter {

    private GameDataImporter() {}

    public interface Callback {
        void onStatus(String message);
        void onComplete(Validation validation);
        void onError(String message, Throwable error);
    }

    public static final class Validation {
        public final boolean valid;
        public final String message;

        public Validation(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
    }

    private static final class Node {
        final DocumentFile file;
        final int depth;
        Node(DocumentFile file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }

    private static final class Stats {
        long bytes;
        int files;
    }

    public static Validation validateInstalled(File baseDir) {
        if (baseDir == null || !baseDir.exists()) {
            return new Validation(false, "Chưa có thư mục dữ liệu game.");
        }

        File data = findChildIgnoreCase(baseDir, "data");
        File texdb = findChildIgnoreCase(baseDir, "texdb");
        if (data == null || !data.isDirectory()) {
            return new Validation(false, "Thiếu thư mục data của GTA SA.");
        }
        if (texdb == null || !texdb.isDirectory()) {
            return new Validation(false, "Thiếu thư mục texdb của GTA SA.");
        }

        boolean hasMain = findFileRecursiveShallow(data, "mainv1.scm", 2) != null;
        boolean hasGta3 = hasPrefixFile(texdb, "gta3");
        if (!hasMain) {
            return new Validation(false, "Thiếu data/mainV1.scm của GTA SA.");
        }
        if (!hasGta3) {
            return new Validation(false, "Thiếu bộ texture gta3 trong texdb.");
        }

        long bytes = folderSizeShallow(texdb, 2);
        if (bytes < 50L * 1024L * 1024L) {
            return new Validation(false, "Dữ liệu texdb quá nhỏ, có thể bộ GTA chưa đầy đủ.");
        }

        return new Validation(true,
                "Đã nhận diện dữ liệu GTA SA. texdb=" + human(bytes));
    }

    public static void importFromTree(Context context, Uri treeUri, File destination, Callback callback) {
        new Thread(() -> {
            try {
                if (treeUri == null) {
                    throw new IllegalArgumentException("Chưa chọn thư mục.");
                }

                DocumentFile selected = DocumentFile.fromTreeUri(context, treeUri);
                if (selected == null || !selected.exists() || !selected.isDirectory()) {
                    throw new IllegalArgumentException("Không đọc được thư mục đã chọn.");
                }

                callback.onStatus("Đang nhận diện dữ liệu GTA SA...");
                DocumentFile gameRoot = findGameRoot(selected);

                if (gameRoot != null) {
                    Stats stats = scan(gameRoot, 0);
                    if (stats.files == 0) {
                        throw new IllegalArgumentException("Thư mục GTA không có file dữ liệu.");
                    }
                    ensureSpace(destination, stats.bytes);
                    callback.onStatus("Tìm thấy " + stats.files + " file (" + human(stats.bytes) + "). Đang sao chép...");
                    long[] copied = new long[]{0L};
                    copyDirectory(context.getContentResolver(), gameRoot, destination, copied, stats.bytes, callback, 0);
                } else {
                    DocumentFile obb = findLargestObb(selected);
                    if (obb == null) {
                        throw new IllegalArgumentException(
                                "Không tìm thấy cấu trúc GTA SA (data + texdb) hoặc file .obb trong thư mục đã chọn.");
                    }
                    ensureSpace(destination, Math.max(obb.length(), 512L * 1024L * 1024L));
                    callback.onStatus("Tìm thấy " + safeName(obb) + ". Đang giải nén dữ liệu game...");
                    extractObb(context.getContentResolver(), obb.getUri(), destination, callback);
                }

                Validation validation = validateInstalled(destination);
                if (!validation.valid) {
                    throw new IllegalStateException("Đã sao chép nhưng dữ liệu chưa hợp lệ: " + validation.message);
                }
                callback.onComplete(validation);
            } catch (Throwable t) {
                callback.onError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
            }
        }, "VNRP-GTA-Importer").start();
    }

    private static DocumentFile findGameRoot(DocumentFile selected) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(selected, 0));
        int inspected = 0;

        while (!queue.isEmpty() && inspected < 160) {
            Node node = queue.removeFirst();
            inspected++;
            if (looksLikeGameRoot(node.file)) {
                return node.file;
            }
            if (node.depth >= 3) continue;

            for (DocumentFile child : safeList(node.file)) {
                if (!child.isDirectory()) continue;
                String name = safeName(child).toLowerCase(Locale.ROOT);
                if (name.equals("cache") || name.equals("download") || name.equals("samp")) continue;
                queue.addLast(new Node(child, node.depth + 1));
            }
        }
        return null;
    }

    private static boolean looksLikeGameRoot(DocumentFile dir) {
        if (dir == null || !dir.isDirectory()) return false;
        DocumentFile data = findChildIgnoreCase(dir, "data");
        DocumentFile texdb = findChildIgnoreCase(dir, "texdb");
        if (data == null || texdb == null || !data.isDirectory() || !texdb.isDirectory()) return false;
        return findFileRecursiveShallow(data, "mainv1.scm", 2) != null && hasPrefixFile(texdb, "gta3");
    }

    private static DocumentFile findLargestObb(DocumentFile selected) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(selected, 0));
        DocumentFile best = null;
        long bestSize = -1;
        int inspected = 0;

        while (!queue.isEmpty() && inspected < 200) {
            Node node = queue.removeFirst();
            inspected++;
            for (DocumentFile child : safeList(node.file)) {
                if (child.isDirectory() && node.depth < 2) {
                    queue.addLast(new Node(child, node.depth + 1));
                    continue;
                }
                String name = safeName(child).toLowerCase(Locale.ROOT);
                if (child.isFile() && name.endsWith(".obb") && child.length() > bestSize) {
                    best = child;
                    bestSize = child.length();
                }
            }
        }
        return best;
    }

    private static Stats scan(DocumentFile dir, int depth) {
        Stats result = new Stats();
        if (depth > 20) return result;
        for (DocumentFile child : safeList(dir)) {
            String name = safeName(child);
            if (shouldSkip(name)) continue;
            if (child.isDirectory()) {
                Stats nested = scan(child, depth + 1);
                result.bytes += nested.bytes;
                result.files += nested.files;
            } else if (child.isFile()) {
                result.files++;
                result.bytes += Math.max(0L, child.length());
            }
        }
        return result;
    }

    private static void copyDirectory(ContentResolver resolver, DocumentFile srcDir, File dstDir,
                                      long[] copied, long total, Callback callback, int depth) throws Exception {
        if (depth > 30) throw new IllegalStateException("Cấu trúc thư mục quá sâu.");
        if (!dstDir.exists() && !dstDir.mkdirs()) {
            throw new IllegalStateException("Không tạo được thư mục " + dstDir.getAbsolutePath());
        }

        for (DocumentFile child : safeList(srcDir)) {
            String name = safeName(child);
            if (name.isEmpty() || shouldSkip(name)) continue;
            File out = new File(dstDir, name);

            if (child.isDirectory()) {
                copyDirectory(resolver, child, out, copied, total, callback, depth + 1);
            } else if (child.isFile()) {
                long expected = child.length();
                if (out.exists() && expected > 0 && out.length() == expected) {
                    copied[0] += expected;
                    continue;
                }
                copyOne(resolver, child.getUri(), out);
                copied[0] += Math.max(expected, out.length());
                int percent = total > 0 ? (int)Math.min(100L, copied[0] * 100L / total) : -1;
                callback.onStatus((percent >= 0 ? percent + "% - " : "") + name +
                        " (" + human(copied[0]) + (total > 0 ? "/" + human(total) : "") + ")");
            }
        }
    }

    private static void copyOne(ContentResolver resolver, Uri source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Không tạo được " + parent.getAbsolutePath());
        }
        File temp = new File(destination.getAbsolutePath() + ".vnrp-part");
        if (temp.exists()) temp.delete();

        try (InputStream raw = resolver.openInputStream(source);
             BufferedInputStream input = new BufferedInputStream(raw, 1024 * 1024);
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temp), 1024 * 1024)) {
            if (raw == null) throw new IllegalStateException("Không mở được file nguồn.");
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }

        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Không thể thay file " + destination.getName());
        }
        if (!temp.renameTo(destination)) {
            throw new IllegalStateException("Không thể hoàn tất file " + destination.getName());
        }
    }

    private static void extractObb(ContentResolver resolver, Uri uri, File destination, Callback callback) throws Exception {
        long extracted = 0L;
        int files = 0;
        try (InputStream raw = resolver.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 1024 * 1024))) {
            if (raw == null) throw new IllegalStateException("Không mở được OBB.");
            ZipEntry entry;
            byte[] buffer = new byte[1024 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String relative = normalizeArchivePath(entry.getName());
                if (relative.isEmpty() || shouldSkip(relative)) {
                    zip.closeEntry();
                    continue;
                }
                File out = safeDestination(destination, relative);
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(out), 1024 * 1024)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            extracted += count;
                        }
                    }
                    files++;
                    if ((files & 7) == 0) {
                        callback.onStatus("Đang giải nén: " + relative + " - " + human(extracted));
                    }
                }
                zip.closeEntry();
            }
        }
        if (files == 0) {
            throw new IllegalArgumentException("File OBB không phải ZIP/OBB dữ liệu GTA SA có thể đọc được.");
        }
    }

    private static String normalizeArchivePath(String raw) {
        if (raw == null) return "";
        String path = raw.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        String lower = path.toLowerCase(Locale.ROOT);
        String marker = "com.rockstargames.gtasa/files/";
        int idx = lower.indexOf(marker);
        if (idx >= 0) return path.substring(idx + marker.length());
        if (lower.startsWith("files/")) return path.substring(6);
        return path;
    }

    private static File safeDestination(File root, String relative) throws Exception {
        File out = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        String outPath = out.getCanonicalPath();
        if (!outPath.startsWith(rootPath)) {
            throw new SecurityException("Đường dẫn OBB không an toàn: " + relative);
        }
        return out;
    }

    private static void ensureSpace(File destination, long expectedBytes) {
        if (!destination.exists()) destination.mkdirs();
        long free = destination.getUsableSpace();
        if (expectedBytes > 0 && free > 0 && free < expectedBytes + 256L * 1024L * 1024L) {
            throw new IllegalStateException("Không đủ bộ nhớ trống. Cần khoảng " + human(expectedBytes) +
                    ", còn " + human(free) + ".");
        }
    }

    private static boolean shouldSkip(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("samp") || lower.equals("download") || lower.endsWith(".apk") ||
                lower.equals("samp_log.txt") || lower.equals("vnrp_native_stage.txt");
    }

    private static DocumentFile[] safeList(DocumentFile dir) {
        try {
            DocumentFile[] list = dir.listFiles();
            return list == null ? new DocumentFile[0] : list;
        } catch (Throwable ignored) {
            return new DocumentFile[0];
        }
    }

    private static String safeName(DocumentFile file) {
        String name = file == null ? null : file.getName();
        return name == null ? "" : name;
    }

    private static DocumentFile findChildIgnoreCase(DocumentFile dir, String name) {
        for (DocumentFile child : safeList(dir)) {
            if (name.equalsIgnoreCase(safeName(child))) return child;
        }
        return null;
    }

    private static File findChildIgnoreCase(File dir, String name) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File child : list) {
            if (name.equalsIgnoreCase(child.getName())) return child;
        }
        return null;
    }

    private static DocumentFile findFileRecursiveShallow(DocumentFile dir, String filename, int depth) {
        if (dir == null || depth < 0) return null;
        for (DocumentFile child : safeList(dir)) {
            if (child.isFile() && filename.equalsIgnoreCase(safeName(child))) return child;
            if (child.isDirectory() && depth > 0) {
                DocumentFile nested = findFileRecursiveShallow(child, filename, depth - 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static File findFileRecursiveShallow(File dir, String filename, int depth) {
        if (dir == null || depth < 0) return null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File child : list) {
            if (child.isFile() && filename.equalsIgnoreCase(child.getName())) return child;
            if (child.isDirectory() && depth > 0) {
                File nested = findFileRecursiveShallow(child, filename, depth - 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean hasPrefixFile(DocumentFile dir, String prefix) {
        String target = prefix.toLowerCase(Locale.ROOT);
        for (DocumentFile child : safeList(dir)) {
            if (child.isFile() && safeName(child).toLowerCase(Locale.ROOT).startsWith(target) && child.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrefixFile(File dir, String prefix) {
        File[] list = dir.listFiles();
        if (list == null) return false;
        String target = prefix.toLowerCase(Locale.ROOT);
        for (File child : list) {
            if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).startsWith(target) && child.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static long folderSizeShallow(File dir, int depth) {
        if (dir == null || depth < 0) return 0L;
        long size = 0L;
        File[] list = dir.listFiles();
        if (list == null) return 0L;
        for (File child : list) {
            if (child.isFile()) size += Math.max(0L, child.length());
            else if (child.isDirectory() && depth > 0) size += folderSizeShallow(child, depth - 1);
        }
        return size;
    }

    private static String human(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }
}
