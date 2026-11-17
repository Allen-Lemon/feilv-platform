<template>
  <section class="wechat-settings">
    <div class="page-head">
      <div><span class="eyebrow">WECHAT SETTINGS</span><h1>微信配置</h1><p>集中管理登录与支付参数，让每一次配置变更都有据可循。</p></div>
      <el-button :disabled="busy" :loading="loading" @click="reloadSettings"><span class="button-content"><AppIcon name="refresh" :size="16" />重新加载</span></el-button>
    </div>

    <el-alert v-if="pageError" :title="pageError" type="error" show-icon :closable="false" class="notice">
      <template #default><el-button size="small" :disabled="busy" @click="reloadSettings">重试加载</el-button></template>
    </el-alert>
    <el-alert v-if="needsReload" title="配置已被其他管理员修改，或配置来源已变化。请重新加载后核对并再次编辑，不会自动覆盖。" type="warning" show-icon :closable="false" class="notice" />
    <el-alert v-if="ready && !encryptionReady" title="配置加密尚未就绪：可编辑草稿，暂不能保存生效" type="warning" show-icon :closable="false" class="notice">
      <template #default>请在后端运行环境中配置 CONFIG_ENCRYPTION_KEY（32 字节随机密钥的 Base64 编码），重启后端后重新加载。本机或 IDEA 启动需设置进程环境变量，Docker Compose 使用服务器 .env。不要在本页面填写主密钥，也不要用 AppSecret 或 API v3 密钥代替；已有加密配置时须恢复原主密钥，勿重新生成替换。</template>
    </el-alert>

    <div class="panel settings-panel" v-loading="loading">
      <div class="security-note"><AppIcon name="shield" :size="18" /><p>仅超级管理员（SUPER_ADMIN）或获授「微信配置」菜单的管理员可操作。机密只供本次提交，切换标签、提交成功或离开页面会清空所有机密输入及清除勾选；不会存储到本地。</p></div>
      <el-tabs v-model="tab" :before-leave="canLeaveTab">
        <el-tab-pane v-for="loginTab in loginTabs" :key="loginTab.section" :label="loginTab.label" :name="loginTab.section">
          <div class="panel-heading"><div><h2>{{ loginTab.label }}参数</h2><p>两端必须使用不同 AppID；每端 AppID 与 AppSecret 必须配套。骑手端未配置不会借用用户端凭据。</p></div><el-tag :type="savedFor(loginTab.section)?.enabled ? 'success' : 'info'">{{ savedFor(loginTab.section) ? (savedFor(loginTab.section).enabled ? '已保存 · 启用' : '已保存 · 关闭') : '尚未加载' }}</el-tag></div>
          <div v-if="savedFor(loginTab.section)" class="config-meta">
            <span>配置来源 <el-tag size="small" effect="plain">{{ savedFor(loginTab.section).source === 'DATABASE' ? '数据库 · DATABASE' : '环境变量 · ENVIRONMENT' }}</el-tag></span>
            <span>版本 {{ versionText(savedFor(loginTab.section).version) }}</span><span>更新时间 {{ savedFor(loginTab.section).updatedTime || '未记录' }}</span>
          </div>
          <el-alert title="原用户端 AppID 应保持不变。修改 AppID 不会自动合并原有账号；关闭仅影响该端新登录。修改 AppID 时须同时提供新 AppSecret 或明确清除旧值。" type="info" show-icon :closable="false" class="notice" />
          <el-alert v-if="savedFor(loginTab.section)?.source === 'ENVIRONMENT'" title="当前使用该端环境变量。首次保存会建立独立数据库配置；同端 AppID 不变且密钥留空时，后端会保留该端环境 AppSecret 并加密迁入数据库。" type="info" show-icon :closable="false" class="notice" />
          <el-form :ref="element => setLoginFormRef(loginTab.section, element)" :model="formFor(loginTab.section)" :rules="rulesFor(loginTab.section)" label-position="top" :disabled="fieldsDisabled" @submit.prevent>
            <el-form-item label="启用该端微信登录" prop="enabled"><el-switch v-model="formFor(loginTab.section).enabled" active-text="启用" inactive-text="关闭" /></el-form-item>
            <div class="form-grid">
              <el-form-item label="小程序 AppID" prop="appId"><el-input v-model="formFor(loginTab.section).appId" placeholder="wx 开头的 16 位十六进制标识" maxlength="18" autocomplete="off" spellcheck="false" /></el-form-item>
              <el-form-item prop="appSecret">
                <template #label><span class="secret-label">AppSecret <el-tag size="small" :type="savedFor(loginTab.section)?.appSecretConfigured ? 'success' : 'info'">{{ configuredText(savedFor(loginTab.section)?.appSecretConfigured) }}</el-tag></span></template>
                <el-input v-model="formFor(loginTab.section).appSecret" type="password" show-password autocomplete="new-password" :disabled="formFor(loginTab.section).clearAppSecret" :placeholder="secretPlaceholder(savedFor(loginTab.section)?.appSecretConfigured) + '，新值须为 32 位十六进制'" spellcheck="false" />
                <el-checkbox v-model="formFor(loginTab.section).clearAppSecret" class="clear-secret" @change="clearMarkedSecret(loginTab.section, 'appSecret', $event)">明确清除该端已保存的 AppSecret</el-checkbox>
              </el-form-item>
            </div>
            <p class="field-help">机密输入始终不回显原值。留空（包括纯空白）保存会保留原密钥；输入新值会替换。请勿填写 **** 等掩码。</p>
          </el-form>
          <div class="form-actions"><span>保存需要二次确认，后端执行最终校验。</span><div><el-button :loading="validating === loginTab.section" :disabled="!canValidate" @click="validateSaved(loginTab.section)">校验已保存配置</el-button><el-button type="primary" :disabled="!canSave" :loading="saving === loginTab.section" @click="saveSection(loginTab.section)">保存该端登录配置</el-button></div></div>
          <div v-if="actionErrors[loginTab.section]" class="action-error" role="alert">{{ actionErrors[loginTab.section] }}</div>
          <ValidationResult :result="validationResults[loginTab.section]" />
        </el-tab-pane>

        <el-tab-pane label="微信支付" name="PAYMENT">
          <div class="panel-heading"><div><h2>商户与支付参数</h2><p>参数集中保存，支付运行模式仍固定为 MOCK。</p></div><el-tag type="warning">MOCK · 模拟模式</el-tag></div>
          <div v-if="paymentSaved" class="config-meta"><span>已保存状态 {{ paymentSaved.enabled ? '启用' : '关闭' }}</span><span>版本 {{ versionText(paymentSaved.version) }}</span><span>更新时间 {{ paymentSaved.updatedTime || '未记录' }}</span><span>支付运行模式 MOCK</span></div>
          <el-alert title="启用参数不代表真实收单、回调或分账能力已接通" type="warning" show-icon :closable="false" class="notice">
            <template #default>当前仅 MOCK 模拟模式，不提供真实支付能力。下方校验只检查已保存参数的格式与完整性，不联网、不扣款，也不验证微信端可用性。</template>
          </el-alert>
          <el-form ref="paymentFormRef" :model="paymentForm" :rules="paymentRules" label-position="top" :disabled="fieldsDisabled" @submit.prevent>
            <el-form-item label="支付参数已准备（不启用真实支付）" prop="enabled"><el-switch v-model="paymentForm.enabled" active-text="参数已准备" inactive-text="未准备" /></el-form-item>
            <div class="form-grid">
              <el-form-item label="支付 AppID" prop="appId"><el-input v-model="paymentForm.appId" placeholder="wx 开头的 16 位十六进制标识" maxlength="18" autocomplete="off" spellcheck="false" /></el-form-item>
              <el-form-item label="商户号 mchId" prop="mchId"><el-input v-model="paymentForm.mchId" placeholder="8–32 位数字" maxlength="32" autocomplete="off" /></el-form-item>
              <el-form-item label="商户证书序列号 merchantSerialNo" prop="merchantSerialNo"><el-input v-model="paymentForm.merchantSerialNo" placeholder="16–64 位十六进制证书序列号" maxlength="64" autocomplete="off" spellcheck="false" /></el-form-item>
              <el-form-item label="平台公钥 ID platformPublicKeyId" prop="platformPublicKeyId"><el-input v-model="paymentForm.platformPublicKeyId" placeholder="PUB_KEY_ID_ 开头的平台公钥 ID" autocomplete="off" spellcheck="false" /></el-form-item>
              <el-form-item label="支付回调地址 notifyUrl" prop="notifyUrl"><el-input v-model="paymentForm.notifyUrl" placeholder="https://example.com/payment/notify" autocomplete="off" spellcheck="false" /></el-form-item>
              <el-form-item label="退款回调地址 refundNotifyUrl（选填）" prop="refundNotifyUrl"><el-input v-model="paymentForm.refundNotifyUrl" placeholder="https://example.com/refund/notify" autocomplete="off" spellcheck="false" /></el-form-item>
            </div>
            <div class="secret-section">
              <h3><AppIcon name="lock" :size="16" />支付机密材料</h3>
              <p class="field-help">仅粘贴本次要替换的内容，不保存到本地。留空保留原值，勾选「明确清除」才发送对应 clear 标记；清除后是否允许启用由后端最终判断。</p>
              <el-form-item prop="apiV3Key">
                <template #label><span class="secret-label">API v3 密钥 <el-tag size="small" :type="paymentSaved?.apiV3KeyConfigured ? 'success' : 'info'">{{ configuredText(paymentSaved?.apiV3KeyConfigured) }}</el-tag></span></template>
                <el-input v-model="paymentForm.apiV3Key" type="password" show-password autocomplete="new-password" :disabled="paymentForm.clearApiV3Key" :placeholder="secretPlaceholder(paymentSaved?.apiV3KeyConfigured) + '，新值须为 32 位 ASCII'" spellcheck="false" />
                <el-checkbox v-model="paymentForm.clearApiV3Key" class="clear-secret" @change="clearMarkedSecret('PAYMENT', 'apiV3Key', $event)">明确清除已保存的 API v3 密钥</el-checkbox>
              </el-form-item>
              <el-form-item v-for="field in pemFields" :key="field.key" :prop="field.key">
                <template #label><span class="secret-label">{{ field.label }} <el-tag size="small" :type="paymentSaved?.[field.configured] ? 'success' : 'info'">{{ configuredText(paymentSaved?.[field.configured]) }}</el-tag></span></template>
                <div class="pem-editor">
                  <div class="pem-toolbar"><span>{{ field.hint }}</span><el-button size="small" :disabled="paymentForm[field.clear]" @click="pemVisible[field.key] = !pemVisible[field.key]">{{ pemVisible[field.key] ? '隐藏内容' : '粘贴 / 编辑 PEM' }}</el-button></div>
                  <el-input v-if="pemVisible[field.key]" v-model="paymentForm[field.key]" type="textarea" :rows="6" :disabled="paymentForm[field.clear]" :placeholder="field.placeholder" autocomplete="off" spellcheck="false" :aria-label="field.label + '（敏感内容，仅本次提交）'" />
                  <el-input v-else :model-value="paymentForm[field.key]" type="password" readonly :disabled="paymentForm[field.clear]" :placeholder="secretPlaceholder(paymentSaved?.[field.configured]) + '；点击上方按钮粘贴'" autocomplete="new-password" :aria-label="field.label + '（已隐藏）'" />
                  <p v-if="pemVisible[field.key]" class="sensitive-hint">敏感内容当前可见，请注意周围环境；多行 PEM 不支持原生密码模式，使用后可点击隐藏。</p>
                </div>
                <el-checkbox v-model="paymentForm[field.clear]" class="clear-secret" @change="clearMarkedSecret('PAYMENT', field.key, $event)">明确清除已保存的{{ field.shortLabel }}</el-checkbox>
              </el-form-item>
            </div>
          </el-form>
          <div class="form-actions"><span>所有支付能力说明以 MOCK 模拟模式为准。</span><div><el-button :loading="validating === 'PAYMENT'" :disabled="!canValidate" @click="validateSaved('PAYMENT')">校验已保存配置</el-button><el-button type="primary" :disabled="!canSave" :loading="saving === 'PAYMENT'" @click="saveSection('PAYMENT')">保存支付配置</el-button></div></div>
          <div v-if="actionErrors.PAYMENT" class="action-error" role="alert">{{ actionErrors.PAYMENT }}</div>
          <ValidationResult :result="validationResults.PAYMENT" />
        </el-tab-pane>
      </el-tabs>
      <p class="validation-note">「校验已保存配置」不会提交页面草稿，只检查后端已保存的格式与完整性，不联网、不扣款。关闭状态可保存不完整参数；已填写的值仍需满足基本格式。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElAlert, ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import AppIcon from '../components/AppIcon.vue'

