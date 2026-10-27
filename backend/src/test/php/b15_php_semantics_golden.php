<?php
/*
 * B15（MACC-39）配置写回所依赖的 PHP 语义的黄金样本（docs/architecture/24-b15-admin-system.md 第 3 节）：
 *   ① var_export($v, true) 与旧版三种写文件外壳（mac_arr2file、Service::refresh 的 addons.php、set_addon_fullconfig 的 config.php）；
 *   ② 浮点的 var_export 写法（serialize_precision=-1）；
 *   ③ htmlentities / html_entity_decode（默认参数：ENT_COMPAT | ENT_HTML401、UTF-8）——后台系统配置的保存经
 *      input('', '', 'htmlentities') 过滤每个值，site_tj 再经 html_entity_decode；
 *   ④ mac_replace_text（type=1 与 2）。
 * 纯计算：不连库、不读写仓库外文件、不访问网络。值用带类型的 JSON 表示（见 typed()），字节一律 base64。
 *
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   D:/Tools/php/8.0/php.exe backend/src/test/php/b15_php_semantics_golden.php > backend/src/test/resources/legacyconfig/php80-semantics-golden.json
 *   D:/Tools/php/7.3/php.exe backend/src/test/php/b15_php_semantics_golden.php > backend/src/test/resources/legacyconfig/php73-semantics-golden.json
 */

error_reporting(E_ALL);
ini_set('serialize_precision', '-1');

function typed($v)
{
    if (is_string($v)) {
        return ['s', base64_encode($v)];
    }
    if (is_int($v)) {
        return ['i', (string)$v];
    }
    if (is_float($v)) {
        return ['f', bin2hex(pack('E', $v))];
    }
    if (is_bool($v)) {
        return ['b', $v];
    }
    if ($v === null) {
        return ['n'];
    }
    $items = [];
    foreach ($v as $k => $x) {
        $items[] = [is_int($k) ? 'i' : 's', is_int($k) ? (string)$k : base64_encode($k), typed($x)];
    }
    return ['a', $items];
}

/** 确定性的伪随机（不依赖 mt_rand 的版本差异）。 */
function lcg(&$state)
{
    $state = ($state * 1103515245 + 12345) & 0x7fffffff;
    return $state;
}

function random_string(&$state)
{
    $pieces = ["a", "Z", "0", "9", " ", "'", "\\", "\"", "\n", "\r", "\t", "#", "$$$", "=>", "<?php", "?>", "{", "}", "/*", "*/",
        "中", "文", "，", "😀", "é", "©", "&", "&amp;", "<", ">", "%", "+", ",", ".", "[", "]", "array (", "\\'", "''"];
    $n = lcg($state) % 7;
    $s = '';
    for ($i = 0; $i < $n; $i++) {
        $s .= $pieces[lcg($state) % count($pieces)];
    }
    return $s;
}

function random_value(&$state, $depth)
{
    $r = lcg($state) % 10;
    if ($depth < 4 && $r < 3) {
        $arr = [];
        $n = lcg($state) % 5;
        for ($i = 0; $i < $n; $i++) {
            $kr = lcg($state) % 4;
            if ($kr === 0) {
                $arr[] = random_value($state, $depth + 1);
            } elseif ($kr === 1) {
                $arr[(lcg($state) % 200) - 50] = random_value($state, $depth + 1);
            } else {
                $arr[random_string($state)] = random_value($state, $depth + 1);
            }
        }
        return $arr;
    }
    if ($r < 7) {
        return random_string($state);
    }
    if ($r === 7) {
        return (lcg($state) % 2000000) - 1000000;
    }
    if ($r === 8) {
        return [true, false, null][lcg($state) % 3];
    }
    return ((lcg($state) % 100000) - 50000) / 64.0;
}

$fixed = [
    'empty' => [],
    'list' => ['a', 'b', 'c'],
    'nested-empty' => ['x' => [], 'y' => ['z' => []]],
    'scalars' => ['s' => 'v', 'i' => 0, 'neg' => -5, 'big' => PHP_INT_MAX, 't' => true, 'f' => false, 'n' => null,
        'fl' => 1.5, 'fl0' => 3.0, 'fneg' => -0.0],
    'quotes' => ["it's" => "a\\b'c", '\\' => '\\\\', "''" => "'"],
    'keys' => [5 => 'five', '6' => 'six-int', '07' => 'string', '-3' => 'neg-int', '-0' => 'string', '1.0' => 'string',
        ' 1' => 'string', '9223372036854775808' => 'overflow-string'],
    'unicode' => ['名称' => '苹果 iPhone 😀', 'é' => '©®™', 'mixed' => "line1\nline2\r\n\ttab"],
    'deep' => ['a' => ['b' => ['c' => ['d' => ['e' => 'deep']]]]],
    'route-like' => ['__pattern__' => ['id' => '[\s\S]*?', 'page' => '\d+'], 'map' => ['map/index', [], []]],
    'config-like' => ['site' => ['site_name' => '苹果CMS', 'site_url' => 'www.test.cn', 'install_dir' => '/'],
        'db' => ['part_size' => 20971520, 'compress' => 1], 'extra' => []],
];

