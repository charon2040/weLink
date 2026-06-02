<template>
  <div class="login-container">
    <div class="login-shell">
      <section class="login-hero">
        <div class="hero-kicker">WeLink</div>
        <h1 class="login-title">统一的即时通讯工作台</h1>
        <p class="login-subtitle">
          保持联系人、会话和群协作同一套界面语言，让消息管理更清晰，切换页面也更自然。
        </p>
        <div class="hero-points">
          <div class="hero-point">
            <strong>消息</strong>
            <span>会话聚合、历史搜索、上下文跳转</span>
          </div>
          <div class="hero-point">
            <strong>联系人</strong>
            <span>好友、群组、申请统一归档管理</span>
          </div>
          <div class="hero-point">
            <strong>协作</strong>
            <span>围绕群聊与文件共享保持一致体验</span>
          </div>
        </div>
      </section>

      <section class="login-card">
        <div class="login-card-head">
          <h2>欢迎使用</h2>
          <p>登录或创建账号，进入你的消息工作区。</p>
        </div>
        <div class="login-highlights">
          <div class="login-highlight">
            <span>界面</span>
            <strong>统一卡片风格</strong>
          </div>
          <div class="login-highlight">
            <span>入口</span>
            <strong>消息与联系人</strong>
          </div>
        </div>

        <el-tabs v-model="activeTab" class="login-tabs">
          <el-tab-pane label="登录" name="login">
            <el-form :model="loginForm" :rules="loginRules" ref="loginFormRef">
              <el-form-item prop="username">
                <el-input
                  v-model="loginForm.username"
                  placeholder="用户名"
                  prefix-icon="User"
                  size="large"
                />
              </el-form-item>
              <el-form-item prop="password">
                <el-input
                  v-model="loginForm.password"
                  type="password"
                  placeholder="密码"
                  prefix-icon="Lock"
                  size="large"
                  show-password
                  @keyup.enter="handleLogin"
                />
              </el-form-item>
              <el-form-item>
                <el-button
                  type="primary"
                  size="large"
                  class="submit-btn"
                  :loading="loading"
                  @click="handleLogin"
                >
                  登录
                </el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="注册" name="register">
            <el-form :model="registerForm" :rules="registerRules" ref="registerFormRef">
              <el-form-item prop="username">
                <el-input
                  v-model="registerForm.username"
                  placeholder="用户名（4-20字符）"
                  prefix-icon="User"
                  size="large"
                />
              </el-form-item>
              <el-form-item prop="password">
                <el-input
                  v-model="registerForm.password"
                  type="password"
                  placeholder="密码（6-32字符）"
                  prefix-icon="Lock"
                  size="large"
                  show-password
                />
              </el-form-item>
              <el-form-item prop="confirmPassword">
                <el-input
                  v-model="registerForm.confirmPassword"
                  type="password"
                  placeholder="确认密码"
                  prefix-icon="Lock"
                  size="large"
                  show-password
                />
              </el-form-item>
              <el-form-item>
                <el-button
                  type="primary"
                  size="large"
                  class="submit-btn"
                  :loading="loading"
                  @click="handleRegister"
                >
                  注册
                </el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('login')
const loading = ref(false)
const loginFormRef = ref(null)
const registerFormRef = ref(null)

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const validateConfirmPassword = (rule, value, callback) => {
  if (value !== registerForm.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '长度在 4 到 20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '长度在 6 到 32 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

const handleLogin = async () => {
  const valid = await loginFormRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(loginForm)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (error) {
    console.error('Login failed:', error)
  } finally {
    loading.value = false
  }
}

const handleRegister = async () => {
  const valid = await registerFormRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.register({
      username: registerForm.username,
      password: registerForm.password
    })
    ElMessage.success('注册成功，请登录')
    activeTab.value = 'login'
    loginForm.username = registerForm.username
  } catch (error) {
    console.error('Register failed:', error)
    if (error.response?.data?.message) {
      ElMessage.error(error.response.data.message)
    } else if (error.message) {
      ElMessage.error(error.message)
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 24px;
  box-sizing: border-box;
  background:
    radial-gradient(circle at top left, rgba(255, 255, 255, 0.16), transparent 24%),
    linear-gradient(135deg, #1f4b7a 0%, #409eff 55%, #6bb7ff 100%);
}

.login-shell {
  width: min(1080px, 100%);
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 24px;
  align-items: stretch;
}

.login-hero {
  padding: 38px 40px;
  border-radius: 30px;
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
  box-shadow: 0 24px 56px rgba(17, 24, 39, 0.18);
  backdrop-filter: blur(14px);
}

.hero-kicker {
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.72);
}

.login-title {
  margin: 14px 0 12px;
  font-size: 40px;
  line-height: 1.15;
}

.login-subtitle {
  margin: 0;
  max-width: 560px;
  font-size: 15px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.86);
}

.hero-points {
  display: grid;
  gap: 14px;
  margin-top: 28px;
}

.hero-point {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 20px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.14);
}

.hero-point strong {
  font-size: 16px;
}

.hero-point span {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.78);
}

.login-card {
  padding: 32px;
  background: rgba(255, 255, 255, 0.94);
  border-radius: 30px;
  box-shadow: 0 24px 56px rgba(17, 24, 39, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.4);
}

.login-card-head h2 {
  margin: 0;
  font-size: 28px;
  color: #1f2d3d;
}

.login-card-head p {
  margin: 10px 0 0;
  color: #7a8699;
  font-size: 14px;
  line-height: 1.6;
}

.login-tabs {
  margin-top: 22px;
}

.login-highlights {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 20px;
}

.login-highlight {
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid #e8eef5;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.login-highlight span {
  display: block;
  font-size: 12px;
  color: #8a94a6;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.login-highlight strong {
  display: block;
  margin-top: 8px;
  color: #1f2d3d;
  font-size: 15px;
}

.submit-btn {
  width: 100%;
  height: 48px;
  border-radius: 14px;
  font-weight: 600;
  box-shadow: 0 14px 30px rgba(64, 158, 255, 0.22);
}

:deep(.login-tabs .el-tabs__header) {
  margin-bottom: 20px;
}

:deep(.login-tabs .el-tabs__nav-wrap::after) {
  background-color: #edf1f7;
}

:deep(.login-tabs .el-tabs__item) {
  height: 42px;
  font-size: 15px;
  font-weight: 600;
  color: #7a8699;
}

:deep(.login-tabs .el-tabs__item.is-active) {
  color: #409eff;
}

:deep(.login-tabs .el-input__wrapper) {
  border-radius: 16px;
  box-shadow: 0 0 0 1px #e8eef5 inset;
  min-height: 48px;
}

:deep(.login-tabs .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #409eff inset, 0 10px 24px rgba(64, 158, 255, 0.12);
}

:deep(.login-tabs .el-form-item) {
  margin-bottom: 18px;
}

@media (max-width: 900px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-title {
    font-size: 32px;
  }
}

@media (max-width: 600px) {
  .login-container {
    padding: 14px;
  }

  .login-hero,
  .login-card {
    padding: 24px 20px;
  }

  .login-highlights {
    grid-template-columns: 1fr;
  }
}
</style>
