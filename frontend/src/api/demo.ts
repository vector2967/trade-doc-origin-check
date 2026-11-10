import { apiFetch, apiRaw } from './client'
import type { DemoSamplesResponse } from './types'

const filePath = (setId: string, name: string) => `/api/v1/demo/samples/${encodeURIComponent(setId)}/${encodeURIComponent(name)}`

/** 체험용 서류 (인증 없이도 받을 수 있다) */
export const demoApi = {
  samples: () => apiFetch<DemoSamplesResponse>('/api/v1/demo/samples'),

  /** 체험용 PDF 를 받아 업로드에 바로 쓸 File 로 만든다 */
  file: async (setId: string, name: string) => {
    const res = await apiRaw(filePath(setId, name))
    return new File([await res.blob()], name, { type: 'application/pdf', lastModified: 0 })
  },
}
