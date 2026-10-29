/*
 * 安装向导跳转提示页的倒计时（B22，与后台 admin-assets/jump.js 相同，31 文档第 7 节）：取自旧 thinkphp/tpl/dispatch_jump.tpl:33-45 的内联脚本。
 * 失败页的地址是 javascript:history.back(-1);，内容安全策略不执行 javascript: 地址，所以这里对它改为直接调用 history.back(-1)
 * （倒计时结束与点击"跳转"两种情况），其余地址照旧跳转。
 */
(function(){
    var BACK = 'javascript:history.back(-1);';
    var link = document.getElementById('href');
    var wait = document.getElementById('wait'),
        raw = link.getAttribute('href'),
        href = link.href;
    function go() {
        if (raw === BACK) {
            history.back(-1);
        } else {
            location.href = href;
        }
    }
    link.addEventListener('click', function (e) {
        if (raw === BACK) {
            e.preventDefault();
            go();
        }
    });
    var interval = setInterval(function(){
        var time = --wait.innerHTML;
        if(time <= 0) {
            go();
            clearInterval(interval);
        };
    }, 1000);
})();
