-- PoC ttpos integration: minimal RBAC seed
-- Mirrors ttpos-server-go schema: per-company DB shop{companyUuid}
-- Tables: ttpos_access, ttpos_role, ttpos_role_access, ttpos_staff, ttpos_staff_role
-- Logic reference: main/app/service/notification_helper/permission.go::GetStaffsByAccessPath

CREATE DATABASE IF NOT EXISTS shop1001 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS shop1002 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============== shop1001 ==============
USE shop1001;

CREATE TABLE IF NOT EXISTS ttpos_access (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  name VARCHAR(255) NOT NULL,
  path VARCHAR(255) NOT NULL DEFAULT '',
  api_path VARCHAR(255) NOT NULL DEFAULT '',
  parent_uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  sort INT NOT NULL DEFAULT 0,
  create_time INT NOT NULL DEFAULT 0,
  update_time INT NOT NULL DEFAULT 0,
  delete_time INT NOT NULL DEFAULT 0,
  INDEX idx_path (path),
  INDEX idx_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ttpos_role (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  name VARCHAR(255) NOT NULL,
  sort INT NOT NULL DEFAULT 0,
  create_time INT NOT NULL DEFAULT 0,
  update_time INT NOT NULL DEFAULT 0,
  delete_time INT NOT NULL DEFAULT 0,
  INDEX idx_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ttpos_role_access (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  role_uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  access_uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  create_time INT NOT NULL DEFAULT 0,
  update_time INT NOT NULL DEFAULT 0,
  delete_time INT NOT NULL DEFAULT 0,
  INDEX idx_role (role_uuid),
  INDEX idx_access (access_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ttpos_staff (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  company_uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  username VARCHAR(255) NOT NULL DEFAULT '',
  real_name VARCHAR(255) NOT NULL DEFAULT '',
  is_super TINYINT NOT NULL DEFAULT 0,
  is_disable TINYINT NOT NULL DEFAULT 0,
  create_time INT NOT NULL DEFAULT 0,
  update_time INT NOT NULL DEFAULT 0,
  delete_time INT NOT NULL DEFAULT 0,
  INDEX idx_uuid (uuid),
  INDEX idx_super (is_super)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ttpos_staff_role (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  uuid BIGINT UNSIGNED NOT NULL DEFAULT 0,
  staff_uuid BIGINT NOT NULL DEFAULT 0,
  role_uuid BIGINT NOT NULL DEFAULT 0,
  create_time INT NOT NULL DEFAULT 0,
  update_time INT NOT NULL DEFAULT 0,
  delete_time INT NOT NULL DEFAULT 0,
  INDEX idx_staff (staff_uuid),
  INDEX idx_role (role_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed shop1001:
--   access  : transfer_order_approve (uuid=2001)
--   role    : store_manager (uuid=3001) -> access 2001
--   staffs  : Alice (uuid=10011, store_manager), Bob (uuid=10012, is_super=1), Eve (uuid=10013, no role)
INSERT INTO ttpos_access (uuid, name, path) VALUES
  (2001, '调拨单审批', 'transfer_order_approve'),
  (2002, '采购单审批', 'purchase_order_approve');

INSERT INTO ttpos_role (uuid, name) VALUES
  (3001, '店长'),
  (3002, '区域经理');

INSERT INTO ttpos_role_access (uuid, role_uuid, access_uuid) VALUES
  (4001, 3001, 2001),
  (4002, 3002, 2001),
  (4003, 3002, 2002);

INSERT INTO ttpos_staff (uuid, company_uuid, username, real_name, is_super) VALUES
  (10011, 1001, 'alice',  'Alice (店长)',    0),
  (10012, 1001, 'bob',    'Bob (超管)',      1),
  (10013, 1001, 'eve',    'Eve (无审批权限)', 0);

INSERT INTO ttpos_staff_role (uuid, staff_uuid, role_uuid) VALUES
  (5001, 10011, 3001);

-- ============== shop1002 ==============
USE shop1002;

CREATE TABLE IF NOT EXISTS ttpos_access LIKE shop1001.ttpos_access;
CREATE TABLE IF NOT EXISTS ttpos_role LIKE shop1001.ttpos_role;
CREATE TABLE IF NOT EXISTS ttpos_role_access LIKE shop1001.ttpos_role_access;
CREATE TABLE IF NOT EXISTS ttpos_staff LIKE shop1001.ttpos_staff;
CREATE TABLE IF NOT EXISTS ttpos_staff_role LIKE shop1001.ttpos_staff_role;

-- Seed shop1002:
--   access  : transfer_order_approve (uuid=2001, same path name; different company-scoped data)
--   role    : regional_manager (uuid=3101)
--   staffs  : Carol (uuid=10021, regional_manager), Dave (uuid=10022, is_super=1)
INSERT INTO ttpos_access (uuid, name, path) VALUES
  (2001, '调拨单审批', 'transfer_order_approve');

INSERT INTO ttpos_role (uuid, name) VALUES
  (3101, '区域经理');

INSERT INTO ttpos_role_access (uuid, role_uuid, access_uuid) VALUES
  (4101, 3101, 2001);

INSERT INTO ttpos_staff (uuid, company_uuid, username, real_name, is_super) VALUES
  (10021, 1002, 'carol', 'Carol (区域经理)', 0),
  (10022, 1002, 'dave',  'Dave (超管)',     1);

INSERT INTO ttpos_staff_role (uuid, staff_uuid, role_uuid) VALUES
  (5101, 10021, 3101);
