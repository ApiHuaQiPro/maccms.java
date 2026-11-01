<?php
/*
 * S3-05（APPK-2，C-33）模板解释器黄金样本生成器：在 PHP 7.3.33 上跑旧站代码本身，把结果落成 Java 侧
 * （`src/test/resources/phptemplate/**`）逐字节比对的黄金。
 *
 * 依据：
 *   - 技术设计 v13 `9c73bf6ced044f40eeb87ef7861e616b6157499a`：C-33 ①～④、RT-1～RT-8、V-11～V-16、
 *     D1-FLD-1～5、PROJ-1、QG-1～QG-7、ZDUMP-1（见 docs/design/APPK-2/tech-design.md）
 *   - PRE-5 定稿 `b550151b4243622fe7ac060c9ff6e720d83ae605`：template-scan.md 9.2（PHP-1～PHP-8）、
 *     9.3（时间来源归属）、9.4 CAL-3/CAL-5/CAL-6、9.5 GAP-1
 *   - 模板包 `200cffc5d01090faf4f61e153feb472b107b34df`（third_party/maccms-template-v2020.1000.1035/）
 *   - PHP 基线 `a57e9cb2b94d3e500bea7dfe2c579daee093d969`
 *
 * 子命令（全部在仓库根目录执行；PHP 必须是 7.3.33 NTS x64）：
 *   1) 值语义 + 函数 + 宏/编辑器黄金（V-11、V-15；不连数据库）：
 *      D:/Tools/php/7.3/php.exe src/test/php/tpl_runtime_golden.php --semantics \
 *        --out src/test/resources/phptemplate
 *   2) 168 个模板的编译产物（V-12；不连数据库）：
 *      D:/Tools/php/7.3/php.exe src/test/php/tpl_runtime_golden.php --compile \
 *        --package .compare/APPK-2/pkg/third_party/maccms-template-v2020.1000.1035/template \
 *        --out src/test/resources/phptemplate
 *   3) art/detail 上一篇/下一篇片段渲染 R-1（V-13；桩 function，不连数据库）：
 *      D:/Tools/php/7.3/php.exe src/test/php/tpl_runtime_golden.php --render-sample art-detail-prevnext \
 *        --package .compare/APPK-2/pkg/third_party/maccms-template-v2020.1000.1035/template \
 *        --out src/test/resources/phptemplate
 *   4) Art.infoData 模型调用黄金（V-16；连一次性库 maccms_it_*，须调度放行；缺库时输出 BLOCKED）：
 *      D:/Tools/php/7.3/php.exe src/test/php/tpl_runtime_golden.php --model art-info \
 *        --out src/test/resources/phptemplate
 *
 * 不修改任何旧 PHP 文件；不读取仓库外文件；不写仓库外路径（子命令 2 的编译缓存写在临时目录，退出前删除）。
 * 输出一律写成 UTF-8、LF 换行的文本或 JSON，对象键序固定，便于逐字节比对与两次运行 diff。
 */

error_reporting(E_ALL);
ini_set('display_errors', 'Off');
ini_set('log_errors', 'Off');
date_default_timezone_set('PRC');
ini_set('precision', '14');
setlocale(LC_ALL, 'C');

$repo = realpath(__DIR__ . '/../../..');
if ($repo === false || !is_dir($repo . '/php/thinkphp')) {
    fwrite(STDERR, "无法定位仓库根目录（仓库根下应有 php/thinkphp）\n");
    exit(3);
}
// 仓库根：src/test/php 向上三级。若本脚本被放在别处，退回当前工作目录。
if (!is_dir($repo . '/src/test/php')) {
    $repo = getcwd();
}
$argvIn = $argv;
array_shift($argvIn);

$opts = ['cmd' => null, 'out' => null, 'package' => null, 'json' => false];
foreach ($argvIn as $a) {
    if ($a === '--semantics' || $a === '--compile' || $a === '--model') {
        $opts['cmd'] = substr($a, 2);
    } elseif ($a === '--render-sample') {
        $opts['cmd'] = 'render-sample';
    } elseif ($a === '--model-calls') {
        $opts['cmd'] = 'model-calls';
    } elseif ($a === '--json') {
        $opts['json'] = true;
    } elseif (substr($a, 0, 10) === '--fixture=') {
        $opts['fixture'] = substr($a, 10);
    } elseif ($a === '--fixture') {
        $opts['next'] = 'fixture';
    } elseif (substr($a, 0, 13) === '--explain-out=') {
        $opts['explain_out'] = substr($a, 13);
    } elseif ($a === '--explain-out') {
        $opts['next'] = 'explain_out';
    } elseif (substr($a, 0, 6) === '--out=') {
        $opts['out'] = substr($a, 6);
    } elseif ($a === '--out') {
        $opts['next'] = 'out';
    } elseif (substr($a, 0, 10) === '--package=') {
        $opts['package'] = substr($a, 10);
    } elseif ($a === '--package') {
        $opts['next'] = 'package';
    } elseif (isset($opts['next'])) {
        $opts[$opts['next']] = $a;
        unset($opts['next']);
    } elseif ($opts['cmd'] === 'render-sample' && $opts['sample'] === null) {
        $opts['sample'] = $a;
    } elseif ($opts['cmd'] === 'model' && $opts['model'] === null) {
        $opts['model'] = $a;
    } elseif ($opts['cmd'] === 'model-calls' && !isset($opts['model'])) {
        $opts['model'] = $a;
    }
}
if ($opts['cmd'] === null) {
    fwrite(STDERR, "用法见本文件头部注释\n");
    exit(2);
}
$outRoot = $opts['out'] !== null ? $opts['out'] : 'src/test/resources/phptemplate';
if (!preg_match('~^(/|[A-Za-z]:)~', $outRoot)) {
    $outRoot = $repo . '/' . $outRoot;
}
$phpVersion = PHP_VERSION;
$phpBinary = PHP_BINARY;

/* ---------------------------------------------------------------- 公共工具 */

function jexit($data, $code = 0)
{
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), "\n";
    exit($code);
}

/** 递归建目录。 */
function ensureDir($dir)
{
    if (!is_dir($dir) && !mkdir($dir, 0777, true) && !is_dir($dir)) {
        fwrite(STDERR, "无法创建目录：$dir\n");
        exit(3);
    }
}

/** 写文件：UTF-8 原文，强制 LF；内容没有结尾换行时补一个（黄金按行比对，末尾统一）。 */
function writeText($path, $text, $ensureTrailingNewline = true)
{
    ensureDir(dirname($path));
    $text = str_replace("\r\n", "\n", $text);
    if ($ensureTrailingNewline && substr($text, -1) !== "\n") {
        $text .= "\n";
    }
    if (file_put_contents($path, $text) === false) {
        fwrite(STDERR, "写入失败：$path\n");
        exit(3);
    }
}

/** 记一条“左侧是表达式原文、右侧是 PHP 实际结果”的向量；结果的类型也记下来（RT-6 要求类型可比）。 */
function vec(&$dst, $expr, $value, $extra = [])
{
    $dst[] = array_merge(['expr' => $expr, 'type' => gettype($value), 'value' => $value], $extra);
}

/** 字符串化：PHP 的 (string) 与 echo 等价，是 RT-3 的唯一口径。 */
function strOf($v)
{
    return (string)$v;
}

/** 把值转成“可写进 JSON 的十六进制字节串”，避免编码歧义（字符串按 UTF-8 字节）。 */
function hexOf($v)
{
    if (is_array($v)) {
        return ['type' => 'array', 'hex' => bin2hex(serializeArray($v))];
    }
    return ['type' => gettype($v), 'hex' => bin2hex(strOf($v))];
}

