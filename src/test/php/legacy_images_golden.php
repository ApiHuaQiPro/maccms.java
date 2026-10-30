<?php
/*
 * B13（MACC-37）上传图片缩略图与文字水印的几何黄金样本（22 文档第 7.10 节；对照 …/content/application/LegacyImages.java）。
 *
 * 直接调用仓库 vendor/ 里的 think-image（旧 common/model/Image.php 的 makethumb、watermark 用的就是它）：
 *   - 缩略图：纯色源图（PNG 与 JPEG）× 若干源尺寸 × 若干目标尺寸 × 六种方式 thumb($w, $h, $type)->save()，记录输出的宽高与
 *     3×3 网格中心点的类别（源图颜色 / 白色 / 透明 / 其他），即缩放、裁剪与留白的位置；
 *   - 水印：白底源图 text('B13', static/font/test.ttf, 20, '#FF0000', 位置 1–9)->save()，记录红色像素的外接矩形；
 *   - 旧 common/model/Image::makethumb 本身：多个尺寸 × 方式 × 水印开关，记录每张缩略图的宽高与 3×3 网格。
 * 只比几何（尺寸、位置），不比像素字节：新版的像素由 Java2D 生成，与 GD 不保证相同（22 文档第 12 节）。
 *
 * 不连接数据库、不访问网络、不修改任何旧 PHP 文件；只在系统临时目录里读写图片（makethumb 部分只加载 ThinkPHP 的基础部分以实例化模型）。需要仓库根目录下的 vendor/（按 AGENTS.md 从备份恢复）。
 * 用法（仓库根目录）：D:/Tools/php/8.0/php.exe src/test/php/legacy_images_golden.php \
 *       > src/test/resources/content/admin/legacy-images-php80-golden.json
 */

error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../php');
if (!is_file($repo . '/vendor/topthink/think-image/src/Image.php')) {
    fwrite(STDERR, "缺少 vendor/topthink/think-image（按 AGENTS.md 从备份恢复 vendor/）\n");
    exit(2);
}
// 只加载 think-image 自己的类（vendor/autoload.php 会顺带执行其他包的引导文件）
spl_autoload_register(function ($class) use ($repo) {
    $base = $repo . '/vendor/topthink/think-image/src/';
    if ($class === 'think\Image') {
        require $base . 'Image.php';
    } elseif (strpos($class, 'think\\image\\') === 0) {
        require $base . 'image/' . str_replace('\\', '/', substr($class, strlen('think\\image\\'))) . '.php';
    }
});

$tmp = sys_get_temp_dir() . '/maccms-images-golden-' . bin2hex(random_bytes(4));
mkdir($tmp);

/** 纯色源图：红色 (220, 30, 30)。 */
function source($tmp, $ext, $w, $h)
{
    $im = imagecreatetruecolor($w, $h);
    imagefill($im, 0, 0, imagecolorallocate($im, 220, 30, 30));
    $path = "$tmp/src-{$w}x{$h}.$ext";
    $ext === 'png' ? imagepng($im, $path) : imagejpeg($im, $path, 95);
    imagedestroy($im);
    return $path;
}

function white($tmp, $w, $h)
{
    $im = imagecreatetruecolor($w, $h);
    imagefill($im, 0, 0, imagecolorallocate($im, 255, 255, 255));
    $path = "$tmp/white-{$w}x{$h}.png";
    imagepng($im, $path);
    imagedestroy($im);
    return $path;
}

function load($path)
{
    $info = getimagesize($path);
    return $info[2] === IMAGETYPE_PNG ? imagecreatefrompng($path) : imagecreatefromjpeg($path);
}

/** 像素类别：S 源图红色、W 白、T 透明（alpha ≥ 64）、O 其他。 */
function kind($im, $x, $y)
{
    $c = imagecolorsforindex($im, imagecolorat($im, $x, $y));
    if ($c['alpha'] >= 64) {
        return 'T';
    }
    if ($c['red'] > 180 && $c['green'] < 90 && $c['blue'] < 90) {
        return 'S';
    }
    if ($c['red'] > 230 && $c['green'] > 230 && $c['blue'] > 230) {
        return 'W';
    }
    return 'O';
}

function grid($im)
{
    $w = imagesx($im);
    $h = imagesy($im);
    $out = '';
    foreach ([1, 3, 5] as $j) {
        foreach ([1, 3, 5] as $i) {
            $out .= kind($im, min($w - 1, intdiv($w * $i, 6)), min($h - 1, intdiv($h * $j, 6)));
        }
    }
    return $out;
}

