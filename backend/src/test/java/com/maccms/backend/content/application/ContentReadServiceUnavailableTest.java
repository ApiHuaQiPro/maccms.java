package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * content.application 把数据库连接类故障归一为 ApiException(SERVICE_UNAVAILABLE)（MACC-14），调用方不需要 Spring DAO 类型。
 * 判定范围与 GlobalExceptionHandler 原有的 503 分支相同；其他数据访问异常不转换。日志只有类型链，不含 message。
 */
@ExtendWith(OutputCaptureExtension.class)
class ContentReadServiceUnavailableTest {

    private static final String LEAK = "SYNTHETIC-LEAK-0d9e";

    private TypeMapper typeMapper;
    private GroupMapper groupMapper;
    private VodMapper vodMapper;
    private ContentReadService service;

    @BeforeEach
    void setUp() {
        typeMapper = mock(TypeMapper.class);
        groupMapper = mock(GroupMapper.class);
        vodMapper = mock(VodMapper.class);
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(anyInt())).thenReturn(Fixtures.guestGroup());
        service = new ContentReadService(typeMapper, groupMapper, vodMapper, new ContentProperties(true, true));
    }

    private static CannotGetJdbcConnectionException connectionFailure() {
        return new CannotGetJdbcConnectionException("jdbc:mysql://" + LEAK + "-host/db password=" + LEAK,
                new SQLTransientConnectionException(LEAK + " user=synthetic_user"));
    }

    private void assertUnavailable(Runnable call, CapturedOutput output) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo("服务暂时不可用");
                    assertThat(e.fieldErrors()).isEmpty();
                    assertThat(e.getCause()).isNull();
                });
        assertThat(output.getAll()).contains("database unavailable: org.springframework.jdbc"
                + ".CannotGetJdbcConnectionException <- java.sql.SQLTransientConnectionException")
                .doesNotContain(LEAK).doesNotContain("synthetic_user");
    }

    @Test
    void connectionFailureInEachMethodBecomesServiceUnavailable(CapturedOutput output) {
        when(typeMapper.selectEnabledVodTypes()).thenThrow(connectionFailure());
        assertUnavailable(service::listCategories, output);
        assertUnavailable(() -> service.listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1, 20)), output);
        when(vodMapper.selectPublishedDetail(anyLong())).thenThrow(connectionFailure());
        assertUnavailable(() -> service.getVideo(1), output);
    }

    /**
     * 真实连接失败时 MyBatis 抛出的是 MyBatisSystemException ← PersistenceException ← CannotGetJdbcConnectionException
     * （本地用不可达地址实测的类型链）；外层不是 DataAccessResourceFailureException，要沿 cause 链判定。
     */
    @Test
    void connectionFailureWrappedByMyBatisIsServiceUnavailable(CapturedOutput output) {
        RuntimeException wrapped = new org.mybatis.spring.MyBatisSystemException(LEAK,
                new org.apache.ibatis.exceptions.PersistenceException(LEAK, connectionFailure()));
        doThrow(wrapped).when(typeMapper).selectEnabledVodTypes();
        assertThatThrownBy(service::listCategories).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(output.getAll()).contains("database unavailable: org.mybatis.spring.MyBatisSystemException"
                + " <- org.apache.ibatis.exceptions.PersistenceException"
                + " <- org.springframework.jdbc.CannotGetJdbcConnectionException").doesNotContain(LEAK);
    }

    @Test
    void myBatisWrappedNonConnectionFailureIsNotConverted() {
        RuntimeException wrapped = new org.mybatis.spring.MyBatisSystemException(LEAK,
                new org.apache.ibatis.exceptions.PersistenceException(LEAK, new IllegalStateException(LEAK)));
        doThrow(wrapped).when(typeMapper).selectEnabledVodTypes();
        assertThatThrownBy(service::listCategories).isSameAs(wrapped);
    }

    /** MACC-25：cause 链判定与 MACC-22 的执行时限判定并存，被 MyBatis 包装的 3024 仍按时限超时记录。 */
    @Test
    void myBatisWrappedServerTimeoutIsStillLoggedAsTimeout(CapturedOutput output) {
        RuntimeException wrapped = new org.mybatis.spring.MyBatisSystemException(LEAK,
                new org.apache.ibatis.exceptions.PersistenceException(LEAK, new SQLException(LEAK, "HY000", 3024)));
        doThrow(wrapped).when(typeMapper).selectEnabledVodTypes();
        assertThatThrownBy(service::listCategories).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(output.getAll()).contains("database statement timeout: org.mybatis.spring.MyBatisSystemException")
                .doesNotContain("database unavailable").doesNotContain(LEAK);
    }

    /** cause 链超过 8 层才出现的连接类故障不再识别，与 MACC-14 原实现的深度上限相同。 */
    @Test
    void connectionFailureBeyondCauseDepthLimitIsNotConverted() {
        RuntimeException e = connectionFailure();
        for (int i = 0; i < 8; i++) {
            e = new org.mybatis.spring.MyBatisSystemException(LEAK, e);
        }
        RuntimeException tooDeep = e;
        doThrow(tooDeep).when(typeMapper).selectEnabledVodTypes();
        assertThatThrownBy(service::listCategories).isSameAs(tooDeep);
    }

    @Test
    void transientFailuresAreAlsoServiceUnavailable() {
        for (DataAccessException e : new DataAccessException[] {
                new QueryTimeoutException(LEAK), new org.springframework.dao.TransientDataAccessResourceException(LEAK)}) {
            doThrow(e).when(typeMapper).selectEnabledVodTypes();
            assertThatThrownBy(service::listCategories).isInstanceOfSatisfying(ApiException.class,
                    a -> assertThat(a.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }
    }

    @Test
    void serverTimeoutCodesAreServiceUnavailableWithTypeChainOnlyLog(CapturedOutput output) {
        // 3024 与 1028（排序阶段中止）按错误码识别，不区分是否带新条件；message 不进日志
        for (int code : new int[] {3024, 1028}) {
            doThrow(new UncategorizedSQLException(LEAK, "SELECT " + LEAK, new SQLException(LEAK, "HY000", code)))
                    .when(typeMapper).selectEnabledVodTypes();
            assertThatThrownBy(service::listCategories).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                assertThat(e.getMessage()).isEqualTo("服务暂时不可用");
                assertThat(e.getCause()).isNull();
            });
        }
        assertThat(output.getAll()).contains("database statement timeout: org.springframework.jdbc"
                + ".UncategorizedSQLException <- java.sql.SQLException").doesNotContain(LEAK);
    }

    @Test
    void otherDataAccessFailuresAreNotConverted() {
        for (DataAccessException e : new DataAccessException[] {
                new BadSqlGrammarException(LEAK, "SELECT 1", new SQLException(LEAK)),
                new RecoverableDataAccessException(LEAK)}) {
            doThrow(e).when(typeMapper).selectEnabledVodTypes();
            assertThatThrownBy(service::listCategories).isSameAs(e);
        }
    }

    @Test
    void businessErrorsPassThroughUnchanged() {
        assertThatThrownBy(() -> service.listVideos(new VideoListQuery(999, VideoSort.TIME, true, 1, 20)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND));
        Consumer<RuntimeException> rethrown = e -> {
            when(vodMapper.selectPublishedDetail(anyLong())).thenThrow(e);
            assertThatThrownBy(() -> service.getVideo(1)).isSameAs(e);
        };
        rethrown.accept(new IllegalStateException(LEAK));
    }
}