/**
 * 把 ReflectionFunction 的字符串结果稳定化（S3-05 复核阻断项 1）。
 *
 * `(string) new ReflectionFunction($fn)` 的输出里带**定义该函数的源文件绝对路径**，例如
 * `@@ C:\Users\…\worktree/php/application/common.php 1633 - 1657`。这条路径随生成机、随检出目录变化，
 * 直接写进黄金就会让黄金不能跨工作目录重复生成。这里把仓库根绝对路径统一替换成稳定占位符
 * `<REPO>`，并把剩余的分隔符统一成 `/`：
 *   - 原串里的 `\` 与 `/` 两种写法都替换（PHP 在 Windows 下用的是 `\`，`\` 只在双引号里有转义
 *     问题，这里全部按字面量处理）；
 *   - Windows 路径大小写不敏感，替换不区分大小写；
 *   - 替换前先把仓库根自身也做同样的分隔符归一，避免 PHP 侧混用分隔符导致匹配不上。
 *
 * 归一之后仍保留 PHP 版本（`_meta.php`）、函数名、参数签名与相对源文件路径及行号区间，
 * 实现依据依旧可核验；只是不再包含生成机工作目录。
 */
function stableReflection($fn)
{
    global $repo;
    $text = (string)(new ReflectionFunction($fn));
    $targets = [];
    if (is_string($repo) && $repo !== '') {
        $targets[] = $repo;                          // 原样（通常是反斜杠）
        $slash = str_replace(chr(92), '/', $repo);   // 全正斜杠
        $targets[] = $slash;
        $targets[] = str_replace('/', chr(92), $repo); // 全反斜杠
        // 另登记一份 realpath 归一（处理 8.3 短名/大小写差异），拿不到就跳过
        $rp = realpath($repo);
        if ($rp !== false && $rp !== '') {
            $targets[] = $rp;
            $targets[] = str_replace(chr(92), '/', $rp);
        }
    }
    if (PHP_OS_FAMILY === 'Windows') {
        // PHP 自身也可能把路径写成大写盘符，这里给盘符补一个大写变体
        foreach ($targets as $t) {
            if (preg_match('~^[A-Za-z]:~', $t)) {
                $targets[] = strtoupper(substr($t, 0, 1)) . substr($t, 1);
                $targets[] = strtolower(substr($t, 0, 1)) . substr($t, 1);
            }
        }
    }
    // 长的先替换，避免短前缀先把长串切坏
    $targets = array_values(array_unique(array_filter($targets, 'strlen')));
    usort($targets, function ($a, $b) {
        return strlen($b) - strlen($a);
    });
    foreach ($targets as $t) {
        $text = str_ireplace($t, '<REPO>', $text);
    }
    // 剩余分隔符统一（`<REPO>` 之后的部分也一并归一，保证跨平台一致）
    $text = str_replace(chr(92), '/', $text);
    return $text;
}

/** 数组按 PHP 顺序序列化成规范文本（键类型也标出，RT-2 要求 "1" 规范化为 int 1）。 */
function serializeArray(array $a)
{    $s = '';
    foreach ($a as $k => $v) {
        $s .= (is_int($k) ? 'i:' : 's:') . $k . '=>';
        $s .= is_array($v) ? '[' . serializeArray($v) . ']' : gettype($v) . '(' . strOf($v) . ')';
        $s .= ';';
    }
    return $s;
}

/*
 * ZDUMP-1（技术设计 v13 第 §6.21 节）：PHP 黄金与 Java 测试共用的类型化编码。
 *   null   → {"t":"null"}
 *   bool   → {"t":"bool","v":true|false}
 *   int    → {"t":"int","v":"<十进制>"}
 *   float  → {"t":"float","v":"<var_export 结果>"}
 *   string → {"t":"string","v":"<字节的 Base64>"}
 *   数组   → {"t":"array","v":[[<键：int 或 string>, <值的 ZDUMP-1>], …]}（保持键序）
 * PHP 侧用 gettype 判型；比较为整份 JSON 逐字节相等。
 * 编码器只做编码，不做任何取舍；取舍在 PROJ-1 里做。
 */
function zdump_encode($v)
{
    switch (gettype($v)) {
        case 'NULL':
            return ['t' => 'null'];
        case 'boolean':
            return ['t' => 'bool', 'v' => $v];
        case 'integer':
            return ['t' => 'int', 'v' => (string)$v];
        case 'double':
            return ['t' => 'float', 'v' => var_export($v, true)];
        case 'string':
            return ['t' => 'string', 'v' => base64_encode($v)];
        case 'array':
            $pairs = [];
            foreach ($v as $k => $item) {
                $pairs[] = [is_int($k) ? $k : (string)$k, zdump_encode($item)];
            }
            return ['t' => 'array', 'v' => $pairs];
        default:
            // 其他类型（object/resource）不属于 ZDUMP-1 的值域，直接报错，不静默降级
            fwrite(STDERR, 'ZDUMP-1 不支持的类型：' . gettype($v) . "\n");
            exit(6);
    }
}

/** ZDUMP-1 文档编码：稳定、可逐字节比较（无转义歧义、无浮点格式差异）。 */
function zdump_json($v)
{
    return json_encode($v, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT) . "\n";
}

/* -------------------------------------------------- 1) 值语义 / 函数 / 宏 */