$thumbs = [];
foreach (['png', 'jpg'] as $ext) {
    foreach ([[640, 360], [360, 640], [100, 80], [300, 300], [1000, 40]] as list($sw, $sh)) {
        $src = source($tmp, $ext, $sw, $sh);
        foreach ([[120, 80], [300, 300], [200, 50]] as list($tw, $th)) {
            foreach ([1, 2, 3, 4, 5, 6] as $type) {
                $out = "$tmp/out.$ext";
                \think\Image::open($src)->thumb($tw, $th, $type)->save($out);
                $im = load($out);
                $thumbs[] = ['ext' => $ext, 'src' => [$sw, $sh], 'target' => [$tw, $th], 'type' => $type,
                    'size' => [imagesx($im), imagesy($im)], 'grid' => grid($im)];
                imagedestroy($im);
                unlink($out);
            }
        }
    }
}

$marks = [];
$font = $repo . '/static/font/test.ttf';
foreach ([[400, 300], [120, 80]] as list($w, $h)) {
    $src = white($tmp, $w, $h);
    foreach (range(1, 9) as $locate) {
        $out = "$tmp/mark.png";
        \think\Image::open($src)->text('B13', $font, 20, '#FF0000', $locate)->save($out);
        $im = load($out);
        $minX = $minY = PHP_INT_MAX;
        $maxX = $maxY = -1;
        for ($y = 0; $y < $h; $y++) {
            for ($x = 0; $x < $w; $x++) {
                $c = imagecolorsforindex($im, imagecolorat($im, $x, $y));
                if ($c['red'] > 200 && $c['green'] < 120 && $c['blue'] < 120) {
                    $minX = min($minX, $x);
                    $minY = min($minY, $y);
                    $maxX = max($maxX, $x);
                    $maxY = max($maxY, $y);
                }
            }
        }
        $marks[] = ['src' => [$w, $h], 'locate' => $locate, 'box' => $maxX < 0 ? null : [$minX, $minY, $maxX, $maxY]];
        imagedestroy($im);
        unlink($out);
    }
}

// 旧 common/model/Image::makethumb 本身（多个尺寸、水印开关）：同一个 think\Image 对象依次 thumb()->save()，开水印时再改成打开刚存的
// 缩略图加水印——所以后面的尺寸是从前一张缩略图（开水印时是加过水印的那张）再缩出来的。只为能实例化模型而加载 ThinkPHP 的基础部分，
// 不连数据库；VENDOR_PATH 指向不存在的目录，不执行 vendor/ 里其他包的引导文件（think\Image 仍由上面的加载器提供）。
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $tmp . '/');
define('VENDOR_PATH', $tmp . '/no-vendor/');
define('RUNTIME_PATH', $tmp . '/runtime/');
require $repo . '/thinkphp/base.php';
require THINK_PATH . 'helper.php';
\think\Loader::addNamespace('app', APP_PATH);
$chains = [];
$site = "$tmp/site";
foreach ([['120x80,300x300', 1, 0], ['300x300,120x80', 1, 0], ['300x300,120x80', 2, 0], ['300x300,200x50,120x80', 3, 0],
             ['300x300,120x80', 1, 1], ['640x640,300x300', 6, 1]] as list($sizes, $type, $mark)) {
    foreach (['png', 'jpg'] as $ext) {
        @mkdir("$site/upload/vod", 0777, true);
        chdir($site);
        $rel = "upload/vod/src.$ext";
        copy(source($tmp, $ext, 640, 360), $rel);
        $config = ['thumb_type' => (string)$type, 'thumb_size' => $sizes, 'watermark' => (string)$mark,
            'watermark_content' => 'B13', 'watermark_font' => $font, 'watermark_size' => '20', 'watermark_color' => '#FF0000'];
        $data = (new \app\common\model\Image())->makethumb($rel, $config, 'vod');
        $outs = [];
        foreach ($data['thumb'] as $t) {
            $im = load($t['file']);
            $outs[] = ['file' => $t['file'], 'size' => [imagesx($im), imagesy($im)], 'grid' => grid($im)];
            imagedestroy($im);
        }
        $chains[] = ['ext' => $ext, 'sizes' => $sizes, 'type' => $type, 'watermark' => $mark, 'thumbs' => $outs];
        chdir($tmp);
        array_map('unlink', glob("$site/upload/vod/*"));
    }
}

chdir($repo);
rm_tree($tmp);
echo json_encode(['generator' => 'backend/src/test/php/legacy_images_golden.php', 'php' => PHP_VERSION,
        'gd' => gd_info()['GD Version'], 'thumbs' => $thumbs, 'marks' => $marks, 'makethumb' => $chains],
        JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";

function rm_tree($dir)
{
    if (!is_dir($dir)) {
        return;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST);
    foreach ($it as $f) {
        $f->isDir() ? rmdir($f->getPathname()) : unlink($f->getPathname());
    }
    rmdir($dir);
}
