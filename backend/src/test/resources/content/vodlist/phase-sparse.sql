-- 随机排序第 2 阶段（稀疏 ID）：批量行的 vod_id 乘以 10（10010..30010，步长 10），基础行不变；全表 MIN/MAX = 1/30010。
-- PHP 黄金样本与 VideoListGoldenMySqlIT 在同一个一次性库上、第 1 阶段之后执行同一份文件。
UPDATE mac_vod SET vod_id = vod_id * 10 WHERE vod_id > 1000 ORDER BY vod_id DESC;
