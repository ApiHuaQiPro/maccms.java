package com.maccms.backend.legacyconfig.application;

import com.maccms.backend.legacyconfig.infrastructure.AtomicFiles;
import com.maccms.backend.legacyconfig.infrastructure.LegacySiteRoot;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 旧站根目录（旧 {@code ROOT_PATH}：{@code maccms.legacy-config.dir} 指向的 {@code application/} 目录的上一级，旧版 {@code APP_PATH}
 * 固定是 {@code ROOT_PATH . 'application/'}）中后台要读写的文件（B15，24 文档第 3.4 节）。按用途限定区域：
 * <ul>
 * <li>{@code static/}：播放器配置 {@code static/js/playerconfig.js}、统计代码 {@code static/js/tj.js}、插件脚本 {@code static/js/addons.js}、
 * 播放器代码 {@code static/player/*.js}、插件资源 {@code static/addons/*}；</li>
 * <li>{@code template/}：模板与广告文件（模板在线编辑）；{@code addons/}：本地插件；</li>
 * <li>{@code application/data/backup/}：数据库备份（旧 {@code db.backup_path} 的默认位置）；</li>
 * <li>只列目录：{@code application/lang/}（语言包选择）、{@code application/common/extend/}（扩展列表 {@code mac_extends_list}）；</li>
 * <li>插件的全局文件（旧 {@code Service::getCheckDirs} 的 {@code application/}、{@code static/}，{@link #replaceAddonGlobal}）：
 * {@code application/} 下除顶层文件、{@code extra/}、{@code data/install/}、{@code lang/} 以外的位置。</li>
 * </ul>
 * 旧配置文件（{@code application/extra/*.php}、{@code route.php}）只经 {@link LegacyConfigService} 读写；{@code database.php} 等其他文件
 * 一律访问不到。没有配置旧配置目录时 {@link #isConfigured()} 为 false，各操作抛 {@link FileException}。
 */
@Service
public class LegacySiteFiles {

    /** 失败原因（信息不含路径与内容）。 */
    public enum Reason {
        NOT_CONFIGURED, FORBIDDEN, INVALID_NAME, OUTSIDE_ROOT, NOT_REGULAR_FILE, IS_DIRECTORY, TOO_LARGE, CONFLICT, IO
    }

    /** 文件访问失败。 */
    public static final class FileException extends RuntimeException {
        private final Reason reason;

        public FileException(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** 目录项。 */
    public record Entry(String name, boolean directory, long size, long modifiedEpochSeconds) {
    }

    private enum Mode {
        READ, WRITE, LIST
    }

    private static final List<String> RW_AREAS = List.of("static/", "template/", "addons/", "application/data/backup/");
    private static final List<String> LIST_AREAS = List.of("application/lang", "application/common/extend/");

    private final LegacySiteRoot root;

    public LegacySiteFiles(LegacyConfigProperties properties) {
        String dir = properties == null || properties.dir() == null ? "" : properties.dir().strip();
        if (dir.isEmpty()) {
            this.root = null;
            return;
        }
        try {
            Path app = Path.of(dir).toRealPath();
            this.root = new LegacySiteRoot(app.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("maccms.legacy-config.dir（留空，或已存在的旧站 application 目录）", null);
        }
    }

    public boolean isConfigured() {
        return root != null;
    }

    public byte[] read(String relative, int maxBytes) {
        LegacySiteRoot r = check(relative, Mode.READ);
        try {
            return r.read(relative, maxBytes);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    /**
     * 读取扩展类的源码（只限 {@code application/common/extend/<类型>/<类名>.php}，B15 取扩展的显示名用；不执行，只按文本查找）。
     */
    public byte[] readListedSource(String relative, int maxBytes) {
        if (root == null) {
            throw new FileException(Reason.NOT_CONFIGURED);
        }
        if (!LegacySiteRoot.validRelative(relative) || !relative.matches("application/common/extend/[a-z]+/[A-Za-z0-9_]+\\.php")) {
            throw new FileException(Reason.FORBIDDEN);
        }
        try {
            return root.read(relative, maxBytes);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public Entry stat(String relative) {
        LegacySiteRoot r = check(relative, Mode.LIST);
        try {
            LegacySiteRoot.Entry e = r.stat(relative);
            return e == null ? null : new Entry(e.name(), e.directory(), e.size(), e.modifiedEpochSeconds());
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public List<Entry> list(String relative) {
        LegacySiteRoot r = check(relative, Mode.LIST);
        try {
            return r.list(relative).stream().map(e -> new Entry(e.name(), e.directory(), e.size(), e.modifiedEpochSeconds())).toList();
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public List<String> walkFiles(String relative) {
        LegacySiteRoot r = check(relative, Mode.READ);
        try {
            return r.walkFiles(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public String version(String relative) {
        LegacySiteRoot r = check(relative, Mode.READ);
        try {
            return r.version(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    /** 原子写回（{@code expectedVersion} 为 null 时不检查他人修改）。 */
    public void replace(String relative, byte[] content, String expectedVersion) {
        LegacySiteRoot r = check(relative, Mode.WRITE);
        write(r, relative, content, expectedVersion);
    }

    /**
     * 以追加方式打开文件写入（不存在时创建，上级目录自动创建；B15 数据库备份逐行写入，同旧版 {@code fopen($f, 'a')}）。调用方负责关闭。
     */
    public java.io.OutputStream openAppend(String relative) {
        LegacySiteRoot r = check(relative, Mode.WRITE);
        try {
            java.nio.file.Path p = r.resolve(relative);
            java.nio.file.Files.createDirectories(p.getParent());
            return java.nio.file.Files.newOutputStream(p, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.WRITE);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        } catch (java.io.IOException e) {
            throw new FileException(Reason.IO);
        }
    }

    /** 插件的全局文件（{@code application/…}、{@code static/…}）原子写入。 */
    public void replaceAddonGlobal(String relative, byte[] content) {
        LegacySiteRoot r = checkAddonGlobal(relative);
        write(r, relative, content, null);
    }

    /** 删除插件的全局文件。 */
    public boolean deleteAddonGlobal(String relative) {
        LegacySiteRoot r = checkAddonGlobal(relative);
        try {
            return r.delete(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    /** 删除插件全局文件所在的空目录（旧 {@code remove_empty_folder}）。 */
    public boolean deleteEmptyAddonGlobalDirectory(String relative) {
        LegacySiteRoot r = checkAddonGlobal(relative);
        try {
            return r.deleteEmptyDirectory(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public boolean delete(String relative) {
        LegacySiteRoot r = check(relative, Mode.WRITE);
        try {
            return r.delete(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public void deleteTree(String relative) {
        LegacySiteRoot r = check(relative, Mode.WRITE);
        if (RW_AREAS.contains(relative + "/")) {
            // 不删除整个区域本身（static/、template/ 等）
            throw new FileException(Reason.FORBIDDEN);
        }
        try {
            r.deleteTree(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public boolean deleteEmptyDirectory(String relative) {
        LegacySiteRoot r = check(relative, Mode.WRITE);
        try {
            return r.deleteEmptyDirectory(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    public void mkdirs(String relative) {
        LegacySiteRoot r = check(relative, Mode.WRITE);
        try {
            r.mkdirs(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    /** 复制文件（源可读、目标可写；插件全局文件用 {@link #copyToAddonGlobal}）。 */
    public void copyFile(String from, String to) {
        LegacySiteRoot r = check(from, Mode.READ);
        check(to, Mode.WRITE);
        try {
            r.copyFile(from, to);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    /** 把插件目录里的文件复制成全局文件（旧 {@code copydirs($addonDir . $dir, ROOT_PATH . $dir)}）。 */
    public void copyToAddonGlobal(String from, String to) {
        LegacySiteRoot r = check(from, Mode.READ);
        checkAddonGlobal(to);
        try {
            r.copyFile(from, to);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    /** 根目录的真实路径（写回之外的用途：运行时模板编译的外部目录）。 */
    public Path resolve(String relative) {
        LegacySiteRoot r = check(relative, Mode.READ);
        try {
            return r.resolve(relative);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        }
    }

    private void write(LegacySiteRoot r, String relative, byte[] content, String expectedVersion) {
        try {
            r.replace(relative, content, expectedVersion);
        } catch (LegacySiteRoot.AccessException e) {
            throw translate(e);
        } catch (AtomicFiles.WriteException e) {
            throw new FileException(e.failure() == AtomicFiles.Failure.CONFLICT ? Reason.CONFLICT : Reason.IO);
        }
    }

    private LegacySiteRoot check(String relative, Mode mode) {
        if (root == null) {
            throw new FileException(Reason.NOT_CONFIGURED);
        }
        if (!LegacySiteRoot.validRelative(relative)) {
            throw new FileException(Reason.INVALID_NAME);
        }
        String dir = relative + "/";
        for (String area : RW_AREAS) {
            if (relative.startsWith(area) || (mode == Mode.LIST && dir.equals(area))) {
                return root;
            }
        }
        if (mode == Mode.LIST) {
            for (String area : LIST_AREAS) {
                if (relative.equals(area) || relative.startsWith(area.endsWith("/") ? area : area + "/")) {
                    return root;
                }
            }
        }
        throw new FileException(Reason.FORBIDDEN);
    }

    private LegacySiteRoot checkAddonGlobal(String relative) {
        if (root == null) {
            throw new FileException(Reason.NOT_CONFIGURED);
        }
        if (!LegacySiteRoot.validRelative(relative)) {
            throw new FileException(Reason.INVALID_NAME);
        }
        if (relative.startsWith("static/")) {
            return root;
        }
        if (relative.startsWith("application/")) {
            String rest = relative.substring("application/".length());
            if (rest.indexOf('/') > 0 && !rest.startsWith("extra/") && !rest.startsWith("data/install/")
                    && !rest.startsWith("lang/")) {
                return root;
            }
        }
        throw new FileException(Reason.FORBIDDEN);
    }

    private static FileException translate(LegacySiteRoot.AccessException e) {
        return new FileException(switch (e.problem()) {
            case "INVALID_NAME" -> Reason.INVALID_NAME;
            case "OUTSIDE_ROOT" -> Reason.OUTSIDE_ROOT;
            case "NOT_REGULAR_FILE" -> Reason.NOT_REGULAR_FILE;
            case "IS_DIRECTORY" -> Reason.IS_DIRECTORY;
            case "TOO_LARGE" -> Reason.TOO_LARGE;
            default -> Reason.IO;
        });
    }
}
