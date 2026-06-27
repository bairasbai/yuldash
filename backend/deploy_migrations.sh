#!/bin/bash
# Deploy OAuth & WhatsApp migrations to yulbash.ru production DB
# Run from server: ssh root@85.239.52.55, then execute this script

set -e

DB_USER="yuldash_app"
DB_HOST="localhost"
DB_NAME="yuldash_prod"

echo "🔄 Applying OAuth migration (Telegram/VK)..."
psql -U $DB_USER -h $DB_HOST -d $DB_NAME -c "
ALTER TABLE \"user\"
ADD COLUMN telegram_id VARCHAR(255) UNIQUE NULL,
ADD COLUMN vk_id VARCHAR(255) UNIQUE NULL;

CREATE INDEX idx_user_telegram_id ON \"user\"(telegram_id);
CREATE INDEX idx_user_vk_id ON \"user\"(vk_id);
"

echo "✅ OAuth migration applied."

echo "🔄 Applying WhatsApp migration..."
psql -U $DB_USER -h $DB_HOST -d $DB_NAME -c "
ALTER TABLE \"user\"
ADD COLUMN whatsapp_verified BOOLEAN DEFAULT FALSE;

CREATE INDEX idx_user_whatsapp_verified ON \"user\"(whatsapp_verified);
"

echo "✅ WhatsApp migration applied."

echo "🎯 Verifying schema..."
psql -U $DB_USER -h $DB_HOST -d $DB_NAME -c "
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name='user' AND column_name IN ('telegram_id', 'vk_id', 'whatsapp_verified')
ORDER BY ordinal_position;
"

echo "✅ All done. OAuth/WhatsApp fields ready for production."
