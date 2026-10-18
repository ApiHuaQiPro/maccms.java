@import com.maccms.backend.site.web.view.UserPortraitPage
@param UserPortraitPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--portrait" data-site-page="user-portrait" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">修改头像</h1>
  <div class="member-portrait">
    <img class="member-profile__avatar" src="${page.avatar().href()}" alt="当前头像" width="100" height="100" data-member-portrait>
  </div>
  @if(page.action().isPresent())
  <form class="member-form" method="post" action="${page.action().get().href()}" enctype="multipart/form-data" data-member-form="portrait">
    <div class="member-form__field">
      <label class="member-form__label" for="member-portrait-file">选择图片</label>
      <input class="member-form__input" id="member-portrait-file" name="file" type="file" accept="image/jpeg,image/png,image/gif" required aria-describedby="member-portrait-hint">
      <p class="member-form__hint" id="member-portrait-hint">支持 jpg、png、gif，不超过 2 MB。</p>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">上传</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  @else
  <p class="notice">未开启自定义头像功能</p>
  @endif
  <ul class="member-links">
    <li><a href="${page.indexUrl().href()}" rel="nofollow">返回会员中心</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
