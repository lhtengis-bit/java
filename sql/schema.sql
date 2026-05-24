-- =====================================================================
-- PC Cafe Management Engine — MySQL schema
-- Run on Computer A (Database Server).
-- Connect as a privileged user (root or DBA) to execute DDL + triggers.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS pc_cafe
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE pc_cafe;

-- ---------------------------------------------------------------------
-- Table 1: stations
--   Tracks live physical configurations. Mutable: status & time_remaining
--   are updated as the cashier funds time or the kiosk reports expiry.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS stations;
CREATE TABLE stations (
    station_id      INT             NOT NULL,
    ip_address      VARCHAR(45)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'OFFLINE',
    time_remaining  INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (station_id),
    CONSTRAINT chk_status
        CHECK (status IN ('LOCKED', 'UNLOCKED', 'OFFLINE'))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Table 2: cash_transactions
--   Immutable append-only ledger. Rows must never be UPDATEd or DELETEd.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS cash_transactions;
CREATE TABLE cash_transactions (
    transaction_id  INT             NOT NULL AUTO_INCREMENT,
    station_id      INT             NOT NULL,
    amount_paid     DECIMAL(10,2)   NOT NULL,   -- widened from (6,2); supports up to 99,999,999.99₮
    `timestamp`     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transaction_id),
    CONSTRAINT fk_tx_station
        FOREIGN KEY (station_id) REFERENCES stations(station_id),
    CONSTRAINT chk_amount_positive
        CHECK (amount_paid > 0),
    INDEX idx_tx_date (`timestamp`)              -- sargable range queries on timestamp
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Immutability triggers — block UPDATE and DELETE on the ledger.
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS block_transaction_updates;
DROP TRIGGER IF EXISTS block_transaction_deletes;

DELIMITER //

CREATE TRIGGER block_transaction_updates
BEFORE UPDATE ON cash_transactions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Updates are not allowed on the ledger!';
END//

CREATE TRIGGER block_transaction_deletes
BEFORE DELETE ON cash_transactions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Deletes are not allowed on the ledger!';
END//

DELIMITER ;

-- ---------------------------------------------------------------------
-- Dedicated application user (least privilege).
-- Replace 'StrongP@ss!' before running in production.
-- ---------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'cafe_app'@'%' IDENTIFIED BY 'StrongP@ss!';
GRANT SELECT, INSERT, UPDATE ON pc_cafe.stations          TO 'cafe_app'@'%';
GRANT SELECT, INSERT          ON pc_cafe.cash_transactions TO 'cafe_app'@'%';
FLUSH PRIVILEGES;
