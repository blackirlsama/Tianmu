<template>
  <el-carousel trigger="click" arrow="always">
    <el-carousel-item v-for="(item, index) in carousels" :key="index">
      <a class="carousel-inner" :href="item.target" target="_blank">
        <img :src="item.url" alt="" />
        <div class="carousel-footer">
          <div class="carousel-title">
            {{ item.title }}
          </div>
          <div class="carousel-mask"></div>
        </div>
      </a>
    </el-carousel-item>
  </el-carousel>
</template>

<script>
import carouselJson from "@/assets/json/carousel.json";

export default {
  name: "CarouselIndex",
  data() {
    return {
      // 轮播图列表el-carousel__button
      carousels: [],
    };
  },
  methods: {
    // 请求
    getCarousels() {
      this.carousels = carouselJson;
    },
  },
  created() {
    this.getCarousels();
  },
  mounted() {},
  beforeMount() {},
};
</script>

<style scoped>
.carousel-inner {
  height: 100%;
  width: 100%;
  position: relative;
  display: inline-block;
  line-height: 1;
  vertical-align: middle;
  background-color: var(--graph_bg_regular);
  cursor: pointer;
}
img {
  width: 100%;
  height: 100%;
  object-fit: cover; /* 确保图片覆盖整个容器 */
  position: absolute;
  top: 0;
  left: 0;
}
::v-deep.el-carousel.el-carousel--horizontal {
  height: 100%;
}
::v-deep .el-carousel__container {
  height: 100%;
}
::v-deep .el-carousel__arrow {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background-color: rgba(255, 255, 255, 0.1);
}
::v-deep .el-carousel__arrow:hover {
  background-color: rgba(255, 255, 255, 0.2);
}
::v-deep .el-carousel__arrow.el-carousel__arrow--right {
  top: unset;
  bottom: 7%;
}
::v-deep .el-carousel__arrow.el-carousel__arrow--left {
  top: unset;
  bottom: 7%;
  left: unset;
  right: 56px;
}
.carousel-footer {
  position: absolute;
  bottom: 0;
  width: 100%;
  height: 80px;
  z-index: 2; /* 确保文字在图片上方 */
}
.carousel-title {
  width: calc(100% - 120px);
  position: relative;
  left: 15px;
  top: 15px;
  z-index: 9;
  color: #fff;
  font-size: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.carousel-footer .carousel-mask {
  width: 100%;
  content: "";
  display: block;
  transition: translate 0s;
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 1;
  user-select: none;
  pointer-events: none;
  background: transparent; /* 改为透明 */
}
@media (min-width: 1367px) and (max-width: 1700.9px) {
  .carousel-footer .carousel-mask {
    height: 810px;
  }
}
::v-deep .el-carousel__indicators--horizontal {
  position: absolute;
  left: 15px;
  bottom: 20px;
  margin: -1.5px;
  z-index: 2;
  display: flex;
  align-items: center;
  transform: unset;
}
::v-deep .el-carousel__indicator.el-carousel__indicator--horizontal {
  margin: 4px;
}
::v-deep .el-carousel__button {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  transition: all 0.4s;
}
::v-deep .el-carousel__indicator.is-active button {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background-color: #fff;
}
@media (max-width: 1700.9px) {
  .title span {
    font-size: 18px;
  }
}

@media (min-width: 1701px) {
  .title span {
    font-size: 20px;
  }
}
</style>