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
