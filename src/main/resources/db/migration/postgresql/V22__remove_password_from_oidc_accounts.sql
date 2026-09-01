-- Connected (OIDC-linked) accounts are OIDC-only by definition: connecting an identity provider now removes the password outright (there is no
-- hybrid password+OIDC state and no disconnect), so normalise any pre-existing linked rows that still carry a hash.
UPDATE users
SET password_hash = NULL
WHERE oidc_subject IS NOT NULL
  AND password_hash IS NOT NULL;
