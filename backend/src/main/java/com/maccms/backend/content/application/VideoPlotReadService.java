package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.view.VideoPlotListResponse;
import com.maccms.backend.content.infrastructure.VodPlotMapper;
import com.maccms.backend.content.infrastructure.VodPlotRow;
import org.springframework.stereotype.Service;

/**
 * 视频分集剧情（契约 1.2.0，旧 plot/detail 页的 vod_plot_list）。可见性与视频详情（ContentReadService#getVideo）完全相同：
 * vod_status=1、分类可见、游客详情权限、观看密码、版权开关。旧 plot 页只检查前三项（Plot.php:38-42 → label_vod_detail，
 * 不经过 Vod::detail 的密码与版权判断），新版沿用视频详情的更严格判定（docs/architecture/11-content-read-completion.md 第 5 节）。
 */
@Service
public class VideoPlotReadService {

    private final VodPlotMapper plotMapper;
    private final ContentCatalogs catalogs;
    private final ContentProperties properties;

    public VideoPlotReadService(VodPlotMapper plotMapper, ContentCatalogs catalogs, ContentProperties properties) {
        this.plotMapper = plotMapper;
        this.catalogs = catalogs;
        this.properties = properties;
    }

    public VideoPlotListResponse getPlots(long videoId) {
        return read(() -> {
            VodPlotRow row = plotMapper.selectPublishedPlot(videoId);
            if (row == null) {
                throw notFound();
            }
            GroupPermission viewer = catalogs.viewer();
            CategoryCatalog catalog = catalogs.vodCatalog(viewer);
            int categoryId = nz(row.categoryId());
            if (!catalog.isVisible(categoryId) || !catalogs.mayViewDetail(viewer, categoryId)) {
                throw notFound();
            }
            if (nz(row.detailPasswordRequired()) != 0) {
                throw new ApiException(ErrorCode.VIDEO_PASSWORD_REQUIRED, "该视频详情需要观看密码");
            }
            if (properties.copyrightHidesDetail() && nz(row.copyright()) == 1) {
                throw new ApiException(ErrorCode.VIDEO_COPYRIGHT_RESTRICTED, "该视频因版权原因不提供详情");
            }
            return new VideoPlotListResponse(row.id(), LegacyMultiValue.plots(row.plotNames(), row.plotDetails()));
        });
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.VIDEO_NOT_FOUND, "视频不存在");
    }
}
