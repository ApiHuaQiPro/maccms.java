// 同源播放器内页的引导脚本（B05，docs/architecture/15-b05-readonly-pages.md 第 6.2 节）。由 Java 维护，不是前端样式资源。
// 1. 从 div#player-stage 的 data-player 属性（后端输出的 JSON，经 HTML 属性转义）读出旧 static/js/player.js 需要的全局变量
//    maccms（只含 path）与 player_aaaa（common/controller/All.php:496-543 的字段）；页面中没有内联脚本。
// 2. base64decode：player.js 在 app.encrypt=2 时调用（static/js/player.js:2）。旧模板由 static/js/home.js 提供，home.js 加载时
//    还会调用点击、顶踩、定时任务等带写入副作用的接口，所以这里只原样保留其中的 base64decode（home.js:4），不加载 home.js。
(function () {
  var stage = document.getElementById('player-stage');
  var data = JSON.parse(stage.getAttribute('data-player'));
  window.maccms = data.maccms;
  window.player_aaaa = data.player;
})();
var base64DecodeChars=new Array(-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,-1,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,-1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1);function base64decode(str){var c1,c2,c3,c4;var i,len,out;len=str.length;i=0;out="";while(i<len){do{c1=base64DecodeChars[str.charCodeAt(i++)&0xff]}while(i<len&&c1==-1);if(c1==-1)break;do{c2=base64DecodeChars[str.charCodeAt(i++)&0xff]}while(i<len&&c2==-1);if(c2==-1)break;out+=String.fromCharCode((c1<<2)|((c2&0x30)>>4));do{c3=str.charCodeAt(i++)&0xff;if(c3==61)return out;c3=base64DecodeChars[c3]}while(i<len&&c3==-1);if(c3==-1)break;out+=String.fromCharCode(((c2&0XF)<<4)|((c3&0x3C)>>2));do{c4=str.charCodeAt(i++)&0xff;if(c4==61)return out;c4=base64DecodeChars[c4]}while(i<len&&c4==-1);if(c4==-1)break;out+=String.fromCharCode(((c3&0x03)<<6)|c4)}return out}
