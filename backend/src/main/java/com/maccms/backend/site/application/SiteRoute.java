package com.maccms.backend.site.application;

import com.maccms.backend.content.application.VideoId;
import com.maccms.backend.content.application.VideoListQuery;

/**
 * Java 页面承接的旧路由（首批：首页、分类页、详情页，只支持数字 id；MACC-20 增加搜索页）。取值范围与公开 API 一致，越界抛
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

    /**
     * 搜索页（旧 {@code vod/search} 的固定路径 + 查询串形式，MACC-20），由 {@link LegacyUrlParser#parseSearch} 识别。
     *
     * @param filters 已归一、已校验的条件
     * @param page    1..1000（与 API page 上限一致）
     */
    record VodSearch(SearchFilters filters, int page) implements SiteRoute {
        public VodSearch {
            if (filters == null || page < 1 || page > VideoListQuery.MAX_PAGE) {
                throw new IllegalArgumentException("搜索条件缺失或页码越界");
            }
        }
    }

    /**
     * B05 承接的"模块/操作 + 数字参数"页面（{@link LegacyPage}）：播放/下载四个入口、内容页、单条 RSS、ajax_detail。
     *
     * @param page 页面
     * @param id   id（{@link LegacyPage.Id#NONE} 时为 0）
     * @param sid  播放源序号（播放/下载入口 1..{@value LegacyPage#MAX_SID}，否则 0）
     * @param nid  集序号（播放/下载入口 1..{@value LegacyPage#MAX_NID}，否则 0）
     * @param pageNo 页码（可分页时 1..1000，否则 1）
     */
    record Legacy(LegacyPage page, long id, int sid, int nid, int pageNo) implements SiteRoute {
        public Legacy {
            if (page == null) {
                throw new IllegalArgumentException("页面缺失");
            }
            boolean idOk = page.id() == LegacyPage.Id.NONE ? id == 0 : id >= 1 && id <= page.id().max();
            boolean episodeOk = page.episode() ? sid >= 1 && sid <= LegacyPage.MAX_SID && nid >= 1
                    && nid <= LegacyPage.MAX_NID : sid == 0 && nid == 0;
            boolean pageOk = page.paged() ? pageNo >= 1 && pageNo <= VideoListQuery.MAX_PAGE : pageNo == 1;
            if (!idOk || !episodeOk || !pageOk) {
                throw new IllegalArgumentException("页面参数越界");
            }
        }

        public static Legacy of(LegacyPage page, long id) {
            return new Legacy(page, id, 0, 0, 1);
        }

        public static Legacy paged(LegacyPage page, long id, int pageNo) {
            return new Legacy(page, id, 0, 0, pageNo);
        }

        public static Legacy episode(LegacyPage page, long id, int sid, int nid) {
            return new Legacy(page, id, sid, nid, 1);
        }
    }
}
