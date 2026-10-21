-- The database-server edition has no XS applications, so the HTTP web dispatcher only takes memory from the tenant.
-- The image enables it on the HOST layer, the only layer this parameter accepts; Compose pins the host name.
ALTER SYSTEM ALTER CONFIGURATION ('daemon.ini', 'HOST', 'hxehost') SET ('webdispatcher', 'instances') = '0' WITH RECONFIGURE;
DO
BEGIN
    DECLARE service_count INTEGER;
    SELECT COUNT(*) INTO service_count
    FROM SYS_DATABASES.M_SERVICES
    WHERE DATABASE_NAME = 'HXE' AND SERVICE_NAME = 'diserver';
    IF :service_count = 0 THEN
        EXEC 'ALTER DATABASE HXE ADD ''diserver''';
    END IF;
END;
