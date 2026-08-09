import { useMemo } from 'react'
import { useAuth } from './auth-context'
import { createApi } from './api'

export function useApi() {
  const { token } = useAuth()
  return useMemo(() => createApi(() => token), [token])
}
