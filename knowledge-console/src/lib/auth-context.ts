import { createContext, useContext } from 'react'

export type AuthState = {
  ready: boolean
  authenticated: boolean
  token?: string
  username: string
  tenant: string
  roles: string[]
  error?: string
  login: () => void
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
