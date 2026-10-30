package com.maccms.backend.content.infrastructure;

/**
 * 后台内容列表与批量处理的筛选条件（B13，22 文档第 5 节）。每个字段对应 Mapper XML 里写死列名的一个条件（为 null 时不加）；取值是旧版交给
 * ThinkPHP 的原值（由 MySQL 按列类型比较），包含匹配是已转写成 {@code ESCAPE '!'} 形式的模式。各表只用到其中一部分（见各自的 XML）。
 */
public final class AdminCriteria {

    /** {@code type_id|type_id_1 = ?}（列表页）。 */
    public String typeEither;
    /** {@code type_id = ?}（批量处理页）。 */
    public String typeExact;
    public String level;
    public String status;
    public String copyright;
    public String isend;
    /** {@code *_lock = ?}（属性名与 XML 写法避开读 Mapper 护栏的 {@code "LOCK "} 关键字）。 */
    public String locked;
    public String state;
    public String area;
    public String lang;
    public String plot;
    public String rid;
    /** {@code vod_play_url = ''}。 */
    public boolean urlEmpty;
    /** {@code vod_points_play|vod_points_down > 0}。 */
    public boolean points;
    /** 图片：1 为空、2 远程（{@code LIKE 'http%'}）、3 同步失败（{@code LIKE '%#err%'}）。 */
    public Integer pic;
    public String weekdayLike;
    /** 名称（及视频的主演、副标题）包含匹配的模式。 */
    public String wdLike;
    /** 视频的关键字只匹配名称（批量处理页；列表页同时匹配主演、副标题）。 */
    public boolean wdNameOnly;
    /** 播放器：{@code no} 为空或 no，否则包含匹配的模式。 */
    public boolean playerNo;
    public String playerLike;
    public boolean downerNo;
    public String downerLike;
    public String serverLike;
    /** 排序列（列表页的 order 白名单；为 null 时按各表的默认排序）。 */
    public String order;
    /** 查重：名称在"同名超过一条"的名称集合里（旧版的临时表，新版是子查询）。 */
    public boolean repeat;

    public boolean isWdNameOnly() {
        return wdNameOnly;
    }

    public String getTypeEither() {
        return typeEither;
    }

    public String getTypeExact() {
        return typeExact;
    }

    public String getLevel() {
        return level;
    }

    public String getStatus() {
        return status;
    }

    public String getCopyright() {
        return copyright;
    }

    public String getIsend() {
        return isend;
    }

    public String getLocked() {
        return locked;
    }

    public String getState() {
        return state;
    }

    public String getArea() {
        return area;
    }

    public String getLang() {
        return lang;
    }

    public String getPlot() {
        return plot;
    }

    public String getRid() {
        return rid;
    }

    public boolean isUrlEmpty() {
        return urlEmpty;
    }

    public boolean isPoints() {
        return points;
    }

    public Integer getPic() {
        return pic;
    }

    public String getWeekdayLike() {
        return weekdayLike;
    }

    public String getWdLike() {
        return wdLike;
    }

    public boolean isPlayerNo() {
        return playerNo;
    }

    public String getPlayerLike() {
        return playerLike;
    }

    public boolean isDownerNo() {
        return downerNo;
    }

    public String getDownerLike() {
        return downerLike;
    }

    public String getServerLike() {
        return serverLike;
    }

    public String getOrder() {
        return order;
    }

    public boolean isRepeat() {
        return repeat;
    }
}
