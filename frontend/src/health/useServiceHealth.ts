import { useEffect, useState } from 'react'

export type HealthState = 'checking' | 'available' | 'unavailable'

export function useServiceHealth(): HealthState {
  const [health, setHealth] = useState<HealthState>('checking')

  useEffect(() => {
    const controller = new AbortController()

    fetch('/health', {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error('Health request failed')
        }
        setHealth('available')
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setHealth('unavailable')
        }
      })

    return () => controller.abort()
  }, [])

  return health
}
