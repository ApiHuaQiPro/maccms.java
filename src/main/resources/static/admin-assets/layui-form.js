/*
 * 只初始化 layui 表单与弹层的页面脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）：逐行取自旧
 * application/admin/view/system/configuser.html、configcomment.html 等页面末尾的内联脚本（回调为空，只为渲染单选框、复选框与下拉框）。
 */
layui.use(['form', 'layer'], function(){
    // 操作对象
    var form = layui.form
            , layer = layui.layer;


});
