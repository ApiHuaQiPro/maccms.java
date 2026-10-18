/*
 * B09 会员账号表单交互（MACC-33 K2）。由后端在会员页面末尾以
 * <script nonce src=".../site/member.js" defer> 加载（同源）。
 *
 * 行为契约：docs/architecture/18-b09-member-account.md 第 8.3 节。
 *   挂点：form[data-member-form]、[data-member-message]、[data-captcha-refresh]
 *   （内含 img[data-captcha-src]）、img[data-member-portrait]、
 *   data-success-url、data-state="pending|success|error"。
 * 约束：不读写 cookie，不做 HTML 字符串注入（只写 textContent），不访问外部域名，不引入依赖。
 * 新版验证码猜错即作废（D-B08-4），所以每次提交后不论成败都要刷新验证码。
 */
(function (window, document) {
  'use strict';

  var FAIL_MESSAGE = '请求失败，请稍后重试。';
  var FormDataCtor = window.FormData;
  var URLSearchParamsCtor = window.URLSearchParams;

  function setState(form, state) {
    form.setAttribute('data-state', state);
  }

  function clearState(form) {
    form.removeAttribute('data-state');
  }

  function messageBox(form) {
    return form.querySelector('[data-member-message]');
  }

  function showMessage(form, text) {
    var box = messageBox(form);
    if (!box) {
      return;
    }
    box.textContent = text;
    if (typeof box.hasAttribute === 'function' && box.hasAttribute('hidden')) {
      box.removeAttribute('hidden');
    }
    box.hidden = false;
  }

  function captchaButton(form) {
    return form.querySelector('[data-captcha-refresh]');
  }

  /* 契约第 5 条：把验证码 img 的 src 设为 data-captcha-src + ?r=<随机数>。 */
  function refreshCaptchaIn(button) {
    if (!button) {
      return;
    }
    var img = button.querySelector('img');
    if (!img) {
      return;
    }
    var base = img.getAttribute('data-captcha-src');
    if (!base) {
      return;
    }
    img.setAttribute('src', base + '?r=' + Math.random());
  }

  /* 契约第 3 条：每次提交后刷新验证码并清空 verify 输入（猜错即作废）。 */
  function resetCaptcha(form) {
    var button = captchaButton(form);
    if (!button) {
      return;
    }
    refreshCaptchaIn(button);
    var verify = form.querySelector('[name="verify"]');
    if (verify) {
      verify.value = '';
    }
  }

  function submitButton(form) {
    return form.querySelector('[type="submit"]');
  }

  function isPortraitForm(form) {
    return form.getAttribute('data-member-form') === 'portrait';
  }

  /* 只接受以单个 '/' 开头的站内路径（'//' 是协议相对地址，拒绝）。 */
  function isSafeSitePath(url) {
    return typeof url === 'string' && url.charAt(0) === '/' && url.charAt(1) !== '/';
  }

  function updatePortrait(fileUrl) {
    if (!isSafeSitePath(fileUrl)) {
      return;
    }
    var imgs = document.querySelectorAll('img[data-member-portrait]');
    for (var i = 0; i < imgs.length; i++) {
      imgs[i].setAttribute('src', fileUrl);
    }
  }

  function buildBody(form) {
    var data = new FormDataCtor(form);
    if (isPortraitForm(form)) {
      return data; // multipart/form-data
    }
    return new URLSearchParamsCtor(data); // application/x-www-form-urlencoded
  }

  function parseJson(response) {
    return response.json();
  }

  function handleResult(form, result) {
    var code = result && typeof result.code !== 'undefined' ? Number(result.code) : null;
    var msg = result && typeof result.msg === 'string' ? result.msg : FAIL_MESSAGE;
    showMessage(form, msg);
    if (code === 1) {
      setState(form, 'success');
      if (isPortraitForm(form)) {
        updatePortrait(result && result.file);
      }
      var successUrl = form.getAttribute('data-success-url');
      if (successUrl) {
        // 跳转前不必刷新验证码：页面即将卸载。
        window.location.assign(successUrl);
        return;
      }
      // 成功但不跳转：验证码已作废，刷新（契约第 3 条）。
      resetCaptcha(form);
      return;
    }
    setState(form, 'error');
    resetCaptcha(form);
  }

  function handleFailure(form) {
    showMessage(form, FAIL_MESSAGE);
    setState(form, 'error');
    resetCaptcha(form);
  }

  function submit(form) {
    var button = submitButton(form);
    if (button) {
      button.disabled = true;
    }
    setState(form, 'pending');
    return window.fetch(form.getAttribute('action'), {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      body: buildBody(form)
    }).then(parseJson).then(function (result) {
      handleResult(form, result);
    }).catch(function () {
      handleFailure(form);
    }).then(function () {
      if (button) {
        button.disabled = false;
      }
    });
  }

  function onSubmit(event) {
    var form = event.target;
    if (event.preventDefault) {
      event.preventDefault();
    }
    submit(form);
  }

  function onDocumentClick(event) {
    var node = event.target;
    while (node && node !== document.documentElement) {
      if (typeof node.hasAttribute === 'function' && node.hasAttribute('data-captcha-refresh')) {
        refreshCaptchaIn(node);
        return;
      }
      node = node.parentNode;
    }
  }

  function boot() {
    var forms = document.querySelectorAll('form[data-member-form]');
    for (var i = 0; i < forms.length; i++) {
      clearState(forms[i]);
      forms[i].addEventListener('submit', onSubmit);
    }
    document.addEventListener('click', onDocumentClick);
  }

  if (document.readyState === 'loading' && typeof document.addEventListener === 'function') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  // 暴露给前端行为测试；不改变任何权限语义。
  window.MacMember = {
    boot: boot,
    submit: submit,
    refreshCaptchaIn: refreshCaptchaIn,
    resetCaptcha: resetCaptcha,
    isSafeSitePath: isSafeSitePath,
    FAIL_MESSAGE: FAIL_MESSAGE
  };
}(window, document));
