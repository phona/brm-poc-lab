USE ttpos_poc_biz;

CREATE TABLE IF NOT EXISTS poc_mock_shop (
    id INT AUTO_INCREMENT PRIMARY KEY,
    shop_key VARCHAR(64) NOT NULL UNIQUE COMMENT 'e.g. Shop A',
    display_name VARCHAR(128) NOT NULL,
    manager_user_id VARCHAR(64) NOT NULL,
    region_manager_user_id VARCHAR(64) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS poc_mock_transfer_order (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    from_shop VARCHAR(64) NOT NULL,
    to_shop VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL DEFAULT 0 COMMENT 'PoC: same unit as doc e.g. 5000',
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO poc_mock_shop (shop_key, display_name, manager_user_id, region_manager_user_id) VALUES
('Shop A', '北京店', 'user_01', NULL),
('Shop B', '上海店', 'user_02', 'user_03')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

CREATE TABLE IF NOT EXISTS poc_cc_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_id VARCHAR(64) NOT NULL,
    node_code VARCHAR(64) NOT NULL,
    timing VARCHAR(64) NOT NULL,
    cc_user_ids TEXT COMMENT 'JSON array of user ids',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO poc_mock_transfer_order (order_no, from_shop, to_shop, amount, status) VALUES
('TB-1001', 'Shop A', 'Shop B', 5000, 'draft'),
('TB-1002', 'Shop A', 'Shop B', 1200, 'draft'),
('TB-1003', 'Shop A', 'Shop B', 900, 'draft'),
('TB-1004', 'Shop A', 'Shop B', 700, 'draft')
ON DUPLICATE KEY UPDATE from_shop = VALUES(from_shop), to_shop = VALUES(to_shop), amount = VALUES(amount);
