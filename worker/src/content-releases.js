export const CONTENT_RELEASES = Object.freeze({
  '0b3170748035504b': Object.freeze({
    sha256: '0b3170748035504b44f947017855ec14893884d6c18acd996b6b76cfe454a2c9',
    size: 899_729,
    key:
      'apps/weibian-content/releases/0b3170748035504b/' +
      '0b3170748035504b44f947017855ec14893884d6c18acd996b6b76cfe454a2c9.json',
  }),
  fc68413c7b70da0e: Object.freeze({
    sha256: 'fc68413c7b70da0e1f14e36bb2229c4d9ae64fb8f26d75251f87d7457f8ffa75',
    size: 871_333,
    key:
      'apps/weibian-content/releases/fc68413c7b70da0e/' +
      'fc68413c7b70da0e1f14e36bb2229c4d9ae64fb8f26d75251f87d7457f8ffa75.json',
  }),
});

export function contentReleaseForVersion(version) {
  return CONTENT_RELEASES[String(version || '')] || null;
}

export function deltaObjectKey(filename) {
  if (!/^[a-f0-9]{8}-[a-f0-9]{8}\.json$/.test(String(filename || ''))) {
    return null;
  }
  return `apps/weibian-content/deltas/${filename}`;
}
