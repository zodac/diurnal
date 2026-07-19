# Release Notes

Reworked the OIDC implementation.

- Logging in with an existing local account no longer silently connects, and users explicitly connect through the user settings page
    - When both `PASSWORD_AUTH_ENABLED` and `OIDC_ENABLED` are both set to **true**
    - When only `OIDC_ENABLED` is enabled, there should only be the initial admin left as a local user
- Connecting to an identity provider removes the account's password
- "Log out from everywhere" now also revokes OIDC sessions
- Support for custom scopes using `OIDC_SCOPES` (**openid** always selected)
- Support for PKCE can be enabled or disabled using `OIDC_PKCE_ENABLED`
- Some other edge cases cleaned up and tested
