-- Only the disposable profiling instance. No production privilege changes.
GRANT SELECT ON performance_schema.* TO 'matcher'@'%';
GRANT SELECT ON mysql.innodb_index_stats TO 'matcher'@'%';
