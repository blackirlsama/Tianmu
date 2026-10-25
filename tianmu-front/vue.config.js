const { defineConfig } = require('@vue/cli-service')
const path = require('path');
const CompressionWebpackPlugin = require('compression-webpack-plugin')
const CssMinimizerPlugin = require('css-minimizer-webpack-plugin');
module.exports = defineConfig({
  // 关闭语法检查eslint
  lintOnSave: process.env.NODE_ENV === 'production',
  transpileDependencies: true,
  
  pages: {
    index: {
      // page 的入口
      entry: 'src/main.js',
      // 模板来源
      template: 'public/index.html',
      // 在 dist/index.html 的输出
      filename: 'index.html',
      // 当使用 title 选项时，
      // template 中的 title 标签需要是 <title><%= htmlWebpackPlugin.options.title %></title>
      title: '天幕',
      // 在这个页面中包含的块，默认情况下会包含
      // 提取出来的通用 chunk 和 vendor chunk。
      chunks: ['chunk-vendors', 'chunk-common', 'index']
    },
  },
  publicPath: '/',
  assetsDir: 'static',
  productionSourceMap: process.env.NODE_ENV === 'production' ? false : true, // 生产环境是否生成 sourceMap 文件，一般情况不建议打开,  // npm run build打包时js文件夹不会生成map文件
  outputDir: 'dist', // 打包后的文件存放目录
  // Feature flag __VUE_PROD...，定义环境变量给源代码使用，从而解决vue3页面警告的问题
  chainWebpack: (config) => {
    config.plugin('define').tap((definitions) => {
      Object.assign(definitions[0], {
        __VUE_OPTIONS_API__: 'true',
        __VUE_PROD_DEVTOOLS__: 'false',
        __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false'
      })
      return definitions
    })
  },
  configureWebpack: {
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src')
      }
    },
    plugins: [
      // new BundleAnalyzerPlugin(),
      // Gzipped压缩
      new CompressionWebpackPlugin({
        filename: '[path][base].gz', // 输出的文件名
        algorithm: 'gzip',           // 使用 gzip 压缩
        test: /\.(js|mjs|json|css|html|ttf|woff2|woff|json|gif|jpe?g|png|svg)$/, // 匹配需要压缩的文件类型
        threshold: 1,            // 只处理大于1KB 的文件
        minRatio: 0.8,               // 只有压缩率小于 0.8 的文件才会被处理
        deleteOriginalAssets: false, // 是否删除源文件
      }),
    ],
    // 开启分离 js
    optimization: {
      usedExports: true, // 仅导出被使用的代码，确保启用tree shaking
      minimize: true, // 启用代码压缩
      minimizer: [
        new CssMinimizerPlugin(),//压缩css
        new (require('terser-webpack-plugin'))({
          terserOptions: {
            compress: {
              drop_console: true, // 去掉 console.log 和其他调试信息
              drop_debugger: true, // 去掉 debugger 语句
            },
            output: {
              comments: false, // 去掉注释
            },
          },
        }),
      ],
      runtimeChunk: 'single', //将 Webpack 的运行时逻辑提取到单独的文件中，以提高缓存效果。
      splitChunks: {
        chunks: 'all',
        maxInitialRequests: 5, //初始请求的数量。
        maxAsyncRequests: 5, //最大并行异步请求数，默认最多 5 个。
        minSize: 20000, //生成 chunk 的最小体积为 20KB，拆分出来的文件小于这个大小就会合并到其他文件中。
        cacheGroups: {
          vueCommon: {
            test: /[\\/]node_modules[\\/](@vue|vue)[\\/]/, // 匹配 vue 相关的模块
            // test: /[\\/]node_modules[\\/](@vue|vue|vue-router|@vueuse|pinia)[\\/]/, // 匹配 vue 相关的模块
            name: "chunk-vue",
            chunks: "all",
            priority: 100,
            reuseExistingChunk: true,
            enforce: true
          },
          elementPlus: {
            name: "chunk-element-plus",
            test: /[\\/]node_modules[\\/](element-plus)[\\/]/,
            chunks: "all",
            priority: 90,
            reuseExistingChunk: true,
            enforce: true
          },
          elementIcon: {
            test: /[\\/]node_modules[\\/]@element-plus[\\/]/,
            name: "chunk-element-icon",
            chunks: "all",
            priority: 80,
            reuseExistingChunk: true,
            enforce: true
          },
          default: {
            minChunks: 2, // 如果一个模块被引用超过 2 次，则提取出来
            priority: 10,
            reuseExistingChunk: true,
          }
        }
      }
    },
  },
  // 配置代理跨域
  devServer: {
    proxy: {
      "/api/agent": {
        target: process.env.TIANMU_AGENT_PROXY_TARGET || 'http://127.0.0.1:8201',
        changeOrigin: true,
        timeout: 75000,
        proxyTimeout: 75000,
      },
      "/api": {
        target: process.env.TIANMU_API_PROXY_TARGET || 'http://127.0.0.1:8101',
        changeOrigin: true,
      }
    }
  }
})
