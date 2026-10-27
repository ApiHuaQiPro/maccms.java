/*
 * 数据库批量替换页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 9 节）。逐行取自旧 application/admin/view/database/rep.html:76-131 的
 * 内联脚本；地址与语言包文字改由本 script 标签的 data-* 属性提供。旧版把列名拼成 href="javascript:setfield('列名')" 的按钮，内容安全策略不允许
 * javascript: 地址，新版写成 href="javascript:;" 加 data-field，点击时同样调用 setfield；列名转义后再拼进 HTML。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var COLUMNS_URL = self.getAttribute('data-columns-url');
    var esc = function (s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    };
    layui.use(['form', 'layer'], function(){
        // 操作对象
        var form = layui.form
                , layer = layui.layer,
                $ = layui.jquery;

        form.on('select(table)', function(data){
            $('.fields').html('');
            if(data.value !=''){
                $.post(COLUMNS_URL, {table:data.value}, function(res) {
                    if (res.code == 1) {
                        $.each(res.data,function(index,row){
                            $(".fields").append('<a class="layui-btn layui-btn-xs w80" href="javascript:;" data-field="'+esc(row.Field)+'">'+esc(row.Field)+'</a>&nbsp;&nbsp;');
                            if(index>0 && index%5==0){
                                //$(".fields").append('<br>');
                            }
                        });
                    }
                    layer.msg(res.msg);
                });
            }
        });

        // 验证
        form.verify({
            table: function (value) {
                if (value == "") {
                    return self.getAttribute('data-msg-table');
                }
            },
            field: function (value) {
                if (value == "") {
                    return self.getAttribute('data-msg-field');
                }
            },
            findstr: function (value) {
                if (value == "") {
                    return self.getAttribute('data-msg-findstr');
                }
            },
            tostr: function (value) {
                if (value == "") {
                    return self.getAttribute('data-msg-tostr');
                }
            }
        });
    });

    function setfield(v){
        $('#field').val(v);
    }

    $(document).on('click', '.fields [data-field]', function () {
        setfield(this.getAttribute('data-field'));
    });
})();
