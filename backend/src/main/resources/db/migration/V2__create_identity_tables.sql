-- Module identity : utilisateurs et rôles (RM-AUTH-01, RM-AUTH-02).
CREATE TABLE app_user (
    id            UUID                     NOT NULL,
    email         VARCHAR(254)             NOT NULL,
    password_hash VARCHAR(100)             NOT NULL,
    enabled       BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT ck_app_user_email_not_blank CHECK (length(btrim(email)) > 0)
);

-- unicité insensible à la casse de l'e-mail
CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user (lower(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL,
    role    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('LIBRARIAN', 'MEMBER'))
);

CREATE INDEX ix_user_roles_role ON user_roles (role);
