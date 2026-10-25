/*
 * 管理员表单页的页面脚本（B12，docs/architecture/21-b12-admin-framework.md 第 8 节）。
 * 逐行取自旧 application/admin/view/admin/info.html:80-151 的内联脚本；语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var MSG_NAME = self.getAttribute('data-msg-name');
    var MSG_PASS = self.getAttribute('data-msg-pass');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            admin_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            },
            admin_pwd: function (value) {
                if (value == "") {
                    return MSG_PASS;
                }
            }
        });

        form.on('checkbox(roleAuth1)', function(data) {
            var child = $(data.elem).parent('dt').siblings('dd').find('input');
            /* 自动选中子节点 */
            child.each(function(index, item) {
                if(item.disabled == true){

                }
                else {
                    item.checked = data.elem.checked;
                }
            });
            form.render('checkbox');
        });

        form.on('checkbox(roleAuth2)', function(data) {
            var child = $(data.elem).parent().find('input');
            var parent = $(data.elem).parent('dd').siblings('dt').find('input');
            var parent_ck= true;
            /* 自动选中子节点 */
            child.each(function(index, item) {
                if(!item.checked){
                    parent_ck = false;
                }
            });
            parent.each(function(index, item) {
                item.checked = parent_ck;
            });
            form.render('checkbox');
        });


        $('.formCheckAll').click(function(){
            var child = $('.role-list-form-top').find('input');
            /* 自动选中子节点 */
            child.each(function(index, item) {
                item.checked = true;
            });
            form.render('checkbox');
        });
        $('.formCheckOther').click(function(){
            var child = $('.role-list-form-top').find('input');
            /* 自动选中子节点 */
            child.each(function(index, item) {
                item.checked = (item.checked  ? false : true);
            });
            form.render('checkbox');
        });

    });
})();
