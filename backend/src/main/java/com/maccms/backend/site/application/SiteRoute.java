package com.maccms.backend.site.application;

import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;

/**
 * Java 页面承接的旧路由（首批：首页、分类页、详情页，只支持数字 id）。取值范围与公开 API 一致，越界抛
 * {@link IllegalArgumentException}；请求解析时由 {@link LegacyUrlParser} 先校验并转成 INVALID_PARAMETER。
 */
public sealed interface SiteRoute {

    /** 首页（旧 {@code index/index}）。 */
    record Home() implements SiteRoute {
    }

    /**
     * 分类页（旧 {@code vod/type}，Vod.php:17-21）。
     *
     * @param typeId 1..65535（mac_type.type_id smallint unsigned，与 API categoryId 一致）
     * @param page   1..1000（与 API page 上限一致）
     */
    record VodType(int typeId, int page) implements SiteRoute {
        public VodType {
            if (typeId < 1 || typeId > VideoListQuery.MAX_CATEGORY_ID || page < 1 || page > VideoListQuery.MAX_PAGE) {
                throw new IllegalArgumentException("分类 id 或页码越界");
            }
        }
    }

    /**
     * 视频详情页（旧 {@code vod/detail}，Vod.php:55-64）。
     *
     * @param vodId 1..4294967295（mac_vod.vod_id int unsigned）
     */
    record VodDetail(long vodId) implements SiteRoute {
        public VodDetail {
            if (vodId < 1 || vodId > VideoId.MAX) {
                throw new IllegalArgumentException("视频 id 越界");
            }
        }
    }
}
