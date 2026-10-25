<template>
  <div class="login-register">
    <div class="login-register-container">
      <el-tabs stretch class="login-tabs" @tab-click="handleClick">
        <el-tab-pane label="登录" lazy>
          <div class="login-box">
            <!-- 关闭自动校验：设置 :validate-on-rule-change="false" 禁止规则变化时自动校验 -->
            <el-form
              :model="loginForm"
              style="width: 100%"
              :rules="loginRules"
              :validate-on-rule-change="false"
            >
              <el-form-item style="width: 100%" label="" prop="account">
                <div style="position: relative; width: 100%">
                  <el-input
                    type="text"
                    class="input"
                    v-model="loginForm.account"
                    placeholder="请输入手机号/邮箱"
                    maxlength="30"
                  />
                  <div
                    v-if="loginType === 2"
                    class="sms-send"
                    :style="
                      loginAccountIsValid && loginCountdown <= 0
                        ? 'background-color: #00AEEC;'
                        : 'background-color: #e8e8e8;cursor:not-allowed'
                    "
                    @click="getSmsCode('login')"
                  >
                    {{ loginCodeText }}
                  </div>
                </div>
              </el-form-item>

              <el-form-item
                v-if="loginType === 1"
                style="width: 100%"
                label=""
                prop="password"
              >
                <el-input
                  type="password"
                  show-password
                  class="input"
                  v-model="loginForm.password"
                  placeholder="请输入密码"
                />
              </el-form-item>
              <el-form-item v-if="loginType === 2" label="" prop="code">
                <el-input
                  type="text"
                  class="input"
                  v-model="loginForm.code"
                  placeholder="请输入验证码"
                />
              </el-form-item>
            </el-form>
            <div class="login-type" @click="changeLoginType">
              {{ loginTypeText }}
            </div>
            <div class="submit" @click="submitLogin">登&nbsp;录</div>
            <div class="tips">
              登录即代表你同意我们的<span class="agreement">用户协议</span>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="注册" lazy>
          <div class="register-box">
            <el-form
              :model="registerForm"
              style="width: 100%"
              :rules="registerRules"
            >
              <el-form-item style="width: 100%" label="" prop="account">
                <div style="position: relative; width: 100%">
                  <el-input
                    type="text"
                    class="input"
                    v-model="registerForm.account"
                    placeholder="请输入手机号/邮箱"
                    maxlength="30"
                  />
                  <div
                    class="sms-send"
                    :style="
                      resgAccountIsValid && resgCountdown <= 0
                        ? 'background-color: #00AEEC;'
                        : 'background-color: #e8e8e8;cursor:not-allowed'
                    "
                    @click="getSmsCode('register')"
                  >
                    {{ resgCodeText }}
                  </div>
                </div>
              </el-form-item>
              <el-form-item label="" prop="code">
                <el-input
                  type="text"
                  class="input"
                  v-model="registerForm.code"
                  placeholder="请输入验证码"
                />
              </el-form-item>
              <el-form-item label="" prop="password">
                <el-input
                  type="password"
                  show-password
                  class="input"
                  v-model="registerForm.password"
                  placeholder="请输入密码"
                />
              </el-form-item>
              <el-form-item label="" prop="nickname">
                <el-input
                  type="text"
                  class="input"
                  v-model="registerForm.nickname"
                  placeholder="请输入昵称"
                />
              </el-form-item>

              <div class="submit" @click="submitRegister">注&nbsp;册</div>
            </el-form>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script>
