package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 直接构造 VideoListQuery（其他模块不经 parse）也必须遵守与 parse 相同的取值范围。 */
class VideoListQueryTest {

    @Test
    void constructorAcceptsContractBoundaries() {
        assertThat(new VideoListQuery(null, VideoSort.TIME, true, 1, 1).categoryId()).as("null = 不筛选").isNull();
        assertThat(new VideoListQuery(1, VideoSort.ID, false, 1, 1).offset()).isZero();
        assertThat(new VideoListQuery(65535, VideoSort.HITS, true, 1000, 100).offset()).isEqualTo(99_900L);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "0,      TIME, 1,    20,  categoryId",
            "-1,     TIME, 1,    20,  categoryId",
            "65536,  TIME, 1,    20,  categoryId",
            "NULL,   NULL, 1,    20,  sort",
            "NULL,   TIME, 0,    20,  page",
            "NULL,   TIME, -1,   20,  page",
            "NULL,   TIME, 1001, 20,  page",
            "NULL,   TIME, 1,    0,   pageSize",
            "NULL,   TIME, 1,    101, pageSize",
            "NULL,   TIME, 1,    -5,  pageSize",
    })
    void constructorRejectsOutOfRangeWithParseErrorSemantics(Integer categoryId, VideoSort sort, int page,
            int pageSize, String field) {
        assertThatThrownBy(() -> new VideoListQuery(categoryId, sort, true, page, pageSize))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(e.fieldErrors()).extracting(ApiException.FieldError::field).containsExactly(field);
                });
    }

    @Test
    void constructorAndParseReportTheSameError() {
        ApiException fromParse = catchApi(() -> VideoListQuery.parse(null, null, null, null, "101"));
        ApiException fromConstructor = catchApi(() -> new VideoListQuery(null, VideoSort.TIME, true, 1, 101));
        assertThat(fromConstructor.getMessage()).isEqualTo(fromParse.getMessage());
        assertThat(fromConstructor.fieldErrors()).isEqualTo(fromParse.fieldErrors());
    }

    @Test
    void parseBehaviourIsUnchanged() {
        assertThat(VideoListQuery.parse(null, null, null, null, null))
                .isEqualTo(new VideoListQuery(null, VideoSort.TIME, true, 1, 20));
        assertThat(VideoListQuery.parse("", "", "", "", "")).isEqualTo(new VideoListQuery(null, VideoSort.TIME, true, 1, 20));
        assertThat(VideoListQuery.parse("6", "hits_month", "asc", "3", "100"))
                .isEqualTo(new VideoListQuery(6, VideoSort.HITS_MONTH, false, 3, 100));
        assertThat(catchApi(() -> VideoListQuery.parse("65536", null, null, null, null)).fieldErrors())
                .extracting(ApiException.FieldError::field).containsExactly("categoryId");
    }

    private static ApiException catchApi(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("应抛出 ApiException");
    }
}
