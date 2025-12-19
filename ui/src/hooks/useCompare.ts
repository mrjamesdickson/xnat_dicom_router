/**
 * DICOM Comparison Hooks
 *
 * Hooks for fetching DICOM comparison data from the backend API.
 */

import { useFetch, apiGet, apiFetchImageUrl } from './useApi'

// Types for comparison data
export interface TagComparison {
  tag: string
  name: string
  vr: string
  category: string
  originalValue: string
  anonymizedValue: string
  changed: boolean
  removed: boolean
  added: boolean
  isPhi: boolean
}

export interface HeaderComparison {
  tags: TagComparison[]
  totalTags: number
  changedTags: number
  removedTags: number
  addedTags: number
  phiTags: number
  originalFile: string
  anonymizedFile: string
}

export interface FileComparison {
  sopInstanceUid: string
  originalFile: string
  anonymizedFile: string
  instanceNumber: number
  hasOriginal: boolean
  hasAnonymized: boolean
}

export interface ScanComparison {
  seriesUid: string
  seriesDescription: string
  modality: string
  seriesNumber: number
  instanceCount: number
  files: FileComparison[]
}

export interface StudyComparison {
  studyUid: string
  patientId: string
  patientName: string
  studyDate: string
  studyDescription: string
  accessionNumber: string
  originalPath: string
  anonymizedPath: string
  scanCount: number
  fileCount: number
  aeTitle: string
  reviewId: string
  reviewStatus: string
  submittedAt: string
  scriptUsed: string
  phiFieldsModified: number
  warnings: string[]
  ocrDetectedRegions: number
  hasPixelPhi: boolean
}

export interface OcrRegion {
  x: number
  y: number
  width: number
  height: number
  text: string
  confidence: number
  isPhi: boolean
}

export interface OcrResult {
  path: string
  regionCount: number
  regions: OcrRegion[]
  hasPhiRegions: boolean
}

export interface ImageInfo {
  path: string
  width: number
  height: number
  frames: number
  modality: string
  sopClassUid: string
}

export interface ScansResponse {
  aeTitle: string
  studyUid: string
  scanCount: number
  scans: ScanComparison[]
}

/**
 * Hook to fetch study comparison metadata.
 */
export function useStudyComparison(aeTitle: string, studyUid: string) {
  return useFetch<StudyComparison>(
    aeTitle && studyUid ? `/compare/${encodeURIComponent(aeTitle)}/${encodeURIComponent(studyUid)}` : ''
  )
}

/**
 * Hook to fetch scan comparisons for a study.
 */
export function useScanComparisons(aeTitle: string, studyUid: string) {
  return useFetch<ScansResponse>(
    aeTitle && studyUid ? `/compare/${encodeURIComponent(aeTitle)}/${encodeURIComponent(studyUid)}/scans` : ''
  )
}

/**
 * Fetch header comparison between original and anonymized files.
 */
export async function fetchHeaderComparison(originalPath: string, anonymizedPath: string): Promise<HeaderComparison> {
  const params = new URLSearchParams({
    original: originalPath,
    anonymized: anonymizedPath
  })
  return apiGet<HeaderComparison>(`/compare/header?${params}`)
}

/**
 * Get URL for rendered DICOM image with optional OCR overlay.
 */
export function getImageUrl(filePath: string, overlay: boolean = false, frame: number = 0): string {
  const params = new URLSearchParams({
    path: filePath,
    overlay: String(overlay),
    frame: String(frame)
  })
  return `/api/compare/image?${params}`
}

/**
 * Fetch image with authentication and return blob URL.
 */
export async function fetchImageUrl(filePath: string, overlay: boolean = false, frame: number = 0): Promise<string> {
  const params = new URLSearchParams({
    path: filePath,
    overlay: String(overlay),
    frame: String(frame)
  })
  return apiFetchImageUrl(`/compare/image?${params}`)
}

/**
 * Fetch OCR detected regions for a file.
 */
export async function fetchOcrRegions(filePath: string): Promise<OcrResult> {
  const params = new URLSearchParams({ path: filePath })
  return apiGet<OcrResult>(`/compare/ocr?${params}`)
}

/**
 * Fetch image info (dimensions, frames, etc).
 */
export async function fetchImageInfo(filePath: string): Promise<ImageInfo> {
  const params = new URLSearchParams({ path: filePath })
  return apiGet<ImageInfo>(`/compare/image-info?${params}`)
}

/**
 * Hook to fetch header comparison (with auto-refresh capability).
 */
export function useHeaderComparison(originalPath: string, anonymizedPath: string) {
  const params = originalPath && anonymizedPath
    ? `?original=${encodeURIComponent(originalPath)}&anonymized=${encodeURIComponent(anonymizedPath)}`
    : ''
  return useFetch<HeaderComparison>(
    params ? `/compare/header${params}` : ''
  )
}