$values = [];
foreach ($fixed as $label => $v) {
    $values[] = ['label' => $label, 'value' => typed($v), 'export' => base64_encode(var_export($v, true)),
        'arr2file' => base64_encode("<?php\nreturn " . var_export($v, true) . ";"),
        'addons' => base64_encode("<?php\n\n" . "return " . var_export($v, true) . ";"),
        'addonConfig' => base64_encode("<?php\n\n" . "return " . var_export($v, true) . ";\n")];
}
$state = 20261026;
for ($i = 0; $i < 300; $i++) {
    $v = ['r' => random_value($state, 0), 'k' . $i => random_value($state, 1)];
    $values[] = ['label' => 'random-' . $i, 'value' => typed($v), 'export' => base64_encode(var_export($v, true))];
}

$floats = [0.0, -0.0, 1.0, -1.0, 0.5, 0.05, 0.1, 0.2, 0.3, 0.30000000000000004, 1.5, 3.0, 100.0, 1e15, 1e16, 1e17, 1e18,
    123456789012345678.0, 12345678901234567.0, 0.0001, 0.00001, 1e-4, 1.25e-5, 2.5e-300, 1.7976931348623157e308,
    5e-324, 2.2250738585072014e-308, 1e25, 1.5e25, 123.456, -987.654321, 1 / 3, 2 / 3, 9007199254740993.0,
    4.35, 0.1 + 0.7, 1e21, 1e22, 1e23, 8.41e21, 5e-7, 33.0e-5];
$state = 77;
for ($i = 0; $i < 200; $i++) {
    $floats[] = unpack('E', pack('J', (lcg($state) << 33) ^ (lcg($state) << 2) ^ lcg($state)))[1];
}
$floatOut = [];
foreach ($floats as $f) {
    if (is_nan($f) || is_infinite($f)) {
        continue;
    }
    $floatOut[] = ['bits' => bin2hex(pack('E', $f)), 'export' => var_export($f, true)];
}

// ③ htmlentities：翻译表与向量（含非法 UTF-8：PHP 7.3/8.0 默认不带 ENT_SUBSTITUTE，返回空串）
$table = [];
foreach (get_html_translation_table(HTML_ENTITIES, ENT_COMPAT | ENT_HTML401, 'UTF-8') as $ch => $ent) {
    $table[] = [base64_encode($ch), $ent];
}
$strings = ["", "plain", "A&B", "&amp;", "<script>alert('x')</script>", "\"quoted\"", "it's", "中文标题", "©®™ é ü ß",
    "\xC2\xA0nbsp", "😀 emoji", "\xFF\xFEbad", "ok\xC3", "&#39;&#039;&#x27;&quot;&lt;", "&nbsp;&copy;&hellip;&euro;",
    "tab\there\nnew\rline", "\x00nul", "like", "%41+", "&#20013;&#x4e2d;", "&bogus; & amp;", "&AMP;&Amp;", "&#0;&#128;&#x80;",
    "&#65536;&#x110000;", "&#xD800;", "&#9;&#10;&#13;&#127;", "&euro&copy", "<?php echo 1; ?>",
    "document.write('<script src=\"//x\"></script>')", "a\x7Fb", "\xE2\x80\xA8\xE2\x80\xA9"];
$state = 4242;
for ($i = 0; $i < 200; $i++) {
    $strings[] = random_string($state) . random_string($state);
}
$entities = [];
foreach ($strings as $s) {
    $enc = htmlentities($s);
    $entities[] = ['in' => base64_encode($s), 'htmlentities' => base64_encode($enc),
        'decode' => base64_encode(html_entity_decode($s)), 'roundTrip' => base64_encode(html_entity_decode($enc))];
}

// ④ mac_replace_text（application/common.php:141-147）
$replace = [];
foreach (["a#b#c", "a\r\nb\r\nc", "a\nb\rc", "##", "\r\r", "x\n#\ry", ""] as $s) {
    $replace[] = ['in' => base64_encode($s), 'type1' => base64_encode(str_replace('#', chr(13), $s)),
        'type2' => base64_encode(str_replace(chr(13), '#', str_replace(chr(10), '', $s)))];
}

echo json_encode(['generator' => 'backend/src/test/php/b15_php_semantics_golden.php', 'php' => PHP_VERSION,
        'values' => $values, 'floats' => $floatOut, 'htmlTable' => $table, 'entities' => $entities, 'replaceText' => $replace],
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
