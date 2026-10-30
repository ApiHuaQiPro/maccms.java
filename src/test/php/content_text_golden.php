<?php
/*
 * B13（MACC-37）后台内容保存用到的旧 PHP 文本函数的黄金向量（不连数据库、不访问网络、不改旧代码），契约见
 * docs/architecture/22-b13-admin-content.md 第 4 节：
 *   - strip_tags（PHP 内置，旧 mac_filter_xss 的第一步）、htmlspecialchars(…, ENT_QUOTES)、mac_filter_xss（application/common.php:1222-1225）；
 *   - mb_substr(…, 0, n)（common/validate/Vod.php formatDataBeforeDb 的长度裁剪）；
 *   - mac_substring(strip_tags(…), 100)（简介为空时取正文前 100 字，common.php:637-679）；
 *   - Pinyin::get（拼音为空时由名称生成，common/util/Pinyin.php，读 static/data/pinyin.dat）与首字母 strtoupper(substr(…, 0, 1))；
 *   - 正文图片地址的协议替换（common/model/Vod.php:651-661 等：preg_match_all 后 str_replace 'http:' / 'https:' → 'mac:'）；
 *   - mac_restore_htmlfilter（:1227-1232，列表与表单显示用）、urldecode（列表关键字）。
 * 只截取仓库 application/common.php 中用到的函数源码后 eval，Pinyin 类直接 include（它只读 ./static/data/pinyin.dat，所以先 chdir 到仓库根目录）。
 * 语料：手写用例 + 固定种子的随机串（字母表偏向 strip_tags 的状态机：< > ! - ? 引号 反斜线 括号 xml/doctype 字母、NUL、空白与多字节字符）。
 * 用法（仓库根目录）：
 *   D:/Tools/php/8.0/php.exe src/test/php/content_text_golden.php > src/test/resources/content/admin/text-php80-golden.json
 *   D:/Tools/php/7.3/php.exe src/test/php/content_text_golden.php > src/test/resources/content/admin/text-php73-golden.json
 */

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING);
$repo = realpath(__DIR__ . '/../../../php');
chdir($repo);
$common = file_get_contents($repo . '/application/common.php');

function extract_function($src, $name)
{
    $start = strpos($src, "function $name(");
    if ($start === false) {
        throw new RuntimeException("missing $name");
    }
    $next = strpos($src, "\nfunction ", $start + 10);
    return substr($src, $start, $next === false ? strlen($src) - $start : $next - $start);
}

foreach (['mac_filter_xss', 'mac_restore_htmlfilter', 'mac_substring'] as $f) {
    eval(extract_function($common, $f));
}
require $repo . '/application/common/util/Pinyin.php';

/** 旧模型保存正文时的图片协议替换（以 http 为协议时）。 */
function content_img($content, $protocol)
{
    $pattern_src = '/<img[\s\S]*?src\s*=\s*[\"|\'](.*?)[\"|\'][\s\S]*?>/';
    @preg_match_all($pattern_src, $content, $match_src1);
    if (!empty($match_src1)) {
        foreach ($match_src1[1] as $v1) {
            $v2 = str_replace($protocol . ':', 'mac:', $v1);
            $content = str_replace($v1, $v2, $content);
        }
    }
    return $content;
}

