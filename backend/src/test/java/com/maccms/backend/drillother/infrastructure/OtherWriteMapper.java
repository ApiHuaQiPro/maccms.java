package com.maccms.backend.drillother.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import org.apache.ibatis.annotations.Param;

/** 演练用写 Mapper：模块 {@code drillother} 故意没有任何登记，它的写入必须被拒绝（B07）。 */
@WriteMapper
public interface OtherWriteMapper {

    int addPoints(@Param("userId") long userId, @Param("points") int points);
}
