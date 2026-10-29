@import com.maccms.backend.site.web.view.NavLink
@import java.util.List
@param List<NavLink> links
@param boolean binding
@if(!links.isEmpty())
<section class="oauth-connect">
  <h2 class="oauth-connect__title">${binding ? "第三方账号" : "其他登录方式"}</h2>
  <ul class="oauth-connect__list">
    @for(var link : links)
    @if(link.label().startsWith("QQ"))
    <li><a class="${binding ? "oauth-connect__link oauth-connect__link--qq" : "oauth-login oauth-login--qq"}" href="${link.url().href()}" rel="nofollow" aria-label="${binding ? "绑定 QQ 账号" : "使用 QQ 登录"}"><img class="oauth-connect__icon" src="/site/oauth-qq.svg" width="24" height="24" alt=""><span>${binding ? "绑定QQ" : "QQ登录"}</span></a></li>
    @else
    <li><a class="${binding ? "oauth-connect__link oauth-connect__link--weixin" : "oauth-login oauth-login--weixin"}" href="${link.url().href()}" rel="nofollow" aria-label="${binding ? "绑定微信账号" : "使用微信登录"}"><img class="oauth-connect__icon" src="/site/oauth-weixin.svg" width="24" height="24" alt=""><span>${binding ? "绑定微信" : "微信登录"}</span></a></li>
    @endif
    @endfor
  </ul>
  @if(binding)
  <p class="oauth-connect__hint">绑定后可用于快捷登录。重复绑定会更新当前账号的授权信息。</p>
  @endif
</section>
@endif
