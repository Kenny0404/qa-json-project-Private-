# QA Admin UI (Vue 3)

## Dev

1. Start Spring Boot backend (default `http://localhost:8080`).
2. In this folder:

```bash
npm i
cp .env.example .env
npm run dev
```

- Vite will proxy `/api/*` to `VITE_BACKEND_URL` (default `http://localhost:8080`).
- By default, the frontend calls `/api/admin/**` with a relative URL, so the proxy will forward it to your backend.
- If backend sets `admin.api-key`, set `VITE_ADMIN_KEY` in `.env`. The UI will send header `X-Admin-Key: <VITE_ADMIN_KEY>`.

## Env

- `VITE_BACKEND_URL`: Vite dev server proxy target (where your Spring Boot runs)
- `VITE_ADMIN_KEY`: optional; required only when backend has `admin.api-key`

## Notes

- The TypeScript errors like "Cannot find module 'vue'" will disappear after `npm i` (node_modules installed).
- If you want to deploy the admin UI separately (not via Vite proxy), set `VITE_API_BASE_URL` to your backend origin and rebuild.

## Routes

- `/admin/faqs`
- `/admin/config`
- `/admin/logs`
