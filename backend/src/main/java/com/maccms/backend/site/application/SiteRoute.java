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
     * 分类筛选页（旧 {@code vod/show}，Vod.php:23-28；B05，队长裁定 D-B05-7）：分类本身 + 直接子分类（与 API categoryId 相同），
     * 叠加与搜索页相同的条件（不含 {@code tid}）。由 {@link LegacyUrlParser#parseVodList} 识别。
     *
     * @param categoryId 1..65535
     * @param filters    已归一、已校验的条件（{@code tid} 为 null）
     * @param page       1..1000
     */
    record VodShow(int categoryId, SearchFilters filters, int page) implements SiteRoute {
        public VodShow {
            if (categoryId < 1 || categoryId > VideoListQuery.MAX_CATEGORY_ID || filters == null || filters.tid() != null
                    || page < 1 || page > VideoListQuery.MAX_PAGE) {
                throw new IllegalArgumentException("分类、条件或页码越界");
            }
        }
    }

    /**
     * 文章、专题、演员、角色、网址、剧情的筛选/搜索页与 ajax 片段（{@link ModuleListPage}，B05 D-B05-5），由
     * {@link LegacyUrlParser#parseModuleList} 识别。
     *
     * @param page       页面
     * @param categoryId 带分类的筛选页的分类（1..65535）；搜索页与角色筛选页为 null；演员筛选页未给出时为 null（旧版取第一个演员分类，
     *                   Actor.php:24-35）
     * @param filters    已归一、已校验的条件（模块与页面相同）
     * @param pageNo     1..1000
     */
    record ModuleList(ModuleListPage page, Integer categoryId, ModuleFilters filters, int pageNo) implements SiteRoute {
        public ModuleList {
            if (page == null || filters == null || !filters.module().equals(page.module()) || pageNo < 1
                    || pageNo > VideoListQuery.MAX_PAGE) {
                throw new IllegalArgumentException("页面、条件或页码不合法");
            }
            boolean categoryOk = categoryId == null
                    ? !page.categorized() || page.module().equals("actor")
                    : page.categorized() && categoryId >= 1 && categoryId <= VideoListQuery.MAX_CATEGORY_ID;
            if (!categoryOk) {
                throw new IllegalArgumentException("分类不合法");
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
