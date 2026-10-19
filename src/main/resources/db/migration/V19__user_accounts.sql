-- CSDT4-63: credentials and authorization role for resident and admin accounts.
CREATE TABLE user_account (
    username VARCHAR(100) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('RESIDENT', 'ADMIN')),
    household_id BIGINT REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_account_household
        CHECK ((role = 'RESIDENT' AND household_id IS NOT NULL)
            OR (role = 'ADMIN' AND household_id IS NULL))
);

CREATE INDEX idx_user_account_household ON user_account (household_id);
