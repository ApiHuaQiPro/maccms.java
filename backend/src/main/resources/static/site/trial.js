/*
 * B05 试看计时（MACC-54 K4）。只在 vod/player 播放器内页由后端按需加载（同源）。
 *
 * 挂点契约（docs/architecture/15-b05-readonly-pages.md 第 6 节）：
 *   div#player-stage.player-stage[data-trial-minutes="<非负整数>"] —— 播放器挂载区；
 *   p#trial-message.trial-message[role="status"] —— 本地结束提示。
 * data-trial-minutes 为 0 或缺失/非法时不计时，脚本完全不动页面。
 * 到期后：暂停挂载区内可访问的 video/audio，移除挂载区中的
 * iframe/object/embed/video/audio，并在 #trial-message 显示固定文案。
 * 计时以 Date.now() 的经过时间为准，后台标签页的定时器节流不会延长试看。
 * 本脚本不能授予任何服务端权限；授权/密码/开关始终由后端决定。
 */
(function (window, document) {
  'use strict';

  var END_MESSAGE = '试看已结束，请登录后继续观看。';
  var TICK_MS = 1000;

  function removeAll(list) {
    for (var i = 0; i < list.length; i++) {
      var node = list[i];
      if (node.parentNode) {
        node.parentNode.removeChild(node);
      }
    }
  }

  function start(doc, win) {
    var stage = doc.getElementById('player-stage');
    if (!stage || typeof stage.getAttribute !== 'function') {
      return null;
    }
    var minutes = parseInt(stage.getAttribute('data-trial-minutes'), 10);
    if (!isFinite(minutes) || minutes <= 0) {
      return null;
    }
    var message = doc.getElementById('trial-message');
    var deadline = Date.now() + minutes * 60 * 1000;
    var ended = false;
    var timer = null;

    function end() {
      if (ended) {
        return;
      }
      ended = true;
      if (timer !== null) {
        win.clearInterval(timer);
        timer = null;
      }
      var playable = stage.querySelectorAll('video, audio');
      for (var i = 0; i < playable.length; i++) {
        try {
          playable[i].pause();
        } catch (e) {
          /* 某些播放器对象不支持 pause，忽略后继续移除 */
        }
      }
      removeAll(stage.querySelectorAll('iframe, object, embed, video, audio'));
      if (message) {
        message.textContent = END_MESSAGE;
        if (message.hidden) {
          message.hidden = false;
        }
        if (typeof message.removeAttribute === 'function' && message.hasAttribute
            && message.hasAttribute('hidden')) {
          message.removeAttribute('hidden');
        }
      }
    }

    function check() {
      if (Date.now() >= deadline) {
        end();
      }
    }

    timer = win.setInterval(check, TICK_MS);
    // 后台标签页定时器会被节流；回到前台时立即按经过时间复核。
    if (typeof doc.addEventListener === 'function') {
      doc.addEventListener('visibilitychange', function () {
        if (!doc.hidden) {
          check();
        }
      });
    }
    return {
      hasEnded: function () {
        return ended;
      },
      endNow: end
    };
  }

  function boot() {
    start(document, window);
  }

  if (document.readyState === 'loading' && typeof document.addEventListener === 'function') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  // 暴露给同源页面内调试与前端行为测试；不改变任何权限语义。
  window.MacTrial = { start: start };
}(window, document));
