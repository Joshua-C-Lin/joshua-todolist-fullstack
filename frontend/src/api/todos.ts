import type { Todo } from '../types'

const API_ORIGIN = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''
const API_BASE = `${API_ORIGIN}/api/todos`

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  })

  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    throw new Error(errorBody?.message ?? `Request failed: ${response.status}`)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return response.json() as Promise<T>
}

export function getTodos(): Promise<Todo[]> {
  return request<Todo[]>(API_BASE)
}

export function createTodo(title: string): Promise<Todo> {
  return request<Todo>(API_BASE, {
    method: 'POST',
    body: JSON.stringify({ title }),
  })
}

export function updateTodo(id: number, payload: Partial<Pick<Todo, 'title' | 'completed'>>): Promise<Todo> {
  return request<Todo>(`${API_BASE}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteTodo(id: number): Promise<void> {
  return request<void>(`${API_BASE}/${id}`, {
    method: 'DELETE',
  })
}
