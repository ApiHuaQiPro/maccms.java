@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<style>
    .layui-form-pane .layui-form-label { width:140px; }
    .layui-form-pane .layui-input-block { margin-left:140px; }
</style>
<div class="page-container">
    <form class="layui-form layui-form-pane" action="">
        <input type="hidden" name="__token__" value="${page.token()}" />
        <div class="layui-tab" lay-filter="tb1">
            <ul class="layui-tab-title">
                <li class="layui-this" lay-id="configcollect_1">${page.lang().get("admin/system/configcollect/vod")}</li>
                <li lay-id="configcollect_2">${page.lang().get("admin/system/configcollect/art")}</li>
                <li lay-id="configcollect_3">${page.lang().get("admin/system/configcollect/actor")}</li>
                <li lay-id="configcollect_4">${page.lang().get("admin/system/configcollect/role")}</li>
                <li lay-id="configcollect_5">${page.lang().get("admin/system/configcollect/website")}</li>
                <li lay-id="configcollect_6">${page.lang().get("admin/system/configcollect/comment")}</li>
                <li lay-id="configcollect_7">${page.lang().get("admin/system/configcollect/words")}</li>
            </ul>

            <div class="layui-tab-content">

                <div class="layui-tab-item layui-show">

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[vod][status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.collect.vod.status"), 1L)}">
                        <input type="radio" name="collect[vod][status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.collect.vod.status"), 1L)}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/hits_rnd")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[vod][hits_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.vod.hits_start"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[vod][hits_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.vod.hits_end"))}" class="layui-input">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/updown_rnd")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[vod][updown_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.vod.updown_start"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[vod][updown_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.vod.updown_end"))}" class="layui-input">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/score_rnd")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][score]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.score"), 1L)}">
                        <input type="radio" name="collect[vod][score]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.score"), 1L)}">
                    </div>
                </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/sync_pic")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][pic]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.pic"), 1L)}">
                        <input type="radio" name="collect[vod][pic]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.pic"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/auto_tag")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][tag]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.tag"), 1L)}">
                        <input type="radio" name="collect[vod][tag]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.tag"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/class_filter")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][class_filter]" value="0" title="${page.lang().get("close")}" checked="${page.eq(page.at("config.collect.vod.class_filter"), "0")}">
                        <input type="radio" name="collect[vod][class_filter]" value="1" title="${page.lang().get("open")}" checked="${!page.eq(page.at("config.collect.vod.class_filter"), "0")}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcollect/class_filter_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psename")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][psename]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.psename"), 1L)}">
                        <input type="radio" name="collect[vod][psename]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.psename"), 1L)}">
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcollect/psename_tip")}</div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psernd")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][psernd]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.psernd"), 1L)}">
                        <input type="radio" name="collect[vod][psernd]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.psernd"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psesyn")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][psesyn]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.psesyn"), 1L)}">
                        <input type="radio" name="collect[vod][psesyn]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.psesyn"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/pseplayer")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][pseplayer]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.pseplayer"), 1L)}">
                        <input type="radio" name="collect[vod][pseplayer]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.pseplayer"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psearea")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][psearea]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.psearea"), 1L)}">
                        <input type="radio" name="collect[vod][psearea]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.psearea"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/pselang")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][pselang]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.vod.pselang"), 1L)}">
                        <input type="radio" name="collect[vod][pselang]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.vod.pselang"), 1L)}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/urlrole")}：</label>
                    <div class="layui-input-inline">
                        <input type="radio" name="collect[vod][urlrole]" value="0" title="${page.lang().get("replace")}" checked="${!page.eq(page.at("config.collect.vod.urlrole"), 1L)}">
                        <input type="radio" name="collect[vod][urlrole]" value="1" title="${page.lang().get("merge")}" checked="${page.eq(page.at("config.collect.vod.urlrole"), 1L)}">
                        <!-- <input type="radio" name="collect[vod][urlrole]" value="2" title="${page.lang().get("admin/system/configcollect/urlrole/use_more")}" checked="${page.eq(page.at("config.collect.vod.urlrole"), 2L)}"> -->
                    </div>
                    <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcollect/urlrole_tip")}</div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configcollect/inrule")}：</label>
                    <div class="layui-input-block">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="a" title="${page.lang().get("name")}" checked disabled>
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="b" title="${page.lang().get("type")}" checked="${page.has(page.at("config.collect.vod.inrule"), "b")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="c" title="${page.lang().get("years")}" checked="${page.has(page.at("config.collect.vod.inrule"), "c")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="d" title="${page.lang().get("area")}" checked="${page.has(page.at("config.collect.vod.inrule"), "d")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="e" title="${page.lang().get("lang")}" checked="${page.has(page.at("config.collect.vod.inrule"), "e")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="f" title="${page.lang().get("actor")}" checked="${page.has(page.at("config.collect.vod.inrule"), "f")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][inrule][]" value="g" title="${page.lang().get("director")}" checked="${page.has(page.at("config.collect.vod.inrule"), "g")}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">
                        ${page.lang().get("admin/system/configcollect/uprule")}：</label>
                    <div class="layui-input-block">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="a" title="${page.lang().get("playurl")}" checked="${page.has(page.at("config.collect.vod.uprule"), "a")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="b" title="${page.lang().get("downurl")}" checked="${page.has(page.at("config.collect.vod.uprule"), "b")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="c" title="${page.lang().get("serial")}" checked="${page.has(page.at("config.collect.vod.uprule"), "c")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="d" title="${page.lang().get("remarks")}" checked="${page.has(page.at("config.collect.vod.uprule"), "d")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="e" title="${page.lang().get("director")}" checked="${page.has(page.at("config.collect.vod.uprule"), "e")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="f" title="${page.lang().get("actor")}" checked="${page.has(page.at("config.collect.vod.uprule"), "f")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="g" title="${page.lang().get("years")}" checked="${page.has(page.at("config.collect.vod.uprule"), "g")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="h" title="${page.lang().get("area")}" checked="${page.has(page.at("config.collect.vod.uprule"), "h")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="i" title="${page.lang().get("lang")}" checked="${page.has(page.at("config.collect.vod.uprule"), "i")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="j" title="${page.lang().get("pic")}" checked="${page.has(page.at("config.collect.vod.uprule"), "j")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="k" title="${page.lang().get("content")}" checked="${page.has(page.at("config.collect.vod.uprule"), "k")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="l" title="TAG" checked="${page.has(page.at("config.collect.vod.uprule"), "l")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="m" title="${page.lang().get("sub")}" checked="${page.has(page.at("config.collect.vod.uprule"), "m")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="n" title="${page.lang().get("class")}" checked="${page.has(page.at("config.collect.vod.uprule"), "n")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="o" title="${page.lang().get("writer")}" checked="${page.has(page.at("config.collect.vod.uprule"), "o")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="p" title="${page.lang().get("version")}" checked="${page.has(page.at("config.collect.vod.uprule"), "p")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="q" title="${page.lang().get("state")}" checked="${page.has(page.at("config.collect.vod.uprule"), "q")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="r" title="${page.lang().get("blurb")}" checked="${page.has(page.at("config.collect.vod.uprule"), "r")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="s" title="${page.lang().get("tv")}" checked="${page.has(page.at("config.collect.vod.uprule"), "s")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="t" title="${page.lang().get("weekday")}" checked="${page.has(page.at("config.collect.vod.uprule"), "t")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="u" title="${page.lang().get("total")}" checked="${page.has(page.at("config.collect.vod.uprule"), "u")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="v" title="${page.lang().get("isend")}" checked="${page.has(page.at("config.collect.vod.uprule"), "v")}">
                        <input type="checkbox" lay-skin="primary" name="collect[vod][uprule][]" value="w" title="${page.lang().get("plot")}" checked="${page.has(page.at("config.collect.vod.uprule"), "w")}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/filter")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][filter]" class="layui-textarea" placeholder="${page.lang().get("multi_separate_tip")}">${page.t(page.at("config.collect.vod.filter"))}</textarea>
                    </div>
                </div>
            </div>

                <div class="layui-tab-item">

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/status")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[art][status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.collect.art.status"), 1L)}">
                        <input type="radio" name="collect[art][status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.collect.art.status"), 1L)}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/hits_rnd")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[art][hits_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.art.hits_start"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[art][hits_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.art.hits_end"))}" class="layui-input">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/updown_rnd")}：</label>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[art][updown_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.art.updown_start"))}" class="layui-input">
                    </div>
                    <div class="layui-input-inline">
                        <input type="text" name="collect[art][updown_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.art.updown_end"))}" class="layui-input">
                    </div>
                </div>


                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/score_rnd")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[art][score]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.art.score"), 1L)}">
                        <input type="radio" name="collect[art][score]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.art.score"), 1L)}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/sync_pic")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[art][pic]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.art.pic"), 1L)}">
                        <input type="radio" name="collect[art][pic]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.art.pic"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/auto_tag")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[art][tag]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.art.tag"), 1L)}">
                        <input type="radio" name="collect[art][tag]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.art.tag"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psernd")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[art][psernd]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.art.psernd"), 1L)}">
                        <input type="radio" name="collect[art][psernd]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.art.psernd"), 1L)}">
                    </div>
                </div>
                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psesyn")}：</label>
                    <div class="layui-input-block">
                        <input type="radio" name="collect[art][psesyn]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.art.psesyn"), 1L)}">
                        <input type="radio" name="collect[art][psesyn]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.art.psesyn"), 1L)}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/inrule")}：</label>
                    <div class="layui-input-block">
                        <input type="checkbox" lay-skin="primary" name="collect[art][inrule][]" value="a" title="${page.lang().get("name")}" checked disabled>
                        <input type="checkbox" lay-skin="primary" name="collect[art][inrule][]" value="b" title="${page.lang().get("type")}" checked="${page.has(page.at("config.collect.art.inrule"), "b")}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/uprule")}：</label>
                    <div class="layui-input-block">
                        <input type="checkbox" lay-skin="primary" name="collect[art][uprule][]" value="a" title="${page.lang().get("content")}" checked="${page.has(page.at("config.collect.art.uprule"), "a")}">
                        <input type="checkbox" lay-skin="primary" name="collect[art][uprule][]" value="b" title="${page.lang().get("author")}" checked="${page.has(page.at("config.collect.art.uprule"), "b")}">
                        <input type="checkbox" lay-skin="primary" name="collect[art][uprule][]" value="c" title="${page.lang().get("from")}" checked="${page.has(page.at("config.collect.art.uprule"), "c")}">
                        <input type="checkbox" lay-skin="primary" name="collect[art][uprule][]" value="d" title="${page.lang().get("pic")}" checked="${page.has(page.at("config.collect.art.uprule"), "d")}">
                        <input type="checkbox" lay-skin="primary" name="collect[art][uprule][]" value="e" title="TAG" checked="${page.has(page.at("config.collect.art.uprule"), "e")}">
                        <input type="checkbox" lay-skin="primary" name="collect[art][uprule][]" value="f" title="${page.lang().get("blurb")}" checked="${page.has(page.at("config.collect.art.uprule"), "f")}">
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/filter")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[art][filter]" class="layui-textarea" placeholder="${page.lang().get("multi_separate_tip")}">${page.t(page.at("config.collect.art.filter"))}</textarea>
                    </div>
                </div>

            </div>

                <div class="layui-tab-item">

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[actor][status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.collect.actor.status"), 1L)}">
                            <input type="radio" name="collect[actor][status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.collect.actor.status"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/hits_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[actor][hits_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.actor.hits_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[actor][hits_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.actor.hits_end"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/updown_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[actor][updown_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.actor.updown_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[actor][updown_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.actor.updown_end"))}" class="layui-input">
                        </div>
                    </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/score_rnd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[actor][score]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.actor.score"), 1L)}">
                            <input type="radio" name="collect[actor][score]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.actor.score"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/sync_pic")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[actor][pic]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.actor.pic"), 1L)}">
                            <input type="radio" name="collect[actor][pic]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.actor.pic"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psernd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[actor][psernd]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.actor.psernd"), 1L)}">
                            <input type="radio" name="collect[actor][psernd]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.actor.psernd"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psesyn")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[actor][psesyn]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.actor.psesyn"), 1L)}">
                            <input type="radio" name="collect[actor][psesyn]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.actor.psesyn"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/inrule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][inrule][]" value="a" title="${page.lang().get("actor_name")}" checked disabled>
                            <input type="checkbox" lay-skin="primary" name="collect[actor][inrule][]" value="c" title="${page.lang().get("type")}" checked="${page.has(page.at("config.collect.actor.inrule"), "c")}">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][inrule][]" value="b" title="${page.lang().get("sex")}" checked="${page.has(page.at("config.collect.actor.inrule"), "b")}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/uprule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][uprule][]" value="a" title="${page.lang().get("content")}" checked="${page.has(page.at("config.collect.actor.uprule"), "a")}">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][uprule][]" value="b" title="${page.lang().get("blurb")}" checked="${page.has(page.at("config.collect.actor.uprule"), "b")}">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][uprule][]" value="c" title="${page.lang().get("remarks")}" checked="${page.has(page.at("config.collect.actor.uprule"), "c")}">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][uprule][]" value="d" title="${page.lang().get("works")}" checked="${page.has(page.at("config.collect.actor.uprule"), "d")}">
                            <input type="checkbox" lay-skin="primary" name="collect[actor][uprule][]" value="e" title="${page.lang().get("pic")}" checked="${page.has(page.at("config.collect.actor.uprule"), "e")}">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/filter")}：</label>
                        <div class="layui-input-block">
                            <textarea name="collect[actor][filter]" class="layui-textarea" placeholder="${page.lang().get("multi_separate_tip")}">${page.t(page.at("config.collect.actor.filter"))}</textarea>
                        </div>
                    </div>

                </div>

                <div class="layui-tab-item">

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[role][status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.collect.role.status"), 1L)}">
                            <input type="radio" name="collect[role][status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.collect.role.status"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/hits_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[role][hits_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.role.hits_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[role][hits_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.role.hits_end"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/updown_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[role][updown_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.role.updown_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[role][updown_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.role.updown_end"))}" class="layui-input">
                        </div>
                    </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/score_rnd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[role][score]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.role.score"), 1L)}">
                            <input type="radio" name="collect[role][score]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.role.score"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/sync_pic")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[role][pic]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.role.pic"), 1L)}">
                            <input type="radio" name="collect[role][pic]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.role.pic"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psernd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[role][psernd]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.actor.psernd"), 1L)}">
                            <input type="radio" name="collect[role][psernd]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.actor.psernd"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psesyn")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[role][psesyn]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.role.psesyn"), 1L)}">
                            <input type="radio" name="collect[role][psesyn]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.role.psesyn"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/inrule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[role][inrule][]" value="a" title="${page.lang().get("role_name")}" checked disabled>
                            <input type="checkbox" lay-skin="primary" name="collect[role][inrule][]" value="b" title="${page.lang().get("vod_name")}${page.lang().get("or")}${page.lang().get("douban_id")}" checked disabled>
                            <input type="checkbox" lay-skin="primary" name="collect[role][inrule][]" value="c" title="${page.lang().get("actor_name")}" checked="${page.has(page.at("config.collect.role.inrule"), "c")}" >
                            <input type="checkbox" lay-skin="primary" name="collect[role][inrule][]" value="d" title="${page.lang().get("director")}" checked="${page.has(page.at("config.collect.role.inrule"), "d")}" >
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcollect/inrule_tip_role")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/uprule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[role][uprule][]" value="a" title="${page.lang().get("content")}" checked="${page.has(page.at("config.collect.role.uprule"), "a")}">
                            <input type="checkbox" lay-skin="primary" name="collect[role][uprule][]" value="b" title="${page.lang().get("remarks")}" checked="${page.has(page.at("config.collect.role.uprule"), "b")}">
                            <input type="checkbox" lay-skin="primary" name="collect[role][uprule][]" value="c" title="${page.lang().get("pic")}" checked="${page.has(page.at("config.collect.role.uprule"), "c")}">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/filter")}：</label>
                        <div class="layui-input-block">
                            <textarea name="collect[role][filter]" class="layui-textarea" placeholder="${page.lang().get("multi_separate_tip")}">${page.t(page.at("config.collect.role.filter"))}</textarea>
                        </div>
                    </div>

                </div>

                <div class="layui-tab-item">

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[website][status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.collect.website.status"), 1L)}">
                            <input type="radio" name="collect[website][status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.collect.website.status"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/hits_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[website][hits_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.website.hits_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[website][hits_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.website.hits_end"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/updown_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[website][updown_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.website.updown_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[website][updown_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.website.updown_end"))}" class="layui-input">
                        </div>
                    </div>


                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/score_rnd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[website][score]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.website.score"), 1L)}">
                            <input type="radio" name="collect[website][score]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.website.score"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/sync_pic")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[website][pic]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.website.pic"), 1L)}">
                            <input type="radio" name="collect[website][pic]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.website.pic"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psernd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[website][psernd]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.website.psernd"), 1L)}">
                            <input type="radio" name="collect[website][psernd]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.website.psernd"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psesyn")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[website][psesyn]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.website.psesyn"), 1L)}">
                            <input type="radio" name="collect[website][psesyn]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.website.psesyn"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/inrule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[website][inrule][]" value="a" title="${page.lang().get("name")}" checked disabled>
                            <input type="checkbox" lay-skin="primary" name="collect[website][inrule][]" value="b" title="${page.lang().get("type")}" checked="${page.has(page.at("config.collect.website.inrule"), "b")}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/uprule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[website][uprule][]" value="a" title="${page.lang().get("content")}" checked="${page.has(page.at("config.collect.website.uprule"), "a")}">
                            <input type="checkbox" lay-skin="primary" name="collect[website][uprule][]" value="b" title="${page.lang().get("blurb")}" checked="${page.has(page.at("config.collect.website.uprule"), "b")}">
                            <input type="checkbox" lay-skin="primary" name="collect[website][uprule][]" value="c" title="${page.lang().get("remarks")}" checked="${page.has(page.at("config.collect.website.uprule"), "c")}">
                            <input type="checkbox" lay-skin="primary" name="collect[website][uprule][]" value="d" title="${page.lang().get("jumpurl")}" checked="${page.has(page.at("config.collect.website.uprule"), "d")}">
                            <input type="checkbox" lay-skin="primary" name="collect[website][uprule][]" value="e" title="${page.lang().get("pic")}" checked="${page.has(page.at("config.collect.website.uprule"), "e")}">

                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/filter")}：</label>
                        <div class="layui-input-block">
                            <textarea name="collect[website][filter]" class="layui-textarea" placeholder="${page.lang().get("multi_separate_tip")}">${page.t(page.at("config.collect.website.filter"))}</textarea>
                        </div>
                    </div>

                </div>

                <div class="layui-tab-item">

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/status")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[comment][status]" value="0" title="${page.lang().get("reviewed_not")}" checked="${!page.eq(page.at("config.collect.comment.status"), 1L)}">
                            <input type="radio" name="collect[comment][status]" value="1" title="${page.lang().get("reviewed")}" checked="${page.eq(page.at("config.collect.comment.status"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/updown_rnd")}：</label>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[comment][updown_start]" placeholder="${page.lang().get("min_val")}" value="${page.v(page.at("config.collect.comment.updown_start"))}" class="layui-input">
                        </div>
                        <div class="layui-input-inline">
                            <input type="text" name="collect[comment][updown_end]" placeholder="${page.lang().get("max_val")}" value="${page.v(page.at("config.collect.comment.updown_end"))}" class="layui-input">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psernd")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[comment][psernd]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.actor.psernd"), 1L)}">
                            <input type="radio" name="collect[comment][psernd]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.actor.psernd"), 1L)}">
                        </div>
                    </div>
                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/psesyn")}：</label>
                        <div class="layui-input-block">
                            <input type="radio" name="collect[comment][psesyn]" value="0" title="${page.lang().get("close")}" checked="${!page.eq(page.at("config.collect.comment.psesyn"), 1L)}">
                            <input type="radio" name="collect[comment][psesyn]" value="1" title="${page.lang().get("open")}" checked="${page.eq(page.at("config.collect.comment.psesyn"), 1L)}">
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/inrule")}：</label>
                        <div class="layui-input-block">
                            <input type="checkbox" lay-skin="primary" name="collect[comment][inrule][]" value="a" title="${page.lang().get("rel_name")}${page.lang().get("or")}${page.lang().get("douban_id")}" checked disabled>
                            <input type="checkbox" lay-skin="primary" name="collect[comment][inrule][]" value="b" title="${page.lang().get("comment_content")}" checked="${page.has(page.at("config.collect.comment.inrule"), "b")}">
                            <input type="checkbox" lay-skin="primary" name="collect[comment][inrule][]" value="c" title="${page.lang().get("comment_name")}" checked="${page.has(page.at("config.collect.comment.inrule"), "c")}" >
                        </div>
                        <div class="layui-form-mid layui-word-aux">${page.lang().get("admin/system/configcollect/inrule_tip_comment")}</div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/uprule")}：</label>
                        <div class="layui-input-block">


                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/filter")}：</label>
                        <div class="layui-input-block">
                            <textarea name="collect[comment][filter]" class="layui-textarea" placeholder="${page.lang().get("multi_separate_tip")}">${page.t(page.at("config.collect.comment.filter"))}</textarea>
                        </div>
                    </div>

                </div>

                <div class="layui-tab-item">
                <blockquote class="layui-elem-quote layui-quote-nm">
                    ${page.lang().get("admin/system/configcollect/words_tip")}
                </blockquote>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/vod_namewords")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][namewords]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.vod.namewords")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/vod_thesaurus")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][thesaurus]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.vod.thesaurus")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/vod_playerwords")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][playerwords]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.vod.playerwords")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/vod_areawords")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][areawords]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.vod.areawords")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/vod_langwords")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][langwords]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.vod.langwords")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/vod_words")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[vod][words]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.vod.words")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/art_thesaurus")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[art][thesaurus]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.art.thesaurus")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/art_words")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[art][words]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.art.words")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/actor_thesaurus")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[actor][thesaurus]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.actor.thesaurus")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/actor_words")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[actor][words]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.actor.words")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/role_thesaurus")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[role][thesaurus]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.role.thesaurus")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/role_words")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[role][words]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.role.words")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/website_thesaurus")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[website][thesaurus]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.website.thesaurus")))}</textarea>
                    </div>
                </div>

                <div class="layui-form-item">
                    <label class="layui-form-label">${page.lang().get("admin/system/configcollect/website_words")}：</label>
                    <div class="layui-input-block">
                        <textarea name="collect[website][words]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.website.words")))}</textarea>
                    </div>
                </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/comment_thesaurus")}：</label>
                        <div class="layui-input-block">
                            <textarea name="collect[comment][thesaurus]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.comment.thesaurus")))}</textarea>
                        </div>
                    </div>

                    <div class="layui-form-item">
                        <label class="layui-form-label">${page.lang().get("admin/system/configcollect/comment_words")}：</label>
                        <div class="layui-input-block">
                            <textarea name="collect[comment][words]" class="layui-textarea">${page.t(page.replaceText(page.at("config.collect.comment.words")))}</textarea>
                        </div>
                    </div>

            </div>
            </div>
        </div>
        <div class="layui-form-item center">
            <div class="layui-input-block">
                <button type="submit" class="layui-btn" lay-submit="" lay-filter="formSubmit">${page.lang().get("btn_save")}</button>
                <button class="layui-btn layui-btn-warm" type="reset">${page.lang().get("btn_reset")}</button>
            </div>
        </div>
    </form>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().staticPath()}/js/jquery.cookie.js"></script>
<script type="text/javascript" src="${page.head().assetsPath()}/system-tab.js" data-cookie="configcollect_tab"></script>
`)
