import type { DoctorGateway } from '../types/contracts'
import { LegacyHttpDoctorGateway } from './httpDoctorGateway'

export { isDoctorReviewSubmittedRefreshError } from './httpDoctorGateway'

export type DoctorGatewayMode = 'mock' | 'api'

export function resolveDoctorGatewayMode(): DoctorGatewayMode {
  if (!import.meta.env.DEV) return 'api'
  const configured = String(import.meta.env.VITE_DOCTOR_DATA_SOURCE ?? '').toLowerCase()
  const query = new URLSearchParams(window.location.search)
  if (configured === 'mock' || query.get(['doctor', 'Mock'].join('')) === '1') return 'mock'
  return 'api'
}

function createLazyDevMockDoctorGateway(): DoctorGateway {
  let latestToken = ''
  const gatewayPromise = import('./mockDoctorGateway').then(({ MockDoctorGateway }) => {
    const gateway: DoctorGateway = new MockDoctorGateway()
    gateway.updateToken(latestToken)
    return gateway
  })
  return new Proxy({} as DoctorGateway, {
    get(_target, property) {
      if (property === 'updateToken') {
        return (token: string) => {
          latestToken = token
          void gatewayPromise.then((gateway) => gateway.updateToken(token))
        }
      }
      return (...args: unknown[]) => gatewayPromise.then((gateway) => {
        const method = Reflect.get(gateway, property)
        if (typeof method !== 'function') throw new Error(`Mock doctor gateway method is unavailable: ${String(property)}`)
        return Reflect.apply(method, gateway, args)
      })
    }
  })
}

export function createDoctorGateway(options: {
  token: string
  displayName: string
  clinicName: string
  authenticatedFetch?: typeof fetch
}): DoctorGateway {
  if (import.meta.env.DEV && resolveDoctorGatewayMode() === 'mock') return createLazyDevMockDoctorGateway()
  const baseUrl = String(import.meta.env.VITE_DOCTOR_API_BASE_URL ?? '').replace(/\/$/, '')
  return new LegacyHttpDoctorGateway(
    options.token,
    { displayName: options.displayName, clinicName: options.clinicName },
    baseUrl,
    options.authenticatedFetch
  )
}
