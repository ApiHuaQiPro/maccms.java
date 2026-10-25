<?php
/*
 * B12（MACC-36，承接 B05 D-B05-8）：旧版"手机模板与语言"选择的 PHP 黄金样本（契约见 docs/architecture/21-b12-admin-framework.md
 * 第 12 节）。逐个用例以独立的 php 进程执行 template_select_golden_child.php（旧 common/behavior/Init.php 用 define 定义常量，一个进程
 * 只能跑一次），记录：是否手机版 MAC_MOB、模板目录、html 目录、广告目录、页面里的 {$maccms.path_tpl}/{$maccms.path_ads}、站群并入后的
 * site 配置、生效的语言（default_lang）与 lang() 取到的一段文字。
 *
 * 不连接数据库，不读写仓库外文件，不修改旧 PHP 文件；只读加载 thinkphp/ 与 application/。
 * 用法（仓库根目录）：
 *   D:/Tools/php/8.0/php.exe backend/src/test/php/template_select_golden.php > backend/src/test/resources/site/template-select/php80-golden.json
 */

error_reporting(E_ALL);
$repo = realpath(__DIR__ . '/../../../..');
$php = PHP_BINARY;

const MOBILE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148';
const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36';

function cases()
{
    $c = [];
    $base = ['template_dir' => 'default', 'html_dir' => 'html', 'ads_dir' => 'ads', 'mob_template_dir' => 'mobile',
        'mob_html_dir' => 'mhtml', 'mob_ads_dir' => 'mads', 'site_url' => 'www.example.test', 'site_wapurl' => 'm.example.test',
        'install_dir' => '/'];
    // UA 关键字（不分大小写，任意位置）
    foreach (['nokia', 'SONY', 'Ericsson', 'mot', 'samsung', 'sgh', 'lg', 'philips', 'panasonic', 'alcatel', 'lenovo',
                 'meizu', 'cldc', 'midp', 'iPhone', 'WAP', 'Mobile', 'android', 'MotionBlur', 'Glgl', 'smotion'] as $kw) {
        $c[] = ['label' => 'ua-' . $kw, 'site' => $base + ['mob_status' => '2'], 'ua' => 'X ' . $kw . ' Y', 'host' => 'www.example.test'];
    }
    foreach (['', 'Mozilla/5.0', DESKTOP_UA, 'curl/8.0', 'Googlebot/2.1'] as $i => $ua) {
        $c[] = ['label' => 'ua-desktop-' . $i, 'site' => $base + ['mob_status' => '2'], 'ua' => $ua, 'host' => 'www.example.test'];
    }
    $c[] = ['label' => 'ua-missing', 'site' => $base + ['mob_status' => '2'], 'ua' => null, 'host' => 'www.example.test'];
    // mob_status 0/1/2 × 手机/桌面 × 主机名是否为 site_wapurl
    foreach (['0', '1', '2', '', '3', 'abc', '01', ' 2'] as $st) {
        foreach (['mobile' => MOBILE_UA, 'desktop' => DESKTOP_UA] as $kind => $ua) {
            foreach (['www.example.test', 'm.example.test', 'M.Example.Test'] as $host) {
                $c[] = ['label' => 'status-' . ($st === '' ? 'empty' : str_replace(' ', '_', $st)) . '-' . $kind . '-' . $host,
                    'site' => $base + ['mob_status' => $st], 'ua' => $ua, 'host' => $host];
            }
        }
    }
    // mob_status 是整数
    $c[] = ['label' => 'status-int-2', 'site' => ['mob_status' => 2] + $base, 'ua' => MOBILE_UA, 'host' => 'www.example.test'];
    $c[] = ['label' => 'status-int-1-wap', 'site' => ['mob_status' => 1] + $base, 'ua' => MOBILE_UA, 'host' => 'm.example.test'];
    // 手机模板目录为空 / no：非站群时不回退（照样用空目录）
    $c[] = ['label' => 'mobdir-empty', 'site' => ['mob_template_dir' => ''] + $base + ['mob_status' => '2'], 'ua' => MOBILE_UA,
        'host' => 'www.example.test'];
    $c[] = ['label' => 'mobdir-no', 'site' => ['mob_template_dir' => 'no'] + $base + ['mob_status' => '2'], 'ua' => MOBILE_UA,
        'host' => 'www.example.test'];
    // site_wapurl 为空时主机名永远不等
    $c[] = ['label' => 'wapurl-empty', 'site' => ['site_wapurl' => ''] + $base + ['mob_status' => '1'], 'ua' => MOBILE_UA,
        'host' => ''];
    // 站群（extra/domain.php）：主机名命中时并入该站点的配置，手机切换不再生效
    $domain = ['sub.example.test' => ['site_url' => 'sub.example.test', 'site_name' => '分站', 'template_dir' => 'sub',
        'html_dir' => 'shtml', 'ads_dir' => 'sads', 'mob_template_dir' => 'no', 'map_dir' => '']];
    foreach (['2' => MOBILE_UA, '1' => MOBILE_UA, '0' => DESKTOP_UA] as $st => $ua) {
        $c[] = ['label' => 'domain-' . $st, 'site' => $base + ['mob_status' => (string)$st], 'ua' => $ua, 'host' => 'sub.example.test',
            'domain' => $domain];
    }
    $c[] = ['label' => 'domain-miss', 'site' => $base + ['mob_status' => '2'], 'ua' => MOBILE_UA, 'host' => 'SUB.example.test',
        'domain' => $domain];
    $c[] = ['label' => 'domain-mobdir-empty', 'site' => $base + ['mob_status' => '2'], 'ua' => MOBILE_UA,
        'host' => 'sub.example.test', 'domain' => ['sub.example.test' => ['template_dir' => 'sub', 'mob_template_dir' => '']]];
    $c[] = ['label' => 'domain-mobdir-set', 'site' => $base + ['mob_status' => '2'], 'ua' => MOBILE_UA,
        'host' => 'sub.example.test', 'domain' => ['sub.example.test' => ['template_dir' => 'sub', 'mob_template_dir' => 'm2']]];
    $c[] = ['label' => 'domain-not-array', 'site' => $base + ['mob_status' => '2'], 'ua' => MOBILE_UA, 'host' => 'www.example.test',
        'domain' => ''];
    // 子目录安装
    $c[] = ['label' => 'install-dir', 'site' => ['install_dir' => '/cms/'] + $base + ['mob_status' => '2'], 'ua' => MOBILE_UA,
        'host' => 'www.example.test'];
    // 语言：app.lang
    foreach (['zh-cn', 'zh-tw', '', 'en-us', 'ZH-TW'] as $lang) {
        $c[] = ['label' => 'lang-' . ($lang === '' ? 'empty' : $lang), 'site' => $base + ['mob_status' => '0'], 'ua' => DESKTOP_UA,
            'host' => 'www.example.test', 'lang' => $lang];
    }
    return $c;
}

$out = ['generator' => 'backend/src/test/php/template_select_golden.php', 'php' => PHP_VERSION, 'cases' => []];
foreach (cases() as $case) {
    $env = getenv();
    $env['GOLDEN_ARGS'] = json_encode($case, JSON_UNESCAPED_UNICODE);
    $cmd = escapeshellarg($php) . ' ' . escapeshellarg(__DIR__ . '/template_select_golden_child.php');
    $proc = proc_open($cmd, [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $repo, $env, ['bypass_shell' => true]);
    $stdout = stream_get_contents($pipes[1]);
    $stderr = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($proc);
    $r = json_decode($stdout, true);
    if (!is_array($r)) {
        fwrite(STDERR, $case['label'] . ': ' . $stdout . $stderr . "\n");
        exit(1);
    }
    $out['cases'][] = ['label' => $case['label'], 'input' => $case, 'result' => $r];
}
echo json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), "\n";