if ($opts['cmd'] === 'semantics') {
    $sem = [];
    $sem['_meta'] = [
        'generator' => 'src/test/php/tpl_runtime_golden.php --semantics',
        'php' => $phpVersion, 'php_binary' => basename($phpBinary),
        'int_size' => PHP_INT_SIZE, 'precision' => ini_get('precision'),
        'timezone' => date_default_timezone_get(), 'lc_all' => setlocale(LC_ALL, '0'),
        'design' => '9c73bf6ced044f40eeb87ef7861e616b6157499a', 'pre5' => 'b550151b4243622fe7ac060c9ff6e720d83ae605',
    ];

    // RT-2 值类型与数组键规范化（规则同 PhpArray.normalizeKey）
    $sem['rt2_array_keys'] = [];
    $arr = [];
    $arr['1'] = 'a';      // "1" → int 1
    $arr['01'] = 'b';     // 不是规范十进制 → 仍是 string '01'
    $arr[1.7] = 'c';      // float → int 1
    $arr[true] = 'd';     // bool true → int 1
    $arr[null] = 'e';     // null → ''
    $arr['-3'] = 'f';     // int -3
    $arr[' 3'] = 'g';     // 前导空格 → string
    $arr['3 '] = 'h';     // 尾随空格 → string
    $arr['+4'] = 'i';     // 带正号 → string
    $arr['-0'] = 'j';     // "-0" → int 0? PHP 实际：string '-0'
    $arr['1.0'] = 'k';    // string
    $arr['9223372036854775808'] = 'l'; // 溢出 → string
    foreach ($arr as $k => $v) {
        $sem['rt2_array_keys'][] = ['written' => $v, 'key_type' => gettype($k), 'key' => (string)$k];
    }
    $sem['rt2_array_order'] = [];
    $o = [];
    $o['b'] = 1;
    $o[] = 2;
    $o['a'] = 3;
    $o[10] = 4;
    $o[] = 5; // 最大 int 键 +1
    foreach ($o as $k => $v) {
        $sem['rt2_array_order'][] = ['key_type' => gettype($k), 'key' => (string)$k, 'value' => $v];
    }

    // RT-3 字符串化（precision=14）
    $sem['rt3_stringify'] = [];
    foreach ([
        'true' => true, 'false' => false, 'null' => null, 'int0' => 0, 'int-42' => -42,
        'int_max' => PHP_INT_MAX, 'int_min' => PHP_INT_MIN,
        'float0' => 0.0, 'negzero' => -0.0, 'float1' => 1.0, 'float1_5' => 1.5,
        'float_1e14' => 1.0e14, 'float_1e15' => 1.0e15, 'float_1e-5' => 1.0e-5,
        'float_third' => 1 / 3, 'float_pi' => M_PI,
        'float_1_0000000000001' => 1.0000000000001, 'float_big' => 123456789012345678.0,
        'empty_str' => '', 'str_zero' => '0', 'str_num' => '0123',
        'array' => [1, 2],
    ] as $k => $v) {
        $sem['rt3_stringify'][] = ['label' => $k, 'type' => gettype($v), 'string' => strOf($v)];
    }
    // 浮点的 %.14G 细节（PHPB-7）与 json_encode（V-11 点名的 json_encode 向量）
    $sem['rt3_json'] = [];
    foreach ([0.0, -0.0, 1.0, 1.5, 1 / 3, 1.0e14, 1.0e15, M_PI, 123456789012345.0] as $i => $f) {
        $sem['rt3_json'][] = ['label' => 'float' . $i, 'value' => $f, 'json' => json_encode($f)];
    }
    foreach ([['a' => 1, 'b' => 'x'], [], [1, 2, 3], ['0' => 'a', '1' => 'b'], ['k' => null, 'j' => false], [true, false, null]] as $i => $a) {
        $sem['rt3_json'][] = ['label' => 'arr' . $i, 'json' => json_encode($a), 'keys' => array_map('strOf', array_keys($a))];
    }

    // RT-4 PHP 7.3 弱比较
    $sem['rt4_compare'] = [];
    $pairs = [
        ['"abc" == 0', 'abc', 0], ['"1" == 1', '1', 1], ['"01" == 1', '01', 1], ['"1 " == 1', '1 ', 1],
        ['" 1" == 1', ' 1', 1], ['"1x" == 1', '1x', 1], ['"x1" == 1', 'x1', 1], ['"" == 0', '', 0],
        ['null == 0', null, 0], ['null == ""', null, ''], ['null == false', null, false],
        ['"0" == false', '0', false], ['"0.0" == 0', '0.0', 0], ['"1e2" == 100', '1e2', 100],
        ['[] == false', [], false], ['[] == 0', [], 0], ['[0] == false', [0], false],
        ['"abc" == "ABC"', 'abc', 'ABC'], ['"10" == "1e1"', '10', '1e1'],
        ['PHP_INT_MAX == "9223372036854775807"', PHP_INT_MAX, '9223372036854775807'],
        ['"9223372036854775808" == 9.2233720368548E+18', '9223372036854775808', 9.2233720368547758E+18],
    ];
    foreach ($pairs as $p) {
        $sem['rt4_compare'][] = ['label' => $p[0], 'lhs' => hexOf($p[1]), 'rhs' => hexOf($p[2]),
            'eq' => $p[1] == $p[2], 'neq' => $p[1] != $p[2], 'identical' => $p[1] === $p[2],
            'lt' => $p[1] < $p[2], 'gt' => $p[1] > $p[2], 'lte' => $p[1] <= $p[2], 'gte' => $p[1] >= $p[2]];
    }
    // in_array 默认弱比较（RT-4）
    $sem['rt4_in_array'] = [];
    foreach ([['abc', [0, 1]], ['1', [1, 2]], [0, ['abc']], ['abc', ['ABC']], [null, [0]], [null, ['']]] as $c) {
        $sem['rt4_in_array'][] = ['needle' => hexOf($c[0]), 'haystack' => array_map('hexOf', $c[1]),
            'in_array' => in_array($c[0], $c[1]), 'in_array_strict' => in_array($c[0], $c[1], true)];
    }

    // RT-5 未定义取值与字符串下标
    $sem['rt5_undefined'] = [];
    $undef = [];
    $sem['rt5_undefined'][] = ['label' => 'undefined var', 'string' => strOf(@$undefinedVar), 'is_null' => @$undefinedVar === null];
    $sem['rt5_undefined'][] = ['label' => 'undefined index', 'string' => strOf(@$undef['nope']), 'is_null' => @$undef['nope'] === null];
    $sem['rt5_undefined'][] = ['label' => 'nested undefined', 'string' => strOf(@$undef['a']['b']['c'])];
    $sem['rt5_undefined'][] = ['label' => 'isset undefined', 'isset' => isset($undef['nope'])];
    $sem['rt5_undefined'][] = ['label' => 'empty undefined', 'empty' => empty($undef['nope'])];
    $s = 'abc';
    $sem['rt5_str_index'] = [];
    foreach ([0, 1, 2, 3, -1, '1'] as $i) {
        $sem['rt5_str_index'][] = ['index' => $i, 'char' => strOf(@$s[$i]), 'type' => gettype(@$s[$i])];
    }
    $u = "\xe4\xbd\xa0"; // "你" 的 UTF-8 3 字节
    $sem['rt5_str_index_bytes'] = [];
    foreach ([0, 1, 2, 3] as $i) {
        $sem['rt5_str_index_bytes'][] = ['index' => $i, 'byte_hex' => bin2hex((string)@$u[$i])];
    }

    // 裸标识符（GAP-1）：未定义常量按 PHP 7.3 取名字作字符串
    $sem['gap1_bare_identifier'] = [];
    $sem['gap1_bare_identifier'][] = ['label' => 'color', 'is_string' => is_string(@color), 'string' => strOf(@color)];
    $sem['gap1_bare_identifier'][] = ['label' => 'defined-true', 'bool' => true];
    // mac_day($t, color) —— GAP-1 的两个真实写法
    $sem['gap1_mac_day_color'] = [];

    // V-15 函数黄金：PRE-5 函数清单（9.4 CAL-5 ① 24 个过滤器函数 + ② in_array + ③ PHP 代码里的函数）
    // PHP 侧函数来自 application/common.php 与旧地址生成器；这里只加载 common.php（不连库）
    define('APP_PATH', $repo . '/php/application/');
    define('ROOT_PATH', $repo . '/php/');
    $runtime = sys_get_temp_dir() . '/maccms-s3-05-' . getmypid();
    ensureDir($runtime);
    define('RUNTIME_PATH', $runtime . '/runtime/');
    define('BIND_MODULE', 'index');
    define('ENTRANCE', 'index');
    define('IN_FILE', '/index.php');
    require $repo . '/php/thinkphp/base.php';
    \think\Config::set([
        'app_debug' => false, 'app_trace' => false,
        'cache' => ['type' => 'File', 'path' => $runtime . '/cache/', 'prefix' => '', 'expire' => 0],
    ]);
    require $repo . '/php/thinkphp/helper.php';
    require APP_PATH . 'common.php';
    error_reporting(E_ALL);
    $maccms = include APP_PATH . 'extra/maccms.php';
    $maccms['app']['cache_core'] = 0;
    \think\Config::set($maccms, 'maccms');
    $GLOBALS['config'] = $maccms;
    \think\Config::set($maccms);
    define('MAC_PATH', '/');

    // mac_day：固定时钟覆盖“含当天”“不含当天”两种分支（9.3 给实现与测试的约束）
    $sem['fn_mac_day'] = [];
    $fixedNow = mktime(12, 0, 0, 11, 2, 2026); // 2026-11-02 12:00:00 PRC
    $nowYmd = date('Y-m-d', $fixedNow);
    $todayTs = mktime(9, 30, 0, 11, 2, 2026);
    $yesterdayTs = mktime(9, 30, 0, 11, 1, 2026);
    $cases = [
        ['label' => 'null', 't' => null], ['label' => 'int0', 't' => 0], ['label' => 'emptystr', 't' => ''],
        ['label' => 'emptyarr', 't' => []],
        ['label' => 'int-today', 't' => $todayTs], ['label' => 'int-yesterday', 't' => $yesterdayTs],
        ['label' => 'str-datetime-today', 't' => date('Y-m-d H:i:s', $todayTs)],
        ['label' => 'str-datetime-yesterday', 't' => date('Y-m-d H:i:s', $yesterdayTs)],
        ['label' => 'str-date-today', 't' => date('Y-m-d', $todayTs)],
        ['label' => 'numericstr-ts', 't' => (string)$yesterdayTs],
        ['label' => 'nonnumericstr', 't' => 'not-a-date'],
    ];
    foreach ($cases as $c) {
        foreach (['', 'color', 'colou', 'COLOR'] as $f) {
            $sem['fn_mac_day'][] = ['label' => $c['label'], 'f' => $f === '' ? '(omitted)' : $f,
                't' => hexOf($c['t']), 'now' => $nowYmd,
                'out' => hexOf(@mac_day($c['t'], $f))];
        }
    }
    // GAP-1 两处真实写法：mac_day($t, color) —— color 是裸标识符
    foreach ([['today', $todayTs], ['yesterday', $yesterdayTs], ['null', null]] as $c) {
        $sem['gap1_mac_day_color'][] = ['label' => $c[0], 't' => hexOf($c[1]), 'now' => $nowYmd,
            'out' => hexOf(@mac_day($c[1], @color))];
    }

    // date(格式, 值) 的取值边界（9.3：整数、数字串、0、null、空串、非数字串）
    $sem['fn_date'] = [];
    foreach (['Y-m-d', 'Y-m-d H:i:s', 'm-d'] as $fmt) {
        foreach ([
            ['int-ts', $yesterdayTs], ['numstr-ts', (string)$yesterdayTs], ['int0', 0], ['numstr0', '0'],
            ['null', null], ['emptystr', ''], ['nonnumeric', 'not-a-date'], ['float', 1793582400.0],
        ] as $c) {
            $sem['fn_date'][] = ['fmt' => $fmt, 'label' => $c[0], 'arg' => hexOf($c[1]),
                'out' => hexOf(@date($fmt, $c[1])), 'raw_type' => gettype(@date($fmt, $c[1]))];
        }
    }

    // 其余过滤器函数（9.4 CAL-5 ① 清单逐项，取值覆盖正常/边界/空）
    $sem['fn_list'] = [];
    $fnCases = [
        'str_replace' => [['「a」', ['a'], ['b'], '「a」'], ['abc', ['ab', 'c'], ['', 'X'], 'abc'], ['', ['a'], ['b'], '']],
        'htmlspecialchars' => [["<a href=\"x\">&'</a>", null, null, null]],
        'mac_default' => [['', 'x'], [null, 'x'], ['0', 'x'], ['v', 'x']],
        'mac_substring' => [['abcdef', 3, '...'], ['abcdef', 0, '...'], ['abcdef', 10, '...'], ['', 3, '...'],
            ['<a>中文&amp;', 4, '..']],
        'long2ip' => [[167772161], [0], [4294967295], [-1]],
        'mac_long2ip' => [[167772161], [0], ['167772161'], ['abc'], [null]],
        'ceil' => [[1.2], [1.0], [-1.2], [0], ['2.1']],
        'count' => [[[1, 2, 3]], [[]], [null]],
        'mac_filter_html' => [['<p class="x">a<script>alert(1)</script></p>'], ['']],
        'intval' => [[10], [10.9], ['10'], ['10abc'], ['abc'], [null], [true], ['0x1A'], [0777]],
        'strip_tags' => [['<p>a<b>b</b></p>', '<b>'], ['<p>a<b>b</b></p>', null]],
        'urlencode' => [['a b&c=d/中'], ['']],
        'explode' => [['a,b,,c', ','], [',a,', ','], ['abc', ','], ['a,b', '']],
        'mac_em_replace' => [['[em1] 换行'], ['']],
        'mac_get_plog_type_text' => [[1], [2], [3], [4], [5], [0], ['1']],
    ];
    foreach ($fnCases as $fn => $calls) {
        foreach ($calls as $args) {
            $res = @call_user_func_array($fn, $args);
            $sem['fn_list'][] = ['fn' => $fn, 'args' => array_map('hexOf', $args),
                'out' => hexOf($res), 'php_type' => gettype($res)];
        }
    }

    // 地址类函数：返回字符串，靠 URL 生成器的既有黄金（B05/B06）逐字比对；这里只固定几个多值入参的签名
    $urlFns = ['mac_url_img', 'mac_url_create', 'mac_url_actor_detail', 'mac_url_page', 'mac_url_vod_detail',
        'mac_url_vod_plot', 'mac_url_art_detail', 'mac_url_type', 'mac_get_user_portrait', 'mac_url_content_img'];
    $sem['fn_url_list'] = [];
    foreach ($urlFns as $fn) {
        $sem['fn_url_list'][] = ['fn' => $fn, 'exists' => function_exists($fn),
            'reflection' => function_exists($fn) ? stableReflection($fn) : null];
    }

    // 编译产物会生成的内部函数（Cx/Maccms 编译器代码为准；RT-7）：只登记存在性与签名
    $internal = ['is_array', 'count', 'array_slice', 'isset', 'empty', 'in_array', 'explode', 'trim', 'substr',
        'str_replace', 'htmlspecialchars', 'sprintf', 'implode', 'join'];
    $sem['fn_internal'] = [];
    foreach ($internal as $fn) {
        $sem['fn_internal'][] = ['fn' => $fn, 'exists' => function_exists($fn),
            'reflection' => function_exists($fn) ? stableReflection($fn) : null];
    }

    // V-15 的 4 个 {maccms:path/siteaid/sitetid/siteid} 标签 + CAL-3 的 9 个原文输出标签名
    $sem['tag_names'] = [
        'label_maccms' => ['path', 'siteaid', 'sitetid', 'siteid'],
        'raw_output_tpl11' => ['path', 'siteaid', 'sitetid', 'siteid', 'link_gbook', 'link_map_baidu',
            'link_map_google', 'link_map_rss', 'link_map_vod'],
    ];

    // C-33 ④ 的空入参黄金（V-13 TagModelsInfoDataTest：infoData('Art', [], '*', 1)）
    $sem['art_info_empty_where'] = ['code' => 1001, 'msg' => lang('param_err')];
    $sem['art_info_not_found'] = ['code' => 1002, 'msg' => lang('obtain_err')];

    $semJson = json_encode($sem, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";

    // S3-05 复核阻断项 1 的护栏：黄金不得含生成机工作目录。
    // 这里在落盘前自检一次，不满足就非 0 退出且不写文件，避免又悄悄生成一份不可复现的黄金。
    // 注意：要同时检查“原文”与“JSON 转义后”两种写法——JSON 里的反斜杠是成对的，
    // 只查原文会漏。
    $leak = [];
    if (is_string($repo) && $repo !== '') {
        $cands = [$repo, str_replace(chr(92), '/', $repo)];
        foreach ($cands as $c) {
            foreach ([$c, json_encode($c, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)] as $probe) {
                // json_encode 会带上两端引号，去掉引号后才是正文里的片段
                $needle = trim($probe, '"');
                if ($needle !== '' && stripos($semJson, $needle) !== false) {
                    $leak[] = '仓库绝对路径';
                    break 2;
                }
            }
        }
    }
    if ($leak) {
        fwrite(STDERR, '值语义黄金仍包含不稳定路径（' . implode('、', $leak)
            . "），已中止且未写文件；检查 stableReflection() 的归一化\n");
        exit(7);
    }

    writeText($outRoot . '/semantics/php73-vectors.json', $semJson);

    // 递归删除临时目录
    $rm = function ($p) use (&$rm) {
        if (is_dir($p)) {
            foreach (scandir($p) as $f) {
                if ($f !== '.' && $f !== '..') {
                    $rm($p . '/' . $f);
                }
            }
            @rmdir($p);
        } elseif (is_file($p)) {
            @unlink($p);
        }
    };
    $rm($runtime);

    jexit(['ok' => true, 'cmd' => 'semantics', 'out' => $outRoot . '/semantics/php73-vectors.json',
        'php' => $phpVersion, 'vectors' => array_map('count', array_filter($sem, 'is_array'))]);
}

/* --------------------------------------------- 2) 168 个模板的编译产物（V-12） */

if ($opts['cmd'] === 'compile') {
    if ($opts['package'] === null || !is_dir($opts['package'])) {
        jexit(['ok' => false, 'status' => 'BLOCKED', 'cmd' => 'compile',
            'reason' => '未提供 --package（模板包 template 目录）；按 9.9 ② 取出 200cffc5 的 third_party'], 4);
    }
    $pkg = rtrim($opts['package'], '/\\');
    if (!preg_match('~^(/|[A-Za-z]:)~', $pkg)) {
        $pkg = $repo . '/' . $pkg;
    }
    $pkg = realpath($pkg);
    $cache = sys_get_temp_dir() . '/maccms-s3-05-compile-' . getmypid();
    ensureDir($cache);

    define('APP_PATH', $repo . '/php/application/');
    define('ROOT_PATH', $repo . '/php/');
    define('RUNTIME_PATH', $cache . '/runtime/');
    define('BIND_MODULE', 'index');
    define('ENTRANCE', 'index');
    define('IN_FILE', '/index.php');
    require $repo . '/php/thinkphp/base.php';
    \think\Config::set(['app_debug' => false, 'app_trace' => false]);
    require $repo . '/php/thinkphp/helper.php';
    \think\Loader::addNamespace('app', APP_PATH);

    $files = [];
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($pkg, FilesystemIterator::SKIP_DOTS));
    foreach ($it as $f) {
        if ($f->isFile() && substr($f->getFilename(), -5) === '.html') {
            $files[] = str_replace('\\', '/', substr($f->getPathname(), strlen($pkg) + 1));
        }
    }
    sort($files, SORT_STRING);
    $outDir = $outRoot . '/compile-golden';
    ensureDir($outDir);

    $manifest = [];
    $tpl = new \think\Template([
        'cache_path' => $cache . '/',
        'cache_suffix' => 'php',
        'view_suffix' => 'html',
        'tpl_cache' => false,
        'strip_space' => false,
        'layout_on' => false,
        'taglib_load' => true,
        'taglib_build_in' => 'cx',
        'taglib_pre_load' => '',
        'tpl_deny_php' => false,
        'tpl_deny_func_list' => 'echo,exit',
        'tpl_begin' => '{', 'tpl_end' => '}',
        'taglib_begin' => '{', 'taglib_end' => '}',
        'cache_prefix' => '',
    ]);
    // 站点装配（Init.php:71）：view_path = 'template/<模板目录>/<html 目录>/'，相对于站点根（= 包根的父目录）。
    // {include file="public/paging"} 由此按包内相对路径找到，与 PHP 站点完全同一条解析路径。
    $siteRoot = dirname($pkg);            // .../third_party/maccms-template-v2020.1000.1035
    $tplDir = basename(dirname($pkg)) === 'template' ? basename($pkg) : basename($pkg);
    $prevCwd = getcwd();
    chdir($siteRoot);

    $chunks = 0;
    foreach ($files as $rel) {
        $path = $pkg . '/' . $rel;
        $raw = file_get_contents($path);
        // <模板目录>/<html 目录>/<相对路径>，例如 default_pc/html/art/detail.html
        $parts = explode('/', $rel, 3);       // [模板目录, html 目录, 其余]
        $tpl->config(['view_path' => 'template/' . $parts[0] . '/' . $parts[1] . '/']);
        $content = $raw;
        $cacheFile = $cache . '/c_' . md5($rel) . '.php';
        $ref = new ReflectionMethod($tpl, 'compiler');
        $ref->setAccessible(true);
        try {
            $ref->invokeArgs($tpl, [&$content, $cacheFile]);
        } catch (Throwable $e) {
            jexit(['ok' => false, 'status' => 'BLOCKED', 'cmd' => 'compile',
                'reason' => '编译 ' . $rel . ' 抛异常：' . get_class($e) . ': ' . $e->getMessage()], 5);
        }
        $compiled = is_file($cacheFile) ? file_get_contents($cacheFile) : $content;
        // 去掉头代码块里的 serialize（含绝对路径与 mtime）：V-12 规定比较时去掉
        $compiled = preg_replace('/^<\?php if \(!defined\(.THINK_PATH.\)\) exit\(\); \/\*.*?\*\/ \?>' . "\n" . '/s', '', $compiled);
        // 编译产物里 include 内联后仍可能带站点根绝对路径：统一换掉，保证两次运行可比
        $compiled = str_replace(str_replace(chr(92), '/', $siteRoot) . '/', '<SITE>/', $compiled);
        $compiled = str_replace(chr(92), '/', $compiled);

        $dst = $outDir . '/' . $rel;
        writeText($dst, $compiled, false);
        // 计数口径：`model` / `infoData` 前面不是标识符字符或 `$`，后面允许空白再接 `(`。
        // 旧写法想用单词边界，但正则字面量里混进了 U+0008 控制字符，导致总是 0；
        // 这里用显式否定型零宽断言，等价于单词边界但不会在 `$model(` 上漏判。
        // 同一个口径在 Java 侧 PhpTemplateGoldenAssetsTest 里逐项复核。
        $manifest[] = ['path' => $rel, 'source_sha1' => sha1($raw), 'source_bytes' => strlen($raw),
            'compiled_sha1' => sha1($compiled), 'compiled_bytes' => strlen($compiled),
            'php_chunks' => preg_match_all('/<\?php|<\?=/', $compiled),
            'model_calls' => preg_match_all('/(?<![A-Za-z0-9_$])model\s*\(/', $compiled),
            'info_data_calls' => preg_match_all('/infoData\s*\(/', $compiled)];
        $chunks += $manifest[count($manifest) - 1]['php_chunks'];
        @unlink($cacheFile);
    }
    chdir($prevCwd);

    writeText($outDir . '/manifest.json', json_encode([
        '_meta' => ['generator' => 'src/test/php/tpl_runtime_golden.php --compile',
            'php' => $phpVersion, 'package' => '200cffc5d01090faf4f61e153feb472b107b34df',
            'design' => '9c73bf6ced044f40eeb87ef7861e616b6157499a', 'file_count' => count($manifest)],
        'files' => $manifest,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n");

    // R-2：手机文章详情页不出现 infoData
    $wap = null;
    foreach ($manifest as $m) {
        if ($m['path'] === 'default_wap/html/art/detail.html') {
            $wap = $m;
        }
    }
    $rm = function ($p) use (&$rm) {
        if (is_dir($p)) {
            foreach (scandir($p) as $f) {
                if ($f !== '.' && $f !== '..') {
                    $rm($p . '/' . $f);
                }
            }
            @rmdir($p);
        } elseif (is_file($p)) {
            @unlink($p);
        }
    };
    $rm($cache);

    jexit(['ok' => true, 'cmd' => 'compile', 'files' => count($manifest), 'php_chunks' => $chunks,
        'out' => $outDir, 'wap_art_detail' => $wap]);
}

/* ------------------------------- 3) art/detail 上一篇/下一篇片段渲染（V-13 R-1） */

if ($opts['cmd'] === 'render-sample') {
    $sample = isset($opts['sample']) ? $opts['sample'] : 'art-detail-prevnext';
    if ($sample !== 'art-detail-prevnext') {
        jexit(['ok' => false, 'status' => 'BLOCKED', 'reason' => '未知样例：' . $sample], 4);
    }
    if ($opts['package'] === null || !is_dir($opts['package'])) {
        jexit(['ok' => false, 'status' => 'BLOCKED', 'cmd' => 'render-sample',
            'reason' => '未提供 --package（模板包 template 目录）；按 9.9 ② 取出 200cffc5 的 third_party'], 4);
    }
    $pkg = rtrim($opts['package'], '/\\');
    $src = $pkg . '/default_pc/html/art/detail.html';
    $lines = preg_split('/\n/', file_get_contents($src));
    $slice = implode("\n", array_slice($lines, 31, 19)) . "\n"; // 第 32～50 行
    if (strlen($slice) !== 808 || sha1('blob ' . strlen($slice) . "\0" . $slice) !== 'a615d09d955020c6c86924bf3c8a2a88852b308e') {
        jexit(['ok' => false, 'status' => 'BLOCKED', 'cmd' => 'render-sample',
            'reason' => '样例切片与 V-13 R-1 登记的 808 字节 / a615d09d 不一致，拒绝生成漂移黄金',
            'bytes' => strlen($slice)], 5);
    }

    define('APP_PATH', $repo . '/php/application/');
    define('ROOT_PATH', $repo . '/php/');
    $rt = sys_get_temp_dir() . '/maccms-s3-05-render-' . getmypid();
    ensureDir($rt);
    define('RUNTIME_PATH', $rt . '/runtime/');
    define('BIND_MODULE', 'index');
    define('ENTRANCE', 'index');
    define('IN_FILE', '/index.php');
    require $repo . '/php/thinkphp/base.php';
    \think\Config::set(['app_debug' => false, 'app_trace' => false,
        'cache' => ['type' => 'File', 'path' => $rt . '/cache/', 'prefix' => '', 'expire' => 0]]);
    require $repo . '/php/thinkphp/helper.php';
    require APP_PATH . 'common.php';
    error_reporting(E_ALL);
    $maccms = include APP_PATH . 'extra/maccms.php';
    $maccms['app']['cache_core'] = 0;
    \think\Config::set($maccms, 'maccms');
    $GLOBALS['config'] = $maccms;
    \think\Config::set($maccms);
    define('MAC_PATH', '/');

    // 桩：mac_url_art_detail($info) 两侧都返回 '/art/'.$info['art_id'].'.html'（V-13 R-1）
    // 桩：model('art')->infoData(...) 换成记录调用的假模型（PHP 侧不改旧代码，用运行时替换 model() 助手）
    $calls = [];
    $replies = [];
    $zCallIndex = 0;
    $GLOBALS['__z_calls'] = &$calls;
    $GLOBALS['__z_replies'] = &$replies;

    $outDir = $outRoot . '/render-golden';
    ensureDir($outDir);

    // 桩：编译产物里的 model('art')->infoData(...) 被替换成这个函数；只记录实参并返回预设结果。
    // PHP 侧不改旧代码，也不定义常量 color（GAP-1 与本样例无关）。
    if (!function_exists('z_stub_info_data')) {
        function z_stub_info_data($model, $where = null, $field = '*', $cache = 0)
        {
            $GLOBALS['__z_calls'][] = ['model' => $model, 'where' => $where, 'field' => $field, 'cache' => $cache];
            $i = $GLOBALS['__z_idx']++;
            return $GLOBALS['__z_replies'][$i];
        }
    }
    // 桩：mac_url_art_detail($info) = '/art/'.$info['art_id'].'.html'（V-13 R-1 原文规定）
    if (!function_exists('z_stub_art_url')) {
        function z_stub_art_url($info = [])
        {
            return '/art/' . $info['art_id'] . '.html';
        }
    }

    $scenarios = [
        'a' => ['label' => 'both-hit', 'replies' => [
            ['code' => 1, 'msg' => 'ok', 'info' => ['art_id' => 10, 'art_name' => 'A&B<i>"']],
            ['code' => 1, 'msg' => 'ok', 'info' => ['art_id' => 30, 'art_name' => '下篇']],
        ]],
        'b' => ['label' => 'prev-1002', 'replies' => [
            ['code' => 1002, 'msg' => 'err'],
            ['code' => 1, 'msg' => 'ok', 'info' => ['art_id' => 30, 'art_name' => '下篇']],
        ]],
        'c' => ['label' => 'next-1002', 'replies' => [
            ['code' => 1, 'msg' => 'ok', 'info' => ['art_id' => 10, 'art_name' => 'A&B<i>"']],
            ['code' => 1002, 'msg' => 'err'],
        ]],
        'd' => ['label' => 'both-1002', 'replies' => [
            ['code' => 1002, 'msg' => 'err'],
            ['code' => 1002, 'msg' => 'err'],
        ]],
        'e' => ['label' => 'prev-code-string-1', 'replies' => [
            ['code' => '1', 'msg' => 'ok', 'info' => ['art_id' => 10, 'art_name' => 'A&B<i>"']],
            ['code' => 1002, 'msg' => 'err'],
        ]],
    ];

    $results = [];
    foreach ($scenarios as $key => $sc) {
        $calls = [];
        $idx = 0;
        // 编译这段片段（think\Template 的 compiler()，与 V-12 同一引擎、同一配置）
        $cache = $rt . '/render_' . $key . '/';
        ensureDir($cache);
        $tpl = new \think\Template([
            'cache_path' => $cache, 'cache_suffix' => 'php', 'tpl_cache' => false, 'strip_space' => false,
            'layout_on' => false, 'taglib_load' => true, 'taglib_build_in' => 'cx', 'taglib_pre_load' => '',
            'tpl_deny_php' => false, 'tpl_deny_func_list' => 'echo,exit',
            'tpl_begin' => '{', 'tpl_end' => '}', 'taglib_begin' => '{', 'taglib_end' => '}',
            'cache_prefix' => '', 'view_path' => $pkg . '/default_pc/html/art/',
        ]);
        $content = $slice;
        $cacheFile = $cache . 'art_detail_frag.php';
        $ref = new ReflectionMethod($tpl, 'compiler');
        $ref->setAccessible(true);
        $ref->invokeArgs($tpl, [&$content, $cacheFile]);
        $compiled = file_get_contents($cacheFile);
        // 只把 model('art')->infoData( 换成桩函数 z_stub_info_data('Art', ：实参原样保留（ZDUMP-1 比对的就是实参）
        $patched = preg_replace('/model\(\s*[\'"]([A-Za-z_]\w*)[\'"]\s*\)\s*->\s*infoData\s*\(/',
            "z_stub_info_data('Art', ", $compiled);
        if ($patched === null || strpos($patched, 'z_stub_info_data') === false) {
            jexit(['ok' => false, 'status' => 'BLOCKED',
                'reason' => '编译产物里没有找到 model(...)->infoData(，V-13 R-1 样例已漂移'], 5);
        }
        // V-13 规定：桩函数 mac_url_art_detail($info) 两侧都返回 '/art/'.$info['art_id'].'.html'。
        // 旧站的真实实现要读 $GLOBALS['maccms']['path'] 与 $param['page']，与本文样例无关，所以换成桩。
        $patched = preg_replace('/\bmac_url_art_detail\s*\(/', 'z_stub_art_url(', $patched);
        if ($patched === null || strpos($patched, 'z_stub_art_url') === false) {
            jexit(['ok' => false, 'status' => 'BLOCKED',
                'reason' => '编译产物里没有找到 mac_url_art_detail(，V-13 R-1 样例已漂移'], 5);
        }
        $runFile = $cache . 'art_detail_run.php';
        writeText($runFile, $patched, false);

        // 桩：记录调用实参并返回本情形预设的结果（PHP 数组按值传，等价 MC-4 的快照语义）
        $GLOBALS['__z_replies'] = $sc['replies'];
        $GLOBALS['__z_idx'] = 0;
        $GLOBALS['__z_calls'] = [];
        ob_start();
        $obj = ['art_id' => 20];
        include $runFile;
        $html = ob_get_clean();
        $calls = $GLOBALS['__z_calls'];

        writeText($outDir . '/art-detail-prevnext-' . $key . '.html', $html, false);
        $results[$key] = ['label' => $sc['label'], 'bytes' => strlen($html),
            'calls' => array_map(function ($c) {
                return ['model' => $c['model'],
                    'where' => array_map(function ($w) { return [$w[0], $w[1]]; }, $c['where']),
                    'field' => $c['field'], 'cache' => $c['cache']];
            }, $calls)];
        @unlink($cacheFile);
        @unlink($runFile);
    }


    writeText($outDir . '/art-detail-prevnext.json', json_encode([
        '_meta' => ['generator' => 'src/test/php/tpl_runtime_golden.php --render-sample art-detail-prevnext',
            'php' => $phpVersion, 'sample_slice' => 'default_pc/html/art/detail.html:32-50',
            'slice_bytes' => strlen($slice), 'slice_git_blob' => 'a615d09d955020c6c86924bf3c8a2a88852b308e',
            'design' => '9c73bf6ced044f40eeb87ef7861e616b6157499a'],
        'scenarios' => $results,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n");

    jexit(['ok' => true, 'cmd' => 'render-sample', 'sample' => $sample, 'out' => $outDir,
        'scenarios' => array_map(function ($r) {
            return ['label' => $r['label'], 'bytes' => $r['bytes'], 'calls' => count($r['calls'])];
        }, $results)]);
}

/* --------------------------------- 4) Art.infoData 模型调用黄金（V-16，连一次性库） */

/**
 * PROJ-1（v12）：把 PHP `infoData` 的返回值投影到 D1-FLD，再按 ZDUMP-1 编码。
 *
 * 保留 `code`、`msg`；若有 `info`，按 PHP 原键序只保留：
 *   - D1-FLD-1 的 5 列：art_id、type_id、art_name、art_en、art_time
 *   - D1-FLD-2 的 2 个派生键：type（数组时只留 type_en，null 保持 null）、
 *                       type_1（数组时只留 type_id、type_en，null 保持 null）
 * 其他键丢弃（D1-FLD-3，含 art_page_list / art_page_total / art_pic_screenshot_list / group）。
 * 投影是本黄金里唯一的变换；实现不得再改 key 序或类型。
 */
function projInfoData($res)
{
    if (!is_array($res)) {
        return $res;
    }
    $out = [];
    foreach ($res as $k => $v) {
        if ($k === 'info') {
            $info = [];
            $keep = ['art_id', 'type_id', 'art_name', 'art_en', 'art_time', 'type', 'type_1'];
            foreach ($keep as $f) {
                if (!array_key_exists($f, $v)) {
                    continue;
                }
                $val = $v[$f];
                if ($f === 'type') {
                    $info[$f] = is_array($val) ? ['type_en' => $val['type_en']] : null;
                } elseif ($f === 'type_1') {
                    $info[$f] = is_array($val)
                        ? ['type_id' => $val['type_id'], 'type_en' => $val['type_en']] : null;
                } else {
                    $info[$f] = $val;
                }
            }
            $out[$k] = $info;
        } elseif ($k === 'code' || $k === 'msg') {
            $out[$k] = $v;
        }
        // 其余键按 D1-FLD-3 丢弃
    }
    return $out;
}

/**
 * 连一次性库、跑 IA-1～IA-5，产出 model-golden。
 *
 * 前置（缺一即 BLOCKED，不做任何请求、不写任何文件）：
 *   MACCMS_IT_ADMIN_URL / MACCMS_IT_ADMIN_USERNAME / MACCMS_IT_ADMIN_PASSWORD
 *   GOLDEN_DB_NAME=maccms_it_*（一次性库，建好、结构已导入 install.sql、夹具已导入）
 */
function runModelCalls(array $opts)
{
    $dsn = getenv('MACCMS_IT_ADMIN_URL');
    $user = getenv('MACCMS_IT_ADMIN_USERNAME');
    $pass = getenv('MACCMS_IT_ADMIN_PASSWORD');
    $dbName = getenv('GOLDEN_DB_NAME');
    $missing = [];
    if ($dsn === false || trim((string)$dsn) === '') {
        $missing[] = 'MACCMS_IT_ADMIN_URL';
    }
    if ($user === false) {
        $missing[] = 'MACCMS_IT_ADMIN_USERNAME';
    }
    if ($pass === false) {
        $missing[] = 'MACCMS_IT_ADMIN_PASSWORD';
    }
    if ($dbName === false || !preg_match('/^maccms_it_[A-Za-z0-9_]+$/', (string)$dbName)) {
        $missing[] = 'GOLDEN_DB_NAME(maccms_it_*)';
    }
    if ($missing) {
        jexit(['ok' => false, 'status' => 'BLOCKED', 'cmd' => 'model-calls', 'missing' => $missing,
            'reason' => '缺少可验证的一次性库参数；静态或模拟结果不得当作通过'], 4);
    }

    $repo = dirname(dirname(dirname(__DIR__)));
    if (!defined('APP_PATH')) {
        define('APP_PATH', $repo . '/php/application/');
        define('ROOT_PATH', $repo . '/php/');
        $rt = sys_get_temp_dir() . '/maccms-s3-05-model-' . getmypid();
        ensureDir($rt);
        define('RUNTIME_PATH', $rt . '/runtime/');
        define('BIND_MODULE', 'index');
        define('ENTRANCE', 'index');
        define('IN_FILE', '/index.php');
        require $repo . '/php/thinkphp/base.php';
        \think\Config::set(['app_debug' => false, 'app_trace' => false,
            'cache' => ['type' => 'File', 'path' => $rt . '/cache/', 'prefix' => '', 'expire' => 0]]);
        require $repo . '/php/thinkphp/helper.php';
        require APP_PATH . 'common.php';
        \think\Loader::addNamespace('app', APP_PATH);
        // Loader::model() 用 Request 的 module 拼类名（app\<module>\model\X），找不到再回退 common\；
        // 命令行下没有请求上下文，必须手动绑定，否则 model('art') 解析不到 app\common\model\Art。
        \think\Request::instance()->module('index');
        $maccms = include APP_PATH . 'extra/maccms.php';
        $maccms['app']['cache_core'] = 0;
        \think\Config::set($maccms, 'maccms');
        $GLOBALS['config'] = $maccms;
        \think\Config::set($maccms);
        define('MAC_PATH', '/');
    }

    // 只接受一次性库：DSN 里带的库名与 GOLDEN_DB_NAME 必须一致，且形如 maccms_it_*
    $itUrl = (string)$dsn;
    if (strpos($itUrl, 'maccms_it_') === false) {
        jexit(['ok' => false, 'status' => 'BLOCKED', 'cmd' => 'model-calls',
            'reason' => 'MACCMS_IT_ADMIN_URL 不含一次性库名 maccms_it_*，拒绝执行'], 4);
    }

    // 覆盖数据库连接：ThinkPHP 5.0 的 Db 从配置读，这里直接改配置项
    \think\Config::set([
        'database' => [
            'type' => 'mysql', 'dsn' => '', 'hostname' => '', 'database' => $dbName,
            'username' => $user, 'password' => $pass, 'hostport' => '',
            'params' => [], 'charset' => 'utf8', 'prefix' => 'mac_', 'debug' => false,
            'deploy' => 0, 'rw_separate' => false, 'master_num' => 1, 'slave_no' => '',
            'fields_strict' => false, 'resultset_type' => 'array',
            'auto_timestamp' => false, 'datetime_format' => 'Y-m-d H:i:s',
            'sql_explain' => false, 'builder' => '', 'query' => '\think\db\Query',
            'break_reconnect' => false,
        ],
    ]);

    // DSN 里的 host/port 一并沿用，避免脚本自带连接参数
    if (preg_match('~mysql://([^:]+):([^@]*)@([^:]+):(\d+)/([^?]*)~', $itUrl, $m)) {
        \think\Config::set('database', array_merge(\think\Config::get('database'), [
            'hostname' => $m[3], 'hostport' => $m[4], 'database' => $m[5],
        ]));
    } elseif (preg_match('~mysql://([^:]+):([^@]*)@([^/]+)/([^?]*)~', $itUrl, $m)) {
        \think\Config::set('database', array_merge(\think\Config::get('database'), [
            'hostname' => $m[3], 'hostport' => '3306', 'database' => $m[4],
        ]));
    }

    $outDir = $opts['out'] !== null ? rtrim($opts['out'], "/\\") . '/model-golden' : 'model-golden';
    if (!preg_match('~^(/|[A-Za-z]:)~', $outDir)) {
        $outDir = $repo . '/' . $outDir;
    }
    ensureDir($outDir);

    // IA-1～IA-5：where 均为 art_status eq 1、field='*'、cache=1
    $a1 = 910001;
    $an = 910052;
    $a2 = 910002;
    $aNm1 = 910051;
    $cases = [
        ['id' => 'IA-1', 'label' => 'lt a1：较小的一行被状态条件排除',
            'where' => ['art_status' => ['eq', 1], 'art_id' => ['lt', $a1]]],
        ['id' => 'IA-2', 'label' => 'gt an：较大的一行被状态条件排除',
            'where' => ['art_status' => ['eq', 1], 'art_id' => ['gt', $an]]],
        ['id' => 'IA-3', 'label' => 'lt a2：命中 a1，键序与 D1-FLD-1/2 一致',
            'where' => ['art_status' => ['eq', 1], 'art_id' => ['lt', $a2]]],
        ['id' => 'IA-4', 'label' => 'gt a(n-1)：命中 an，type/type_1 为 null',
            'where' => ['art_status' => ['eq', 1], 'art_id' => ['gt', $aNm1]]],
        ['id' => 'IA-5a', 'label' => '多候选 lt an：结果等于 PHP 黄金（不预设是哪一行）',
            'where' => ['art_status' => ['eq', 1], 'art_id' => ['lt', $an]]],
        ['id' => 'IA-5b', 'label' => '多候选 gt a1：结果等于 PHP 黄金（不预设是哪一行）',
            'where' => ['art_status' => ['eq', 1], 'art_id' => ['gt', $a1]]],
    ];

    $results = [];
    foreach ($cases as $c) {
        // PHP 侧调用真实的 model('art')->infoData(...)；旧实现见 Art.php:397
        $raw = model('art')->infoData($c['where'], '*', 1);
        $proj = projInfoData($raw);
        $enc = zdump_encode($proj);
        $txt = zdump_json($enc);
        $results[$c['id']] = [
            'label' => $c['label'],
            'where' => $c['where'],
            'raw_keys' => is_array($raw) && isset($raw['info']) && is_array($raw['info'])
                ? array_keys($raw['info']) : [],
            'golden' => $proj,
            'zdump' => $enc,
            'bytes' => strlen($txt),
        ];
        writeText($outDir . '/' . strtolower(str_replace('-', '', $c['id'])) . '.zdump.txt', $txt, false);
    }

    // IA-8 ②：EXPLAIN 对比（Java 语句 vs PHP 实际语句）+ SELECT VERSION()
    $explain = [];
    $ver = \think\Db::query('SELECT VERSION() AS v');
    $explain[] = 'mysql_version: ' . (isset($ver[0]['v']) ? $ver[0]['v'] : 'UNKNOWN');
    $explain[] = '';
    $phpSqlLt = 'SELECT * FROM mac_art WHERE art_status=1 AND art_id < 910002 LIMIT 1';
    $phpSqlGt = 'SELECT * FROM mac_art WHERE art_status=1 AND art_id > 910051 LIMIT 1';
    $javaSqlLt = 'SELECT /*+ MAX_EXECUTION_TIME(3000) */ art_id, type_id, art_name, art_en, art_time '
        . 'FROM mac_art WHERE art_status = 1 AND art_id < 910002 LIMIT 1';
    $javaSqlGt = 'SELECT /*+ MAX_EXECUTION_TIME(3000) */ art_id, type_id, art_name, art_en, art_time '
        . 'FROM mac_art WHERE art_status = 1 AND art_id > 910051 LIMIT 1';
    foreach ([['php-lt', $phpSqlLt], ['php-gt', $phpSqlGt], ['java-lt', $javaSqlLt], ['java-gt', $javaSqlGt]] as $q) {
        $explain[] = '--- EXPLAIN ' . $q[0] . ' ---';
        $explain[] = 'sql: ' . $q[1];
        $rows = \think\Db::query('EXPLAIN ' . $q[1]);
        foreach ($rows as $r) {
            $explain[] = json_encode($r, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        }
        $explain[] = '';
    }
    $explainTxt = implode("
", $explain) . "
";
    if ($opts['explain_out'] !== null) {
        $ep = $opts['explain_out'];
        if (!preg_match('~^(/|[A-Za-z]:)~', $ep)) {
            $ep = $repo . '/' . $ep;
        }
        ensureDir(dirname($ep));
        writeText($ep, $explainTxt, false);
    }

    $doc = [
        'generator' => 'src/test/php/tpl_runtime_golden.php --model-calls art-infodata',
        'php_version' => PHP_VERSION,
        'fixture' => 'src/test/resources/phptemplate/model-fixtures/art-infodata.sql',
        'design' => 'tech-design v13 9c73bf6ced044f40eeb87ef7861e616b6157499a: V-16, INFO-1~INFO-7, '
            . 'D1-FLD-1~5, PROJ-1, QG-1~QG-7',
        'projection' => 'PROJ-1: only code, msg and info keys of D1-FLD-1 (5 columns) + D1-FLD-2 '
            . '(type.type_en; type_1.type_id,type_1.type_en); all other keys dropped; encoded per ZDUMP-1',
        'rows' => 54,
        'on_range' => [910001, 910052],
        'cases' => $results,
    ];
    writeText($outDir . '/art-infodata.json', zdump_json($doc), false);

    jexit(['ok' => true, 'cmd' => 'model-calls', 'out' => $outDir,
        'cases' => array_map(function ($c) {
            return ['label' => $c['label'], 'bytes' => $c['bytes'], 'raw_keys' => $c['raw_keys']];
        }, $results)], 0);
}

if ($opts['cmd'] === 'model-calls') {
    runModelCalls($opts);
}

fwrite(STDERR, "未知命令
");
exit(2);
