-- =====================================================
-- GoEats PostgreSQL Schema Initialization
-- =====================================================
-- Monolithic: uses 'public' schema (default)
-- MSA / MSA-Traffic: each service has its own schema
--   - order_schema   (order-service,   port 8081)
--   - store_schema   (store-service,   port 8082)
--   - payment_schema (payment-service, port 8083)
--   - delivery_schema(delivery-service, port 8084)
-- =====================================================

CREATE SCHEMA IF NOT EXISTS order_schema;
CREATE SCHEMA IF NOT EXISTS store_schema;
CREATE SCHEMA IF NOT EXISTS payment_schema;
CREATE SCHEMA IF NOT EXISTS delivery_schema;

-- Grant all privileges to goeats user
GRANT ALL PRIVILEGES ON SCHEMA order_schema TO goeats;
GRANT ALL PRIVILEGES ON SCHEMA store_schema TO goeats;
GRANT ALL PRIVILEGES ON SCHEMA payment_schema TO goeats;
GRANT ALL PRIVILEGES ON SCHEMA delivery_schema TO goeats;
