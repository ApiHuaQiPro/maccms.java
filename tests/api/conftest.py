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
    config.addinivalue_line(
        "markers", "install_wizard: 安装向导（API-030）用例；缺少安装模式/向导前置实例时整条 BLOCKED"
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
# COLLECT_FETCH_FAILED / COLLECT_PARSE_FAILED 的固定文案（契约 x-error-codes；PHP lang 原文，不附 URL 与响应片段）。
# 用途：把「环境侧不可达」与「实现拒绝」分开（OUT-002 的控制样本归因）。
MSG_COLLECT_FETCH_FAILED = "连接API接口失败，通常为服务器网络不稳定、IP被封、禁用相关函数！"  # lang(model/collect/get_html_err)
MSG_COLLECT_PARSE_FAILED_XML = "XML格式不正确，不支持采集"   # lang(model/collect/xml_err)
MSG_COLLECT_PARSE_FAILED_JSON = "JSON格式不正确，不支持采集"  # lang(model/collect/json_err)
MSG_COLLECT_PARSE_FAILED = (MSG_COLLECT_PARSE_FAILED_XML, MSG_COLLECT_PARSE_FAILED_JSON)


# ───────────────────────────── 共享断言（第 2 轮修订新增） ─────────────────────────────
# 以下工具都在 tests/api/ 内，供 APPK-2 用例复用；只做断言与参数构造，不发起任何额外请求。

MSG_ACCESS_DENIED = "非法访问"                        # INSTALL_ACCESS_DENIED，lang(install/access_denied)
MSG_PLEASE_TEST = "请先点击测试数据库连接！"           # INSTALL_NOT_TESTED，lang(install/please_test_connect)
MSG_ENV_FAILED = "环境检测未通过，不能进行下一步操作！"  # 第 2/3 步 lang(install/environment_failed)；第 5 步为 MSG_INIT_ERR
MSG_INIT_ERR = "初始失败！"                            # 第 5 步 lang(install/init_err)
MSG_ADMIN_REQUIRED = "请填写管理账号和密码！"           # INSTALL_ADMIN_REQUIRED
MSG_TABLES_EXIST = "已存在数据表"                      # INSTALL_TABLES_EXIST（文案在 openapi 里未逐字给出，仅作辅助识别）
MSG_LOCKED_PATH = "install.lock"                      # INSTALL_LOCKED 固定文本的第一段

# 其他 INSTALL_* 的固定文案（用来判定「响应停在哪个分支」，避免把前置分支当目标分支）
OTHER_INSTALL_MSGS = (MSG_PLEASE_TEST, MSG_ENV_FAILED, MSG_INIT_ERR, MSG_ADMIN_REQUIRED, MSG_TABLES_EXIST)

# 安装向导里已安装的固定文本：出现即说明 install.lock 存在（INS-SEC-2）
LOCKED_PAGE_MARKERS = (MSG_LOCKED_PATH, "如需重新安装请删除")

# 排除 install.lock 提示后的正文，才用于逐分支判定（INSTALL_LOCKED 页本身就是 "install.lock" 字样）
def without_locked_page(text):
    """把 INSTALL_LOCKED 固定文本里的 install.lock 字样屏蔽后返回小写正文。"""
    return (text or "").replace("install.lock", "<install-lock-path>").lower()


def branch_markers(text):
    """返回该响应命中的 INSTALL_* 固定文案列表（不含 INSTALL_LOCKED 的路径字样）。"""
    plain = without_locked_page(text)
    return [m for m in OTHER_INSTALL_MSGS if m in plain]


def is_locked_page(resp):
    """是否为 INSTALL_LOCKED 固定纯文本页（INS-SEC-2）。"""
    return all(marker in resp.text for marker in LOCKED_PAGE_MARKERS)


def is_admin_failure(resp):
    """是否为后台 ADM-2 动作类失败体（HTTP 200 + application/json + code=0）。"""
    if resp.status_code != 200:
        return False
    if "application/json" not in resp.headers.get("Content-Type", ""):
        return False
    try:
        body = resp.json()
    except ValueError:
        return False
    return isinstance(body, dict) and body.get("code") == 0


def assert_no_success(resp, success_markers):
    """拒绝/失败响应不得含成功标记（防止把成功页当拒绝）。"""
    for marker in success_markers:
        assert marker not in resp.text, "拒绝响应里出现了成功标记：%r" % marker


# ── OUT-1-9 错误体去敏：按每个输入动态生成禁止片段 ──────────────────────────────

def _ipv6_forms(text):
    """IPv6 字面量在响应里可能的等价写法。

    第 2 次复核 REV-I-05：不能只调大小写和方括号。同一个地址在响应里可能被**规范化**成
    压缩形式（fc00::1）或**完全展开**形式（fc00:0000:...:0001，含前导零），两种都要按等价类禁用。
    覆盖：原样 / 去 [] / 加 [] / 大小写 / ipaddress 规范化压缩形式）与展开形式 /
    内嵌 IPv4（::ffff:a.b.c.d）的十六进制与点分写法。纯离线计算，不发请求。
    """
    import ipaddress

    forms = set()
    if not text:
        return forms
    bare = text.strip().strip("[]")
    # 1) 原样与括号、大小写变体
    for base in (text, bare):
        forms.add(base)
        forms.add(base.lower())
        forms.add(base.upper())
    forms.add("[" + bare + "]")
    forms.add("[" + bare.lower() + "]")
    # 2) 解析后的规范形式（压缩写法）与完全展开写法
    addr = None
    try:
        addr = ipaddress.IPv6Address(bare)
    except ValueError:
        try:
            addr = ipaddress.IPv6Address(bare.split("%")[0])  # 去掉 zone id（fe80::1%eth0）
        except ValueError:
            addr = None
    if addr is not None:
        for rendered in (
            str(addr),                  # 压缩形式 fc00::1
            addr.exploded,              # 展开形式 fc00:0000:0000:0000:0000:0000:0000:0001
            str(addr).upper(),
            addr.exploded.upper(),
            "[" + str(addr) + "]",
            "[" + addr.exploded + "]",
        ):
            forms.add(rendered)
        # 3) 内嵌 IPv4 的两种写法（::ffff:7f00:1 与 ::ffff:127.0.0.1）
        embedded = _ipv6_embedded_ipv4(str(addr))
        if embedded:
            forms |= _ipv4_forms(embedded)
            forms.add("::ffff:" + embedded)
            forms.add("[" + "::ffff:" + embedded + "]")
    return {f for f in forms if len(f) >= 3}


def _ipv4_forms(dotted):
    """同一个 IPv4 的等价书写：点分十进制、整数、十六进制、八进制、简写。"""
    try:
        parts = [int(p, 10) for p in dotted.split(".")]
    except ValueError:
        return set()
    if len(parts) != 4 or any(p < 0 or p > 255 for p in parts):
        return set()
    value = (parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]
    forms = {
        dotted,
        str(value),
        "0x%08x" % value,
        "0x%x" % value,
        ".".join("%d" % p for p in parts),
        "%d.%d.%d.%d" % tuple(parts),
    }
    # 八进制写法：每段前面加 0（至少第一段非零时成立）
    if parts[0] < 8:
        forms.add("0%d.%d.%d.%d" % tuple(parts))
    # 末段合写的简写（a.b.c.d -> a.b.(c<<8|d)）
    forms.add("%d.%d.%d" % (parts[0], parts[1], (parts[2] << 8) | parts[3]))
    return {f for f in forms if f and f not in ("0", "00")}


def _ipv4_from_any(host):
    """把主机名的任意 IPv4 写法（点分/整数/十六进制/八进制/三段简写）还原成点分十进制，取不到则返回 None。"""
    import socket

    try:
        return socket.inet_ntoa(socket.inet_aton(host))
    except OSError:
        return None


def _ipv6_embedded_ipv4(host):
    """从 ::a.b.c.d / ::ffff:a.b.c.d / 64:ff9b::a.b.c.d 里取出内嵌的 IPv4，取不到返回 None。"""
    import ipaddress

    try:
        addr = ipaddress.ip_address(host.strip("[]"))
    except ValueError:
        return None
    mapped = getattr(addr, "ipv4_mapped", None)
    if mapped:
        return str(mapped)
    packed = addr.packed
    # ::a.b.c.d（IPv4 兼容）
    if packed[:12] == bytes(12):
        return str(ipaddress.IPv4Address(packed[12:]))
    # 64:ff9b::/96（NAT64）
    if packed[:12] == bytes.fromhex("0064ff9b0000000000000000"):
        return str(ipaddress.IPv4Address(packed[12:]))
    return None


def assert_error_body_redacted(text, url, label=""):
    """OUT-1-9：错误体（提示文案）不得含 URL、主机、解析地址、端口。

    - 完整 URL；
    - 主机名及其任意子域写法；
    - IP 字面量的等价书写（IPv4 点分/整数/十六进制/八进制/简写，IPv6 原样与去括号）；
    - 端口号（仅在 URL 显式给出端口时）。
    受响应格式限制（JSON 转义），只检查原文与 JSON 反转义后的文本，不做跨编码猜测。
    """
    from urllib.parse import urlsplit, unquote

    raw = text or ""
    decoded = unquote(raw)
    hosts = []
    port = None
    try:
        parts = urlsplit(url)
        host = parts.hostname or ""
        port = parts.port
    except ValueError:
        host = ""
    if host:
        hosts.append(host)

    forbidden = set()
    forbidden.add(url)
    forbidden.add(url.rstrip("/"))
    for suspect_host in hosts:
        forbidden.add(suspect_host)
        if ":" in suspect_host:  # IPv6
            forbidden |= _ipv6_forms(suspect_host)
            # IPv4 映射/兼容的 IPv6（::ffff:a.b.c.d、64:ff9b::/96）解析结果就是那个 IPv4，
            # 响应里写成点分十进制同样是「解析地址」泄露
            embedded = _ipv6_embedded_ipv4(suspect_host)
            if embedded:
                forbidden |= _ipv4_forms(embedded)
                forbidden.add(embedded)
        else:
            forbidden |= _ipv4_forms(suspect_host)
            # 输入是 http://2130706433/ 或 http://0x7f000001/ 这类非常规写法时，
            # 响应里出现的等价点分地址（127.0.0.1）同样是「解析地址」泄露，必须按等价类一并禁用
            dotted = _ipv4_from_any(suspect_host)
            if dotted:
                forbidden |= _ipv4_forms(dotted)
                forbidden.add(dotted)
            # 主机名的后缀写法（example.com 出现在 a.example.com 里）
            forbidden.add("." + suspect_host)
    if port is not None:
        forbidden.add(":%d" % port)
        forbidden.add(":%d/" % port)

    for _hay in (raw, decoded):
        lowered = _hay.lower()
        for frag in forbidden:
            assert frag.lower() not in lowered, (
                "[%s] OUT-1-9：错误体泄露了目标信息 %r（输入 %r）" % (label, frag, url)
            )


# ── SCH-1：响应形态判定（不返回 400） ──────────────────────────────────────────

def assert_not_transport_rejected(resp, label=""):
    """SCH-1：应用不返回 HTTP 400；也不能 5xx（除个别固定页）。"""
    assert resp.status_code not in (400, 413, 414, 415, 422, 431), (
        "[%s] SCH-1 要求不返回传输层拒绝，实际 %s" % (label, resp.status_code)
    )
