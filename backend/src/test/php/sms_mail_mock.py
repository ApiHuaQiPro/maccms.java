"""
B25（短信与邮件验证码）PHP 黄金样本用的本机模拟服务：由 sms_mail_golden.php 启动，只监听 127.0.0.1 的随机端口，充当 php-cgi 子进程里
旧代码 curl 的代理（子进程环境变量 http_proxy / https_proxy 指向这里），不访问任何外部网络。

  - 明文代理请求（请求行是绝对地址，如阿里云 POST http://dysmsapi.aliyuncs.com/）：直接按计划回复；
  - CONNECT 隧道（腾讯云 https://yun.tim.qq.com:443）：回 200 后用启动时生成的一次性自签名证书（openssl 命令行，写在 --dir 指定的临时目录）
    终止 TLS，读出里面的请求再按计划回复。旧代码关闭了证书校验（VERIFYPEER/VERIFYHOST = 0），所以自签名证书可以握手。

计划：父进程每个用例开始前写 <dir>/plan.json：{"<host>": {"action": "respond", "status": 200, "body": "..."}
  | {"action": "close"}（读完请求后直接断开，curl 得到 Empty reply）| {"action": "hang", "seconds": 7}（读完请求后不回复，超过旧代码的 5 秒超时）}。
  没有计划的主机回 599。
记录：每个收到的请求追加一行 JSON 到 <dir>/log.jsonl：方法、完整目标地址、主机、路径、原始查询串、请求头（原始行与小写名/值对，保持顺序）、
  请求体（base64）、是否经 CONNECT 隧道（以及 CONNECT 本身的请求头）、执行的动作。父进程用完即删。
停止：父进程关闭本进程的 stdin 即退出（不需要按进程名结束任何进程）。

用法：python sms_mail_mock.py --dir <临时目录> --openssl <openssl.exe>   启动后在 stdout 打印一行 "PORT <端口>"。
"""

import argparse
import base64
import json
import os
import socket
import socketserver
import ssl
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit

LOCK = threading.Lock()
ARGS = None
SSL_CTX = None


class Reader:
    """在 socket 上按行/按长度读取（自己维护缓冲，便于在 CONNECT 之后换成 TLS socket）。"""

    def __init__(self, sock):
        self.sock = sock
        self.buf = b''

    def _fill(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            return False
        self.buf += chunk
        return True

    def head(self):
        """读到空行为止，返回 (请求行, [原始头部行])；连接提前关闭时返回 (None, [])。"""
        while b'\r\n\r\n' not in self.buf:
            if not self._fill():
                return None, []
        raw, self.buf = self.buf.split(b'\r\n\r\n', 1)
        lines = raw.decode('latin-1').split('\r\n')
        return lines[0], lines[1:]

    def body(self, n):
        while len(self.buf) < n:
            if not self._fill():
                break
        data, self.buf = self.buf[:n], self.buf[n:]
        return data


def header_pairs(lines):
    out = []
    for line in lines:
        if ':' in line:
            name, value = line.split(':', 1)
            out.append([name.strip().lower(), value.strip()])
        else:
            out.append([line.strip().lower(), None])
    return out


def header_value(pairs, name):
    for k, v in pairs:
        if k == name:
            return v
    return None


def load_plan():
    try:
        with open(os.path.join(ARGS.dir, 'plan.json'), 'r', encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def log(rec):
    with LOCK:
        with open(os.path.join(ARGS.dir, 'log.jsonl'), 'a', encoding='utf-8') as f:
            f.write(json.dumps(rec, ensure_ascii=False) + '\n')


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(30)
        reader = Reader(sock)
        try:
            line, lines = reader.head()
        except OSError:
            return
        if line is None:
            return
        parts = line.split(' ', 2)
        if len(parts) != 3:
            return
        method, target, _ = parts
        connect = None
        conn = sock
        if method == 'CONNECT':
            host, _, port = target.rpartition(':')
            connect = {'target': target, 'headers': header_pairs(lines), 'rawHeaders': lines}
            sock.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
            try:
                conn = SSL_CTX.wrap_socket(sock, server_side=True)
            except (ssl.SSLError, OSError) as e:
                log({'method': 'CONNECT', 'url': None, 'host': host, 'tunnel': True, 'connect': connect,
                     'tlsError': str(e), 'action': 'tls-failed'})
                return
            reader = Reader(conn)
            line, lines = reader.head()
            if line is None:
                return
            method, path, _ = line.split(' ', 2)
            url = 'https://' + host + ('' if port == '443' else ':' + port) + path
        else:
            url = target
        pairs = header_pairs(lines)
        if (header_value(pairs, 'expect') or '').lower() == '100-continue':
            conn.sendall(b'HTTP/1.1 100 Continue\r\n\r\n')
        length = int(header_value(pairs, 'content-length') or '0')
        body = reader.body(length) if length > 0 else b''
        u = urlsplit(url)
        host = u.hostname or ''
        plan = load_plan().get(host)
        action = plan['action'] if plan else 'unplanned'
        log({'method': method, 'url': url, 'host': host, 'path': u.path, 'query': u.query, 'tunnel': connect is not None,
             'connect': connect, 'requestLine': line, 'rawHeaders': lines, 'headers': pairs,
             'body': base64.b64encode(body).decode('ascii'), 'action': action})
        try:
            if action == 'respond':
                payload = plan.get('body', '').encode('utf-8')
                status = int(plan.get('status', 200))
                head = ('HTTP/1.1 %d X\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n'
                        % (status, plan.get('contentType', 'application/json;charset=utf-8'), len(payload)))
                conn.sendall(head.encode('latin-1') + payload)
            elif action == 'close':
                pass
            elif action == 'hang':
                time.sleep(float(plan.get('seconds', 7)))
            else:
                payload = b'unplanned'
                conn.sendall(b'HTTP/1.1 599 X\r\nContent-Length: 9\r\nConnection: close\r\n\r\n' + payload)
        except OSError:
            pass
        try:
            conn.close()
        except OSError:
            pass


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


def make_cert(directory, openssl):
    cert = os.path.join(directory, 'cert.pem')
    key = os.path.join(directory, 'key.pem')
    env = dict(os.environ)
    # Git 自带的 openssl 是 MSYS 程序，会把 "/CN=..." 当成路径改写；关掉参数转换
    env['MSYS2_ARG_CONV_EXCL'] = '*'
    env['MSYS_NO_PATHCONV'] = '1'
    subprocess.run([openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert,
                    '-days', '2', '-subj', '/CN=yun.tim.qq.com'], check=True, capture_output=True, env=env)
    return cert, key


def main():
    global ARGS, SSL_CTX
    p = argparse.ArgumentParser()
    p.add_argument('--dir', required=True)
    p.add_argument('--openssl', required=True)
    ARGS = p.parse_args()
    os.makedirs(ARGS.dir, exist_ok=True)
    cert, key = make_cert(ARGS.dir, ARGS.openssl)
    SSL_CTX = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    SSL_CTX.load_cert_chain(cert, key)
    server = Server(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    sys.stdout.write('PORT %d\n' % server.server_address[1])
    sys.stdout.flush()
    # 父进程关闭 stdin 即退出
    sys.stdin.read()
    os._exit(0)


if __name__ == '__main__':
    main()
