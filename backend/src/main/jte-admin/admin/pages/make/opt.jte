@import com.maccms.backend.admin.web.view.MakeOptView
@param MakeOptView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container p10">


        <form class="layui-form layui-form-pane" method="post" action="" id="form1">

            <div class="layui-form-item">
                <label class="layui-form-label">${page.lang().get("vod")}${page.lang().get("type")}：</label>
                <div class="layui-input-inline">
                    <select name="vodtype[]" multiple style="width:150px;height:150px;" lay-ignore>
                        @for(var vo : page.vodTypes())
                        <option value="${vo.value()}" >${vo.label()}</option>
                        @endfor
                    </select>
                </div>
                <div class="layui-input-inline w300">
                    <div class="layui-btn-container">
                    <input type="button" value="${page.lang().get("admin/make/select_type")}" class="layui-btn layui-btn-primary" data-make="ac=type&tab=vod"/>
                    <input type="button" value="${page.lang().get("admin/make/all_type")}" class="layui-btn layui-btn-primary" data-make="ac=type&tab=vod&vodtype=${page.vodTypeIds()}"/>
                    <input type="button" value="${page.lang().get("admin/make/today_type")}" class="layui-btn layui-btn-primary" data-make="ac=type&tab=vod&vodtype=${page.vodTypeIdsToday()}&ac2=day"/>
                    <input type="button" value="${page.lang().get("admin/make/select_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=vod"/>
                    <input type="button" value="${page.lang().get("admin/make/all_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=vod&vodtype=${page.vodTypeIds()}"/>
                    <input type="button" value="${page.lang().get("admin/make/today_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=vod&vodtype=${page.vodTypeIdsToday()}&ac2=day"/>
                    <input type="button" value="${page.lang().get("admin/make/no_make_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=vod&ac2=nomake"/>
                    <input type="button" value="${page.lang().get("admin/make/one_today")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=vod&vodtype=${page.vodTypeIdsToday()}&ac2=day&jump=1"/>
                    </div>
                </div>
                <label class="layui-form-label">${page.lang().get("art")}${page.lang().get("type")}：</label>
                <div class="layui-input-inline">
                    <select name="arttype[]" multiple style="width:150px;height:150px;" lay-ignore>
                        @for(var vo : page.artTypes())
                        <option value="${vo.value()}" >${vo.label()}</option>
                        @endfor
                    </select>
                </div>
                <div class="layui-input-inline w300">
                    <div class="layui-btn-container">
                        <input type="button" value="${page.lang().get("admin/make/select_type")}" class="layui-btn layui-btn-primary" data-make="ac=type&tab=art"/>
                        <input type="button" value="${page.lang().get("admin/make/all_type")}" class="layui-btn layui-btn-primary" data-make="ac=type&tab=art&arttype=${page.artTypeIds()}"/>
                        <input type="button" value="${page.lang().get("admin/make/today_type")}" class="layui-btn layui-btn-primary" data-make="ac=type&tab=art&arttype=${page.artTypeIdsToday()}&ac2=day"/>
                        <input type="button" value="${page.lang().get("admin/make/select_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=art"/>
                        <input type="button" value="${page.lang().get("admin/make/all_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=art&arttype=${page.artTypeIds()}"/>
                        <input type="button" value="${page.lang().get("admin/make/today_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=art&arttype=${page.artTypeIdsToday()}&ac2=day"/>
                        <input type="button" value="${page.lang().get("admin/make/no_make_info")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=art&ac2=nomake"/>
                        <input type="button" value="${page.lang().get("admin/make/one_today")}" class="layui-btn layui-btn-primary" data-make="ac=info&tab=art&arttype=${page.artTypeIdsToday()}&ac2=day&jump=1"/>
                    </div>
                </div>
            </div>

            <hr class="layui-bg-gray">


            <div class="layui-form-item">
                <label class="layui-form-label">${page.lang().get("admin/make/topic_list")}：</label>
                <div class="layui-input-inline">
                    <select name="topic[]" multiple style="width:150px;height:150px;" lay-ignore>
                        @for(var vo : page.topics())
                        <option value="${vo.value()}" >${vo.label()}</option>
                        @endfor
                    </select>
                </div>
                <div class="layui-input-inline w300">
                    <div class="layui-btn-container">
                        <input type="button" value="${page.lang().get("admin/make/select_topic")}" class="layui-btn layui-btn-primary" data-make="ac=topic_info"/>
                        <input type="button" value="${page.lang().get("admin/make/all_topic")}" class="layui-btn layui-btn-primary" data-make="ac=topic_info&topic=${page.topicIds()}"/>
                        <input type="button" value="${page.lang().get("admin/make/topic_index")}" class="layui-btn layui-btn-primary" data-make="ac=topic_index"/>
                    </div>
                </div>
                <label class="layui-form-label">${page.lang().get("admin/make/label_page")}：</label>
                <div class="layui-input-inline">
                    <select name="label[]" multiple style="width:150px;height:150px;" lay-ignore>
                        @for(var vo : page.labels())
                        <option value="${vo.value()}" >${vo.label()}</option>
                        @endfor
                    </select>
                </div>
                <div class="layui-input-inline w300">
                    <div class="layui-btn-container">
                        <input type="button" value="${page.lang().get("make_page")}" class="layui-btn layui-btn-primary" data-make="ac=label   ">
                        <input type="button" value="${page.lang().get("make_all")}" class="layui-btn layui-btn-primary" data-make="ac=label&label=${page.labelIds()}">
                    </div>
                </div>
            </div>

            <hr class="layui-bg-gray">
            <div class="layui-form-item">
                <label class="layui-form-label">SiteMap：</label>
                <div class="layui-input-inline w800">
                    <div class="layui-btn-container">
                        <input type="button" value="${page.lang().get("admin/make/rss")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=index">
                        <input type="button" value="${page.lang().get("admin/make/google")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=google">
                        <input type="button" value="${page.lang().get("admin/make/baidu")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=baidu">
                        <input type="button" value="${page.lang().get("admin/make/so")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=so">
                        <input type="button" value="${page.lang().get("admin/make/sogou")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=sogou">
                        <input type="button" value="${page.lang().get("admin/make/bing")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=bing">
                        <input type="button" value="${page.lang().get("admin/make/sm")}" class="layui-btn layui-btn-primary" data-make="ac=rss&ac2=sm">
                    </div>
                </div>
                <label class="layui-form-label">${page.lang().get("admin/make/make_page_num")}：</label>
                <div class="layui-input-inline w200">
                    <input type="text" name="ps" class="layui-input" placeholder="" value="1" />
                </div>
            </div>

    </form>

</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/make-opt.js" data-cur-url="${page.makeUrl()}"></script>
`)
