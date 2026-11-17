const { definePage } = require('../../utils/page')
const profilePage = require('../../utils/profile-page')

// 登录页和独立资料页共用授权、资料写入与切号隔离逻辑。
definePage(profilePage)
