@import com.maccms.backend.admin.application.view.MenuGroupView
@import com.maccms.backend.admin.application.view.MenuItemView
@import com.maccms.backend.admin.web.view.FramePage
@param FramePage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), styles = @`
<style type="text/css">
    .hs-iframe{width:100%;height:100%;}
    .layui-tab-content {top: 41px; position: absolute; bottom: 0; width: 100%; padding: 0; }
    .layui-tab-item{height:100%;}
    .layui-tab-title .layui-this {background-color: #009688; color: #fff; }
    .layui-tab-title .layui-this:after {border: none; }
    .layui-tab-title li { border-right: 1px solid #e6e6e6;}
    .footer{position:fixed;left:0;bottom:0;z-index:998;}
</style>
`, content = @`
<div class="layui-layout layui-layout-admin">
    <div class="layui-header header-top">

	<div class="top-logo">${page.lang().get("admin/index/index/name")}</div>
	<ul class="layout-right" lay-filter="">
		 <li class="nav-item">
			<a href="${page.head().sitePath()}/" target="_blank"><i class="layui-icon layui-icon-home size-14"></i></a>
	     </li>
		 <li class="nav-item ">
			   <a href="${page.clearUrl()}" class="j-ajax " refresh="yes"><i class="layui-icon layui-icon-delete size-16"></i></a>
		 </li>
		 <li class="nav-item">
			   <a href="javascript:;" id="admin_name">
                   <i class="layui-icon layui-icon-app  size-14"></i>
			   </a>

		 </li>
	</ul>

	</div>

	<div class="layui-header">
	    <div class="fl header-logo">${page.lang().get("admin/index/index/name")}</div>
		<div class="fl header-fold topLevelMenus"><a href="javascript:;" title="${page.lang().get("admin/index/index/menu_switch")}" class="aicon ai-caidan" id="foldSwitch"><i class="layui-icon">&#xe65f;</i></a></div>
		 <a href="javascript:;" class=" site-tree-mobile layui-mobile layui-show-xs-block layui-hide-lg"><i class="layui-icon layui-icon-spread-left size-20"></i></a>
	    <div class="bottom-nav">
		  <ul class="layui-nav fl nobg main-nav " >
            @for(MenuGroupView vo : page.menus())
               <li class="layui-nav-item ${vo.index() == 1 ? "layui-this " : ""}main-nav mob-nav" data-i="${vo.index() == 1 ? String.valueOf(vo.index()) : null}">
                <a href="javascript:;" data-i="${vo.index()}">${vo.name()}</a>
			  </li>
            @endfor
           </ul>
			<ul class="layui-nav layui-layout-right" lay-filter="">
				 <li class="layui-nav-item layui-hide-xs">
					<a href="javascript:void(0);">${page.lang().get("opt")}&nbsp;&nbsp;</a>
					<dl class="layui-nav-child">
                        <dd><a href="${page.clearUrl()}" class="j-ajax" refresh="yes">${page.lang().get("admin/index/index/menu_cache_clear")}</a></dd>
						<dd><a href="javascript:void(0);" id="lockScreen">${page.lang().get("admin/index/index/menu_lock")}</a></dd>
						<dd><a href="${page.logoutUrl()}">${page.lang().get("admin/index/index/menu_logout")}</a></dd>
					</dl>
				</li>
				<li class="layui-nav-item layui-hide-xs">
                    <a href="${page.head().sitePath()}/" target="_blank">${page.lang().get("admin/index/index/menu_index")}</a>
                </li>
			</ul>
		</div>
	</div>

	<div class="nav-item-ul">
	  <div class="">
		  <ul class="layui-nav">
			  <li class="layui-nav-item"><a href="${page.head().sitePath()}/" target="_blank">${page.lang().get("admin/index/index/menu_index")}</a></li>
			  <li class="layui-nav-item"><a href="javascript:void(0);" id="lockScreen">${page.lang().get("admin/index/index/menu_lock")}</a></li>
			  <li class="layui-nav-item"><a href="${page.clearUrl()}" class="j-ajax" refresh="yes">${page.lang().get("admin/index/index/menu_cache_clear")}</a></li>
			  <li class="layui-nav-item"><a href="${page.logoutUrl()}">${page.lang().get("admin/index/index/menu_logout")}</a></li>
		  </ul>

	  </div>
	</div>

    <div class="layui-side layui-bg-black" id="switchNav">
        <div class="layui-side-scroll navBar" id="navBar">
            @for(MenuGroupView v : page.menus())
            <ul class="layui-nav layui-nav-tree" style="${v.index() == 1 ? null : "display:none;"}">
                    <li class="layui-nav-item layui-nav-itemed">
                    <a href="javascript:;"><i class="${v.icon()}"></i>${v.name()}<span class="layui-nav-more"></span></a>

                    <dl class="layui-nav-child">
                        @for(MenuItemView vv : v.items())
                        <dd>
                            @if(vv.quickTitle())
                            <a class="admin-nav-item" data-id="${vv.dataId()}" href="javascript:void(0);return false;"><i class=""></i> ${vv.name()}</a>
                            @elseif(vv.script())
                            <a class="admin-nav-item" data-id="${vv.dataId()}" href="javascript:void(0);"><i class=""></i> ${vv.name()}</a>
                            @elseif(!vv.separator())
                            <a class="admin-nav-item" data-id="${vv.dataId()}" href="${vv.url()}"><i class=""></i> ${vv.name()}</a>
                            @else
                            <hr class="layui-bg-gray">
                            @endif
                        </dd>
                        @endfor
                    </dl>
                    </li>
                </ul>
            @endfor
        </div>
    </div>
    <div class="site-tree-mobile layui-hide">
        <i class="layui-icon"></i>
    </div>
    <div class="site-mobile-shade"></div>

    <div class="layui-body" id="switchBody">
        <div class="layui-tab mag0" id="top_tabs_box" lay-filter="macTab" lay-allowClose="true">
            <div class="tab-go-refresh" id="J_refresh"><i class="layui-icon layui-icon-refresh"></i></div>
            <div class="tab-go-left" id="page-prev"><i class="layui-icon layui-icon-zuozuo"></i></div>
            <ul class="layui-tab-title top_tab" id="B_history">
                <li lay-id="111" class="layui-this">${page.lang().get("admin/index/index/menu_welcome")}</li>
            </ul>
            <div class="tab-right">
                <div class="tab-go-right" id="page-next"><i class="layui-icon layui-icon-youyou"></i></div>
                <ul class="layui-nav closeBox">
                    <li class="layui-nav-item" >
                        <a href="javascript:;">${page.lang().get("admin/index/index/menu_opt")}</a>
                        <dl class="layui-nav-child">
                            <dd><a href="javascript:;" class="closePageAll">&nbsp;${page.lang().get("admin/index/index/menu_close_all")}</a></dd>
                            <dd><a href="javascript:;" class="closePageOther">&nbsp;${page.lang().get("admin/index/index/menu_close_other")}</a></dd>
                        </dl>
                    </li>
                </ul>
            </div>
            <div class="layui-tab-content">
                <div class="layui-tab-item layui-show">
                    <iframe lay-id="111" src="${page.welcomeUrl()}" width="100%" height="100%" frameborder="0" scrolling="yes" class="hs-iframe"></iframe>
                </div>
            </div>

        </div>

    </div>

    <div class="layui-footer footer">
        <div class="fl"></div>
        <div class="fr"> ${page.lang().get("maccms_copyright")}</div>
    </div>
</div>
`, scripts = @`
<script type="text/javascript" src="${page.head().assetsPath()}/index.js" data-checkcache-url="${page.checkcacheUrl()}" data-msg-max="${page.lang().get("admin/index/index/menu_max")}" data-msg-close-empty="${page.lang().get("admin/index/index/menu_close_empty")}" data-msg-cache="${page.lang().get("admin/index/cache_data")}"></script>
`)
