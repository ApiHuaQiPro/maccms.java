@import com.maccms.backend.admin.web.view.collect.CjColUrlView
@import com.maccms.backend.collect.application.CjRunService.LinkRow
@param CjColUrlView page
@template.admin.layout.page(head = page.head(), lang = page.lang(), content = @`
${page.notice()}<br>
@if(page.jumpUrl() != null)
<script src="${page.head().assetsPath()}/progress.js" data-url="${page.jumpUrl()}" data-seconds="${page.seconds()}"></script><span>${page.lang().get("pause")}${page.seconds()}${page.lang().get("continue_in_second")}  >>>  </span><a href="${page.jumpUrl()}" >${page.lang().get("browser_jump")}</a><br>
@endif
<div class="page-container p10">

    <fieldset class="layui-elem-field">
        <legend>${page.lang().get("base_info")}</legend>
        <div class="layui-field-box">
            ${page.result().listUrl()} <br>${page.lang().get("sum")}：${page.result().total()} ${page.lang().get("data")}，${page.lang().get("duplicate_data")}：${page.result().re()}${page.lang().get("data")}，${page.lang().get("distinct_into")}${page.distinct()}${page.lang().get("data")}。
            @if(page.result().missing())
            <br><span class="red">本地文件不存在：${page.result().localPath() == null ? "网址不能映射到本地文件" : page.result().localPath()}</span>
            @endif
            @if(page.result().bad() > 0)
            <br><span class="red">${page.result().bad()} 条的标题或网址超长或含非法字符，未记下</span>
            @endif
        </div>
    </fieldset>

    <table class="layui-table" lay-size="sm">
    <thead>
      <tr>
        <th width="50">${page.lang().get("serial_num")}</th>
		<th>${page.lang().get("link")}</th>
        <th>${page.lang().get("name")}</th>
      </tr>
    </thead>
    <tbody>
		@for(var v : gg.jte.support.ForSupport.of(page.result().rows()))
		  <tr>
			  <td>${v.getIndex() + 1}</td>
			  <td>${v.get().url()}</td>
			  <td>${v.get().title()}</td>
		  </tr>
	  @endfor
    </tbody>
  </table>
</div>
`)
