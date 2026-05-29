-- 清空 am 库业务数据，仅保留 sys_config / sys_config_audit。
-- 随后由 schema.sql 补齐表结构（含 git_commit_file）与 monitor_target 字典种子。
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE analysis_report_user;
TRUNCATE TABLE analysis_report;
TRUNCATE TABLE ai_session_audit;
TRUNCATE TABLE ai_session_message_blob_link;
TRUNCATE TABLE ai_session_message_blob;
TRUNCATE TABLE ai_session_message;
TRUNCATE TABLE ai_session_event;
TRUNCATE TABLE ai_session;
TRUNCATE TABLE usage_report;
TRUNCATE TABLE git_commit_file;
TRUNCATE TABLE git_commit;
TRUNCATE TABLE daily_summary;
TRUNCATE TABLE work_session;
TRUNCATE TABLE agent_heartbeat;
TRUNCATE TABLE agent_alert;
TRUNCATE TABLE agent_nonce;
TRUNCATE TABLE agent_device;
TRUNCATE TABLE agent_version;
TRUNCATE TABLE project_mapping;
TRUNCATE TABLE employee;
TRUNCATE TABLE monitor_target;

SET FOREIGN_KEY_CHECKS = 1;
