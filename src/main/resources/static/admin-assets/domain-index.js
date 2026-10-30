/*
 * 站群管理页的脚本（B15，docs/architecture/24-b15-admin-system.md 第 8 节）。逐行取自旧 application/admin/view/domain/index.html 里的两段内联脚本
 * （页面中间的 var arr_len = N; 与页面末尾的脚本）；已有站点数、模板目录列表、导入地址与语言包文字改由本 script 标签的 data-* 属性提供。
 * 旧版把模板目录名原样拼进新一行的 HTML，新版先转义再拼（目录名含 < & " 时不会注入页面，显示相同）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var arr_len = parseInt(self.getAttribute('data-count'), 10) || 0;
    var IMPORT_URL = self.getAttribute('data-import-url');
    var L = function (k) { return self.getAttribute('data-lang-' + k); };
    var esc = function (s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    };
    var templates = JSON.parse(self.getAttribute('data-templates') || '[]');
    var template_select = '';
    for (var i = 0; i < templates.length; i++) {
        template_select += '<option value="' + esc(templates[i]) + '">' + esc(templates[i]) + '</option>';
    }

    layui.use(['form','layer','upload'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
            , upload = layui.upload;


        upload.render({
            elem: '.layui-upload'
            ,url: IMPORT_URL
            ,method: 'post'
            ,exts:'txt'
            ,before: function(input) {
                layer.msg(L('uploading'), {time:3000000});
            },done: function(res, index, upload) {
                var obj = this.item;
                if (res.code == 0) {
                    layer.msg(res.msg);
                    return false;
                }
                location.reload();
            }
        });

        $('.j-player-add').on('click',function(){
            arr_len++;
            var tpl='<div class="layui-form-item" ><label class="layui-form-label">'+esc(L('website'))+'：'+arr_len+'</label><div class="layui-input-inline w150"><input type="text" name="domain[site_url][]" class="layui-input" placeholder="'+esc(L('domain'))+'" ></div>&nbsp;<div class="layui-input-inline w150"><input type="text" name="domain[site_name][]" class="layui-input" placeholder="'+esc(L('site-name'))+'"></div>&nbsp;<div class="layui-input-inline w150"><input type="text" name="domain[site_keywords][]" class="layui-input" placeholder="'+esc(L('keywords'))+'" ></div>&nbsp;<div class="layui-input-inline w150"><input type="text" name="domain[site_description][]" class="layui-input" placeholder="'+esc(L('description'))+'" ></div>&nbsp;<div class="layui-input-inline w150"><select name="domain[template_dir][]"><option value="no">'+esc(L('select-template'))+'.</option>'+template_select+'</select></div><div class="layui-input-inline w150"><input type="text" name="domain[html_dir][]" class="layui-input" placeholder="'+esc(L('tpl-dir'))+'" ></div><div class="layui-input-inline w150"><input type="text" name="domain[ads_dir][]" class="layui-input" placeholder="'+esc(L('ads-dir'))+'" ></div><div><a href="javascript:void(0)" class="j-editor-remove">'+esc(L('del'))+'</a>&nbsp;</div></div>';
            $("#domain_list").append(tpl);

            form.render('select');
        });

        if(arr_len==0) {
            $('.j-player-add').click();
        }
    });
})();
