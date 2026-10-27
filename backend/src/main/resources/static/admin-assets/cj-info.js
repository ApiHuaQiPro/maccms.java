/*
 * 自定义规则表单的页面脚本（B17，docs/architecture/25-b17-collect-config.md 第 7 节）。逐行取自旧 application/admin/view/cj/info.html:410-519
 * 的内联脚本；"测试"打开的地址与语言包文字改由本 script 标签的 data-* 属性提供。旧模板里的 onclick 与 javascript: 链接改成 data-* 属性，
 * 由这里按同样的参数调用同样的函数：
 *   data-cj="testUrl|showAll|hideAll|addCaiji"、data-insert-id/data-insert-text（insertText）、data-add-tag（add_tag）、
 *   data-add-tag-caiji（旧 onclick="add_tag('content_html_rule_'+caiji+'')"：点击时按当时的 caiji 拼出编号）、
 *   data-select-all/data-select-anti（selectall/anti_selectall）。
 * 除此之外逻辑不变（insertText 用的 document.selection 只有旧版 IE 有，其他浏览器里与旧版一样不起作用；"测试"打开的 cj/show_url 在 B20）。
 */
(function () {
    var self = document.currentScript;
    var SHOW_URL = self.getAttribute('data-show-url');
    var L = function (k) { return self.getAttribute('data-msg-' + k); };
    var form;
    layui.use(['element','form','upload','layer'],function () {
        // 操作对象
        var element = layui.element;
        form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
                , upload = layui.upload;

        form.on('radio(sourcetype)',function (data) {
            var num = 4;
            for (var i=1; i<=num; i++){
                if (data.value==i){
                    $('#url_type_'+i).show();
                } else {
                    $('#url_type_'+i).hide();
                }
            }
        });
        form.on('radio(content_page_rule)',function (data) {
            $('#nextpage').hide();
            if(data.value==2){
                $('#nextpage').show();
            }
        });



        //监听折叠
        element.on('collapse(lay_state)', function(data){
            // layer.msg('展开状态：'+ data.show);
        });
    });

    function selectall(obj) {
        $("input[name='"+obj+"']").each(function(i,n){
            this.checked = true;
        });
    }
    function anti_selectall(obj) {
        $("input[name='"+obj+"']").each(function(i,n){
            if (this.checked) {
                this.checked = false;
            } else {
                this.checked = true;
            }});
    }
    //折叠面板
    function showAll(_this) {
        $(_this).parents(".layui-btn-group").siblings(".layui-collapse").children(".layui-colla-item").children(".layui-colla-content").addClass("layui-show");
    }
    function hideAll(_this) {
        $(_this).parents(".layui-btn-group").siblings(".layui-collapse").children(".layui-colla-item").children(".layui-colla-content").removeClass("layui-show");
    }

    //  包含内容
    function insertText(id, text) {
        $('#' + id).focus();
        var str = document.selection.createRange();
        str.text = text;
    }

    function add_tag(id) {
        var index = layer.open({
            type: 1
            ,title: L('filter-rule') //不显示标题栏
            ,closeBtn: 1
            ,area: '600px;'
            ,shade: 0.8
            ,id: 'LAY_layuipro' //设定一个id，防止重复弹出
            ,btn: [L('add'), L('cancel')]
            ,btnAlign: 'c'
            ,moveType: 1 //拖拽模式，0或者1
            ,content: $('#html_rule_show')
            ,yes: function(layero){
                var str = '';
                $("input[name='html_rule']:checked").each(function(){
                    str+=$(this).val()+"\n";
                });
                alert(str);
                $("#"+id).val(str);
                layer.close(index);
            }
        });
    }

    var caiji=0;
    function add_caiji()
    {
        $('#customize_config').append('<div class="layui-form-item mt10"><label class="layui-form-label">' + L('rule-name') + '：</label><div class="layui-input-inline"><input type="text" name="data[customize_config][name][]" placeholder="" value="" class="layui-input"></div><div class="layui-form-mid">' + L('rule-name-en') + '：</div><div class="layui-input-inline"><input type="text" name="data[customize_config][en_name][]" placeholder="" value="" class="layui-input"></div></div><div class="layui-form-item"><label class="layui-form-label">' + L('match-rule') + '：</label><div class="layui-input-inline"><textarea name="data[customize_config][rule][]" id="role_'+caiji+'" class="layui-textarea"></textarea><div class="layui-form-mid layui-word-aux">' + L('use') + '"<a href="javascript:;" data-insert-id="title_rule" data-insert-text="[内容]"> [内容] </a>"' + L('wildcard-tip') + '    </div></div><div class="layui-form-mid">' + L('filter') + '：</div><div class="layui-input-inline"><textarea name="data[customize_config][html_rule][]" id="content_html_rule_'+caiji+'" class="layui-textarea"></textarea><div class="layui-form-mid layui-word-aux"><a class="layui-btn layui-btn-xs" href="javascript:;" data-add-tag="content_html_rule_'+caiji+'">' + L('select') + '</a></div></div></div><hr>');
        caiji++;
    }

    function testUrl() {
        var data = $('#myform').serialize();

        layer.open({
            type: 2
            ,title: 'test'
            ,closeBtn: 1
            ,area: ['500px;','400px']
            ,shade: 0.8
            ,id: 'LAY_testUrl' //设定一个id，防止重复弹出
            ,btn: [L('close')]
            ,btnAlign: 'c'
            ,moveType: 1 //拖拽模式，0或者1
            ,content: SHOW_URL + '?call=1&' +data
        });
    }

    // 旧模板的 onclick 与 javascript: 链接（见文件头）
    $(document).on('click', '[data-cj]', function (e) {
        var act = $(this).attr('data-cj');
        if (act === 'testUrl') { testUrl(); }
        else if (act === 'showAll') { showAll(this); }
        else if (act === 'hideAll') { hideAll(this); }
        else if (act === 'addCaiji') { add_caiji(); }
    });
    $(document).on('click', '[data-insert-id]', function (e) {
        e.preventDefault();
        insertText($(this).attr('data-insert-id'), $(this).attr('data-insert-text'));
    });
    $(document).on('click', '[data-add-tag]', function () {
        add_tag($(this).attr('data-add-tag'));
    });
    $(document).on('click', '[data-add-tag-caiji]', function () {
        add_tag($(this).attr('data-add-tag-caiji') + caiji + '');
    });
    $(document).on('click', '[data-select-all]', function () {
        selectall($(this).attr('data-select-all'));
    });
    $(document).on('click', '[data-select-anti]', function () {
        anti_selectall($(this).attr('data-select-anti'));
    });
})();
