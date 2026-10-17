import os

out = []
out.append("""-- B05（MACC-29）旧模板标签专属条件与 SSR tid 的合成数据：PHP 黄金样本（backend/src/test/php/vod_tag_golden.php）与 Java 集成测试
-- （VideoTagGoldenMySqlIT）在各自新建的一次性库 maccms_it_* 中导入 install.sql 表结构后执行同一份文件。只含合成数据，不含真实数据、域名或凭证。
-- 视频分类（type_mid=1）：1 电影、6 动作片(1 下)、7 喜剧片(1 下)、10 动作三级(6 下，多级)、2 电视剧、8 国产剧(2 下)、
-- 9 停用分类(1 下，type_status=0)、12 停用分类下的启用分类(9 下：上级链不完整，新版不可见)、11 游客无列表权限的顶级分类。
-- 旧版黄金样本不按可见性过滤（popedom_filter=0），新版只列可见分类（1、6、7、10、2、8）中的行；Java 对照时按 rowTypes 过滤旧结果。
-- 视频 1..46 已审核、47..48 未审核（排序值最大，若被错误列出会排在最前）。每个排序列取值互不相同；type_id_1 多数与上级一致，
-- 另有若干行故意不一致（用于 tid 的 type_id OR type_id_1 条件）。isend、plot、tv、weekday、rel_vod、level、各时间与点击列按 id 轮换。""")
out.append("DELETE FROM mac_type;")
types = [
    (1, '电影', 0, 1), (6, '动作片', 1, 1), (7, '喜剧片', 1, 1), (10, '动作三级', 6, 1), (2, '电视剧', 0, 1),
    (8, '国产剧', 2, 1), (9, '停用分类', 1, 0), (12, '停用下的分类', 9, 1), (11, '无权限分类', 0, 1),
]
for tid, name, pid, status in types:
    out.append("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_extend) "
               f"VALUES ({tid},'{name}','en{tid}',{tid},1,{pid},{status},'');")
pop = ",".join(f'"{t}":{{"1":"1","2":"2","3":"3","4":"4","5":"5"}}' for t in [1, 6, 7, 10, 2, 8, 9, 12])
out.append(f"UPDATE mac_group SET group_type=',1,6,7,10,2,8,9,12,', group_popedom='{{{pop}}}' WHERE group_id=1;")

cycle = [1, 6, 7, 10, 2, 8, 9, 12, 11]
parent = {1: 0, 6: 1, 7: 1, 10: 6, 2: 0, 8: 2, 9: 1, 12: 9, 11: 0}
# 故意不一致的 type_id_1：id → type_id_1
inconsistent = {5: 6, 10: 2, 14: 1, 20: 8, 23: 10, 31: 7, 37: 2}
tvs = ['', 'CCTV1', '湖南卫视', 'CCTV1', '浙江卫视']
weekdays = ['', '一', '一,三', '五', '日', '三']
rels = ['', '1,2', '3', 'abc', '2,5', 'abc,def']
letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
for i in range(1, 49):
    t = cycle[(i - 1) % len(cycle)]
    t1 = inconsistent.get(i, parent[t])
    status = 0 if i >= 47 else 1
    big = 1 if status == 0 else 0
    level = i % 10
    isend = i % 2
    plot = (i // 2) % 2
    tv = tvs[i % len(tvs)]
    wd = weekdays[i % len(weekdays)]
    rel = rels[i % len(rels)]
    vtime = 1999999000 + i if big else 1700000000 + ((i * 17) % 47) * 1000 + i
    tadd = 1999999000 + i if big else 1690000000 + ((i * 29) % 47) * 3600 + i
    thits = 0 if i % 7 == 0 else (1999999000 + i if big else 1695000000 + ((i * 31) % 47) * 3600 + i)
    hits = 99000 + i if big else ((i * 37) % 47) * 10 + i % 10
    hday = 99000 + i if big else ((i * 11) % 47) + 1
    hweek = 99000 + i if big else ((i * 13) % 47) * 3 + 2
    hmonth = 99000 + i if big else ((i * 19) % 47) * 7 + 3
    letter = letters[i % 26]
    out.append(
        "INSERT INTO mac_vod (vod_id,type_id,type_id_1,vod_name,vod_en,vod_status,vod_letter,vod_level,vod_isend,vod_plot,"
        "vod_tv,vod_weekday,vod_rel_vod,vod_time,vod_time_add,vod_time_hits,vod_hits,vod_hits_day,vod_hits_week,"
        "vod_hits_month,vod_content,vod_play_url,vod_down_url,vod_plot_name,vod_plot_detail) VALUES "
        f"({i},{t},{t1},'标签样本{i}','biaoqian{i}',{status},'{letter}',{level},{isend},{plot},'{tv}','{wd}','{rel}',"
        f"{vtime},{tadd},{thits},{hits},{hday},{hweek},{hmonth},'','','','','');")

path = 'backend/src/test/resources/content/vodtag/seed.sql'
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write("\n".join(out) + "\n")
print(len(out))
