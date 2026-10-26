/*
 * 逐页推进的批量处理、附件检查页面的跳转（B13，docs/architecture/22-b13-admin-content.md 第 8.4 节）。
 * 旧 mac_jump($url, $sec) 输出内联脚本 <script>setTimeout(function (){location.href="…";},秒*1000);</script>；内容安全策略不允许内联脚本，
 * 改由本 script 标签的 data-url、data-seconds 属性提供，行为不变（页面上的"如果您的浏览器没有自动跳转，请点击这里"链接照旧）。
 */
(function () {
    var self = document.currentScript;
    var url = self.getAttribute('data-url');
    var sec = Number(self.getAttribute('data-seconds'));
    setTimeout(function () {
        location.href = url;
    }, sec * 1000);
})();
