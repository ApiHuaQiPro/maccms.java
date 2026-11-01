"""APPK-2 接口自动化公共夹具（只写阶段建好，T3 才执行）。

凭据只从环境变量读取，变量名以产品仓 MULTICA.md §5 为准：
  APP_API_TOKEN           默认角色（有 x-permission 的管理员会话令牌）
  APP_API_TOKEN_READONLY  只读 / 低权限角色（无目标 x-permission 的管理员）
  APP_API_TOKEN_OTHER     与默认角色同级的另一个管理员（横向越权对照）
变量未登记或值为空 → BLOCKED（pytest.exit 3），不要求任何人把令牌贴到 Issue。

本契约的后台鉴权与通用 API 不同（见 docs/api/APPK-2/openapi.yaml「通用约定」ADM-1/ADM-3）：
  - 后台地址前缀 = 配置 maccms.admin.entry（默认 /admin.php），由 --admin-entry 传入；
  - 网关顺序：控制器不存在 404 → 来源校验 SRC-1（403 提示页 / 200 JSON）→ 未登录 302 → 无权限 200 业务失败体；
  - Ajax 判定：X-Requested-With: XMLHttpRequest 或 _ajax 为 PHP 真值。
因此默认请求都带 Ajax 头与同源来源头，便于断言 200 业务失败体而不是跳转页。
"""

import os

import pytest
import requests
from dotenv import load_dotenv

load_dotenv()

# 角色 -> 管理员会话令牌的环境变量名（以 MULTICA.md §5 为准）
TOKEN_VARS = {
    "default": "APP_API_TOKEN",
    "readonly": "APP_API_TOKEN_READONLY",
    "other": "APP_API_TOKEN_OTHER",
}

# 备注：本契约的会话是后台会话 cookie（ADM-1），令牌以 Bearer 或会话 Cookie 提交都由实现决定；
# 脚本只按 ADM-1「会话」语义注入，具体头名由 ADMIN_AUTH_HEADER / ADMIN_SESSION_COOKIE 环境变量给出。


def require_env(name):
    value = os.environ.get(name, "")
    if not value:
        pytest.exit(
            "BLOCKED：环境变量 %s 未配置（请人工配置，勿把值贴到 Issue）" % name,
            returncode=3,
        )
    return value


def mask(value):
    """日志/断言失败信息里对凭据打码。"""
    if not value:
        return "<empty>"
    text = str(value)
    if len(text) <= 8:
        return "***"
    return text[:4] + "***" + text[-2:]


def pytest_addoption(parser):
    parser.addoption(
        "--admin-entry",
        action="store",
        default=None,
        help="后台入口前缀（配置 maccms.admin.entry），默认取 MACCMS_ADMIN_ENTRY 环境变量，再默认 /admin.php",
    )
    parser.addoption(
        "--install-entry",
        action="store",
        default="/install.php",
        help="安装向导入口（API-030），默认 /install.php",
    )


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "input_limit: TRN-1 传输上限相关断言（构造大请求）"
    )


@pytest.fixture(scope="session")
def admin_entry(pytestconfig):
    return pytestconfig.getoption("--admin-entry") or os.environ.get(
        "MACCMS_ADMIN_ENTRY", "/admin.php"
    )


@pytest.fixture(scope="session")
def install_entry(pytestconfig):
    return pytestconfig.getoption("--install-entry")


@pytest.fixture(scope="session", autouse=True)
def _require_test_env(base_url):
    """缺测试环境地址 → BLOCKED。地址必须是 G2.5 判定行写明的测试环境，不得是本机或生产。"""
    if not base_url:
        pytest.exit(
            "BLOCKED：未传 --base-url（必须是 G2.5 回传的测试环境地址）", returncode=3
        )


