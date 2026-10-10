<?php
namespace app\common\model;
use think\Db;
use think\Config;

class Addon extends Base {

    // onlineData() 已删除：原为向被 base64 混淆的无效地址发起的在线插件请求（死代码），仅支持本地插件

    public function localData()
    {
        $results = glob(ADDON_PATH.'*');

        $list = [];
        foreach ($results as $addonDir) {
            if ($addonDir === '.' or $addonDir === '..')
                continue;

            if (!is_dir($addonDir))
                continue;

            $info_file = $addonDir .DS. 'info.ini';
            if (!is_file($info_file))
                continue;
            $name = str_replace(ADDON_PATH,'',$addonDir);
            $info = Config::parse($info_file, '', "addon-info-{$name}");
            $info['url'] = mac_url($name);
            $info['install'] = 1;
            $list[$name] = $info;
        }
        return ['code'=>1,'list'=>$list];
    }



}