import Keycloak from 'keycloak-js'
import {
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from 'react'
import { AuthContext, type AuthState } from './auth-context'

const keycloak = new Keycloak({
  url: import.meta.env.VITE_OIDC_URL ?? 'http://localhost:8180',
  realm: import.meta.env.VITE_OIDC_REALM ?? 'infinity-knowledge',
  clientId:
    import.meta.env.VITE_OIDC_CLIENT_ID ?? 'infinity-knowledge-console',
})
const applicationRootUrl = `${window.location.origin}/`

export function AuthProvider({ children }: PropsWithChildren) {
  const [ready, setReady] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)
  const [token, setToken] = useState<string>()
  const [error, setError] = useState<string>()

  useEffect(() => {
    let active = true
    void keycloak
      .init({
        onLoad: 'login-required',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      })
      .then((loggedIn) => {
        if (!active) return
        setError(undefined)
        setAuthenticated(loggedIn)
        setToken(keycloak.token)
        setReady(true)
      })
      .catch((reason: unknown) => {
        if (!active) return
        setError(describeAuthenticationError(reason))
        setAuthenticated(false)
        setToken(undefined)
        setReady(true)
      })

    const timer = window.setInterval(() => {
      if (!keycloak.authenticated) return
      void keycloak
        .updateToken(45)
        .then(() => {
          if (!active) return
          setToken(keycloak.token)
        })
        .catch((reason: unknown) => {
          if (!active) return
          keycloak.clearToken()
          setError(describeAuthenticationError(reason))
          setAuthenticated(false)
          setToken(undefined)
          setReady(true)
        })
    }, 30_000)

    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [])

  const value = useMemo<AuthState>(() => {
    const claims = keycloak.tokenParsed as
      | {
          preferred_username?: string
          tenant_id?: string
          realm_access?: { roles?: string[] }
        }
      | undefined
    return {
      ready,
      authenticated,
      token,
      username: claims?.preferred_username ?? '未登录',
      tenant: claims?.tenant_id ?? '—',
      roles: claims?.realm_access?.roles ?? [],
      error,
      login: () => {
        setError(undefined)
        void keycloak.login({ redirectUri: applicationRootUrl })
      },
      logout: () =>
        void keycloak.logout({ redirectUri: applicationRootUrl }),
    }
  }, [authenticated, error, ready, token])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

function describeAuthenticationError(reason: unknown): string {
  if (reason instanceof Error && reason.message) {
    return reason.message
  }
  if (typeof reason === 'string' && reason.trim()) {
    return reason
  }
  if (isErrorResponse(reason)) {
    const code = textValue(reason.error)
    const description = textValue(reason.error_description)
    const message = [code, description].filter(Boolean).join(': ')
    if (message) return message
  }
  return '登录回调或 Token 交换失败，请检查浏览器网络面板中的具体响应。'
}

function isErrorResponse(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function textValue(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}
