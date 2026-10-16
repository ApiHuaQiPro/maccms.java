package com.maccms.backend.legacyconfig.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigException.Reason;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.AccessException;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.FileState;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 旧配置只读层（B02，docs/architecture/11-guards-and-legacy-config.md 第 4 节）：按 {@link LegacyConfigFile} 读取旧站
 * {@code application/} 目录中的配置文件，用 {@link VarExportParser} 解析（不执行 PHP），只读、不写回（写回在 B15）。
 *
 * <p>热加载：每次读取先取文件的修改时间与大小，与上次解析时相同就直接返回缓存结果，不同就重新读取解析。
 * 解析失败不回退到旧结果（与旧 PHP 遇到语法错误时直接报错一致），同一状态的失败也会缓存，不会每次请求都重读。
 * 修改时间与大小都不变的改写（同一时间粒度内、等长）检测不到，这是已知限制。
 */
@Service
public class LegacyConfigService {

    private record Cached(FileTime modified, long size, PhpArray value, LegacyConfigException error) {
    }

    private final LegacyConfigDirectory directory;
    private final Map<LegacyConfigFile, Cached> cache = new ConcurrentHashMap<>();

    /**
     * {@code dir} 留空时为未配置；不为空但不存在或不是目录时启动失败，信息只含配置项名称。
     */
    public LegacyConfigService(LegacyConfigProperties properties) {
        String dir = properties == null || properties.dir() == null ? "" : properties.dir().strip();
        if (dir.isEmpty()) {
            this.directory = null;
            return;
        }
        try {
            this.directory = new LegacyConfigDirectory(Path.of(dir));
        } catch (IllegalArgumentException e) {
            // 含 InvalidPathException（路径写法不合法）
            throw new IllegalStateException("maccms.legacy-config.dir（留空，或已存在的旧站 application 目录）", null);
        }
    }

    /** 是否配置了旧配置目录。 */
    public boolean isConfigured() {
        return directory != null;
    }

    /**
     * 读取并解析一个配置文件（顶层数组）。
     *
     * @throws LegacyConfigException 未配置、文件不存在或不可读、不是合法的 var_export 数据等
     */
    public PhpArray read(LegacyConfigFile file) {
        if (directory == null) {
            throw new LegacyConfigException(Reason.NOT_CONFIGURED, "未设置 maccms.legacy-config.dir");
        }
        FileState state;
        try {
            state = directory.stat(file.relativePath());
        } catch (AccessException e) {
            throw translate(e);
        }
        Cached cached = cache.get(file);
        if (cached == null || !cached.modified().equals(state.modified()) || cached.size() != state.size()) {
            cached = load(state);
            cache.put(file, cached);
        }
        if (cached.error() != null) {
            throw cached.error();
        }
        return cached.value();
    }

    private Cached load(FileState state) {
        try {
            byte[] bytes = directory.read(state, VarExportParser.MAX_BYTES);
            return new Cached(state.modified(), state.size(), VarExportParser.parse(bytes), null);
        } catch (AccessException e) {
            return new Cached(state.modified(), state.size(), null, translate(e));
        } catch (LegacyConfigException e) {
            return new Cached(state.modified(), state.size(), null, e);
        }
    }

    private static LegacyConfigException translate(AccessException e) {
        return switch (e.problem()) {
            case INVALID_NAME -> new LegacyConfigException(Reason.INVALID_NAME, "文件名不合法");
            case OUTSIDE_ROOT -> new LegacyConfigException(Reason.OUTSIDE_ROOT, "文件不在配置目录内");
            case NOT_FOUND -> new LegacyConfigException(Reason.NOT_FOUND, "文件不存在");
            case NOT_REGULAR_FILE -> new LegacyConfigException(Reason.NOT_REGULAR_FILE, "不是普通文件");
            case TOO_LARGE -> new LegacyConfigException(Reason.TOO_LARGE, "文件超过 " + VarExportParser.MAX_BYTES + " 字节");
            case IO -> new LegacyConfigException(Reason.IO, "读取失败");
        };
    }
}