const tab = ref('LOGIN')
const loading = ref(false), confirming = ref(false), saving = ref(''), validating = ref('')
const ready = ref(false), authorized = ref(false), encryptionReady = ref(false), needsReload = ref(false)
const pageError = ref('')
const loginTabs = [{ section: 'LOGIN', clientType: 'USER', label: '用户端微信登录' }, { section: 'LOGIN_RIDER', clientType: 'RIDER', label: '骑手端微信登录' }]
const sections = ['LOGIN', 'LOGIN_RIDER', 'PAYMENT']
const actionErrors = reactive({ LOGIN: '', LOGIN_RIDER: '', PAYMENT: '' })
const validationResults = reactive({ LOGIN: null, LOGIN_RIDER: null, PAYMENT: null })
const loginSaved = ref(null), riderLoginSaved = ref(null), paymentSaved = ref(null)
const loginFormRef = ref(), riderLoginFormRef = ref(), paymentFormRef = ref()
const loginForm = reactive({ enabled: false, appId: '', appSecret: '', clearAppSecret: false })
const riderLoginForm = reactive({ enabled: false, appId: '', appSecret: '', clearAppSecret: false })
const paymentForm = reactive({ enabled: false, appId: '', mchId: '', merchantSerialNo: '', notifyUrl: '', refundNotifyUrl: '', platformPublicKeyId: '', apiV3Key: '', merchantPrivateKey: '', platformPublicKey: '', clearApiV3Key: false, clearMerchantPrivateKey: false, clearPlatformPublicKey: false })
const pemVisible = reactive({ merchantPrivateKey: false, platformPublicKey: false })
const paymentTextFields = ['appId', 'mchId', 'merchantSerialNo', 'notifyUrl', 'refundNotifyUrl', 'platformPublicKeyId']
const secretFields = {
  LOGIN: [{ key: 'appSecret', clear: 'clearAppSecret', configured: 'appSecretConfigured', label: 'AppSecret' }],
  LOGIN_RIDER: [{ key: 'appSecret', clear: 'clearAppSecret', configured: 'appSecretConfigured', label: 'AppSecret' }],
  PAYMENT: [
    { key: 'apiV3Key', clear: 'clearApiV3Key', configured: 'apiV3KeyConfigured', label: 'API v3 密钥' },
    { key: 'merchantPrivateKey', clear: 'clearMerchantPrivateKey', configured: 'merchantPrivateKeyConfigured', label: '商户私钥' },
    { key: 'platformPublicKey', clear: 'clearPlatformPublicKey', configured: 'platformPublicKeyConfigured', label: '平台公钥' }
  ]
}
const pemFields = [
  { ...secretFields.PAYMENT[1], shortLabel: '商户私钥', label: '商户私钥 merchantPrivateKey', hint: 'PKCS#8 RSA 2048 位及以上，须为 BEGIN PRIVATE KEY（非 RSA PRIVATE KEY）', placeholder: '-----BEGIN PRIVATE KEY-----\n仅粘贴 PKCS#8 PEM 内容\n-----END PRIVATE KEY-----' },
  { ...secretFields.PAYMENT[2], shortLabel: '平台公钥', label: '平台公钥 platformPublicKey', hint: 'RSA 2048 位及以上 PUBLIC KEY PEM，非证书或私钥', placeholder: '-----BEGIN PUBLIC KEY-----\n仅粘贴平台 PUBLIC KEY PEM 内容\n-----END PUBLIC KEY-----' }
]
let disposed = false
const busy = computed(() => loading.value || confirming.value || !!saving.value || !!validating.value)
const canValidate = computed(() => ready.value && authorized.value && !needsReload.value && !busy.value)
const canSave = computed(() => canValidate.value && encryptionReady.value)
const fieldsDisabled = computed(() => !canValidate.value)
const canLeaveTab = () => !busy.value
const configuredText = (configured) => !ready.value ? '尚未加载' : configured ? '已配置' : '未配置'
const secretPlaceholder = (configured) => configured ? '已配置，留空保留原值' : '未配置，请按需输入'
const versionText = (version) => version === null ? '无数据库版本' : String(version)
function requireSection(section) { if (!sections.includes(section)) throw new Error('INVALID_SECTION'); return section }
const formFor = (section) => ({ LOGIN: loginForm, LOGIN_RIDER: riderLoginForm, PAYMENT: paymentForm })[requireSection(section)]
const savedRefFor = (section) => ({ LOGIN: loginSaved, LOGIN_RIDER: riderLoginSaved, PAYMENT: paymentSaved })[requireSection(section)]
const savedFor = (section) => savedRefFor(section).value
const formRefFor = (section) => ({ LOGIN: loginFormRef, LOGIN_RIDER: riderLoginFormRef, PAYMENT: paymentFormRef })[requireSection(section)]
const clientFor = (section) => loginTabs.find(item => item.section === requireSection(section))?.clientType
function setLoginFormRef(section, element) { formRefFor(section).value = element }
const hasValue = (value) => typeof value === 'string' && value.trim().length > 0
const isMask = (value) => /^[*＊•●·]+$/.test(value.trim())

