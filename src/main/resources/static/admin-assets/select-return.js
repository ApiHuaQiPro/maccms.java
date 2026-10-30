/*
 * 列表页"选择返回"按钮（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 旧模板 {if condition="$param.select eq 1"} 里的内联事件 onclick="parent.onSelectResult('{$param.input}',$('.checkbox-ids:checked'))"
 * 改成这里绑定：输入框名改由按钮的 data-input 属性提供（旧版原样拼进脚本字符串）。行为不变。
 */
(function () {
    $(document).on('click', 'a[data-input]', function () {
        parent.onSelectResult($(this).attr('data-input'), $('.checkbox-ids:checked'));
    });
})();
