// 金额在接口边界始终为安全整数分；不使用浮点乘法 / 四舍五入吞掉小数分。
export const isFen = value => Number.isSafeInteger(value) && value >= 0

export function formatFen(value) {
  if (!Number.isSafeInteger(value)) return '—'
  const absolute = Math.abs(value)
  return `${value < 0 ? '-' : ''}${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`
}

export function yuanToFen(value) {
  const text = String(value ?? '').trim()
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null
  const [yuan, decimal = ''] = text.split('.')
  const fen = BigInt(yuan) * 100n + BigInt(decimal.padEnd(2, '0'))
  return fen <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(fen) : null
}
