package com.maccms.backend.admin.web;

import com.maccms.backend.admin.infrastructure.AdminMapper;
import com.maccms.backend.admin.infrastructure.AdminRow;
import com.maccms.backend.admin.infrastructure.AdminWriteMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存里的 mac_admin 替身（只用于不连库的 MVC 测试；真实 MySQL 的比较规则由 AdminGoldenMySqlIT 覆盖）：账号按不分大小写、忽略尾部空格比较，
 * 其余与写 Mapper 的 SQL 语义相同（条件更新、匹配行数）。
 */
final class FakeAdminTable implements AdminMapper, AdminWriteMapper {

    final Map<Long, AdminRow> rows = new TreeMap<>();
    final AtomicLong nextId = new AtomicLong(100);
    final List<String> writes = new ArrayList<>();

    synchronized void put(long id, String name, String pwd, int status, String auth) {
        rows.put(id, new AdminRow(id, name, pwd, status, auth, 0, 0, 0, 0, 0));
    }

    synchronized void update(long id, java.util.function.UnaryOperator<AdminRow> f) {
        rows.put(id, f.apply(rows.get(id)));
    }

    private static String norm(String name) {
        return name.replaceAll(" +$", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized AdminRow selectById(long adminId) {
        return rows.get(adminId);
    }

    @Override
    public synchronized AdminRow selectForLogin(String name, String pwd) {
        return rows.values().stream().filter(r -> norm(r.adminName()).equals(norm(name)) && r.adminPwd().equals(pwd)
                && r.adminStatus() == 1).min(Comparator.comparingLong(AdminRow::adminId)).orElse(null);
    }

    @Override
    public synchronized long count(String like) {
        return rows.size();
    }

    @Override
    public synchronized List<AdminRow> list(String like, long offset, int limit) {
        List<AdminRow> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparingLong(AdminRow::adminId).reversed());
        return all.subList((int) Math.min(offset, all.size()), (int) Math.min(offset + limit, all.size()));
    }

    @Override
    public synchronized int recordLogin(long adminId, String pwd, long now, long ip, String random) {
        AdminRow r = rows.get(adminId);
        if (r == null || !r.adminPwd().equals(pwd) || r.adminStatus() != 1) {
            return 0;
        }
        writes.add("login " + adminId + " " + ip + " " + random.length());
        rows.put(adminId, new AdminRow(r.adminId(), r.adminName(), r.adminPwd(), r.adminStatus(), r.adminAuth(), now, ip,
                r.adminLoginNum() + 1, r.adminLoginTime(), r.adminLoginIp()));
        return 1;
    }

    @Override
    public synchronized int insert(String name, String pwd, int status, String auth) {
        long id = nextId.incrementAndGet();
        writes.add("insert " + id + " " + name + " " + pwd + " " + status + " " + auth);
        put(id, name, pwd, status, auth);
        return 1;
    }

    @Override
    public synchronized int update(long adminId, String name, String pwd, Integer status, String auth) {
        writes.add("update " + adminId + " " + name + " " + pwd + " " + status + " " + auth);
        AdminRow r = rows.get(adminId);
        if (r == null) {
            return 0;
        }
        rows.put(adminId, new AdminRow(adminId, name, pwd == null ? r.adminPwd() : pwd,
                status == null ? r.adminStatus() : status, auth, r.adminLoginTime(), r.adminLoginIp(), r.adminLoginNum(),
                r.adminLastLoginTime(), r.adminLastLoginIp()));
        return 1;
    }

    @Override
    public synchronized int delete(List<Long> ids) {
        writes.add("delete " + ids);
        int n = 0;
        for (Long id : ids) {
            n += rows.remove(id) == null ? 0 : 1;
        }
        return n;
    }

    @Override
    public synchronized int setStatus(List<Long> ids, int status) {
        writes.add("status " + ids + " " + status);
        int n = 0;
        for (Long id : ids) {
            AdminRow r = rows.get(id);
            if (r != null) {
                n++;
                rows.put(id, new AdminRow(id, r.adminName(), r.adminPwd(), status, r.adminAuth(), r.adminLoginTime(),
                        r.adminLoginIp(), r.adminLoginNum(), r.adminLastLoginTime(), r.adminLastLoginIp()));
            }
        }
        return n;
    }
}
