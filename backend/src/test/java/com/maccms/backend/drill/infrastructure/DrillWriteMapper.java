package com.maccms.backend.drill.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 演练用写 Mapper（只在测试类路径上，SQL 见 src/test/resources/mapper-write/drill/DrillWriteMapper.xml）。
 * 语句仿照后续写库批次会用到的形态：条件扣减、原子计数、认领、补偿、追加记录、条件插入。
 * {@link #insertGbook}、{@link #deleteUser} 故意不在演练登记表里。
 */
@WriteMapper
public interface DrillWriteMapper {

    int deductPoints(@Param("userId") long userId, @Param("points") int points);

    int addPoints(@Param("userId") long userId, @Param("points") int points);

    /** 按 id 区间加积分：可能命中多行，用来演练"匹配行数超过预期"。 */
    int addPointsInRange(@Param("fromId") long fromId, @Param("toId") long toId, @Param("points") int points);

    int setPoints(@Param("userId") long userId, @Param("points") int points);

    int claimCard(@Param("cardId") long cardId, @Param("userId") long userId, @Param("useTime") long useTime);

    int releaseCard(@Param("cardId") long cardId, @Param("userId") long userId, @Param("useTime") long useTime,
                    @Param("saleStatus") int saleStatus);

    int hit(@Param("vodId") long vodId);

    int insertPlog(PlogInsert row);

    int deletePlog(@Param("plogId") long plogId);

    int insertUlogOnce(@Param("userId") long userId, @Param("mid") int mid, @Param("type") int type,
                       @Param("rid") long rid, @Param("time") long time);

    int insertGbook(@Param("name") String name, @Param("content") String content, @Param("time") long time);

    int deleteUser(@Param("userId") long userId);

    /** {@code mac_plog} 插入参数；{@code plogId} 由 useGeneratedKeys 回填。 */
    final class PlogInsert {
        public Long plogId;
        public long userId;
        public int type;
        public int points;
        public long time;
        public String remarks;

        public PlogInsert(long userId, int type, int points, long time, String remarks) {
            this.userId = userId;
            this.type = type;
            this.points = points;
            this.time = time;
            this.remarks = remarks;
        }
    }
}
