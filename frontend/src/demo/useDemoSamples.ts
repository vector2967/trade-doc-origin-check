import { useEffect, useState } from 'react'
import { demoApi } from '../api/demo'
import type { DemoSampleSet } from '../api/types'

// 체험용 서류 목록은 바뀌지 않으므로 한 번만 받아 둔다
let cache: Promise<DemoSampleSet[]> | null = null

export function loadDemoSamples() {
  cache ??= demoApi.samples().then((r) => r.sets).catch((e) => {
    cache = null
    throw e
  })
  return cache
}

export function useDemoSamples() {
  const [sets, setSets] = useState<DemoSampleSet[] | null>(null)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let alive = true
    loadDemoSamples().then(
      (v) => alive && setSets(v),
      () => alive && setError(true),
    )
    return () => {
      alive = false
    }
  }, [attempt])
  const retry = () => {
    setError(false)
    setAttempt((n) => n + 1)
  }
  return { sets, error, retry }
}
