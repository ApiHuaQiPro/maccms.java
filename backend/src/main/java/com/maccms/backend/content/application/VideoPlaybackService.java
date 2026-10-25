package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.ref;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.view.PlaybackOutcome;
import com.maccms.backend.content.application.view.VideoMediaDto;
import com.maccms.backend.content.application.view.VideoPlaybackResult;
import com.maccms.backend.content.infrastructure.VodMapper;
import com.maccms.backend.content.infrastructure.VodPlaybackRow;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 播放/下载入口的判定（B05，docs/architecture/15-b05-readonly-pages.md 第 6 节；队长裁定 D-B05-2～4；B09 起按当前请求的有效
 * 会员组，18 文档第 5.3 节）。只调用 Mapper 的 select。旧代码：入口 application/index/controller/Vod.php:86-120，数据与试看
 * common/controller/All.php:442-563，权限 index/controller/Base.php:92-231。
 *
 * <p>判定顺序（每个入口按旧调用链，见 {@link PlaybackEntry}）：
 * <ol>
 * <li>视频不存在、未审核、分类不可见 → VIDEO_NOT_FOUND（404）。旧播放入口不检查详情位 2，这里也不检查；</li>
 * <li>权限（user.status=1，即 {@code maccms.content.enforce-guest-popedom=true} 时；否则一律放行、没有试看）：
 *   播放——无位 3 且（无位 5 或试看时长为 0）→ {@link PlaybackOutcome#NO_PERMISSION}；组 &lt; 3、无位 3、有位 5、时长 &gt; 0 →
 *   {@link PlaybackOutcome#TRIAL}；组 &lt; 3 且积分列 &gt; 0 → {@link PlaybackOutcome#POINTS_REQUIRED}（Base.php:143-168；
 *   是否已购买的 {@code mac_ulog} 查询归 B10）；其余放行（组 ≥ 3 有位 5 而无位 3 时旧版直接放行，照搬）。
 *   下载——无位 4 → NO_PERMISSION；组为 1 或 2 且积分列 &gt; 0 → POINTS_REQUIRED（:170-195，组 1 提示先登录、组 2 提示扣积分，
 *   页面文案相同）；组 ≥ 3 放行。组不存在时组号按 0（旧 {@code $group} 为空）；</li>
 * <li>版权（只在实际命中的入口）：vod_copyright=1 且 copyright_status=3 时的 PLAY_PAGE、=4 时的 PLAYER →
 *   {@link PlaybackOutcome#COPYRIGHT_RESTRICTED}。下载入口旧版不检查版权；</li>
 * <li>密码（PLAYER 的 vod_pwd_play、DOWNER 的 vod_pwd_down）非空且没有与当前密码一致的凭据 → {@link PlaybackOutcome#PASSWORD_REQUIRED}。</li>
 * </ol>
 * 旧 vod/player 内页对权限只做不中止的检查（{@code $pe=1}，All.php:474,482），会把地址交给无权限的游客；新版对内页按播放页同样判定，
 * 不输出受限地址（与旧版的差异，15 文档第 11 节）。
 */
@Service
public class VideoPlaybackService {

    /** 访问密码输入的码点上限（列宽 varchar(10)，更长的输入不可能相等，install.sql:150,752,754）。 */
    public static final int MAX_PASSWORD_LENGTH = 64;

    private final VodMapper vodMapper;
    private final ContentCatalogs catalogs;
    private final PlaybackSettings settings;

    @Autowired
    public VideoPlaybackService(VodMapper vodMapper, ContentCatalogs catalogs, ContentProperties content,
                                PlaybackProperties playback) {
        this(vodMapper, catalogs, PlaybackSettings.from(playback, content.copyrightHidesDetail()));
    }

    VideoPlaybackService(VodMapper vodMapper, ContentCatalogs catalogs, PlaybackSettings settings) {
        this.vodMapper = vodMapper;
        this.catalogs = catalogs;
        this.settings = settings;
    }

    public PlaybackSettings settings() {
        return settings;
    }

    /**
     * @param proof 访问密码凭据；没有时为 {@code null}（只对 PLAYER、DOWNER 有意义）
     */
    public VideoPlaybackResult access(long vodId, PlaybackEntry entry, PasswordProof proof) {
        if (vodId < 1 || vodId > VideoId.MAX || entry == null) {
            throw new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在");
        }
        return read(() -> {
            boolean checkPassword = entry == PlaybackEntry.PLAYER || entry == PlaybackEntry.DOWNER;
            VodPlaybackRow row = vodMapper.selectPlayback(vodId, entry.down(),
                    checkPassword && proof != null ? proof.salt() : null,
                    checkPassword && proof != null ? proof.fingerprint() : null);
            if (row == null) {
                throw new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在");
            }
            GroupPermission viewer = catalogs.viewer();
            CategoryCatalog catalog = catalogs.vodCatalog(viewer);
            int typeId = nz(row.categoryId());
            if (!catalog.isVisible(typeId)) {
                throw new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在");
            }
            int trySee = entry.down() ? 0 : trySeeMinutes(row);
            PlaybackOutcome outcome = permission(entry, viewer, typeId, trySee, row);
            if (outcome == PlaybackOutcome.ALLOWED || outcome == PlaybackOutcome.TRIAL) {
                if (copyrightRestricted(entry, row)) {
                    outcome = PlaybackOutcome.COPYRIGHT_RESTRICTED;
                } else if (checkPassword && nz(row.passwordRequired()) != 0 && nz(row.passwordProven()) == 0) {
                    outcome = PlaybackOutcome.PASSWORD_REQUIRED;
                }
            }
            Optional<VideoMediaDto> media = outcome == PlaybackOutcome.ALLOWED || outcome == PlaybackOutcome.TRIAL
                    ? Optional.of(new VideoMediaDto(str(row.sourceFrom()), str(row.sourceUrl()), str(row.sourceServer()),
                            str(row.sourceNote()), nz(row.pointsKind())))
                    : Optional.empty();
            return new VideoPlaybackResult(outcome, nz(row.id()), str(row.name()), str(row.actor()),
                    str(row.director()), str(row.classes()), ref(catalog, typeId), nz(row.updatedAt()), trySee, media);
        });
    }

    /**
     * 访问密码校验：视频已审核、分类可见、对应密码列非空且与 candidate 逐字节相等。candidate 为空或超过
     * {@link #MAX_PASSWORD_LENGTH} 个码点时直接为 false（不查库）。只返回布尔值，密码列不读入内存。
     */
    public boolean verifyPassword(long vodId, PasswordTarget target, String candidate) {
        if (target != PasswordTarget.VIDEO_PLAY && target != PasswordTarget.VIDEO_DOWN) {
            throw new IllegalArgumentException("target");
        }
        if (vodId < 1 || vodId > VideoId.MAX || candidate == null || candidate.isEmpty()
                || candidate.codePointCount(0, candidate.length()) > MAX_PASSWORD_LENGTH) {
            return false;
        }
        boolean down = target == PasswordTarget.VIDEO_DOWN;
        return read(() -> {
            VodPlaybackRow row = vodMapper.selectPlayback(vodId, down, null, null);
            if (row == null || !catalogs.vodCatalog(catalogs.viewer()).isVisible(nz(row.categoryId()))) {
                return false;
            }
            return vodMapper.countPasswordMatch(vodId, down, candidate) > 0;
        });
    }

    /** 旧 {@code $trysee = user.trysee; if (vod_trysee > 0) $trysee = vod_trysee}（All.php:470-473）。 */
    private int trySeeMinutes(VodPlaybackRow row) {
        int own = nz(row.trySee());
        return own > 0 ? own : settings.trySeeMinutes();
    }

    private PlaybackOutcome permission(PlaybackEntry entry, GroupPermission viewer, int typeId, int trySee,
                                       VodPlaybackRow row) {
        if (!catalogs.enforceGuestPopedom()) {
            // user.status=0：check_user_popedom 直接返回 code=1（Base.php:115-117），没有试看
            return PlaybackOutcome.ALLOWED;
        }
        int points = settings.pointsWholeVideo() ? nz(row.pointsWhole()) : nz(row.pointsKind());
        int group = viewer.groupId();
        if (entry.down()) {
            if (!viewer.allows(typeId, GroupPermission.DOWN)) {
                return PlaybackOutcome.NO_PERMISSION;
            }
            // Base.php:174-195：只有组 1（4001 先登录）与组 2（4003 扣积分确认）检查积分
            return points > 0 && (group == 1 || group == 2) ? PlaybackOutcome.POINTS_REQUIRED : PlaybackOutcome.ALLOWED;
        }
        boolean play = viewer.allows(typeId, GroupPermission.PLAY);
        boolean trial = viewer.allows(typeId, GroupPermission.TRY_SEE);
        if (!play && (!trial || trySee == 0)) {
            return PlaybackOutcome.NO_PERMISSION;
        }
        if (group >= 3) {
            // Base.php:146-168 的试看与积分分支都要求 group_id<3：组 ≥ 3 走到这里一律放行
            return PlaybackOutcome.ALLOWED;
        }
        if (!play) {
            return PlaybackOutcome.TRIAL;
        }
        return points > 0 ? PlaybackOutcome.POINTS_REQUIRED : PlaybackOutcome.ALLOWED;
    }

    private boolean copyrightRestricted(PlaybackEntry entry, VodPlaybackRow row) {
        if (nz(row.copyright()) != 1) {
            return false;
        }
        return switch (entry) {
            case PLAY_PAGE -> settings.copyrightStatus() == 3;
            case PLAYER -> settings.copyrightStatus() == 4;
            case DOWN_PAGE, DOWNER -> false;
        };
    }
}