function clearAllSecrets() {
  for (const section of sections) {
    const form = formFor(section)
    for (const field of secretFields[section]) { form[field.key] = ''; form[field.clear] = false }
  }
  pemVisible.merchantPrivateKey = false
  pemVisible.platformPublicKey = false
  for (const section of sections) formRefFor(section).value?.clearValidate()
}
function clearMarkedSecret(section, key, checked) {
  if (checked) {
    formFor(section)[key] = ''
    if (key in pemVisible) pemVisible[key] = false
  }
  const formRef = formRefFor(section)
  formRef.value?.clearValidate(key)
}
watch(tab, clearAllSecrets, { flush: 'sync' })

// 仅在启用时要求完整；显式清除机密交由后端最终判断，不能因已配置占位而提交掩码。
function textRule(form, label, test = () => true, format = '', requiredWhenEnabled = true) {
  return [{ trigger: 'blur', validator: (_rule, value, callback) => {
    const text = String(value || '').trim()
    if (!text) return callback(form.enabled && requiredWhenEnabled ? new Error(`启用时请填写${label}`) : undefined)
    callback(test(text) ? undefined : new Error(format || `${label}格式不正确`))
  } }]
}
function secretRule(section, key, test = () => true, format = '') {
  const field = secretFields[section].find(item => item.key === key)
  return [{ trigger: 'blur', validator: (_rule, value, callback) => {
    const form = formFor(section)
    if (form[field.clear]) return callback()
    if (!hasValue(value)) return callback(form.enabled && !savedFor(section)?.[field.configured] ? new Error(`启用时请填写${field.label}，或明确勾选清除交由后端校验`) : undefined)
    if (isMask(value)) return callback(new Error('请勿提交掩码；留空即可保留原值'))
    Promise.resolve().then(() => test(value)).then(valid => callback(valid ? undefined : new Error(format || `${field.label}格式不正确`))).catch(() => callback(new Error(format || `${field.label}格式不正确`)))
  } }]
}
function isHttpsUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !!url.hostname && !url.username && !url.password && !url.hash && !/\s/.test(value)
  } catch { return false }
}
const isAppId = (value) => /^wx[0-9a-fA-F]{16}$/.test(value)
const isPem = (value, kind) => new RegExp(`^-----BEGIN ${kind}-----[\\r\\n]+[A-Za-z0-9+/=\\s]+[\\r\\n]+-----END ${kind}-----$`).test(value.trim())
async function isRsaPem(value, privateKey) {
  const kind = privateKey ? 'PRIVATE KEY' : 'PUBLIC KEY'
  if (!isPem(value, kind)) return false
  // HTTP 开发环境可能没有 subtle；此时仅做 PEM 基本格式校验，由后端最终检查 RSA 与位数。
  if (!globalThis.crypto?.subtle) return true
  let der
  try {
    const base64 = value.trim().replace(`-----BEGIN ${kind}-----`, '').replace(`-----END ${kind}-----`, '').replace(/\s/g, '')
    der = Uint8Array.from(atob(base64), character => character.charCodeAt(0))
    const key = await globalThis.crypto.subtle.importKey(privateKey ? 'pkcs8' : 'spki', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, [privateKey ? 'sign' : 'verify'])
    return key.algorithm.modulusLength >= 2048
  } catch { return false }
  finally { der?.fill(0) }
}
const loginRules = {
  appId: textRule(loginForm, 'AppID', isAppId, 'AppID 须为 wx + 16 位十六进制字符'),
  appSecret: secretRule('LOGIN', 'appSecret', value => /^[0-9a-fA-F]{32}$/.test(value), 'AppSecret 新值须为 32 位十六进制字符')
}
const riderLoginRules = {
  appId: textRule(riderLoginForm, '骑手端AppID', isAppId, 'AppID 须为 wx + 16 位十六进制字符'),
  appSecret: secretRule('LOGIN_RIDER', 'appSecret', value => /^[0-9a-fA-F]{32}$/.test(value), 'AppSecret 新值须为 32 位十六进制字符')
}
const rulesFor = section => ({ LOGIN: loginRules, LOGIN_RIDER: riderLoginRules, PAYMENT: paymentRules })[requireSection(section)]
const paymentRules = {
  appId: textRule(paymentForm, 'AppID', isAppId, 'AppID 须为 wx + 16 位十六进制字符'),
  mchId: textRule(paymentForm, '商户号', value => /^\d{8,32}$/.test(value), '商户号须为 8–32 位数字'),
  merchantSerialNo: textRule(paymentForm, '商户证书序列号', value => /^[0-9a-fA-F]{16,64}$/.test(value), '证书序列号须为 16–64 位十六进制字符'),
  platformPublicKeyId: textRule(paymentForm, '平台公钥 ID', value => /^PUB_KEY_ID_[A-Za-z0-9_]{1,100}$/.test(value), '平台公钥 ID 须为 PUB_KEY_ID_ + 1–100 位字母、数字或下划线'),
  notifyUrl: textRule(paymentForm, '支付回调地址', isHttpsUrl, '请填写 HTTPS 回调地址，且不包含账号、密码或片段'),
  refundNotifyUrl: textRule(paymentForm, '退款回调地址', isHttpsUrl, '请填写 HTTPS 回调地址，且不包含账号、密码或片段', false),
  apiV3Key: secretRule('PAYMENT', 'apiV3Key', value => /^[\x21-\x7e]{32}$/.test(value), 'API v3 密钥须为 32 位 ASCII 可见字符（不含空格）'),
  merchantPrivateKey: secretRule('PAYMENT', 'merchantPrivateKey', value => isRsaPem(value, true), '请粘贴 PKCS#8 RSA 2048 位及以上私钥 PEM（BEGIN / END PRIVATE KEY）'),
  platformPublicKey: secretRule('PAYMENT', 'platformPublicKey', value => isRsaPem(value, false), '请粘贴 RSA 2048 位及以上平台公钥 PEM（BEGIN / END PUBLIC KEY）')
}

