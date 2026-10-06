-- Application storage only; does not create the source business tables.
-- MySQL 5.7+/8.0 DDL. local profile and tests create this table automatically;
-- production schema is prepared and reviewed by the DBA.
CREATE TABLE IF NOT EXISTS diligence_document (
 kind VARCHAR(40) NOT NULL,
 document_id VARCHAR(128) NOT NULL,
 owner_id VARCHAR(128) NOT NULL,
 payload MEDIUMTEXT NOT NULL,
 PRIMARY KEY (kind, document_id),
 KEY diligence_document_owner (kind, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