$texts = [
    '', '0', ' ', 'a', 'abc', '好看', '  首尾空白  ', "\t\n x \r\0\x0B", "a\0b", '<b>粗</b>', '<<b>>x<</b>>', 'a<b', 'a>b', 'a < b',
    "a <\tb", "a <\nb", '<a href="x>y">t</a>', "<a href='x>y'>t</a>", '<a title="it\'s">t</a>', '<a title=\'say "hi"\'>t</a>',
    '<!-- c -->x', '<!-- a > b -->x', '<!--x-->y<!---->z', '<!DOCTYPE html>x', '<!doctype html><p>x</p>', '<!x>y', '<!-- unclosed',
    '<?php echo 1; ?>x', '<? (a>b) ?>x', '<?xml version="1.0"?><r>t</r>', '<?xml x ?>a', '<? "a?>b" ?>c', '<?(x)?>y',
    '<script>alert(1)</script>', '<style>a{}</style>b', '<p onclick="a>b">t</p>', '"quoted" \'single\'', 'x"<b>"y', "x'<b>'y",
    '<a b="c\\"d">e</a>', '<<>>', '<>', '><', '<b', 'b>', '<b>', '</b>', '< b>', '<b >', '<b/>', '<br/>x', '<img src="a.jpg">',
    '<img src="http://a.com/1.jpg">', "<img alt='x' src='https://b.com/2.png' />", '<IMG SRC="http://c.com/3.gif">',
    '<img src=http://d.com/4.jpg>', '<p><img src="http://e.com/5.jpg"><img src="http://f.com/6.jpg"></p>',
    '<img src="http://g.com/7.jpg"><img src="http://g.com/7.jpg">', 'http://h.com/8.jpg <img src="/upload/9.jpg">',
    '&', '&amp;', '&lt;', '&#39;', '&#039;', "'单引号'", '"双引号"', 'a & b < c > d " e \' f', '&amp;lt;b&amp;gt;',
    'é è ü ñ ç ß ÿ', '€ ™ ©', '😀', '😀x😀', "好\xC3\x28", "\xFF", "\xE4\xB8", 'www.a.com 看', 'WWW,Com', '复仇者联盟4：终局之战',
    '速度与激情 8', 'Iron Man 3', '007：大破量子危机', '哪吒之魔童降世', '你好，世界！', '重庆 长安 银行 行走 音乐 快乐', '一二三四五六七八九十',
    '乐乐', 'a+b/c\\d|e\'f?g%h#i&j=k!l(m)n;o:p<q>r', '中文english123', '　全角空格　', 'Ａ全角Ｂ', '①②③', 'ä', 'Ǎ', '〇', '丄丅',
    str_repeat('好', 120), str_repeat('a', 300), str_repeat('好a', 150), str_repeat('😀', 10), str_repeat('<b>粗</b>', 30),
    "多行\r\n文本\n第三行\r", "  <b>  中  </b>  ", "<p>简介</p>\n<p>第二段</p>", "a\x01\x02\x7Fb",
];

// 固定种子的随机语料（mt_srand 自 PHP 7.1 起各版本序列一致）
$alphabet = ['<', '>', '!', '-', '?', '"', "'", '\\', '(', ')', 'x', 'm', 'l', 'X', 'M', 'L', 'D', 'O', 'C', 'T', 'Y', 'P', 'E',
    'd', 'o', 'c', 't', 'y', 'p', 'e', ' ', "\t", "\n", "\0", 'a', 'b', '/', '=', '中', '好', '😀', '&', ';', '#'];
mt_srand(20261025);
for ($i = 0; $i < 1500; $i++) {
    $len = mt_rand(0, 24);
    $s = '';
    for ($j = 0; $j < $len; $j++) {
        $s .= $alphabet[mt_rand(0, count($alphabet) - 1)];
    }
    $texts[] = $s;
}

$out = ['generator' => 'backend/src/test/php/content_text_golden.php', 'php' => PHP_VERSION, 'cases' => []];
foreach ($texts as $t) {
    $py = \app\common\util\Pinyin::get($t);
    $hs = htmlspecialchars($t, ENT_QUOTES);
    $out['cases'][] = [
        'input' => bin2hex($t),
        'strip_tags' => bin2hex(strip_tags($t)),
        'htmlspecialchars' => bin2hex((string)$hs),
        'filter_xss' => bin2hex(mac_filter_xss($t)),
        'mb_substr_10' => bin2hex(mb_substr($t, 0, 10)),
        'mb_substr_100' => bin2hex(mb_substr($t, 0, 100)),
        'mb_substr_255' => bin2hex(mb_substr($t, 0, 255)),
        'blurb' => bin2hex(mac_substring(strip_tags($t), 100)),
        'blurb_art' => bin2hex(mac_substring(str_replace('$$$', '', strip_tags($t)), 100)),
        'pinyin' => bin2hex($py),
        'letter' => bin2hex(strtoupper(substr($py, 0, 1))),
        'content_img_http' => bin2hex(content_img($t, 'http')),
        'content_img_https' => bin2hex(content_img($t, 'https')),
        'content_img_empty' => bin2hex(content_img($t, '')),
        'restore' => bin2hex(mac_restore_htmlfilter($t)),
        'urldecode' => bin2hex(urldecode($t)),
    ];
}
echo json_encode($out, JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
