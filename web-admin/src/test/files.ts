export function file(name: string, mime: string): File {
  return new File(['test-bytes'], name, { type: mime, lastModified: 1_700_000_000_000 })
}
