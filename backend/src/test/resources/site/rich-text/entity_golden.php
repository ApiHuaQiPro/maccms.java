<?php
/**
 * 普通文本字符引用黄金向量核对脚本（MACC-12）。只在本机手工运行，不属于 Maven 构建，不连接数据库与网络。
 *
 * 用法（工作目录任意）：
 *   php backend/src/test/resources/site/rich-text/entity_golden.php           核对：逐条比较向量中的 php_* 字段
 *   php backend/src/test/resources/site/rich-text/entity_golden.php --update  用实际输出重写 php_* 字段（编写新向量时用）
 *
 * 做法：从仓库的 application/common.php 原样取出 mac_filter_xss 的源码并 eval，不改动、不 include 整个旧代码。
 *   php_filter_xss = mac_filter_xss(input)                               旧后台按该函数存库的字段（如分类，application/common/model/Type.php:248-269）
 *   php_plain      = trim(strip_tags(input))                             录入文本经 mac_filter_xss 去标签、去首尾空白后的样子
 *   php_decode     = html_entity_decode(input, ENT_QUOTES | ENT_HTML5, 'UTF-8')  PHP 的严格解码（只认带分号的引用），作参照
 */

$vectorsFile = __DIR__ . '/entity-vectors.json';
$repoRoot = dirname(__DIR__, 6);
$commonPhp = $repoRoot . '/application/common.php';

function golden_load_function($commonPhp, $name)
{
    $tokens = token_get_all(file_get_contents($commonPhp));
    $count = count($tokens);
    for ($i = 0; $i < $count; $i++) {
        if (!is_array($tokens[$i]) || $tokens[$i][0] !== T_FUNCTION) {
            continue;
        }
        $j = $i + 1;
        while (is_array($tokens[$j]) && $tokens[$j][0] === T_WHITESPACE) {
            $j++;
        }
        if (!is_array($tokens[$j]) || $tokens[$j][1] !== $name) {
            continue;
        }
        $src = '';
        $depth = 0;
        $opened = false;
        for ($k = $i; $k < $count; $k++) {
            $t = $tokens[$k];
            $text = is_array($t) ? $t[1] : $t;
            $src .= $text;
            if ($text === '{' || (is_array($t) && in_array($t[0], [T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES], true))) {
                $depth++;
                $opened = true;
            } elseif ($text === '}') {
                $depth--;
                if ($opened && $depth === 0) {
                    return $src;
                }
            }
        }
    }
    return '';
}

eval(golden_load_function($commonPhp, 'mac_filter_xss'));
if (!function_exists('mac_filter_xss')) {
    fwrite(STDERR, "未能从 application/common.php 取出 mac_filter_xss\n");
    exit(2);
}

$data = json_decode(file_get_contents($vectorsFile), true);
if (!is_array($data)) {
    fwrite(STDERR, "无法解析 $vectorsFile\n");
    exit(2);
}

$update = in_array('--update', $argv, true);
$failures = 0;
foreach ($data['vectors'] as $i => $v) {
    $actual = [
        'php_filter_xss' => mac_filter_xss($v['input']),
        'php_plain' => trim(strip_tags($v['input'])),
        'php_decode' => html_entity_decode($v['input'], ENT_QUOTES | ENT_HTML5, 'UTF-8'),
    ];
    foreach ($actual as $key => $value) {
        if ($update) {
            $data['vectors'][$i][$key] = $value;
        } elseif (!array_key_exists($key, $v) || $v[$key] !== $value) {
            $failures++;
            fwrite(STDERR, "不一致：{$v['id']} {$key}\n");
        }
    }
}

if ($update) {
    $json = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    file_put_contents($vectorsFile, $json . "\n");
    echo "已更新 " . count($data['vectors']) . " 条向量（PHP " . PHP_VERSION . "）\n";
    exit(0);
}
echo ($failures === 0 ? "全部一致" : "不一致 $failures 处") . "：" . count($data['vectors']) . " 条向量，PHP " . PHP_VERSION . "\n";
exit($failures === 0 ? 0 : 1);