import { ElMessage } from "element-plus";
import {
  fetchSmsCode,
  fetchRegister,
  fetchLoginPsw,
  fetchLoginSms,
} from "@/api/user";
import { useUserStore } from "@/store/user";
import { mapActions } from "pinia";
export default {
  name: "LoginRegister",
  data() {
    return {
      type: 1, // 1登录 2注册
      loginType: 1, // 1密码登录 2验证码登录
      loginForm: {
        account: "",
        password: "",
        code: "",
      },
      registerForm: {
        account: "",
        password: "",
        nickname: "",
        code: "",
      },
      loginRules: {
        account: [
          { required: true, message: "请输入天幕账号", trigger: "blur" },
        ],
        password: [
          {
            required: true,
            message: "请输入密码",
            trigger: "blur",
          },
        ],
      },
      registerRules: {
        account: [
          { required: true, message: "请输入天幕账号", trigger: "blur" },
        ],
        password: [
          {
            required: true,
            message: "请输入密码",
            trigger: "blur",
          },
        ],
        nickname: [{ required: true, message: "请输入昵称", trigger: "blur" }],
        code: [{ required: true, message: "请输入验证码", trigger: "blur" }],
      },
      resgAccountIsValid: false, // 注册手机号是否合法
      loginAccountIsValid: false, // 登录手机号是否合法
      resgCountdown: -1, // 注册验证码倒计时秒数
      loginCountdown: -1, // 登录验证码倒计时秒数
    };
  },
  mounted() {
    // this.init();
    document.addEventListener("keydown", (e) => this.handleKeyboard(e));
  },
  beforeUnmount() {
    document.removeEventListener("keydown", (e) => this.handleKeyboard(e));
  },
  computed: {
    resgCodeText() {
      return this.resgCountdown > 0
        ? `${this.resgCountdown}秒后重试`
        : "获取验证码";
    },
    loginCodeText() {
      return this.loginCountdown > 0
        ? `${this.loginCountdown}秒后重试`
        : "获取验证码";
    },
    loginTypeText() {
      return this.loginType === 1 ? "验证码登录" : "密码登录";
    },
  },
  methods: {
    // 映射pinia仓库中的方法
    ...mapActions(useUserStore, ["login"]),
    // 点击标签页触发的事件
    handleClick(tab) {
      if (tab.props.label === "登录") {
        this.type = 1;
      } else {
        this.type = 2;
      }
    },

    // 获取验证码
    async getSmsCode(type) {
      switch (type) {
        // 登录验证码
        case "login": {
          if (!this.loginAccountIsValid || this.loginCountdown > 0) {
            return;
          }
          try {
            const res = await fetchSmsCode(this.loginForm.account);
            if (res && res.code === 200) {
              ElMessage.success("验证码已发送，请留意手机或者邮箱信息");
              this.loginCountdown = 60;
              //   开始倒计时
              this.startCountdown(type);
            }
          } catch (error) {
            ElMessage.error("获取验证码失败:" + error);
          }
          break;
        }
        // 注册验证码
        case "register": {
          // 如果此时号码不规范且处于倒计时状态，则不执行
          if (!this.resgAccountIsValid || this.resgCountdown > 0) {
            return;
          }
          try {
            const res = await fetchSmsCode(this.registerForm.account);
            if (res && res.code === 200) {
              ElMessage.success("验证码已发送，请留意手机或者邮箱信息");
              this.resgCountdown = 60;
              //   开始倒计时
              this.startCountdown(type);
            }
          } catch (error) {
            ElMessage.error("获取验证码失败:" + error);
          }
          break;
        }
      }
    },
    // 倒计时
    startCountdown(type) {
      switch (type) {
        case "login": {
          let loginTimer = null;
          loginTimer = setInterval(() => {
            this.loginCountdown--;
            if (this.loginCountdown <= 0) {
              clearInterval(loginTimer);
              this.loginCountdown = -1;
            }
          }, 1000);
          break;
        }
        case "register": {
          let resgTimer = null;
          resgTimer = setInterval(() => {
            this.resgCountdown--;
            if (this.resgCountdown <= 0) {
              clearInterval(resgTimer);
              this.resgCountdown = -1;
            }
          }, 1000);
          break;
        }
      }
    },
    // 监听键盘回车触发登录
    handleKeyboard(event) {
      if (event.keyCode === 13 && this.type === 1) {
        this.submitLogin();
      }
    },

    // 登录的回调
    async submitLogin() {
      if (this.loginForm.account.trim() == "" || !this.loginAccountIsValid) {
        ElMessage.error("请输入正确的账号");
        return;
      }
      switch (this.loginType) {
        // 密码登录
        case 1:
          if (this.loginForm.password.trim() == "") {
            ElMessage.error("密码不能为空");
            return;
          }
          try {
            const res = await fetchLoginPsw({
              account: this.loginForm.account,
              password: this.loginForm.password,
            });
            if (res && res.code === 200) {
              ElMessage.success("登录成功");
              // 将用户信息存储到pinia中
              this.login(res.data);
              this.$emit("loginSuccess"); // 触发父组件关闭登录框的回调
            }
          } catch (error) {
            ElMessage.error("登录失败:" + error);
          }
          break;
        //  验证码登录
        case 2:
          if (this.loginForm.code.trim() == "") {
            ElMessage.error("验证码不能为空");
            return;
          }
          try {
            const res = await fetchLoginSms({
              account: this.loginForm.account,
              code: this.loginForm.code,
            });
            if (res && res.code === 200) {
              ElMessage.success("登录成功");
              // 将用户信息存储到pinia中
              this.login(res.data);
              this.$emit("loginSuccess"); // 触发父组件关闭登录框的回调
            }
          } catch (error) {
            ElMessage.error("登录失败:" + error);
          }
          break;
      }
    },

    async submitRegister() {
      // 前端先做判断，减轻服务器负担
      if (this.registerForm.account.trim() == "") {
        ElMessage.error("账号不能为空");
        return;
      }
      if (this.registerForm.password.trim() == "") {
        ElMessage.error("密码不能为空");
        return;
      }
      if (this.registerForm.nickname.trim() == "") {
        ElMessage.error("昵称不能为空");
        return;
      }
      if (this.registerForm.code.trim() == "") {
        ElMessage.error("验证码不能为空");
        return;
      }
      try {
        const res = await fetchRegister(this.registerForm);
        if (res && res.code === 200) {
          ElMessage.success("注册成功");
          this.type = 1;
          // 将用户信息存储到pinia中
          this.login(res.data);
          this.$emit("loginSuccess"); // 触发父组件关闭登录框的回调
        }
      } catch (error) {
        ElMessage.error("注册失败:" + error);
      }
    },

    // 切换登录类型
    changeLoginType() {
      if (this.loginType === 1) {
        this.loginType = 2;
      } else if (this.loginType === 2) {
        this.loginType = 1;
      }
    },
  },
  watch: {
    "loginForm.account": {
      handler(newVal) {
        const phoneRegex = /^1[3-9]\d{9}$/; // 正则匹配中国大陆手机号
        const emailRegex = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/; //匹配邮箱有效
        if (phoneRegex.test(newVal) || emailRegex.test(newVal)) {
          this.loginAccountIsValid = true;
        } else {
          this.loginAccountIsValid = false;
        }
      },
    },
    "registerForm.account": {
      handler(newVal) {
        const phoneRegex = /^1[3-9]\d{9}$/; // 正则匹配中国大陆手机号
        const emailRegex = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/; //匹配邮箱有效

        if (phoneRegex.test(newVal) || emailRegex.test(newVal)) {
          this.resgAccountIsValid = true;
        } else {
          this.resgAccountIsValid = false;
        }
      },
    },
    // 监听登录类型的变化，同时更改校验规则
    loginType(newVal) {
      if (newVal === 1) {
        this.loginRules = {
          account: [
            { required: true, message: "请输入天幕账号", trigger: "blur" },
          ],
          password: [
            {
              required: true,
              message: "请输入密码",
              trigger: "blur",
            },
          ],
        };
      } else if (newVal === 2) {
        this.loginRules = {
          account: [
            { required: true, message: "请输入天幕账号", trigger: "blur" },
          ],
          code: [{ required: true, message: "请输入验证码", trigger: "blur" }],
        };
      }
    },
  },
};
</script>

