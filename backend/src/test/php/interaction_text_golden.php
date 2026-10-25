<?php
/*
 * B11（MACC-35）文本与时间函数的 PHP 黄金向量（不连数据库、不访问网络、不改旧代码）：
 *   - 评论/留言写库前的文本处理：htmlentities(mac_filter_words($s))、mac_filter_xss(...)（application/common.php:1222-1225、1457-1471，
 *     index/controller/Comment.php:65、85，common/model/Comment.php:220-229），以及列表显示用的 mac_restore_htmlfilter（:1227-1232）；
 *   - 点击计数的日/周/月区间（index/controller/Ajax.php:178-199）：对一组时刻求 getdate、本周起止、同月/同日判定。
 * 只 include 仓库的 application/common.php 中这几个函数的源码（按函数名截取后 eval），不加载 ThinkPHP。
 * 用法（仓库根目录）：
 *   D:/Tools/php/8.0/php.exe backend/src/test/php/interaction_text_golden.php > backend/src/test/resources/interaction/text-php80-golden.json
 */

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE & ~E_WARNING);
date_default_timezone_set('PRC');
$repo = realpath(__DIR__ . '/../../../..');
$common = file_get_contents($repo . '/application/common.php');

/** 从 common.php 中截取一个函数的源码（到下一个顶层 function 为止）。 */
function extract_function($src, $name)
{
    $start = strpos($src, "function $name(");
    if ($start === false) {
        throw new RuntimeException("missing $name");
    }
    $next = strpos($src, "\nfunction ", $start + 10);
    return substr($src, $start, $next === false ? strlen($src) - $start : $next - $start);
}

$GLOBALS['filter_words'] = '';
function config($key)
{
    // mac_filter_words 只读 config('maccms.app')['filter_words']
    return ['filter_words' => $GLOBALS['filter_words']];
}

foreach (['mac_filter_xss', 'mac_restore_htmlfilter', 'mac_filter_words'] as $f) {
    eval(extract_function($common, $f));
}

$texts = [
    '', '0', 'a', '好看', '  首尾空白  ', "\t\n x \r\0", "a\0b", '<b>粗</b>', '&', '&amp;', '&lt;', '&#39;', '&#039;',
    "'单引号'", '"双引号"', 'é è ü ñ ç ß ÿ', '€ ™ © ® ° ± µ ¶ · ¼ ½', '← ↑ → ↓ ⇒ ∀ ∂ ∃ ∑ √ ∞ ≠ ≤ ≥', 'α β γ Ω',
    '“” ‘’ — – … • ′ ″', "\u{00A0}nbsp", 'Ā ă', '😀', "好\xC3\x28", "\xFF", 'www.a.com 看 http', 'WWW,Com', 'abc&def;',
    '&nbsp;&copy;', '<script>alert(1)</script>', 'a & b < c > d " e \' f', str_repeat('好', 10),
];
$words = ['', 'www,http,com,net', 'admin,cao,sex,xxx', ',', 'a,b,,c', '好,看'];

$out = ['generator' => 'backend/src/test/php/interaction_text_golden.php', 'php' => PHP_VERSION, 'text' => [],
    'restore' => [], 'periods' => []];
foreach ($words as $w) {
    $GLOBALS['filter_words'] = $w;
    foreach ($texts as $t) {
        $filtered = mac_filter_words($t);
        $ent = htmlentities($filtered);
        $out['text'][] = ['words' => $w, 'input' => bin2hex($t), 'filtered' => bin2hex($filtered),
            'entities' => bin2hex((string)$ent), 'stored' => bin2hex(mac_filter_xss((string)$ent))];
    }
}
foreach (['', 'a', '&amp;', '&AMP;lt;', '&amp;lt;b&amp;gt;', '&amp;quot;x&amp;#039;', '&amp;amp;lt;', '&#039;x', '&amp;#39;',
             '&amp;eacute;', 'plain &lt;b&gt;', '&amp;#x27;', '&amp;#34;'] as $s) {
    $out['restore'][] = ['input' => bin2hex($s), 'output' => bin2hex(mac_restore_htmlfilter($s))];
}

// 日/周/月区间：一组 PRC 时刻（含周日、周六、月初、月末、年初、闰年 2 月 29 日）
$moments = ['2026-10-25 00:00:00', '2026-10-25 23:59:59', '2026-10-24 12:00:00', '2026-10-31 23:59:59',
    '2026-11-01 00:00:00', '2027-01-01 00:00:00', '2026-12-31 23:59:59', '2028-02-29 08:00:00', '2026-03-01 00:00:01',
    '2026-10-26 00:00:00', '2026-10-28 15:30:00', '1990-04-15 12:00:00', '1991-09-15 01:30:00'];
foreach ($moments as $m) {
    $now = strtotime($m);
    $new = getdate($now);
    $weekStart = mktime(0, 0, 0, $new["mon"], $new["mday"], $new["year"]) - ($new["wday"] * 86400);
    $weekEnd = mktime(23, 59, 59, $new["mon"], $new["mday"], $new["year"]) + ((6 - $new["wday"]) * 86400);
    // 对一组旧计数时间给出旧版的三个判定
    $checks = [];
    foreach ([0, 1, -1, 86399, 86400, -86400, -7 * 86400, 7 * 86400, -40 * 86400, 40 * 86400] as $d) {
        $old = getdate($now + $d);
        $checks[] = ['offset' => $d,
            'month' => $new['year'] == $old['year'] && $new['mon'] == $old['mon'],
            'week' => ($now + $d) >= $weekStart && ($now + $d) <= $weekEnd,
            'day' => $new['year'] == $old['year'] && $new['mon'] == $old['mon'] && $new['mday'] == $old['mday']];
    }
    $out['periods'][] = ['moment' => $m, 'now' => $now, 'weekStart' => $weekStart, 'weekEnd' => $weekEnd,
        'checks' => $checks];
}
echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
