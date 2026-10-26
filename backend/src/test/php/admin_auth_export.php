<?php
/*
 * B12（MACC-36）：导出旧后台的菜单与权限码 application/admin/common/auth.php，以及 application/admin/controller 下各控制器的公开动作，
 * 供 AdminPermissionsGoldenTest 逐项比对新版的 AdminPermissions（docs/architecture/21-b12-admin-framework.md 第 3 节）。
 *
 * auth.php 里的名称是 lang('menu/…') 的调用：这里把 lang() 换成"返回 @键名"，所以导出的是语言包键（或直接写在 auth.php 里的文字）。
 * 控制器的公开动作按源码里的 public function 取（去掉构造函数与 Base 的 check_auth/_cache_clear），控制器名与动作名转小写。
 * 只读仓库文件；输出到标准输出。
 *
 * 用法（仓库根目录）：
 *   D:/Tools/php/8.0/php.exe backend/src/test/php/admin_auth_export.php > backend/src/test/resources/admin/auth-php.json
 */

error_reporting(E_ALL);
$repo = realpath(__DIR__ . '/../../../..');

function lang($key)
{
    return '@' . $key;
}

$menus = include $repo . '/application/admin/common/auth.php';
$groups = [];
foreach ($menus as $gk => $g) {
    $entries = [];
    foreach ($g['sub'] as $sk => $s) {
        $entries[] = ['key' => $sk, 'show' => $s['show'], 'name' => $s['name'], 'controller' => $s['controller'],
            'action' => $s['action'], 'param' => isset($s['param']) ? $s['param'] : null];
    }
    $groups[] = ['key' => $gk, 'name' => $g['name'], 'icon' => $g['icon'], 'entries' => $entries];
}

$controllers = [];
foreach (glob($repo . '/application/admin/controller/*.php') as $file) {
    $name = strtolower(basename($file, '.php'));
    if ($name === 'base') {
        continue;
    }
    preg_match_all('/public\s+function\s+(\w+)\s*\(/', file_get_contents($file), $m);
    $actions = [];
    foreach ($m[1] as $a) {
        if ($a === '__construct') {
            continue;
        }
        $actions[] = strtolower($a);
    }
    $controllers[$name] = $actions;
}
ksort($controllers);

echo json_encode(['generator' => 'backend/src/test/php/admin_auth_export.php', 'source' => 'application/admin/common/auth.php',
        'sha256' => hash('sha256', file_get_contents($repo . '/application/admin/common/auth.php')),
        'groups' => $groups, 'controllers' => $controllers],
        JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), "\n";