// 严格读取脱敏字段：即便服务端意外添加机密字段，也不回填表单或缓存原响应。
function readSection(data, section) {
  requireSection(section)
  const versionValid = data?.version === null || (Number.isSafeInteger(data?.version) && data.version >= 0) || (typeof data?.version === 'string' && /^\d+$/.test(data.version))
  if (!data || typeof data.enabled !== 'boolean' || typeof data.appId !== 'string' || !versionValid || !(data.updatedTime === null || typeof data.updatedTime === 'string')) throw new Error('INVALID_RESPONSE')
  const result = { enabled: data.enabled, appId: data.appId, version: data.version, updatedTime: data.updatedTime }
  if (section !== 'PAYMENT') {
    if (typeof data.appSecretConfigured !== 'boolean' || !['DATABASE', 'ENVIRONMENT'].includes(data.source)) throw new Error('INVALID_RESPONSE')
    return { ...result, appSecretConfigured: data.appSecretConfigured, source: data.source }
  }
  for (const key of paymentTextFields) {
    if (typeof data[key] !== 'string') throw new Error('INVALID_RESPONSE')
    result[key] = data[key]
  }
  for (const field of secretFields.PAYMENT) {
    if (typeof data[field.configured] !== 'boolean') throw new Error('INVALID_RESPONSE')
    result[field.configured] = data[field.configured]
  }
  if (data.mode !== 'MOCK') throw new Error('INVALID_RESPONSE')
  return { ...result, mode: 'MOCK' }
}
function applySection(data, section) {
  const form = formFor(section)
  form.enabled = data.enabled
  form.appId = data.appId
  if (section === 'PAYMENT') for (const key of paymentTextFields) form[key] = data[key]
  savedRefFor(section).value = data
}
function errorInfo(error) {
  const body = error?.response?.data || error
  return { status: Number(error?.response?.status), code: Number(body?.code) }
}
function handleActionError(error, section, action) {
  const { status, code } = errorInfo(error)
  let message
  if (status === 409 || code === 409) {
    needsReload.value = true
    message = '配置来源或版本已变化（409），本次操作未完成。请重新加载后核对，不会自动重试覆盖。'
  } else if (status === 503 || code === 503) {
    encryptionReady.value = false
    message = '后台配置加密不可用（503），保存已禁用。请联系运维检查 CONFIG_ENCRYPTION_KEY（32 字节密钥的 Base64 编码），勿填写在页面，修复后重新加载。本次输入已保留。'
  } else if ([401, 403].includes(status) || [401, 403].includes(code)) {
    authorized.value = false
    ready.value = false
    pageError.value = '无权访问微信配置或登录已失效。仅 SUPER_ADMIN 或拥有 wechat-settings 菜单权限的管理员可操作，请重新登录或联系超级管理员后重试。'
    message = pageError.value
  } else if (error?.message === 'INVALID_RESPONSE') {
    ready.value = false
    pageError.value = '后端返回的配置格式不完整，已禁用保存。请重新加载，必要时联系后台排查接口。'
    message = pageError.value
  } else {
    // 不展示原始异常或请求体，避免服务端错误意外回显本次机密。
    message = `${action}失败，未获得成功结果。请检查参数、网络和后台状态后重试；本次输入已保留。`
  }
  actionErrors[section] = message
  ElMessage.error(message)
}

