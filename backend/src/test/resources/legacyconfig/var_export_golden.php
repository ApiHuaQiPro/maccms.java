<?php
/**
 * 旧配置只读层黄金向量脚本（B02 / MACC-26）。只在本机手工运行，不属于 Maven 构建，不连接数据库与网络。
 *
 * 用法（工作目录任意）：
 *   php backend/src/test/resources/legacyconfig/var_export_golden.php            核对：逐个文件比较 legacy-config-vectors.json
 *   php backend/src/test/resources/legacyconfig/var_export_golden.php --write    重写 cases/generated_var_export.php 与向量文件
 *
 * 做法：用 PHP 自己 include 每个配置文件（仓库内 application/extra/*.php、application/route.php，以及 cases/ 下的合成文件），
 * 把返回值转成带类型的 JSON（区分整数键与字符串键、整数与浮点、保留顺序），与 Java 的 VarExportParser 结果逐项比较
 * （LegacyConfigGoldenVectorsTest）。cases/generated_var_export.php 由本脚本用 var_export 写出（与 mac_arr2file 相同的格式），
 * 覆盖转义、Unicode、换行、数值边界与字符串键规范化。全部为合成值或仓库内已有的开发配置，不含线上配置。
 */

$here = __DIR__;
$repoRoot = dirname($here, 5);
$vectorsFile = $here . '/legacy-config-vectors.json';
$generated = $here . '/cases/generated_var_export.php';
$write = in_array('--write', $argv, true);

function golden_fixture()
{
    return array(
        'quote' => "it's \\ back\\slash \\' end\\",
        'unicode' => "中文 😀 é",
        'newlines' => "a\nb\r\nc\td",
        'php_tags' => '<?php ?> */ // # /* $x {$y} "dq"',
        'empty' => '',
        'zero_str' => '0',
        'int' => 42,
        'neg' => -7,
        'zero' => 0,
        'max' => PHP_INT_MAX,
        'min_plus_one' => -PHP_INT_MAX,
        'floats' => array(0.1, -0.0, 1.0, 1.5e-7, 1.0e25, -2.5, 3.0E+300, 123456789.125, 5e-324),
        'bools' => array(true, false),
        'null' => null,
        'keys' => array(
            '1' => 'int-from-string',
            '01' => 'string',
            '-0' => 'string',
            '-5' => 'int-from-negative-string',
            ' 1' => 'space',
            '1.0' => 'float-like-string',
            '9223372036854775807' => 'max-int-key',
            '9223372036854775808' => 'overflow-stays-string',
            7 => 'seven',
            'x' => 'x',
        ),
        'nested' => array('a' => array('b' => array('c' => array()))),
        'list' => array('x', 'y', 'z'),
    );
}

function golden_dump($v)
{
    if (is_array($v)) {
        $items = array();
        foreach ($v as $k => $x) {
            $items[] = array(is_int($k) ? array('int', (string)$k) : array('string', $k), golden_dump($x));
        }
        return array('array', $items);
    }
    if (is_string($v)) {
        return array('string', $v);
    }
    if (is_int($v)) {
        return array('int', (string)$v);
    }
    if (is_float($v)) {
        return array('float', var_export($v, true));
    }
    if (is_bool($v)) {
        return array('bool', $v);
    }
    if ($v === null) {
        return array('null');
    }
    fwrite(STDERR, "不支持的类型\n");
    exit(2);
}

/** 文件内容把 CRLF 换成 LF 后的 SHA-256：仓库按 LF 存储，Windows 检出时为 CRLF，两种检出得到同一个值。 */
function golden_hash($path)
{
    return hash('sha256', str_replace("\r\n", "\n", file_get_contents($path)));
}

if ($write) {
    // 与 mac_arr2file（application/common.php:126-139）相同的写法
    file_put_contents($generated, "<?php\nreturn " . var_export(golden_fixture(), true) . ";");
}

$files = array();
foreach (glob($repoRoot . '/application/extra/*.php') as $f) {
    $files[] = 'application/extra/' . basename($f);
}
$files[] = 'application/route.php';
$cases = array();
foreach (glob($here . '/cases/*.php') as $f) {
    $cases[] = basename($f);
}
sort($files);
sort($cases);

$out = array(
    'meta' => array(
        'description' => 'B02 旧配置只读层黄金向量：PHP include 每个文件后的带类型结果。value 为 [类型, 值]，数组为 [array, [[键, 值]...]]，'
            . '键为 [int|string, 文本]，int 用十进制字符串，float 用 var_export 文本。由同目录 var_export_golden.php 生成与核对。',
        'php' => PHP_VERSION,
    ),
    'files' => array(),
);
foreach ($files as $rel) {
    $path = $repoRoot . '/' . $rel;
    $out['files'][] = array('path' => $rel, 'sha256_lf' => golden_hash($path), 'value' => golden_dump(include $path));
}
foreach ($cases as $name) {
    $path = $here . '/cases/' . $name;
    $out['files'][] = array('path' => 'cases/' . $name, 'sha256_lf' => golden_hash($path),
        'value' => golden_dump(include $path));
}

$json = json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n";
if ($json === false) {
    fwrite(STDERR, "json_encode 失败\n");
    exit(2);
}

if ($write) {
    file_put_contents($vectorsFile, $json);
    echo "已写入 " . count($out['files']) . " 个文件的向量（PHP " . PHP_VERSION . "）\n";
    exit(0);
}

$expected = json_decode(file_get_contents($vectorsFile), true);
$ok = true;
foreach ($out['files'] as $i => $f) {
    $e = isset($expected['files'][$i]) ? $expected['files'][$i] : null;
    if ($e === null || $e['path'] !== $f['path'] || $e['sha256_lf'] !== $f['sha256_lf'] || $e['value'] !== $f['value']) {
        echo "不一致：" . $f['path'] . "\n";
        $ok = false;
    }
}
if (count($expected['files']) !== count($out['files'])) {
    echo "文件数不一致\n";
    $ok = false;
}
echo ($ok ? "全部一致" : "存在不一致") . "（PHP " . PHP_VERSION . "，" . count($out['files']) . " 个文件）\n";
exit($ok ? 0 : 1);
