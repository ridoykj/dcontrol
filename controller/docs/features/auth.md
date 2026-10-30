# Authentication

Email + password accounts with email verification, JWT access tokens and rotating refresh tokens.

## Model

- **Account** (`T_ACCOUNT`) is the tenant. Registering creates one account and its `OWNER` user.
- **User** (`T_USER`) belongs to one account. `uk_user_email` is a partial unique index on
  `lower(tx_email) WHERE is_active`.
- A seeded **SYSTEM** account/user (`01900000-…-0001` / `…-0002`) is the actor for migrations, jobs,
  device sockets and unauthenticated requests. Its password hash can never match.

## Tokens

| Token | Format | Lifetime | Where it lives |
|---|---|---|---|
| Access | HS256 JWT: `sub` = user key, `aid` = account key, `typ=access` | 15 min | Browser memory only |
| Refresh | 32 random bytes (base64url); only its SHA-256 is stored in `T_REFRESH_TOKEN` | 30 days, sliding | `dc_refresh` cookie: HttpOnly, `SameSite=Strict`, `Path=/api/auth` |
| Email verification | HS256 JWT `typ=email_verify`, claim `eml` | 24 h | Email link `/verify-email?token=` |
| Password reset | HS256 JWT `typ=password_reset`, claim `pwf` (fingerprint of the current password hash) | 1 h | Email link `/reset-password?token=` |

Each decoder only accepts its own `typ`, so an email token can't be used as an access token.
A reset token stops working once the password changes, which makes it single-use without storing it.

### Refresh rotation

Every `POST /api/auth/refresh` marks the presented token used and issues a new one in the same family.
If an already-used token comes back more than 10 s after it was used, the whole family is deactivated
(theft detection). Reuse within 10 s is treated as two tabs racing: the request fails with 401 but the
family is left alone, and the browser client retries once with the newly set cookie.

`refresh` and `logout` read the cookie, so they also require the `X-Requested-With` header. Cross-site
forms can't set it. Expired refresh tokens are physically deleted every hour.

## Endpoints

| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/api/auth/register` | `{email, password (10–128), displayName}` | 202 `{email}`, sends verification mail; 409 `EMAIL_TAKEN` |
| POST | `/api/auth/verify-email` | `{token}` | 204; 400 `TOKEN_INVALID` |
| POST | `/api/auth/resend-verification` | `{email}` | 202 (always) |
| POST | `/api/auth/login` | `{email, password}` | 200 `{accessToken, tokenType, expiresIn, user}` + cookie; 401 `INVALID_CREDENTIALS`; 403 `EMAIL_NOT_VERIFIED` |
| POST | `/api/auth/refresh` | cookie + `X-Requested-With` | 200 same as login; 401 `TOKEN_INVALID` |
| POST | `/api/auth/logout` | cookie + `X-Requested-With` | 204, clears cookie |
| POST | `/api/auth/forgot-password` | `{email}` | 202 (always) |
| POST | `/api/auth/reset-password` | `{token, password}` | 204, verifies the email, signs out all sessions |
| GET | `/api/me` | bearer | `{id, accountId, email, displayName}` |

Endpoints that take an email always answer the same way whether or not the email exists.

## Rate limits (in memory, per node)

| Key | Limit |
|---|---|
| register per IP | 10 / hour |
| login per IP / per email | 30 / 10 min, 10 / 15 min |
| resend-verification, forgot-password per IP / per email | 10 / hour, 3 / hour (excess emails are silently dropped) |

## Frontend

- `shared/api/client.ts`: adds the bearer token, refreshes once on 401 (a single in-flight refresh shared by
  concurrent requests), and turns problem details into `ApiError`.
- The `_authenticated` route restores the session with `/refresh` on load and redirects to `/login?redirect=…`
  otherwise. Only same-origin paths are accepted as `redirect`.
- Pages: `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password`.

## Configuration

| Property | Env var | Default |
|---|---|---|
| `dcontrol.jwt.secret` | `DCONTROL_JWT_SECRET` | none in prod (≥ 32 bytes); dev profile has a local value |
| `dcontrol.web-base-url` | `DCONTROL_WEB_BASE_URL` | public base URL; dev: `http://localhost:5173` |
| `dcontrol.mail.from` | `DCONTROL_MAIL_FROM` | `dcontrol <no-reply@dcontrol.local>` |
| `spring.mail.host/port` | `DCONTROL_MAIL_HOST/PORT` | `localhost:1025` (Mailpit) |
| `dcontrol.cookie.secure` | — | `true`; dev `false` |
