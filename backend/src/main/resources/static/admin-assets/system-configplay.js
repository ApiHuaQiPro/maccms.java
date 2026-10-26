/*
 * 播放器参数配置页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。逐行取自旧 application/admin/view/system/configplay.html:144-187
 * 的内联脚本；四个配色链接的 onclick="setColor(n);return false;" 改为在这里按 data-color 绑定（内容安全策略不允许内联事件）。除此之外逻辑不变。
 */
(function () {
    function setColor(v) {
        switch (v) {
            case 2:
                v = "EFF4F7,000000,666666,E4E4E4,000000,FF0000,FF0000,DBEBFE,458CE4,DBEBFE,FFFFFF,458CE4,DBEBFE,DBEBFE,fcfcfc";
                break;
            case 3:
                v = "D8CFDF,000000,666666,E4E4E4,000000,FF0000,FF0000,D8CFDF,926C92,BEAFC9,FFFFFF,926C92,BEAFC9,BEAFC9,fcfcfc";
                break;
            case 4:
                v = "D7E7B6,000000,666666,E4E4E4,000000,FF0000,FF0000,9EC14C,A3C656,BAD480,FFFFFF,A3C656,BAD480,BAD480,fcfcfc";
                break;
            default:
                v = "000000,F6F6F6,F6F6F6,333333,666666,FFFFF,FF0000,2c2c2c,ffffff,a3a3a3,2c2c2c,adadad,adadad,48486c,fcfcfc";
                break;
        }
        $("#mac_colors").val(v);
    }

    $(document).on('click', '[data-color]', function () {
        setColor(parseInt(this.getAttribute('data-color'), 10));
        return false;
    });

    layui.use(['form', 'layer'], function(){
        // 操作对象
        var form = layui.form
            , layer = layui.layer;


        $('#btnDef').click(function(){
            $('input[name="play[width]"]').val('100%');
            $('input[name="play[height]"]').val('100%');
            $('input[name="play[widthmob]"]').val('100%');
            $('input[name="play[heightmob]"]').val('100%');
            $('input[name="play[widthpop]"]').val('600');
            $('input[name="play[heightpop]"]').val('500');
            $('input[name="play[second]"]').val('5');
            $('input[name="play[prestrain]"]').val('');
            $('input[name="play[buffer]"]').val('');
            $('input[name="play[parse]"]').val('');

        });

    });
})();
