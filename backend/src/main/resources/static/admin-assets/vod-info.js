/*
 * 视频表单的页面脚本（B13，docs/architecture/22-b13-admin-content.md 第 8 节）。
 * 逐行取自旧 application/admin/view/vod/info.html 的内联脚本：组数（players_arr_len 等）、播放器/下载器/服务器组的选项、上传与扩展字典的地址、
 * img_key/img_api、语言包文字与编辑时的分类编号改由本 script 标签的 data-* 属性提供；拼进 HTML 的选项、扩展项与截图地址先转义（旧版原样
 * 拼接；显示不变）。"查询资料"按钮旧版以 JSONP 请求外部站点 api.maccms.la，不在外部调用白名单内（路线图第 4 节），新版不发请求，校验豆瓣
 * 编号后直接提示"请求失败"（22 文档第 10 节）。除此之外逻辑不变。
 */
(function () {
    var self = document.currentScript;
    var UPLOAD_URL = self.getAttribute('data-upload-url');
    var SHOT_URL = self.getAttribute('data-shot-url');
    var FILE_URL = self.getAttribute('data-file-url');
    var EXTEND_URL = self.getAttribute('data-extend-url');
    var ANNEX_DEL_URL = self.getAttribute('data-annex-del-url');
    var TYPE_ID = self.getAttribute('data-type-id');
    var L = JSON.parse(self.getAttribute('data-lang'));
    window.UPLOAD_IMG_KEY = self.getAttribute('data-img-key');
    window.UPLOAD_IMG_API = self.getAttribute('data-img-api');

    var players_arr_len = Number(self.getAttribute('data-players-len'));
    var downers_arr_len = Number(self.getAttribute('data-downers-len'));
    var plot_arr_len = Number(self.getAttribute('data-plots-len'));

    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function options(attr) {
        var html = '';
        $.each(JSON.parse(self.getAttribute(attr)), function (i, o) {
            html += '<option value="' + esc(o.from) + '">' + esc(o.show) + '</option>';
        });
        return html;
    }

    var ue = editor_getEditor('vod_content');
    var player_select = options('data-player-options');
    var downer_select = options('data-downer-options');
    var server_select = options('data-server-options');

    layui.use(['form','upload', 'layer'], function () {
        // 操作对象
        var form = layui.form
                , layer = layui.layer
                , $ = layui.jquery
                , upload = layui.upload;;

        // 验证
        form.verify({
            vod_name: function (value) {
                if (value == "") {
                    return L['name_empty'];
                }
            }
        });

        $(document).on("click", ".extend", function(){
            var $id = $(this).attr('data-id');
            var $val;
            if($id == 'vod_class' || $id == 'vod_keywords'){
                $val = $("input[id='"+$id+"']").val();
                if($val!=''){
                    $val = $val+',';
                }
                if($val.indexOf($(this).text())>-1){
                    return;
                }
                $("input[id='"+$id+"']").val($val+$(this).text());
            }else{
                $("input[id='"+$id+"']").val($(this).text());
            }
        });

        form.on('select(type_id)', function(data){
            getExtend(data.value);
        });

        //多图片上传
        upload.render({
            elem: '.screenshot'
            ,url: SHOT_URL
            ,multiple: true
            ,before: function(obj){
                obj.preview(function(index, file, result){

                });
            }
            ,done: function(res){
                var val = res.data.file;
                var input = $("#vod_pic_screenshot")
                var content = input.val();
                if(content!=''){
                    content += '\r\n';
                }
                content += val;
                input.val(content);
                $('.screenshot_list').append('<div data-src="'+esc(val)+'"><a href="javascript:;" class="del_screenshot">'+esc(L['del'])+'</a><img src="'+esc(mac_url_img(val))+'" alt="" class="layui-upload-img screenshot-img"></div>');
            }
        });

        //监听文本框
        $('#vod_pic_screenshot').keyup(function(e){
            let html = ``;
            var textArr = $(this).val().split(/[(\r\n)\r\n]+/);
            textArr.forEach((item,index)=>{
                if(!item){
                    textArr.splice(index,1);
                }else{
                    if(item.indexOf('$')>-1){
                        item = item.substring(item.indexOf('$')+1);
                    }
                    html += `<div data-src="${esc(item)}"><a href="javascript:;" class="del_screenshot">${esc(L['del'])}</a><img src="${esc(mac_url_img(item))}"" alt="" class="layui-upload-img screenshot-img"></div>`;
                }
            });
            $('.screenshot_list').html(html);
        });

        upload.render({
            elem: '.layui-upload'
            ,url: UPLOAD_URL
            ,method: 'post'
            ,before: function(input) {
                layer.msg(L['upload_ing'], {time:3000000});
            },done: function(res, index, upload) {
                var obj = this.item;
                if (res.code == 0) {
                    layer.msg(res.msg);
                    return false;
                }
                layer.closeAll();
                var input = $(obj).parent().parent().find('.upload-input');
                if ($(obj).attr('lay-type') == 'image') {
                    input.siblings('img').attr('src', res.data.file).show();
                }
                input.val(res.data.file);

                if(res.data.thumb_class !=''){
                    $('.'+ res.data.thumb_class).val(res.data.thumb[0].file);
                }
            }
        });
        upload.render({
            elem: '.j-editor-upload'
            , url: FILE_URL
            , method: 'post'
            , exts: 'mp4|mp3|mkv|torrent|zip|txt|rar'
            , before: function (input) {
                layer.msg(L['upload_ing'], {time: 3000000});
            }, done: function (res, index, upload) {
                var obj = this.item;
                if (res.code == 0) {
                    layer.msg(res.msg);
                    return false;
                }
                layer.closeAll();
                var txt = $(obj).parent().parent().find('textarea').val();
                if (txt != '') {
                    txt += "\r\n";
                }
                $(obj).parent().parent().find('textarea').val(txt + ROOT_PATH + "/" + res.data.file);
            }
        });

        function initUpload(flag) {
            layui.each($('#'+ flag +'er_list').find('.j-editor-upload').last(), function(index, elem){
                upload.render({
                    elem: elem
                    , url: FILE_URL
                    , method: 'post'
                    , exts: 'mp4|mp3|mkv|torrent|zip|txt|rar'
                    , before: function (input) {
                        layer.msg(L['upload_ing'], {time: 3000000});
                    }, done: function (res, index, upload) {
                        var obj = this.item;
                        if (res.code == 0) {
                            layer.msg(res.msg);
                            return false;
                        }
                        layer.closeAll();
                        var txt = $(obj).parent().parent().find('textarea').val();
                        if (txt != '') {
                            txt += "\r\n";
                        }

                        $(obj).parent().parent().find('textarea').val(txt + ROOT_PATH + "/" +res.data.file);
                    }
                });
            });
        }

        $('.upload-input').hover(function (e){
            var e = window.event || e;
            var imgsrc = $(this).val();
            if(imgsrc.trim()==""){ return; }
            var left = e.clientX+document.body.scrollLeft+20;
            var top = e.clientY+document.body.scrollTop+20;
            $(".showpic").css({left:left,top:top,display:""});
            if(imgsrc.indexOf('://')<0){ imgsrc = ROOT_PATH  + '/' + imgsrc;	} else{ imgsrc = imgsrc.replace('mac:','http:'); }
            $(".showpic_img").attr("src", imgsrc);
        },function (e){
            $(".showpic").css("display","none");
        });

        $("#btn_rnd").click(function(){
            $("#vod_hits").val( rndNum(5000,9999) );
            $("#vod_hits_month").val( rndNum(1000,4999) );
            $("#vod_hits_week").val( rndNum(300,999) );
            $("#vod_hits_day").val( rndNum(1,299) );
            $("#vod_up").val( rndNum(1,999) );
            $("#vod_down").val( rndNum(1,999) );
            $("#vod_score").val( rndNum(10) );
            $("#vod_score_all").val( rndNum(1000) );
            $("#vod_score_num").val( rndNum(100) );
        });

        // 查询资料：旧版 JSONP 请求外部站点，新版不发请求（见文件头）
        $('#btn_douban').click(function(){
            var id = $('#vod_douban_id').val();
            if(id == '' || id < 10000){
                alert(L['admin/vod/douban_id_empty']);
                return;
            }
            alert(L['request_err']);
        });


        $('.contents').on('click','.j-editor-clear',function(){
            $(this).parent().parent().find('textarea').val('');
        });
        $('.contents').on('click','.j-editor-remove',function(){
            $(this).parent().parent().remove();
        });
        $('.contents').on('click','.j-editor-up',function(){
            var current = $(this).parent().parent();
            var current_index = current.index();
            var prev = current.prev();
            if(current_index>0){
                current.insertBefore(prev);
            }
        });
        $('.contents').on('click','.j-editor-down',function(){
            var current = $(this).parent().parent();
            var next = current.next();

            if(next.length>0){
                current.insertAfter(next);
            }
        });

        $('.contents').on('click','.j-editor-xz',function(){
            var arr1,s1,s2,urlarr,urlarrcount,arr1len;
            s1 = $(this).parent().parent().find('textarea').val(); s2="";
            if (s1.length==0){return false;}
            s1 = s1.replaceAll("\r","");
            arr1 = s1.split("\n");
            arr1len = arr1.length;
            for(var j=0;j<arr1len;j++){
                if(arr1[j].length>0){
                    urlarr = arr1[j].split('$'); urlarrcount = urlarr.length-1;
                    if(urlarrcount==0){
                        arr1[j]= getPatName(j,arr1len,arr1[j]) + '$' + arr1[j];
                    }
                    s2+=arr1[j]+"\r\n";
                }
            }
            $(this).parent().parent().find('textarea').val(s2.trim()) ;
        });
        $('.contents').on('click','.j-editor-order',function(){
            var arr1,s1,s2;
            s1 = $(this).parent().parent().find('textarea').val(); s2="";
            if (s1.length==0){return false;}
            s1 = s1.replaceAll("\r","");
            arr1=s1.split("\n");
            for(var j=arr1.length-1;j>=0;j--){
                if(arr1[j].length>0){
                    s2+=arr1[j]+"\r\n";
                }
            }
            $(this).parent().parent().find('textarea').val(s2.trim()) ;
        });
        $('.contents').on('click','.j-editor-dn',function(){
            var arr1,s1,s2,urlarr,urlarrcount;
            s1 = $(this).parent().parent().find('textarea').val(); s2="";
            if (s1.length==0){return false;}
            s1 = s1.replaceAll("\r","");
            arr1=s1.split("\n");
            for(var j=0;j<arr1.length;j++){
                if(arr1[j].length>0){
                    urlarr = arr1[j].split('$'); urlarrcount = urlarr.length-1;
                    if(urlarrcount==0){
                        arr1[j] = arr1[j];
                    }
                    else{
                        arr1[j] = urlarr[1];
                    }
                    s2+=arr1[j]+"\r\n";
                }
            }
            $(this).parent().parent().find('textarea').val(s2.trim()) ;
        });

        function groupTools(withUpload) {
            return '<a href="javascript:void(0)" class="j-editor-clear">'+esc(L['clear'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-remove">'+esc(L['del'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-up">'+esc(L['admin/vod/move_up'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-down">'+esc(L['admin/vod/move_down'])+'</a>&nbsp;'
                + (withUpload ? '<a href="javascript:void(0)" class="j-editor-xz">'+esc(L['admin/vod/correct'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-order">'+esc(L['admin/vod/reverse_order'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-dn">'+esc(L['admin/vod/del_prefix'])+'</a>&nbsp;<a href="javascript:void(0)" class="j-editor-upload">'+esc(L['upload'])+'</a>' : '');
        }

        $('.j-player-add').on('click',function(){
            players_arr_len++;
            var tpl='<div class="layui-form-item" data-i="'+players_arr_len+'"><label class="layui-form-label">'+esc(L['play'])+(players_arr_len)+'：</label><div class="layui-input-inline w150"><select name="vod_play_from[]"><option value="no">'+esc(L['admin/vod/select_player'])+'.</option>'+player_select+'</select></div><div class="layui-input-inline w150"><select name="vod_play_server[]" ><option value="no">'+esc(L['admin/vod/select_server'])+'.</option>'+server_select+'</select></div><div class="layui-input-inline w150"><input type="text" name="vod_play_note[]" class="layui-input" placeholder="'+esc(L['remarks'])+'" ></div><div class="layui-input-inline w400 p10">'+groupTools(true)+'</div><div class="p10 m20"></div><div class="layui-input-block"><textarea id="vod_content'+(players_arr_len)+'" name="vod_play_url[]" class="layui-textarea " style="width:99%;height:250px"></textarea></div></div>';
            $("#player_list").append(tpl);

            form.render('select');
            initUpload('play');
        });
        $('.j-downer-add').on('click',function(){
            downers_arr_len++;
            var tpl='<div class="layui-form-item" data-i="'+downers_arr_len+'"><label class="layui-form-label">'+esc(L['down'])+(downers_arr_len)+'：</label><div class="layui-input-inline w150"><select name="vod_down_from[]"><option value="no">'+esc(L['admin/vod/select_downer'])+'.</option>'+downer_select+'</select></div><div class="layui-input-inline w150"><select name="vod_down_server[]" ><option value="no">'+esc(L['admin/vod/select_server'])+'.</option>'+server_select+'</select></div><div class="layui-input-inline w150"><input type="text" name="vod_down_note[]" class="layui-input" placeholder="'+esc(L['remarks'])+'"></div><div class="layui-input-inline w400 p10">'+groupTools(true)+'</div><div class="p10 m20"></div><div class="layui-input-block"><textarea id="vod_content'+(downers_arr_len)+'" name="vod_down_url[]" class="layui-textarea" style="width:99%;height:250px"></textarea></div></div>';
            $("#downer_list").append(tpl);

            form.render('select');
            initUpload('down');
        });
        $('.j-plot-add').on('click',function(){
            plot_arr_len++;
            var tpl='<div class="layui-form-item" data-i="'+plot_arr_len+'"><label class="layui-form-label">'+esc(L['admin/vod/plot'])+(plot_arr_len)+'：</label><div class="layui-input-inline w500"><input type="text" name="vod_plot_name[]" class="layui-input" placeholder="'+esc(L['admin/vod/plot_name'])+'"></div><div class="layui-input-inline w400 p10">'+groupTools(false)+'</div><div class="p10 m20"></div><div class="layui-input-block"><textarea id="vod_content'+(plot_arr_len)+'" name="vod_plot_detail[]" class="layui-textarea" style="width:99%;height:250px"></textarea></div></div>';
            $("#plot_list").append(tpl);
        });
        $(document).on('click', '.del_screenshot', function() {
            var src = $(this).parent().attr('data-src');
            var input = $("#vod_pic_screenshot")
            var content = input.val();
            var snsArr = content.split(/[(\r\n)\r\n]+/);
            snsArr.forEach((item,index)=>{
                if(!item || item == src){
                    snsArr.splice(index,1);//删除
                }
            });
            $(this).parent().remove();
            input.val(snsArr.join('\r\n'));//重新赋值
            $.get(ANNEX_DEL_URL, {ids:src}, function(res){});
        });
        if(players_arr_len==0 && downers_arr_len==0) {
            $('.j-player-add').click();
        }
    });

    function getExtend(id){
        $.post(EXTEND_URL, {id:id}, function(res) {

            if (res.code == 1) {
                $.each(res.data, function(key, value){
                    $('.vod_'+key+"_label").html('');
                    if(value != ''){
                        $.each(value, function(key2, value2){
                            $(".vod_"+key+"_label").append('<a class="layui-btn layui-btn-xs extend" href="javascript:;" data-id="vod_'+esc(key)+'">'+esc(value2)+'</a>');
                        });
                    }
                });
            }
        });
    }

    function FindNote(s){
        var res="";
        if (s.indexOf("DVD")>0){
            res="DVD";
        }
        else if (s.indexOf("TS")>0 || s.indexOf("TC")>0 || s.indexOf("抢先版")>0) {
            res="抢先版";
        }
        else if (s.indexOf("HD")>0){
            res="HD";
        }
        else if (s.indexOf("BD")>0){
            res="BD";
        }
        else if (s.indexOf("蓝光高清")>0){
            res="蓝光高清";
        }
        else if (s.indexOf("高清")>0){
            res="高清";
        }
        else if (s.indexOf("VCD")>0){
            res="VCD";
        }

        if (s.indexOf("国粤语")>0){
            res +="国粤语";
        }
        else if (s.indexOf("国语")>0){
            res +="国语";
        }
        else if (s.indexOf("粤语")>0){
            res +="粤语";
        }
        else if (s.indexOf("台语")>0){
            res +="台语";
        }
        else if (s.indexOf("英语")>0){
            res +="英语";
        }
        else if (s.indexOf("中文字幕")>0){
            res +="中文字幕";
        }
        return res;
    }

    function getPatName(n,l,s){
        var res="";
        var rc=false;
        if(s.indexOf("qvod:")>-1 || s.indexOf("bdhd:")>-1 || s.indexOf("cool:")>-1){
            var arr = s.split('|');
            if(arr.length>=2){
                res = arr[2].replace(/[^0-9]/ig,"");
                rc=true;

                if(res!=""){
                    if(res.length>3){
                        res += L['issue'];
                    }
                    else if(l==1){
                        res = L['admin/vod/complete_works'];
                    }
                    else{
                        res = L['the'] + res + L['episode'];
                    }

                }
                else{
                    res = FindNote(s);
                    if (s==""){
                        if (l==1){
                            res=L['admin/vod/complete_works'];
                        }
                        else{
                            rc=false;
                        }
                    }
                }
            }
        }
        if(!rc){
            res = L['the'] + (n<9 ? '0' : '') + (n+1) + L['episode'];
        }
        return res;
    }

    if (TYPE_ID !== '') {
        setTimeout(function () {
            getExtend(TYPE_ID)
        },1000);
    }
})();
