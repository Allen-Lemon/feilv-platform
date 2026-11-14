// 省市区由微信原生 region picker 提供，不内置易过期的行政区划清单。
const DEFAULT_REGION = ['贵州省', '铜仁市', '德江县']
const text = value => typeof value === 'string' ? value.trim() : ''
function region(value) {
  if (!Array.isArray(value) || value.length !== 3) return null
  const result = value.map(text)
  if (result.some(part => !part || part.length > 64 || /[\u0000-\u001f\u007f]/.test(part))) return null
  return result
}
function regionOf(value) { return region([value.province, value.city, value.district]) }
function fullAddress(value) {
  const selected = regionOf(value)
  return selected && text(value.detailAddress) ? selected.join('') + text(value.detailAddress) : text(value.address)
}
function orderAddress(value) { return [text(value.poiName), fullAddress(value)].filter(Boolean).join(' ') }
function label(value) {
  return [Number(value.isDefault) === 1 ? '[默认]' : '', text(value.contact), text(value.phone), orderAddress(value)].filter(Boolean).join(' · ')
}
function payload(value) {
  return {
    contact: text(value.contact), phone: text(value.phone), poiName: text(value.poiName), address: text(value.address),
    province: text(value.province), city: text(value.city), district: text(value.district), detailAddress: text(value.detailAddress),
    lng: value.lng == null ? null : value.lng, lat: value.lat == null ? null : value.lat,
    isDefault: Number(value.isDefault) === 1 ? 1 : 0
  }
}
module.exports = { DEFAULT_REGION, region, regionOf, fullAddress, orderAddress, label, payload }