<style scoped>
.login-register {
  position: relative;
  display: flex;
  width: 100%;
  height: 100%;
}

.login-register-container {
  display: block;
  width: 450px;
  height: 400px;
  padding: 30px 40px;
}

.login-tabs {
  width: 90%;
  margin: 0 auto;
}

.login-box,
.register-box {
  display: flex;
  flex-direction: column;
}

.login-box .input,
.login-box .submit,
.login-box .tips {
  margin-top: 20px;
  width: 100%;
}

.register-box .input,
.register-box .submit,
.register-box .tips {
  margin-top: 20px;
  width: 100%;
}

.submit {
  color: #fff;
  border-radius: 4px;
  background-color: var(--brand_blue);
  text-align: center;
  padding: 10px 15px;
  cursor: pointer;
}

.submit:hover {
  background-color: var(--v_brand_blue_hover);
}

.tips {
  color: var(--text2);
  font-size: 12px;
  text-align: center;
}

.tips .agreement {
  color: var(--brand_blue);
  margin-left: 4px;
  cursor: pointer;
}
.login-type {
  width: fit-content;
  font-size: 12px;
  color: var(--brand_blue);
  margin-top: 20px;
  cursor: pointer;
}
/* 验证码按钮 */
.sms-send {
  position: absolute;
  right: 3px;
  top: 50%;
  padding: 0px 15px;
  border-radius: 5px;
  color: var(--Wh0_t);
  /* background-color: var(--main-color); */
  background-color: #e8e8e8;
  transform: translateY(-19%);
  cursor: pointer;
}
/* element 元素 */
.el-input {
  --el-input-focus-border: #ccc;
  --el-input-focus-border-color: #ccc;
  --el-input-border-radius: 10px;
  --el-input-height: 40px;
}

.el-input /deep/ .el-input__inner {
  padding: 8px 15px;
}

.el-input /deep/ .el-input__icon {
  margin-right: 8px;
}

.login-register-container /deep/ .el-tabs__active-bar {
  height: 3px;
}

.login-register-container /deep/ .el-tabs__nav-wrap::after {
  height: 0;
}

.login-register-container /deep/ .el-tabs__item {
  font-size: 17px;
}
</style>