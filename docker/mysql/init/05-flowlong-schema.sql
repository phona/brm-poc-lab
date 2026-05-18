-- FlowLong 1.2.4 schema (from github.com/aizuda/flowlong db/flowlong-mysql.sql)
CREATE DATABASE IF NOT EXISTS flowlong CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE flowlong;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `flw_process`;
CREATE TABLE `flw_process` (
    `id` bigint NOT NULL COMMENT '主键ID',
    `tenant_id` varchar(50) COMMENT '租户ID',
    `create_id` varchar(50) NOT NULL,
    `create_by` varchar(50) NOT NULL,
    `create_time` timestamp NOT NULL,
    `process_key` varchar(100) NOT NULL,
    `process_name` varchar(100) NOT NULL,
    `process_icon` varchar(255) DEFAULT NULL,
    `process_type` varchar(100),
    `process_version` int NOT NULL DEFAULT 1,
    `instance_url` varchar(200),
    `remark` varchar(255),
    `use_scope` tinyint(1) NOT NULL DEFAULT 0,
    `process_state` tinyint(1) NOT NULL DEFAULT 1,
    `model_content` text,
    `sort` tinyint(1) DEFAULT 0,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_process_name`(`process_name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_his_instance`;
CREATE TABLE `flw_his_instance` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `create_id` varchar(50) NOT NULL,
    `create_by` varchar(50) NOT NULL,
    `create_time` timestamp NOT NULL,
    `process_id` bigint NOT NULL,
    `parent_instance_id` bigint,
    `priority` tinyint(1),
    `instance_no` varchar(50),
    `business_key` varchar(100),
    `variable` text,
    `current_node_name` varchar(100) NOT NULL,
    `current_node_key` varchar(100) NOT NULL,
    `expire_time` timestamp NULL DEFAULT NULL,
    `last_update_by` varchar(50),
    `last_update_time` timestamp NULL DEFAULT NULL,
    `instance_state` tinyint(1) NOT NULL DEFAULT 0,
    `end_time` timestamp NULL DEFAULT NULL,
    `duration` bigint,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_his_instance_process_id`(`process_id` ASC) USING BTREE,
    CONSTRAINT `fk_his_instance_process_id` FOREIGN KEY (`process_id`) REFERENCES `flw_process` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_his_task`;
CREATE TABLE `flw_his_task` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `create_id` varchar(50) NOT NULL,
    `create_by` varchar(50) NOT NULL,
    `create_time` timestamp NOT NULL,
    `instance_id` bigint NOT NULL,
    `parent_task_id` bigint,
    `call_process_id` bigint,
    `call_instance_id` bigint,
    `task_name` varchar(100) NOT NULL,
    `task_key` varchar(100) NOT NULL,
    `task_type` tinyint(1) NOT NULL,
    `perform_type` tinyint(1),
    `action_url` varchar(200),
    `variable` text,
    `assignor_id` varchar(100),
    `assignor` varchar(255),
    `expire_time` timestamp NULL DEFAULT NULL,
    `remind_time` timestamp NULL DEFAULT NULL,
    `remind_repeat` tinyint(1) NOT NULL DEFAULT 0,
    `viewed` tinyint(1) NOT NULL DEFAULT 0,
    `finish_time` timestamp NULL DEFAULT NULL,
    `task_state` tinyint(1) NOT NULL DEFAULT 0,
    `duration` bigint,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_his_task_instance_id`(`instance_id` ASC) USING BTREE,
    INDEX `idx_his_task_parent_task_id`(`parent_task_id` ASC) USING BTREE,
    CONSTRAINT `fk_his_task_instance_id` FOREIGN KEY (`instance_id`) REFERENCES `flw_his_instance` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_his_task_actor`;
CREATE TABLE `flw_his_task_actor` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `instance_id` bigint NOT NULL,
    `task_id` bigint NOT NULL,
    `actor_id` varchar(100) NOT NULL,
    `actor_name` varchar(100) NOT NULL,
    `actor_type` int NOT NULL,
    `weight` int,
    `agent_id` varchar(100),
    `agent_type` int,
    `ext` text,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_his_task_actor_task_id`(`task_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_instance`;
CREATE TABLE `flw_instance` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `create_id` varchar(50) NOT NULL,
    `create_by` varchar(50) NOT NULL,
    `create_time` timestamp NOT NULL,
    `process_id` bigint NOT NULL,
    `parent_instance_id` bigint,
    `priority` tinyint(1),
    `instance_no` varchar(50),
    `business_key` varchar(100),
    `variable` text,
    `current_node_name` varchar(100) NOT NULL,
    `current_node_key` varchar(100) NOT NULL,
    `expire_time` timestamp NULL DEFAULT NULL,
    `last_update_by` varchar(50),
    `last_update_time` timestamp NULL DEFAULT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_instance_process_id`(`process_id` ASC) USING BTREE,
    CONSTRAINT `fk_instance_process_id` FOREIGN KEY (`process_id`) REFERENCES `flw_process` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_task`;
CREATE TABLE `flw_task` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `create_id` varchar(50) NOT NULL,
    `create_by` varchar(50) NOT NULL,
    `create_time` timestamp NOT NULL,
    `instance_id` bigint NOT NULL,
    `parent_task_id` bigint,
    `task_name` varchar(100) NOT NULL,
    `task_key` varchar(100) NOT NULL,
    `task_type` tinyint(1) NOT NULL,
    `perform_type` tinyint(1) NULL,
    `action_url` varchar(200),
    `variable` text,
    `assignor_id` varchar(100),
    `assignor` varchar(255),
    `expire_time` timestamp NULL DEFAULT NULL,
    `remind_time` timestamp NULL DEFAULT NULL,
    `remind_repeat` tinyint(1) NOT NULL DEFAULT 0,
    `viewed` tinyint(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_task_instance_id`(`instance_id` ASC) USING BTREE,
    CONSTRAINT `fk_task_instance_id` FOREIGN KEY (`instance_id`) REFERENCES `flw_instance` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_task_actor`;
CREATE TABLE `flw_task_actor` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `instance_id` bigint NOT NULL,
    `task_id` bigint NOT NULL,
    `actor_id` varchar(100) NOT NULL,
    `actor_name` varchar(100) NOT NULL,
    `actor_type` int NOT NULL,
    `weight` int,
    `agent_id` varchar(100),
    `agent_type` int,
    `ext` text,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_task_actor_task_id`(`task_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

DROP TABLE IF EXISTS `flw_ext_instance`;
CREATE TABLE `flw_ext_instance` (
    `id` bigint NOT NULL,
    `tenant_id` varchar(50),
    `process_id` bigint NOT NULL,
    `process_name` varchar(100),
    `process_type` varchar(100),
    `model_content` text,
    PRIMARY KEY (`id`) USING BTREE,
    CONSTRAINT `fk_ext_instance_id` FOREIGN KEY (`id`) REFERENCES `flw_his_instance` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;
