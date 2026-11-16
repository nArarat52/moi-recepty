CREATE TABLE IF NOT EXISTS users (
 id BIGSERIAL PRIMARY KEY, login TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
 password_hash TEXT NOT NULL, subscription_until TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS sessions (
 token_hash TEXT PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id),
 expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '30 days'
);
CREATE TABLE IF NOT EXISTS groups (
 id BIGSERIAL PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES users(id),
 title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS memberships (
 group_id BIGINT NOT NULL REFERENCES groups(id), user_id BIGINT NOT NULL REFERENCES users(id),
 PRIMARY KEY(group_id,user_id)
);
CREATE TABLE IF NOT EXISTS invitations (
 id BIGSERIAL PRIMARY KEY, group_id BIGINT NOT NULL REFERENCES groups(id),
 sender_id BIGINT NOT NULL REFERENCES users(id), recipient_id BIGINT NOT NULL REFERENCES users(id),
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','declined')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS pending_invitation ON invitations(group_id,recipient_id) WHERE status='pending';
CREATE TABLE IF NOT EXISTS images (
 id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES users(id), group_id BIGINT REFERENCES groups(id),
 mime TEXT NOT NULL, data BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS recipes (
 id BIGSERIAL PRIMARY KEY, author_id BIGINT NOT NULL REFERENCES users(id), title TEXT NOT NULL,
 description TEXT NOT NULL DEFAULT '', category TEXT NOT NULL, minutes INT NOT NULL CHECK(minutes BETWEEN 1 AND 1440),
 servings INT NOT NULL CHECK(servings BETWEEN 1 AND 100), ingredients JSONB NOT NULL, steps JSONB NOT NULL,
 image_id UUID REFERENCES images(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS recipe_catalog ON recipes(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS recipe_category ON recipes(category,minutes);
CREATE TABLE IF NOT EXISTS favorites (
 user_id BIGINT NOT NULL REFERENCES users(id), recipe_id BIGINT NOT NULL REFERENCES recipes(id), PRIMARY KEY(user_id,recipe_id)
);
CREATE TABLE IF NOT EXISTS ratings (
 user_id BIGINT NOT NULL REFERENCES users(id), recipe_id BIGINT NOT NULL REFERENCES recipes(id),
 value INT NOT NULL CHECK(value BETWEEN 1 AND 5), PRIMARY KEY(user_id,recipe_id)
);
CREATE TABLE IF NOT EXISTS reviews (
 id BIGSERIAL PRIMARY KEY, author_id BIGINT NOT NULL REFERENCES users(id), recipe_id BIGINT NOT NULL REFERENCES recipes(id),
 text TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(author_id,recipe_id)
);
CREATE TABLE IF NOT EXISTS reactions (
 user_id BIGINT NOT NULL REFERENCES users(id), review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
 value INT NOT NULL CHECK(value IN (-1,1)), PRIMARY KEY(user_id,review_id)
);
CREATE TABLE IF NOT EXISTS dishes (
 id BIGSERIAL PRIMARY KEY, group_id BIGINT NOT NULL REFERENCES groups(id), title TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS attempts (
 id BIGSERIAL PRIMARY KEY, dish_id BIGINT NOT NULL REFERENCES dishes(id), author_id BIGINT NOT NULL REFERENCES users(id),
 number INT NOT NULL, parent_id BIGINT REFERENCES attempts(id), snapshot JSONB NOT NULL, note TEXT NOT NULL,
 image_id UUID REFERENCES images(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(dish_id,number)
);
CREATE TABLE IF NOT EXISTS operations (
 user_id BIGINT NOT NULL REFERENCES users(id), key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 response JSONB NOT NULL, PRIMARY KEY(user_id,key)
);
CREATE OR REPLACE FUNCTION protect_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Saved attempts are immutable'; END $$;
DROP TRIGGER IF EXISTS attempts_immutable ON attempts;
CREATE TRIGGER attempts_immutable BEFORE UPDATE OR DELETE ON attempts FOR EACH ROW EXECUTE FUNCTION protect_attempt();
