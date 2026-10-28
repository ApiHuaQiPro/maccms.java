package com.maccms.backend.staticgen.application;

import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 静态页文件的写入、登记与回滚（B16，25 文档第 6 节）。
 *
 * <ul>
 * <li>写入经 {@link LegacySiteFiles#replaceStaticPage}：站点根目录内、扩展名白名单、不进旧站代码与资源目录，原子替换。</li>
 * <li>每写一个文件，把相对路径追加到登记文件 {@value #MANIFEST}（一行一个，可能重复）。回滚（{@link #cleanup}）按登记逐个删除这些文件、
 * 删掉因此变空的目录，最后删除登记文件——即路线图 B16 行"删除本批生成的文件"。</li>
 * </ul>
 */
public final class StaticFiles {

    /** 登记文件（在 B15 已开放的 {@code application/data/backup/} 区域内）。 */
    public static final String MANIFEST = "application/data/backup/static-pages/generated.txt";
    /** 回滚时读取登记文件的上限。 */
    static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;

    /** 写入失败的原因（输出给管理员，不含路径以外的信息）。 */
    public static final class WriteFailure extends Exception {
        public WriteFailure(String message) {
            super(message, null, false, false);
        }
    }

    /** 回滚结果。 */
    public record Cleanup(int deleted, int missing, List<String> refused) {
    }

    private final LegacySiteFiles files;
    private final Object manifestLock = new Object();

    public StaticFiles(LegacySiteFiles files) {
        this.files = files;
    }

    /** 写一个静态页并登记。 */
    public void write(String relative, byte[] content) throws WriteFailure {
        if (!LegacySiteFiles.isStaticPageName(relative)) {
            throw new WriteFailure("文件名不在允许的范围内");
        }
        synchronized (manifestLock) {
            try (OutputStream o = files.openAppend(MANIFEST)) {
                o.write((relative + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (LegacySiteFiles.FileException | IOException e) {
                throw new WriteFailure("登记失败");
            }
        }
        try {
            files.replaceStaticPage(relative, content);
        } catch (LegacySiteFiles.FileException e) {
            throw new WriteFailure("写入失败（" + e.reason().name() + "）");
        }
    }

    /** 登记过的文件（去重，按首次写入的顺序）；没有登记文件时为空表。 */
    public List<String> generated() {
        byte[] raw;
        synchronized (manifestLock) {
            raw = files.read(MANIFEST, MAX_MANIFEST_BYTES);
        }
        if (raw == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return new ArrayList<>(out);
    }

    /** 回滚：删除登记过的全部静态页与因此变空的目录，再删除登记文件。不在允许范围内的登记行不处理、列入 {@code refused}。 */
    public Cleanup cleanup() {
        synchronized (manifestLock) {
            int deleted = 0;
            int missing = 0;
            List<String> refused = new ArrayList<>();
            for (String f : generated()) {
                if (!LegacySiteFiles.isStaticPageName(f)) {
                    refused.add(f);
                    continue;
                }
                if (files.deleteStaticPage(f)) {
                    deleted++;
                } else {
                    missing++;
                }
                files.deleteEmptyStaticDirectories(f);
            }
            files.delete(MANIFEST);
            return new Cleanup(deleted, missing, refused);
        }
    }
}
