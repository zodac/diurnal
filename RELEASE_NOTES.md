## Patch Updates

Mostly security-related updates for this release

### Require Password On Change

The user must enter their current password before changing to a new one.

### Cookie Hardening

Adding CSRF defence, and SameSite strict/secure.

### Constraints For Passwords

Now enforcing a min/max password length (based on BCrypt limits), and adding a tooltip for user fields.
