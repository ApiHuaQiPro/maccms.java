<?php
/*
 * B12（MACC-36）语言包导出：把仓库的旧语言包 application/lang/<语言>.php 原样导出为新系统读取的 JSON 资源
 * src/main/resources/lang/<语言>.json（契约见 docs/architecture/21-b12-admin-framework.md 第 6 节）。
 *
 * 旧语言包是手写的 PHP 数组字面量（含双引号字符串），不是 var_export 格式，新系统不解析、不执行 PHP；改为构建前用本脚本导出一次，
 * 导出结果与源文件的 SHA-256 一起提交，LegacyLangTest 校验源文件未变（变了就要重新导出）。
 * 键按 ThinkPHP Lang::set 的规则转成小写（array_change_key_case，只改 ASCII 字母），重复键以最后一个为准（PHP 数组字面量的语义）。
 *
 * 用法（仓库根目录）：D:/Tools/php/8.0/php.exe src/test/php/lang_export.php
 * 只读取仓库内的两个语言包文件，只写 src/main/resources/lang/ 下的两个 JSON 文件。
 */

error_reporting(E_ALL);
$repo = realpath(__DIR__ . '/../../../php');
$outDir = dirname($repo) . '/src/main/resources/lang';
if (!is_dir($outDir)) {
    mkdir($outDir, 0777, true);
}
foreach (['zh-cn', 'zh-tw'] as $lang) {
    $src = $repo . '/application/lang/' . $lang . '.php';
    $bytes = file_get_contents($src);
    $array = include $src;
    if (!is_array($array)) {
        fwrite(STDERR, "$lang 不是数组\n");
        exit(1);
    }
    $out = [];
    foreach (array_change_key_case($array, CASE_LOWER) as $k => $v) {
        if (!is_string($v)) {
            fwrite(STDERR, "$lang 的 $k 不是字符串\n");
            exit(1);
        }
        $out[(string)$k] = $v;
    }
    $doc = ['source' => 'application/lang/' . $lang . '.php', 'sha256' => hash('sha256', $bytes),
        'generator' => 'backend/src/test/php/lang_export.php', 'messages' => $out];
    $json = json_encode($doc, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . "\n";
    file_put_contents($outDir . '/' . $lang . '.json', $json);
    fwrite(STDERR, $lang . ': ' . count($out) . " 条\n");
}
