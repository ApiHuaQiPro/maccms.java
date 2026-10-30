-- 随机排序第 3 阶段（ID 很大）：删除基础行，批量行的 vod_id 再加 1000000（1010010..1030010）；全表 MIN/MAX = 1010010/1030010。
-- 旧版候选下标为 0..20000，全部小于最小 ID，一个也命中不了。在第 2 阶段之后执行。
DELETE FROM mac_vod WHERE vod_id < 1000;
UPDATE mac_vod SET vod_id = vod_id + 1000000 ORDER BY vod_id DESC;
