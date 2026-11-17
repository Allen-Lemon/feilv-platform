const { initializeServerConfig } = require('./utils/request')

App({
  globalData: { name: '飞毛腿跑腿' },
  onLaunch() {
    initializeServerConfig()
    // 不在启动时收集位置、手机号；定位只在骑手主动上报时调用。
    console.info('飞毛腿微信原生小程序启动')
  }
})
