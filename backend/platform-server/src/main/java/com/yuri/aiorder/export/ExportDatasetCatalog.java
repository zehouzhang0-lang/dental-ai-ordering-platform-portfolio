package com.yuri.aiorder.export;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 可导出数据集目录（{@code export_dataset}）。
 *
 * <p>「哪些数据算敏感」是**配置不是代码**：客户点名了客户信息、地址、账单、价格四类，
 * 但这个清单以后大概率还会动。改 {@code sensitivity} 一列即可让某个数据集开始或停止走审批，
 * 不需要改 Java 重新发版——与 A/B/C/F 各批次的做法一致。
 *
 * <p>取数 SQL 仍在 {@link ExportDataProvider} 里，因此目录与实现必须一一对应；
 * 两边对不上时启动就会被 {@link ExportDataProvider#validateAgainstCatalog} 拦下，
 * 而不是等到有人点导出才报错。
 */
@Component
public class ExportDatasetCatalog {

    public static final String SENSITIVITY_SENSITIVE = "SENSITIVE";
    public static final String SENSITIVITY_NORMAL = "NORMAL";

    public static final String PERMISSION_EXECUTE = "export:execute";
    public static final String PERMISSION_SENSITIVE = "export:sensitive";
    public static final String PERMISSION_APPROVE = "export:approve";
    public static final String PERMISSION_AUDIT_READ = "export:audit:read";

    private final JdbcClient jdbcClient;

    public ExportDatasetCatalog(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Dataset> listActive() {
        return jdbcClient.sql("""
                        SELECT dataset_code, display_name, sensitivity, permission_code,
                               field_list, description
                        FROM export_dataset
                        WHERE status = 'ACTIVE'
                        ORDER BY sensitivity DESC, dataset_code
                        """)
                .query(ExportDatasetCatalog::mapDataset)
                .list();
    }

    public Dataset require(String datasetCode) {
        String code = datasetCode == null ? "" : datasetCode.trim().toUpperCase(Locale.ROOT);
        return jdbcClient.sql("""
                        SELECT dataset_code, display_name, sensitivity, permission_code,
                               field_list, description
                        FROM export_dataset
                        WHERE dataset_code = :datasetCode
                          AND status = 'ACTIVE'
                        """)
                .param("datasetCode", code)
                .query(ExportDatasetCatalog::mapDataset)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unknown export dataset: " + datasetCode));
    }

    private static Dataset mapDataset(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Dataset(
                rs.getString("dataset_code"),
                rs.getString("display_name"),
                rs.getString("sensitivity"),
                rs.getString("permission_code"),
                rs.getString("field_list"),
                rs.getString("description"));
    }

    public record Dataset(
            String datasetCode,
            String displayName,
            String sensitivity,
            String permissionCode,
            String fieldList,
            String description) {

        public boolean sensitive() {
            return SENSITIVITY_SENSITIVE.equalsIgnoreCase(sensitivity);
        }

        public List<String> fields() {
            return Arrays.stream(fieldList.split(","))
                    .map(String::trim)
                    .filter(field -> !field.isEmpty())
                    .toList();
        }
    }
}
