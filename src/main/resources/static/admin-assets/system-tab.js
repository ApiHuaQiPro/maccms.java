/*
 * 系统配置各页的标签记忆（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。
 * 逐行取自旧 application/admin/view/system/configapi.html、configcollect.html、configpay.html、configseo.html 的内联脚本（四页相同，
 * 只有 cookie 名不同；configseo 旧版用的就是 configapi_tab，照旧），cookie 名改由本 script 标签的 data-cookie 提供。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var COOKIE = self.getAttribute('data-cookie');
    layui.use(['element', 'form', 'layer'], function() {
        var element = layui.element
            ,form = layui.form
            , layer = layui.layer;


        element.on('tab(tb1)', function(){
            $.cookie(COOKIE, this.getAttribute('lay-id'));
        });

        if( $.cookie(COOKIE) !=null ) {
            element.tabChange('tb1', $.cookie(COOKIE));
        }

    });
})();
