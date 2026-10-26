/*
 * 会员组表单的页面脚本（B14，docs/architecture/23-b14-admin-member.md 第 8 节）。
 * 逐行取自旧 application/admin/view/group/info.html:96-130 的内联脚本；语言包文字改由本 script 标签的 data-* 属性提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var MSG_NAME = self.getAttribute('data-msg-name');
    layui.use(['form', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery;

        // 验证
        form.verify({
            group_name: function (value) {
                if (value == "") {
                    return MSG_NAME;
                }
            }
        });

        $('.formCheckAll').click(function(){
            var child = $('.role-list-form').find('input');
            /* 自动选中子节点 */
            child.each(function(index, item) {
                item.checked = true;
            });
            form.render('checkbox');
        });
        $('.formCheckOther').click(function(){
            var child = $('.role-list-form').find('input');
            /* 自动选中子节点 */
            child.each(function(index, item) {
                item.checked = (item.checked  ? false : true);
            });
            form.render('checkbox');
        });
    });
})();
