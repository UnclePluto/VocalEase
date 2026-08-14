export function file(name: string, mime: string): File {
  return new File(['test-bytes'], name, { type: mime })
}
