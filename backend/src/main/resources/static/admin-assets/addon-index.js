/*
 * 插件管理页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 11 节）。逐行取自旧 application/admin/view/addon/index.html:36-133 的内联脚本；
 * 地址与语言包文字改由本 script 标签的 data-* 属性提供。插件信息（标题、作者、简介等，来自插件目录里的 info.ini）旧版原样拼进 HTML，新版先转义
 * 再拼（显示相同，含 < & " 时不会注入页面）。列表照旧用 JSONP 读同站的 addon/downloaded。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var STATE_URL = self.getAttribute('data-state-url');
    var UNINSTALL_URL = self.getAttribute('data-uninstall-url');
    var CONFIG_URL = self.getAttribute('data-config-url');
    var L = function (k) { return self.getAttribute('data-lang-' + k); };
    var esc = function (s) {
        return String(s === undefined || s === null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    };
    var url='';
    layui.use(['form','laypage', 'layer','upload','element'], function() {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , upload = layui.upload
                ,element = layui.element;

        //监听Tab切换
        element.on('tab(tabs)', function(data){
            if(data.index <2){
                url = $(this).attr('data-href');
                load_list();
            }
        });

        $(document).on('click', '.btn-disable,.btn-enable', function() {
            $.ajax({
                type: 'get',
                dataType:'json',
                url: STATE_URL,
                data:{name:$(this).attr('data-name'),action:$(this).attr('data-action'),force:0},
                success:function($r){
                    if($r.code ==1){
                        load_list();
                    }
                    layer.msg($r.msg);
                },
                complete:function(){

                }
            });
        });
        $(document).on('click', '.btn-install', function() {

        });
        $(document).on('click', '.btn-uninstall', function() {
            $.ajax({
                type: 'get',
                dataType:'json',
                url: UNINSTALL_URL,
                data:{name:$(this).attr('data-name'),force:0},
                success:function($r){
                    if($r.code ==1){
                        load_list();
                    }
                    layer.msg($r.msg);
                },
                complete:function(){

                }
            });
        });
        $(document).on('click', '.btn-info', function() {

        });

        $('.btn-local').click();
    });

    function load_list(){
        var h='';
        $('#addon_list').html('');
        layer.msg(L('wait'),{time:500000});

        $.ajax({
            type: 'get',
            dataType:'jsonp',
            url: url,
            success:function($r){
                $.each($r.rows,function(i,row){
                    h ='<div class="layui-col-md3"><div class="addon"> <a href="#" target="_blank"> <img src="'+esc(row.image)+'" class="add-logo"> </a> <div class="addon-caption"> <h4>'+esc(row.title)+'<span class="layui-badge layui-bg-green">'+esc(L('level'))+'</span></h4> <p><b>￥'+esc(row.price)+'</b></p> <p>'+esc(L('author'))+': '+esc(row.author)+'</p> <p>'+esc(L('intro'))+': '+esc(row.intro)+'</p> <p>'+esc(L('ver'))+': '+esc(row.version)+'</p> <p>'+esc(L('update-time'))+': '+ getDataTime(row.createtime)+'</p>';
                    h+='<div class="operate"><span class="btn-group">';
                    if(row.install =='1'){
                        h+='<a href="javascript:;" class="layui-btn layui-btn-normal layui-btn-sm j-iframe" data-name="'+esc(row.name)+'" data-href="'+esc(CONFIG_URL)+'?name='+encodeURIComponent(row.name)+'" ><i class="layui-icon">&#xe614;</i>'+esc(L('config'))+'</a>';
                        if(row.state=='1'){
                            h+='<a href="javascript:;" class="layui-btn layui-btn-warm layui-btn-sm btn-disable" data-name="'+esc(row.name)+'" data-action="disable"><i class="layui-icon">&#x1006;</i>'+esc(L('disable'))+'</a>';
                        }
                        else{
                            h+='<a href="javascript:;" class="layui-btn  layui-btn-sm btn-enable" data-name="'+esc(row.name)+'" data-action="enable"><i class="layui-icon">&#xe605;</i>'+esc(L('enable'))+'</a><a href="javascript:;" class="layui-btn layui-btn-danger layui-btn-sm btn-uninstall" data-name="'+esc(row.name)+'"><i class="layui-icon">&#x1006;</i>'+esc(L('uninstall'))+'</a>';
                        }
                    }
                    else{
                        h+='<a href="javascript:;" class="layui-btn layui-btn-sm btn-install"><i class="layui-icon">&#xe601;</i>'+esc(L('install'))+'</a>';
                    }
                    h+='</span> <span class="fr" style="margin-top:10px;"> <a href="javascript:;" class="btn-info text-gray " data-name="'+esc(row.name)+'" title="'+esc(L('detail'))+'"><i class="layui-icon">&#xe63c;</i></a> </span> </div> </div> </div> </div>';

                    $('#addon_list').append(h);
                });
            },
            complete:function(){
                layer.closeAll();
            }
        });
    }
})();