class Api:
    """一个会话。ajax=True 时带 ADM-2 的 Ajax 头，便于断言 200 业务失败体。"""

    def __init__(self, base_url, token=None, ajax=True, source_headers=True):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.token = token
        self._token_masked = mask(token)
        if ajax:
            self.session.headers["X-Requested-With"] = "XMLHttpRequest"
        if source_headers:
            # ADM-3 SRC-1：只发 Sec-Fetch-Site（与真实浏览器同源请求一致），不发 Origin/Referer，
            # 这样 SRC-2（三个来源头都缺失）的用例可以另行构造。
            self.session.headers["Sec-Fetch-Site"] = "same-origin"
        if token:
            header = os.environ.get("ADMIN_AUTH_HEADER", "")
            cookie = os.environ.get("ADMIN_SESSION_COOKIE", "")
            if header and cookie:
                pytest.exit(
                    "BLOCKED：ADMIN_AUTH_HEADER 与 ADMIN_SESSION_COOKIE 同时配置，"
                    "无法确定后台会话注入方式；请人工只保留一种",
                    returncode=3,
                )
            if header:
                self.session.headers[header] = token
            elif cookie:
                self.session.cookies.set(cookie, token)
            else:
                # 未配置注入方式时按 Bearer 注入，并在用例失败信息里打码；
                # 若实现使用会话 cookie，请人工配置 ADMIN_SESSION_COOKIE（变量名登记在 §5）。
                self.session.headers["Authorization"] = "Bearer " + token

    def call(self, method, path, **kwargs):
        kwargs.setdefault("timeout", 30)
        kwargs.setdefault("allow_redirects", False)
        return self.session.request(method, self.base_url + path, **kwargs)

    def url(self, path):
        return self.base_url + path


@pytest.fixture
def api_as(base_url):
    """api_as("readonly") / api_as("other") / api_as(None)（未登录） / api_as("default", ajax=False)。"""

    def _open(role="default", ajax=True, source_headers=True):
        token = None if role is None else require_env(TOKEN_VARS[role])
        return Api(base_url, token=token, ajax=ajax, source_headers=source_headers)

    return _open


@pytest.fixture
def admin_url(admin_entry):
    """把契约里的后台相对路径拼到后台入口上。"""

    def _join(path):
        return admin_entry.rstrip("/") + "/" + path.lstrip("/")

    return _join


def assert_no_internal_leak(text, extra_forbidden=()):
    """错误响应不得含堆栈、SQL、内部路径（各用例的通用断言）。"""
    lowered = (text or "").lower()
    forbidden = (
        "traceback",
        "sqlexception",
        "java.lang.",
        "org.springframework",
        "at com.",
        "stack trace",
        "select ",
        "insert into",
        "mysql",
        "jdbc:",
    ) + tuple(extra_forbidden)
    for word in forbidden:
        assert word not in lowered, "错误响应疑似泄露内部细节：%r" % word


def assert_json_error(resp, wire_msg=None):
    """后台动作类失败体（ADM-2 / Error）：HTTP 200、application/json、code=0、有 msg。"""
    assert resp.status_code == 200, "期望 HTTP 200 业务失败体，实际 %s" % resp.status_code
    ctype = resp.headers.get("Content-Type", "")
    assert "application/json" in ctype, "期望 JSON 失败体，实际 Content-Type=%r" % ctype
    body = resp.json()
    assert body.get("code") == 0, "期望 code=0，实际 %r" % body.get("code")
    assert isinstance(body.get("msg"), str) and body["msg"], "失败体缺少 msg"
    if wire_msg is not None:
        assert body["msg"] == wire_msg, "msg 不符：期望 %r，实际 %r" % (
            wire_msg,
            body["msg"],
        )
    assert_no_internal_leak(resp.text)
    return body


def assert_admin_success(resp):
    """后台动作类成功体（LegacyJson）：HTTP 200、JSON、code=1。"""
    assert resp.status_code == 200, "期望 HTTP 200，实际 %s" % resp.status_code
    assert "application/json" in resp.headers.get("Content-Type", ""), (
        "期望 JSON，实际 Content-Type=%r" % resp.headers.get("Content-Type")
    )
    body = resp.json()
    assert body.get("code") == 1, "期望 code=1，实际 %r" % body.get("code")
    return body


# 契约里写明的固定文案（lang 语言包默认中文；逐字见 openapi.yaml x-error-codes 的 wire_msg）
MSG_PARAM_ERR = "参数错误"
MSG_PERMISSION_DENIED = "您没有权限访问此页面"
MSG_ILLEGAL_REQUEST = "非法请求"
MSG_COLLECT_URL_REJECTED = "采集链接有误或不能为本地链接"
