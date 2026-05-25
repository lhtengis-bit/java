-- Seed a handful of stations for local testing.
-- OPTIONAL: stations are now auto-created the first time a kiosk connects
-- (via INSERT ... ON DUPLICATE KEY UPDATE in StationDao.upsertAndFetch).
-- Only run this if you want rows pre-populated before any kiosk connects,
-- e.g. so the cashier UI shows stations while everything is booting up.
-- Run AFTER schema.sql.
USE pc_cafe;

INSERT INTO stations (station_id, ip_address, status, time_remaining) VALUES
    (101, '192.168.1.101', 'OFFLINE', 0),
    (102, '192.168.1.102', 'OFFLINE', 0),
    (103, '192.168.1.103', 'OFFLINE', 0),
    (104, '192.168.1.104', 'OFFLINE', 0)
ON DUPLICATE KEY UPDATE ip_address = VALUES(ip_address);