async function loadSettings() {
  if (disposed || loading.value) return
  loading.value = true
  ready.value = false
  authorized.value = false
  pageError.value = ''
  try {
    const profile = await adminApi.profile({ silentError: true })
    if (disposed) return
    authorized.value = (profile.roles || []).includes('SUPER_ADMIN') || (profile.menus || []).some(menu => menu.code === 'wechat-settings')
    if (!authorized.value) {
      pageError.value = '访问被拒绝：仅 SUPER_ADMIN 或获授 wechat-settings 菜单的管理员可管理微信配置。请联系超级管理员授权后重试。'
      return
    }
    const data = await adminApi.wechatSettings()
    if (disposed) return
    if (typeof data?.encryptionReady !== 'boolean' || data.paymentRuntime !== 'MOCK') throw new Error('INVALID_RESPONSE')
    const login = readSection(data.login, 'LOGIN'), riderLogin = readSection(data.riderLogin, 'LOGIN_RIDER'), payment = readSection(data.payment, 'PAYMENT')
    clearAllSecrets()
    applySection(login, 'LOGIN')
    applySection(riderLogin, 'LOGIN_RIDER')
    applySection(payment, 'PAYMENT')
    encryptionReady.value = data.encryptionReady
    needsReload.value = false
    for (const section of sections) { actionErrors[section] = ''; validationResults[section] = null }
    ready.value = true
  } catch (error) {
    if (disposed) return
    const { status, code } = errorInfo(error)
    const denied = [401, 403].includes(status) || [401, 403].includes(code)
    pageError.value = denied ? '微信配置加载被拒绝或登录已失效。请确认拥有 SUPER_ADMIN 角色或 wechat-settings 菜单权限后重试。' : '微信配置加载失败，保存与校验已禁用。请重试加载；不会使用默认值冒充已保存配置。'
    if (denied) authorized.value = false
  } finally { if (!disposed) loading.value = false }
}
async function reloadSettings() {
  if (disposed || busy.value) return
  const hasSecrets = sections.some(section => secretFields[section].some(field => hasValue(formFor(section)[field.key]) || formFor(section)[field.clear]))
  if (ready.value || needsReload.value || hasSecrets) {
    confirming.value = true
    try {
      await ElMessageBox.confirm('重新加载成功后会覆盖页面未保存的修改，并清空所有机密输入及清除勾选。是否继续？', '重新加载微信配置', { type: 'warning', confirmButtonText: '重新加载', cancelButtonText: '保留编辑' })
    } catch { return } finally { confirming.value = false }
  }
  await loadSettings()
}
function buildPayload(section) {
  const form = formFor(section)
  const payload = { enabled: form.enabled, appId: form.appId.trim(), version: savedFor(section).version }
  if (section === 'PAYMENT') for (const key of paymentTextFields) payload[key] = form[key].trim()
  for (const field of secretFields[section]) {
    payload[field.clear] = form[field.clear] === true
    const value = form[field.key]
    const isPemField = field.key === 'merchantPrivateKey' || field.key === 'platformPublicKey'
    payload[field.key] = payload[field.clear] || !hasValue(value) ? '' : (isPemField ? value.trim().replace(/\r\n/g, '\n') : value)
  }
  return payload
}
function confirmationText(section) {
  const form = formFor(section), saved = savedFor(section)
  const replacing = secretFields[section].filter(field => !form[field.clear] && hasValue(form[field.key])).map(field => field.label)
  const clearing = secretFields[section].filter(field => form[field.clear]).map(field => field.label)
  const messages = [`将保存${section === 'PAYMENT' ? '微信支付' : loginTabs.find(item => item.section === section).label}配置（当前版本：${versionText(saved.version)}）。`]
  if (section !== 'PAYMENT') {
    messages.push(`当前来源：${saved.source}。AppID 与 AppSecret 须与同一小程序一致，后端保存后生效。`)
    if (!form.enabled) messages.push('保存后将关闭该端微信登录，不影响另一端的独立配置。')
    if (saved.appId !== form.appId.trim()) messages.push('AppID 将发生变化，原有账号不会自动合并。')
  } else messages.push('支付模式固定 MOCK；启用参数也不代表真实收单、回调或分账已接通。')
  if (replacing.length) messages.push(`将替换：${replacing.join('、')}。旧值不会回显，请确认新值正确。`)
  if (clearing.length) messages.push(`将明确清除：${clearing.join('、')}。可能导致功能不可用，是否允许保存由后端最终判断。`)
  messages.push('其他留空的机密保留原值。确认继续？')
  return messages.join('\n\n')
}
async function saveSection(section) {
  requireSection(section)
  if (disposed || !canSave.value) return
  confirming.value = true
  try {
    const formRef = formRefFor(section)
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || disposed) return
    try {
      await ElMessageBox.confirm(confirmationText(section), '确认保存微信配置', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '继续编辑', customClass: 'wechat-save-confirm' })
    } catch { return }
    if (disposed) return
    saving.value = section
    confirming.value = false
    actionErrors[section] = ''
    try {
      const response = await (section === 'PAYMENT' ? adminApi.saveWechatPayment(buildPayload(section)) : adminApi.saveWechatLogin(buildPayload(section), clientFor(section)))
      if (disposed) return
      const data = readSection(response, section)
      clearAllSecrets()
      applySection(data, section)
      validationResults[section] = null
      ElMessage.success(section !== 'PAYMENT' ? '该端微信登录配置已保存，所有机密输入已清空' : '微信支付参数已保存，当前仍为 MOCK 模式；所有机密输入已清空')
    } catch (error) { if (!disposed) handleActionError(error, section, '保存') }
  } finally { if (!disposed) { saving.value = ''; confirming.value = false } }
}
async function validateSaved(section) {
  requireSection(section)
  if (disposed || !canValidate.value) return
  validating.value = section
  actionErrors[section] = ''
  validationResults[section] = null
  try {
    const data = await (section === 'PAYMENT' ? adminApi.validateWechat('PAYMENT') : adminApi.validateWechat('LOGIN', clientFor(section)))
    if (disposed) return
    if (typeof data?.valid !== 'boolean' || !Array.isArray(data.messages) || !data.messages.every(message => typeof message === 'string') || data.runtime !== (section === 'PAYMENT' ? 'MOCK' : 'WECHAT_CODE2SESSION')) throw new Error('INVALID_RESPONSE')
    validationResults[section] = { valid: data.valid, messages: [...data.messages], runtime: data.runtime }
  } catch (error) { if (!disposed) handleActionError(error, section, '校验') }
  finally { if (!disposed) validating.value = '' }
}
const ValidationResult = defineComponent({
  props: { result: Object },
  setup: (props) => () => props.result ? h(ElAlert, { type: props.result.valid ? 'success' : 'warning', showIcon: true, closable: false, class: 'validation-result', title: props.result.valid ? '已保存配置的格式与完整性校验通过' : '已保存配置校验未通过' }, {
    default: () => [
      h('p', `后端运行模式：${props.result.runtime}。仅针对已保存配置，不联网、不扣款；不代表真实微信能力已接通。`),
      h('ul', props.result.messages.map((message, index) => h('li', { key: index }, message)))
    ]
  }) : null
})
onMounted(loadSettings)
onBeforeUnmount(() => {
  disposed = true
  if (confirming.value) ElMessageBox.close()
  clearAllSecrets()
})
</script>

