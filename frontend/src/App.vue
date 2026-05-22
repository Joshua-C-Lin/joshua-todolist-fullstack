<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createTodo, deleteTodo, getTodos, updateTodo } from './api/todos'
import type { Todo } from './types'

const todos = ref<Todo[]>([])
const newTitle = ref('')
const isLoading = ref(false)
const isSaving = ref(false)
const errorMessage = ref('')

const remainingCount = computed(() => todos.value.filter((todo) => !todo.completed).length)
const completedCount = computed(() => todos.value.length - remainingCount.value)

async function loadTodos() {
  isLoading.value = true
  errorMessage.value = ''

  try {
    todos.value = await getTodos()
  } catch (error) {
    errorMessage.value = toErrorMessage(error)
  } finally {
    isLoading.value = false
  }
}

async function addTodo() {
  const title = newTitle.value.trim()
  if (!title) {
    return
  }

  isSaving.value = true
  errorMessage.value = ''

  try {
    const todo = await createTodo(title)
    todos.value = [...todos.value, todo]
    newTitle.value = ''
  } catch (error) {
    errorMessage.value = toErrorMessage(error)
  } finally {
    isSaving.value = false
  }
}

async function toggleTodo(todo: Todo) {
  const original = todo.completed
  todo.completed = !todo.completed
  errorMessage.value = ''

  try {
    const updated = await updateTodo(todo.id, { completed: todo.completed })
    replaceTodo(updated)
  } catch (error) {
    todo.completed = original
    errorMessage.value = toErrorMessage(error)
  }
}

async function removeTodo(todo: Todo) {
  const originalTodos = todos.value
  todos.value = todos.value.filter((item) => item.id !== todo.id)
  errorMessage.value = ''

  try {
    await deleteTodo(todo.id)
  } catch (error) {
    todos.value = originalTodos
    errorMessage.value = toErrorMessage(error)
  }
}

function replaceTodo(updated: Todo) {
  todos.value = todos.value.map((todo) => (todo.id === updated.id ? updated : todo))
}

function toErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : '發生未知錯誤'
}

onMounted(loadTodos)
</script>

<template>
  <main class="app-shell">
    <section class="todo-panel" aria-labelledby="page-title">
      <header class="todo-header">
        <div>
          <p class="eyebrow">Vue 3 + Java REST</p>
          <h1 id="page-title">Joshua Todo List</h1>
        </div>
        <button class="ghost-button" type="button" :disabled="isLoading" @click="loadTodos">
          重新整理
        </button>
      </header>

      <form class="todo-form" @submit.prevent="addTodo">
        <label class="sr-only" for="new-todo">新增待辦事項</label>
        <input
          id="new-todo"
          v-model="newTitle"
          type="text"
          autocomplete="off"
          placeholder="輸入新的待辦事項"
        />
        <button type="submit" :disabled="isSaving || !newTitle.trim()">
          {{ isSaving ? '新增中' : '新增' }}
        </button>
      </form>

      <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>

      <div class="stats" aria-label="待辦統計">
        <span>全部 {{ todos.length }}</span>
        <span>未完成 {{ remainingCount }}</span>
        <span>已完成 {{ completedCount }}</span>
      </div>

      <div v-if="isLoading" class="empty-state">載入中...</div>

      <ul v-else-if="todos.length" class="todo-list">
        <li v-for="todo in todos" :key="todo.id" class="todo-item" :class="{ completed: todo.completed }">
          <label class="todo-check">
            <input type="checkbox" :checked="todo.completed" @change="toggleTodo(todo)" />
            <span>{{ todo.title }}</span>
          </label>
          <button class="icon-button" type="button" aria-label="刪除" @click="removeTodo(todo)">
            ×
          </button>
        </li>
      </ul>

      <div v-else class="empty-state">目前沒有待辦事項</div>
    </section>
  </main>
</template>

