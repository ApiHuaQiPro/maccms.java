@import com.maccms.backend.admin.web.view.system.ConfigPage
@param ConfigPage page
@param String flag
<%-- 旧 mac_extends_list($flag)['ext_html']（application/common.php:358-377）：按扩展列表的顺序，把有模板的扩展（admin/view/extend/<flag>/<类名小写>.html）依次输出。没有模板的扩展不输出。 --%>
@for(var item_ext : page.each(page.at("extends.ext_list")))
@if(flag.equals("email") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("phpmailer"))@template.admin.pages.system.extend.email_phpmailer(page = page)
@elseif(flag.equals("pay") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("alipay"))@template.admin.pages.system.extend.pay_alipay(page = page)
@elseif(flag.equals("pay") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("codepay"))@template.admin.pages.system.extend.pay_codepay(page = page)
@elseif(flag.equals("pay") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("weixin"))@template.admin.pages.system.extend.pay_weixin(page = page)
@elseif(flag.equals("pay") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("zhapay"))@template.admin.pages.system.extend.pay_zhapay(page = page)
@elseif(flag.equals("sms") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("aliyun"))@template.admin.pages.system.extend.sms_aliyun(page = page)
@elseif(flag.equals("sms") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("qcloud"))@template.admin.pages.system.extend.sms_qcloud(page = page)
@elseif(flag.equals("upload") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("alibaba"))@template.admin.pages.system.extend.upload_alibaba(page = page)
@elseif(flag.equals("upload") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("ftp"))@template.admin.pages.system.extend.upload_ftp(page = page)
@elseif(flag.equals("upload") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("qiniu"))@template.admin.pages.system.extend.upload_qiniu(page = page)
@elseif(flag.equals("upload") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("uomg"))@template.admin.pages.system.extend.upload_uomg(page = page)
@elseif(flag.equals("upload") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("upyun"))@template.admin.pages.system.extend.upload_upyun(page = page)
@elseif(flag.equals("upload") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("weibo"))@template.admin.pages.system.extend.upload_weibo(page = page)
@elseif(flag.equals("urlsend") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("baidu"))@template.admin.pages.system.extend.urlsend_baidu(page = page)
@elseif(flag.equals("urlsend") && page.s(item_ext.key()).toLowerCase(java.util.Locale.ROOT).equals("baidufast"))@template.admin.pages.system.extend.urlsend_baidufast(page = page)
@endif
@endfor
