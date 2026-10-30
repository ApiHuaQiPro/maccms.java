<?php
/*
 * B26（MACC-49）PHP 黄金专用：旧版依赖的 login\ThinkOauth（extend/login）在仓库与备份中都缺失，这里给一个只供黄金子进程使用的替身，
 * 让真实的 index/controller/User.php::logincallback 与 index/event/LoginEvent.php 能够执行到写库为止。
 * 替身不发任何网络请求：换令牌、用户信息、openid 都按父进程给的用例计划（$GLOBALS['golden_oauth']）返回。
 * 只模拟旧代码用到的方法：getInstance($type[, $token])、getRequestCodeURL()、getAccessToken($code, $extend)、call($api)、openid()。
 */

namespace login;

class ThinkOauth
{
    private $type;

    public static function getInstance($type, $token = null)
    {
        if (!in_array($type, ['qq', 'weixin'], true)) {
            throw new \Exception('不支持的登录方式：' . $type);
        }
        return new self($type);
    }

    private function __construct($type)
    {
        $this->type = $type;
    }

    public function getRequestCodeURL()
    {
        return 'https://oauth.example.test/' . $this->type . '/authorize';
    }

    /** 计划 token=false 时按"换取失败"（旧控制器只判断 is_array）。 */
    public function getAccessToken($code, $extend)
    {
        $plan = $GLOBALS['golden_oauth'];
        if (empty($plan['token'])) {
            return false;
        }
        return ['access_token' => 'golden-token', 'expires_in' => 7200, 'openid' => $plan['openid']];
    }

    /** 用户信息：计划给的数组原样返回（QQ 的 ret/msg/nickname，微信的 errcode/errmsg/nickname）。 */
    public function call($api)
    {
        $GLOBALS['golden_oauth_calls'][] = $api;
        return $GLOBALS['golden_oauth']['info'];
    }

    public function openid()
    {
        return $GLOBALS['golden_oauth']['openid'];
    }
}
