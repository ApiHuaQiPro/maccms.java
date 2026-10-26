package com.maccms.backend.legacyconfig.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigException.Reason;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.infrastructure.AtomicFiles;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.AccessException;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.FileState;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * 旧配置层（B02 只读，docs/architecture/11-guards-and-legacy-config.md 第 4 节；B15 写回，24 文档第 3 节）：按 {@link LegacyConfigFile}
 * 读取旧站 {@code application/} 目录中的配置文件，用 {@link VarExportParser} 解析（不执行 PHP）。
 *
 * <p>热加载：每次读取先取文件的修改时间与大小，与上次解析时相同就直接返回缓存结果，不同就重新读取解析。
 * 解析失败不回退到旧结果（与旧 PHP 遇到语法错误时直接报错一致），同一状态的失败也会缓存，不会每次请求都重读。
 * 修改时间与大小都不变的外部改写（同一时间粒度内、等长）检测不到，这是已知限制；本进程的写回会直接作废缓存。
 *
 * <p>写回（{@link #replace}）：内容先经 {@link VarExportParser} 读回校验（新版自己读不回来的文件不写），再由 {@link AtomicFiles} 原子写入；
 * 写之前比较文件当前内容的版本与调用方读到时的版本（{@link #snapshot}），不同则 {@link Reason#CONFLICT}、不写。每次成功写回都使
 * {@link #generation()} 加一，供按旧配置派生设置的组件判断是否要重建（B15 热加载）。
 */
@Service
public class LegacyConfigService {

    private record Cached(FileTime modified, long size, PhpArray value, String version, LegacyConfigException error) {
    }

    /** 一次读取的结果与版本（内容的 SHA-256）。 */
    public record Snapshot(PhpArray value, String version) {
    }

    private final LegacyConfigDirectory directory;
    private final Map<LegacyConfigFile, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

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
        return snapshot(file).value();
    }

    /** 同 {@link #read}，另带读到的内容的版本（写回时用来检查期间是否被他人修改）。 */
    public Snapshot snapshot(LegacyConfigFile file) {
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
        return new Snapshot(cached.value(), cached.version());
    }

    /**
     * 原子写回一个配置文件（B15）。
     *
     * @param content         完整的文件内容（{@link VarExportWriter} 的某种外壳）
     * @param expectedVersion 调用方读到时的版本（{@link Snapshot#version()}；文件原本不存在时为 {@link #ABSENT}）；为 null 时不检查
     * @throws LegacyConfigException {@link Reason#INVALID_VALUE}（新版读不回来）、{@link Reason#CONFLICT}、{@link Reason#WRITE_FAILED}，
     *                               以及未配置、文件名或目录不合法
     */
    public void replace(LegacyConfigFile file, byte[] content, String expectedVersion) {
        if (directory == null) {
            throw new LegacyConfigException(Reason.NOT_CONFIGURED, "未设置 maccms.legacy-config.dir");
        }
        try {
            VarExportParser.parse(content);
        } catch (LegacyConfigException e) {
            throw new LegacyConfigException(Reason.INVALID_VALUE, "写出的内容不能读回");
        }
        Path target;
        try {
            target = directory.writeTarget(file.relativePath());
        } catch (AccessException e) {
            throw translate(e);
        }
        try {
            AtomicFiles.replace(target, content, expectedVersion);
        } catch (AtomicFiles.WriteException e) {
            throw e.failure() == AtomicFiles.Failure.CONFLICT
                    ? new LegacyConfigException(Reason.CONFLICT, "文件已被他人修改")
                    : new LegacyConfigException(Reason.WRITE_FAILED, "写入失败");
        } finally {
            cache.remove(file);
        }
        generation.incrementAndGet();
    }

    /**
     * 在该文件的进程内锁里"读出当前内容 → 算出新内容 → 原子写回"（B15）：同一进程里对同一文件的并发保存被串行化，后一位读到的是前一位写好的内容；
     * 读与写之间被进程外（仍在运行的旧 PHP）改过时 {@link Reason#CONFLICT}。
     *
     * @param change 参数为当前内容（文件不存在时为 null），返回要写的完整内容；返回 null 表示不写。可以抛出运行时异常中止（不写）
     */
    public void update(LegacyConfigFile file, java.util.function.Function<PhpArray, byte[]> change) {
        if (directory == null) {
            throw new LegacyConfigException(Reason.NOT_CONFIGURED, "未设置 maccms.legacy-config.dir");
        }
        Path target;
        try {
            target = directory.writeTarget(file.relativePath());
        } catch (AccessException e) {
            throw translate(e);
        }
        AtomicFiles.locked(target, () -> {
            Snapshot current = snapshotOrNull(file);
            byte[] content = change.apply(current == null ? null : current.value());
            if (content != null) {
                replace(file, content, current == null ? ABSENT : current.version());
            }
            return null;
        });
    }

    /** 同 {@link #snapshot}，文件不存在时为 null（其他错误照常抛出）。 */
    public Snapshot snapshotOrNull(LegacyConfigFile file) {
        try {
            return snapshot(file);
        } catch (LegacyConfigException e) {
            if (e.reason() == Reason.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /** 文件原本不存在时的版本（{@link #replace} 的 {@code expectedVersion}）。 */
    public static final String ABSENT = AtomicFiles.ABSENT;

    /** 本进程成功写回的次数（B15 热加载：派生设置按它与文件状态判断是否要重建）。 */
    public long generation() {
        return generation.get();
    }

    private Cached load(FileState state) {
        try {
            byte[] bytes = directory.read(state, VarExportParser.MAX_BYTES);
            return new Cached(state.modified(), state.size(), VarExportParser.parse(bytes), AtomicFiles.version(bytes), null);
        } catch (AccessException e) {
            return new Cached(state.modified(), state.size(), null, null, translate(e));
        } catch (LegacyConfigException e) {
            return new Cached(state.modified(), state.size(), null, null, e);
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