<style scoped>
.notice { margin-bottom: 18px; }
.notice :deep(.el-alert__description) { line-height: 1.8; }
.notice .el-button { margin-top: 8px; }
.security-note { display: flex; align-items: flex-start; gap: 10px; padding: 14px 16px; margin-bottom: 12px; border: 1px solid #f5e1d4; border-radius: 11px; background: #fff8f3; color: #855034; }
.security-note svg { flex-shrink: 0; margin-top: 2px; }
.security-note p { font-size: 12px; line-height: 1.8; }
.config-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 24px; margin: -4px 0 22px; color: var(--ink-secondary); font-size: 12px; overflow-wrap: anywhere; }
.config-meta > span { display: inline-flex; align-items: center; gap: 7px; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 0 24px; }
.secret-label { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.clear-secret { display: flex; max-width: 100%; height: auto; min-height: 32px; margin: 6px 0 0; }
.clear-secret :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; font-size: 12px; }
.field-help { color: var(--ink-secondary); line-height: 1.8; font-size: 12px; margin-bottom: 20px; }
.secret-section { margin-top: 8px; padding: 22px 22px 2px; border: 1px solid var(--line); background: #fdfcfb; border-radius: 12px; }
.secret-section h3 { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; margin-bottom: 10px; }
.pem-editor { width: 100%; min-width: 0; }
.pem-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.pem-toolbar > span { font-size: 12px; line-height: 1.6; color: var(--ink-secondary); }
.pem-editor :deep(.el-textarea__inner) { font-family: ui-monospace, Consolas, monospace; font-size: 12px; line-height: 1.6; padding: 12px; }
.sensitive-hint { color: #a35022; font-size: 12px; line-height: 1.7; margin-top: 8px; }
.form-actions { border-top: 1px solid var(--line); margin-top: 12px; padding-top: 22px; display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.form-actions > span, .validation-note { color: var(--ink-secondary); font-size: 12px; line-height: 1.8; }
.form-actions > div { display: flex; gap: 10px; flex-wrap: wrap; }
.form-actions .el-button { margin-left: 0; }
.action-error { margin-top: 16px; color: var(--el-color-danger); font-size: 13px; line-height: 1.8; }
.validation-result { margin-top: 18px; }
.validation-result :deep(ul) { padding-left: 18px; margin-top: 7px; }
.validation-note { margin-top: 24px; }
@media (max-width: 900px) { .form-grid { grid-template-columns: minmax(0, 1fr); }.panel-heading { align-items: flex-start; }.secret-section { padding: 18px 14px 0; } }
@media (max-width: 600px) { .form-actions > div { width: 100%; }.form-actions .el-button { flex: 1; }.config-meta { gap: 10px; flex-direction: column; align-items: flex-start; } }
</style>

<style>
.wechat-save-confirm .el-message-box__message { white-space: pre-line; overflow-wrap: anywhere; }
</style>
