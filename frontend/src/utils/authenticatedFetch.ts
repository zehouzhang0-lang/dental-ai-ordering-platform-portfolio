import type { InjectionKey } from 'vue'

export type AuthenticatedFetch = typeof fetch

export const authenticatedFetchKey: InjectionKey<AuthenticatedFetch> = Symbol('authenticated-fetch')
