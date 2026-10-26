@import com.maccms.backend.admin.web.view.WelcomePage
@param WelcomePage page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
<div class="page-container">

    <blockquote class="layui-elem-quote layui-quote-nm mt10">
        <p class="f-20 text-success">${page.lang().get("admin/index/welcome/tip_warn")}</p>
        <p>${page.lang().get("admin/index/welcome/filed_login_num")}：${page.loginNum()}  ${page.lang().get("admin/index/welcome/filed_last_login_ip")}：${page.lastLoginIp()}  ${page.lang().get("admin/index/welcome/filed_last_login_time")}：${page.lastLoginTime()}</p>
    </blockquote>

    <table class="layui-table" >
        <tbody>
        <tr>
            <td width="110">${page.lang().get("admin/index/welcome/filed_os")}</td>
            <td>${page.os()} (${page.server()})</td>
        </tr>
        <tr>
            <td>${page.lang().get("admin/index/welcome/filed_host")}</td>
            <td>${page.host()}</td>
        </tr>
        <tr>
            <td>${page.lang().get("admin/index/welcome/filed_max_upload")}</td>
            <td>${page.maxUpload()}</td>
        </tr>
        <tr>
            <td>${page.lang().get("admin/index/welcome/filed_date")}</td>
            <td>${page.date()}</td>
        </tr>
         <tr>
            <td>${page.lang().get("admin/index/welcome/filed_php_ver")}</td>
            <td>-</td>
        </tr>
        <tr>
            <td>${page.lang().get("admin/index/welcome/filed_thinkphp_ver")}</td>
            <td>-</td>
        </tr>
        </tbody>
    </table>
</div>
`)
