# Vue 3 + Java Todo List

This is a small full-stack todo list example:

- Frontend: Vue 3 + TypeScript + Vite
- Backend: Java 21 with the built-in `HttpServer`
- Data store: Supabase REST API, with in-memory fallback

## Project Structure

```text
.
├── backend
│   ├── .env.example
│   ├── supabase-schema.sql
│   └── src/main/java/com/example/todolist/Main.java
└── frontend
    ├── index.html
    ├── package.json
    └── src
```

## Supabase Setup

1. Create a Supabase project.
2. Open the SQL editor and run `backend/supabase-schema.sql`.
3. Copy your project URL and API key from the Supabase dashboard.
4. Create `backend/.env`:

```bash
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_KEY=your-publishable-anon-secret-or-service-role-key
CORS_ALLOWED_ORIGIN=http://localhost:5173
```

You can also export the same names as environment variables. Exported values take precedence over `backend/.env`.

For a quick backend demo, keep the key only in the Java process. Do not put a `secret` or `service_role` key in Vue or any browser code.

The demo SQL includes permissive anon policies so a publishable or anon key can work for local testing. For a real app, replace those policies with user-based rules.

If `SUPABASE_URL` or `SUPABASE_KEY` is missing, the backend automatically uses an in-memory list.

## Run Backend

```bash
mkdir -p backend/out
javac -d backend/out backend/src/main/java/com/example/todolist/Main.java
java -cp backend/out com.example.todolist.Main
```

The backend runs at `http://localhost:8080`.

Available endpoints:

- `GET /api/todos`
- `POST /api/todos` with `{ "title": "Buy milk" }`
- `PUT /api/todos/{id}` with `{ "completed": true }` or `{ "title": "New title" }`
- `DELETE /api/todos/{id}`

## Run Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend runs at `http://localhost:5173`.

The Vite dev server proxies `/api` requests to `http://localhost:8080`.

## Deploy Backend to Render

Create a Render `Web Service` for the Java backend and use Docker runtime.

- Root Directory: `backend`
- Runtime: `Docker`
- Dockerfile Path: `./Dockerfile`

Environment variables:

```text
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_KEY=your-publishable-anon-secret-or-service-role-key
CORS_ALLOWED_ORIGIN=https://your-vercel-frontend-domain.vercel.app
```

Render provides `PORT` automatically. The backend uses that value when present and falls back to `8080` for local development.
