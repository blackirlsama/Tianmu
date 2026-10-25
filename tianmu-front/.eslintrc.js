module.exports = {
  root: true,
  env: {
    node: true
  },
  'extends': [
    'plugin:vue/vue3-essential',
    'eslint:recommended'
  ],
  parserOptions: {
    parser: '@babel/eslint-parser'
  },
  rules: {
    'no-console': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
    'no-debugger': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
      //在rules中添加自定义规则
      //关闭组件命名规则，解决：Component name “index“ should always be multi-word
    'vue/multi-word-component-names': 'off',
    'no-unused-vars': 'warn',
    'quotes':'off'
  }
}
