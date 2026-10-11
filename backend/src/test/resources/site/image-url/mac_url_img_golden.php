<?php
/**
 * mac_url_img 黄金向量核对脚本（MACC-11）。只在本机手工运行，不属于 Maven 构建，不连接数据库与网络。
 *
 * 用法（工作目录任意）：
 *   php backend/src/test/resources/site/image-url/mac_url_img_golden.php          核对：逐条比较向量中的 php / php_warning
 *   php backend/src/test/resources/site/image-url/mac_url_img_golden.php --print  打印实际输出（编写新向量时用）
 *
 * 做法：从仓库的 application/common.php 原样取出 mac_url_img 与 mac_filter_xss 两个函数的源码并 eval，
 * 不改动、不 include 整个旧代码；$GLOBALS['config']['upload'] 只填向量文件中的合成配置。
 * MAC_PATH 是常量（旧值为 site.install_dir，application/common/behavior/Init.php:48），每个取值在独立子进程中定义。
 * 旧代码运行在 ThinkPHP 中时，E_WARNING 会被 think\Error 转为异常（thinkphp/library/think/Error.php:28,71）；
 * 本脚本没有 ThinkPHP，只记录是否出现警告（php_warning）。
 */

$vectorsFile = __DIR__ . '/mac-url-img-vectors.json';
$repoRoot = dirname(__DIR__, 6);
$commonPhp = $repoRoot . '/application/common.php';

function golden_load_functions($commonPhp, array $names)
{
    $tokens = token_get_all(file_get_contents($commonPhp));
    $code = '';
    $count = count($tokens);
    for ($i = 0; $i < $count; $i++) {
        if (!is_array($tokens[$i]) || $tokens[$i][0] !== T_FUNCTION) {
            continue;
        }
        $j = $i + 1;
        while (is_array($tokens[$j]) && $tokens[$j][0] === T_WHITESPACE) {
            $j++;
        }
        if (!is_array($tokens[$j]) || !in_array($tokens[$j][1], $names, true)) {
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
                    break;
                }
            }
        }
        $code .= $src . "\n";
        $i = $k;
    }
    return $code;
}

function golden_worker($commonPhp, $installDir, array $data)
{
    define('MAC_PATH', $installDir);
    eval(golden_load_functions($commonPhp, ['mac_url_img', 'mac_filter_xss']));
    if (!function_exists('mac_url_img') || !function_exists('mac_filter_xss')) {
        fwrite(STDERR, "未能从 application/common.php 取出函数\n");
        exit(2);
    }
    $out = [];
    foreach ($data['vectors'] as $v) {
        $cfg = $data['configs'][$v['config']]['php'];
        if ($cfg['install_dir'] !== $installDir) {
            continue;
        }
        $GLOBALS['config'] = ['upload' => [
            'protocol' => $cfg['protocol'],
            'mode' => $cfg['mode'],
            'remoteurl' => $cfg['remoteurl'],
            'img_key' => $cfg['img_key'],
            'img_api' => $cfg['img_api'],
        ]];
        $warned = false;
        set_error_handler(function ($no) use (&$warned) {
            if ($no & (E_WARNING | E_NOTICE)) {
                $warned = true;
            }
            return true;
        });
        $result = mac_url_img($v['input']);
        restore_error_handler();
        $out[$v['id']] = ['php' => $result, 'php_warning' => $warned];
    }
    echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

$data = json_decode(file_get_contents($vectorsFile), true);
if (!is_array($data)) {
    fwrite(STDERR, "向量文件不是合法 JSON\n");
    exit(2);
}

if (isset($argv[1]) && $argv[1] === '--worker') {
    golden_worker($commonPhp, $argv[2], $data);
    exit(0);
}

$installDirs = [];
foreach ($data['configs'] as $cfg) {
    $installDirs[$cfg['php']['install_dir']] = true;
}
$actual = [];
foreach (array_keys($installDirs) as $dir) {
    $cmd = escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg(__FILE__) . ' --worker ' . escapeshellarg($dir);
    $json = shell_exec($cmd);
    $part = json_decode((string)$json, true);
    if (!is_array($part)) {
        fwrite(STDERR, "子进程输出无法解析（install_dir 取值见向量文件）\n");
        exit(2);
    }
    $actual += $part;
}

$print = isset($argv[1]) && $argv[1] === '--print';
$failures = 0;
foreach ($data['vectors'] as $v) {
    $got = $actual[$v['id']];
    if ($print) {
        echo $v['id'], "\t", json_encode($got['php'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            $got['php_warning'] ? "\t(warning)" : '', "\n";
        continue;
    }
    $expectWarning = isset($v['php_warning']) ? $v['php_warning'] : false;
    if ($got['php'] !== $v['php'] || $got['php_warning'] !== $expectWarning) {
        $failures++;
        echo 'MISMATCH ', $v['id'], ': php=', json_encode($got['php'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            ' warning=', $got['php_warning'] ? 'true' : 'false', "\n";
    }
}
if (!$print) {
    echo 'PHP ', PHP_VERSION, ': ', count($data['vectors']) - $failures, '/', count($data['vectors']), " 条一致\n";
}
exit($failures === 0 ? 0 : 1);
