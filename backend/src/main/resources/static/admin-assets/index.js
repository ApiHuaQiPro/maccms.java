/*
 * 后台框架页的页面脚本（B12，docs/architecture/21-b12-admin-framework.md 第 8 节）。
 * 逐行取自旧 application/admin/view/index/index.html:165-339 的两段内联脚本；为了内容安全策略不允许内联脚本，移到这里，
 * 模板里由服务端写入的地址与语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var CHECKCACHE_URL = self.getAttribute('data-checkcache-url');
    var MSG_MAX = self.getAttribute('data-msg-max');
    var MSG_CLOSE_EMPTY = self.getAttribute('data-msg-close-empty');
    var MSG_CACHE = self.getAttribute('data-msg-cache');

    window.localStorage.clear();
    window.LAYUI_OFFSET = 60;

    $('#admin_name').click(function(){
         $('body').toggleClass('drawer-body-right');
    });

    layui.use(['element', 'layer'], function() {
        var $ = layui.jquery, element = layui.element, layer = layui.layer;
        $('.layui-tab-content').height($(window).height() - 145);
        var tab = {
            add: function(title, url, id) {
                element.tabAdd('macTab', {
                        title: title,
                        content: '<iframe width="100%" height="100%" lay-id="'+id+'" frameborder="0" src="'+url+'" scrolling="yes" class="x-iframe"></iframe>',
                        id: id
            });
            }, change: function(id) {
                element.tabChange('macTab', id);
            }
        };
        $('.admin-nav-item').click(function(event) {
            var that = $(this);
            var id = that.attr('data-id');
            if ($('iframe[lay-id="'+id+'"]')[0]) {
                tab.change(id);
                event.stopPropagation();
                $("iframe[lay-id='"+id+"']")[0].contentWindow.location.reload(true);//切换后刷新框架
                return false;
            }
            if ($('iframe').length == 10) {
                layer.msg(MSG_MAX);
                return false;
            }
            that.css({color:'#fff'});
            tab.add(that.text(), that.attr('href'), that.attr('data-id'));
            tab.change(that.attr('data-id'));
            event.stopPropagation();
            return false;
        });
        $(document).on('click', '.layui-tab-close', function() {
            $('.layui-nav-child a[data-id="'+$(this).parent('li').attr('lay-id')+'"]').css({color:'rgba(255,255,255,.7)'});
        });


        //显示顶部导航时作位置判断，点击左边菜单、上一tab、下一tab时公用
        function showTab(li) {
            if (li.length) {
                var ul = $('#B_history'),
                    li_offset = li.offset(),
                    li_width = li.outerWidth(true),
                    next_left = $('#page-next').offset().left, //右边按钮的界限位置
                    prev_right = $('#page-prev').offset().left + $('#page-prev').outerWidth(true); //左边按钮的界限位置
                if (li_offset.left + li_width > next_left) { //如果将要移动的元素在不可见的右边，则需要移动
                    var distance = li_offset.left + li_width - next_left; //计算当前父元素的右边距离，算出右移多少像素
                    ul.animate({
                        left: '-=' + distance
                    }, 200, 'swing');
                } else if (li_offset.left < prev_right) { //如果将要移动的元素在不可见的左边，则需要移动
                    var distance = prev_right - li_offset.left; //计算当前父元素的左边距离，算出左移多少像素
                    ul.animate({
                        left: '+=' + distance
                    }, 200, 'swing');
                }
                li.trigger('click');
            }
        }

        //上一个选项卡
        $('#page-prev').click(function(e) {
            e.preventDefault();
            e.stopPropagation();
            var ul = $('#B_history'),
                current = ul.find('.layui-this'),
                li = current.prev('li');
            showTab(li);
        });


        //下一个选项卡
        $('#page-next').click(function(e) {
            e.preventDefault();
            e.stopPropagation();
            var ul = $('#B_history'),
                current = ul.find('.layui-this'),
                li = current.next('li');
            showTab(li);
        });

        //刷新
        $('#J_refresh').click(function(e) {
            e.preventDefault();
            e.stopPropagation();
            var index = layer.load();
            var id = $('#B_history .layui-this').attr('lay-id'),
                iframe = $("iframe[lay-id='"+id+"']")[0];
            if (iframe.contentWindow) {
                iframe.contentWindow.location.reload(true);
                layer.close(index);
            }
        });

        //关闭全部选项卡
        $(".closePageAll").on("click", function() {
            if ($("#B_history li").length > 1) {
                $("#B_history li").each(function() {
                    var data_id = $(this).attr('lay-id');
                    if (data_id != '' && data_id != '111') {
                        element.tabDelete("macTab", data_id);
                        $("iframe[lay-id='"+data_id+"']").remove(); //移除iframe页面
                    }
                })

            } else {
                layer.msg(MSG_CLOSE_EMPTY);
            }
        });
        //关闭其他选项卡
        $(".closePageOther").on("click", function() {
            if ($("#B_history li").length > 1) {
                var cid = $('#B_history .layui-this').attr('lay-id');
                $("#B_history li").each(function() {
                    var data_id = $(this).attr('lay-id');
                    if (data_id != '' && data_id != '111' && data_id != cid) {
                        element.tabDelete("macTab", data_id);
                        $("iframe[lay-id='"+data_id+"']").remove(); //移除iframe页面
                    }
                })

            } else {
                layer.msg(MSG_CLOSE_EMPTY);
            }
        });

        $('.mob-nav').on("click", function() {
            $('.site-tree-mobile').click();
        });
        //手机设备的简单适配
        var treeMobile = $('.site-tree-mobile'),
            shadeMobile = $('.site-mobile-shade')
        treeMobile.on('click', function() {
            $('body').addClass('site-mobile');
            $('.Menus-winner').removeClass('topLevelMenus');//解决top导航
            $('.Menus-winner').addClass('topLevelMenus-winner');//解决top导航

        });
        shadeMobile.on('click', function() {
            $('body').removeClass('site-mobile');
            $('.Menus-winner').addClass('topLevelMenus');//解决top导航
        });
    });
    $(function(){
        // 旧版写成 setInterval("checkCache()", …)（字符串形式需要 eval），这里改为函数引用
        setInterval(checkCache, 30*1000);
    });
    //检查缓存并保持登录状态
    function checkCache(){
        $.ajax({
            url: CHECKCACHE_URL,
            cache: false,
            success: function(r){
                if(r=='haved'){
                    layer.msg(MSG_CACHE, {time: 3000});
                }
            }
        });
    }
})();
